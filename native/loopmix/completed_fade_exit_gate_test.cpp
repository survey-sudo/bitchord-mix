// BitChord: focused physical-fade exit-gate regression using the real renderer.
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
//
// Build with loop_mix_renderer.cpp as a separate translation unit. The same
// harness deliberately fails the newly required completed-fade assertions when
// linked against the retained pre-fix renderer. No private access or substituted
// gate predicate is used: every observation goes through Renderer::render().

#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <string>
#include <vector>

namespace {
using bitchord::loopmix::Renderer;
constexpr int kSampleRate = 48000;
constexpr int kBlock = 128;
constexpr int64_t kHold = 12000;
constexpr int64_t kSourceOffset = 24000;
constexpr int64_t kMapSpan = 65536;
constexpr double kPi = 3.14159265358979323846;

enum class Clock { Straight, Ahead, Held };
enum class Effect { None, Brake, TapeStop, Spinback, Roll, RollWithoutStop };
enum class BoundaryAudio { Audible, Silent };

struct Case {
  const char* name;
  Clock clock;
  double fadeAt;
  int64_t stopFrame;
  int violations;
  BoundaryAudio boundary;
  Effect effect = Effect::None;
  bool effectDone = false;
};

int failures = 0;
std::ofstream pcm;

void check(bool condition, const Case& test, const char* message) {
  if (!condition) {
    ++failures;
    std::cerr << "ASSERTION_FAIL case=" << test.name << " " << message << '\n';
  }
}

void render(Renderer& renderer, int64_t frame, float* output, int frames) {
  renderer.render(frame, output, frames);
  if (pcm.is_open()) {
    pcm.write(reinterpret_cast<const char*>(output),
              std::streamsize(sizeof(float) * size_t(frames) * 2));
  }
}

void configure(Renderer& renderer, const Case& test, int64_t stopFrame) {
  auto& deck = renderer.deck(0);
  // Real populated PCM, default quality, and prepare-allocated render buffers.
  // A DC component keeps the plain-deck one-frame fade boundary unambiguous;
  // the tone also exercises the existing brake/roll DSP on nonconstant input.
  constexpr int count = kSampleRate * 4;
  deck.left.resize(count);
  deck.right.resize(count);
  for (int i = 0; i < count; ++i) {
    deck.left[size_t(i)] = 0.10f + 0.025f * float(std::sin(2 * kPi * 437 * i / kSampleRate));
    deck.right[size_t(i)] = 0.075f + 0.02f * float(std::cos(2 * kPi * 619 * i / kSampleRate));
  }
  deck.regionStart = 17.125;
  deck.preroll = 0.0;
  deck.runOn = true;
  deck.level = 1.0f;
  deck.stopAt = double(stopFrame) / kSampleRate;
  deck.gain.times = {0.0, 4.0};
  deck.gain.values = {1.0f, 1.0f};
  // A power-of-two advancing span represents the unit source rate exactly;
  // one-frame boundary assertions therefore need no floating-point tolerance.
  if (test.clock == Clock::Held) {
    deck.knotTimes = {0.0, double(kHold), double(kHold + kMapSpan)};
    deck.knotPositions = {0.0, 0.0, double(kMapSpan)};
  } else {
    const double offset = test.clock == Clock::Ahead ? double(kSourceOffset) : 0.0;
    deck.knotTimes = {0.0, double(kMapSpan)};
    deck.knotPositions = {offset, offset + kMapSpan};
  }

  switch (test.effect) {
    case Effect::Brake:
      renderer.setBrake(0, 0.05, 0.05, false);
      break;
    case Effect::TapeStop:
      renderer.setTapeStop(0, 0.05, 0.05, 0.003f, 5.f, 18000.f, 500.f, 10.0);
      break;
    case Effect::Spinback:
      renderer.setSpinback(0, 0.05, 0.05, 2.0, 0.0);
      break;
    case Effect::Roll:
    case Effect::RollWithoutStop:
      deck.loopStart = 0;
      deck.loopEnd = 12000;
      deck.releaseAt = 48000;
      renderer.setLoopRollEx(0, true, 0.05, 0.125, 0.15, 0.8,
                            200.f, 2000.f, 2.f, test.effect == Effect::Roll);
      break;
    case Effect::None:
      break;
  }
  renderer.prepare();
  // Keep the physical fade installation after prepare, like the canonical host
  // adapter. Never fake heardPos, started, finished, brake.done or mix buffers.
  deck.fadeOutAt.store(test.fadeAt, std::memory_order_relaxed);
  check(!deck.finished, test, "populated deck must survive prepare");
  check(renderer.deck(1).finished, test, "unused deck must be inactive");
  check(renderer.exitGateViolations() == 0, test, "fresh counter must be zero");
  if (test.clock == Clock::Held) {
    check(deck.heldStartup && deck.heldPrimed, test, "held input must really be prepared and primed");
    check(deck.activeStartFrame == double(kHold), test, "held input cue must equal the declared mix frame");
  }
}

struct PreStop {
  double heard = 0;
  double read = 0;
  float peak = 0;
  float heldPeak = 0;
  bool brakeDone = false;
};

PreStop advanceToStop(Renderer& renderer, const Case& test) {
  PreStop result;
  std::array<float, kBlock * 2> output{};
  int64_t frame = renderer.firstFrame();
  while (frame < test.stopFrame) {
    int count = int(std::min<int64_t>(kBlock, test.stopFrame - frame));
    // Split exactly at the held cue so the held-silence assertion cannot include
    // any active samples. The final callback also ends exactly at stopFrame.
    if (test.clock == Clock::Held && frame < kHold) {
      count = int(std::min<int64_t>(count, kHold - frame));
    }
    render(renderer, frame, output.data(), count);
    for (int i = 0; i < count * 2; ++i) {
      result.peak = std::max(result.peak, std::abs(output[size_t(i)]));
      if (test.clock == Clock::Held && frame + count <= kHold) {
        result.heldPeak = std::max(result.heldPeak, std::abs(output[size_t(i)]));
      }
    }
    frame += count;
  }
  const auto& deck = renderer.deck(0);
  result.heard = deck.heardPos;
  result.read = deck.readPos;
  result.brakeDone = deck.v2.brake.done;
  check(deck.started && !deck.finished, test, "real deck must have started and not yet stopped");
  check(result.peak > 0.01f, test, "pre-stop audio must be nonvacuous");
  check(renderer.exitGateViolations() == 0, test, "no gate trips before stopAt");
  check(deck.gain.at(deck.stopAt) == 1.0f, test, "canonical gain lane must remain neutral");
  if (test.clock == Clock::Held) {
    check(result.heldPeak == 0.0f, test, "held input must actually stay silent until cue");
  }
  if (test.effect == Effect::None) {
    const double expectedHeard = test.clock == Clock::Held
        ? double(test.stopFrame - kHold)
        : double(test.stopFrame + (test.clock == Clock::Ahead ? kSourceOffset : 0));
    // Exact integral knot endpoints: no tolerance or mix-clock approximation.
    check(result.heard == expectedHeard, test, "heard clock must match the actual source endpoint exactly");
    check(deck.heard.load(std::memory_order_relaxed) == deck.regionStart + expectedHeard / kSampleRate,
          test, "public heard track clock must agree with physical source position");
  }
  if (test.effect == Effect::Brake || test.effect == Effect::TapeStop || test.effect == Effect::Spinback) {
    check(deck.v2.brake.enabled && deck.v2.brake.active, test, "configured brake must really engage");
    check(result.brakeDone == test.effectDone, test, "real brake completion state must match the case");
  }
  return result;
}

void runCase(const Case& test) {
  const int before = failures;
  Renderer renderer(kSampleRate);
  configure(renderer, test, test.stopFrame);
  const PreStop state = advanceToStop(renderer, test);
  std::array<float, kBlock * 2> stopped{};
  stopped.fill(123.f);
  render(renderer, test.stopFrame, stopped.data(), kBlock);
  check(renderer.deck(0).finished, test, "render at stopAt must finish the deck");
  check(renderer.exitGateViolations() == test.violations, test, "exit-gate count differs from required semantics");
  check(std::all_of(stopped.begin(), stopped.end(), [](float x) { return x == 0.f; }),
        test, "stopped deck output must be exactly silent");
  render(renderer, test.stopFrame + kBlock, stopped.data(), kBlock);
  check(renderer.exitGateViolations() == test.violations, test, "finished deck must not increment the gate twice");

  // An otherwise identical renderer allowed to continue proves what the next
  // physical sample actually is. A stopped renderer alone would always be zero
  // and could not establish that a fade completed or that a negative is audible.
  Renderer continuing(kSampleRate);
  configure(continuing, test, test.stopFrame + 2 * kBlock);
  const PreStop twin = advanceToStop(continuing, test);
  check(twin.heard == state.heard && twin.read == state.read, test, "continuation must reach identical source state");
  std::array<float, 2> sample{};
  render(continuing, test.stopFrame, sample.data(), 1);
  const float boundary = std::max(std::abs(sample[0]), std::abs(sample[1]));
  check(test.boundary == BoundaryAudio::Silent ? boundary == 0.f : boundary > 0.f,
        test, "actual uninterrupted boundary sample has wrong audible/silent state");
  check(continuing.exitGateViolations() == 0, test, "continuation must not enter the stop gate");

  std::cout << (failures == before ? "PASS" : "FAIL") << " case=" << test.name
            << " stop_mix_frame=" << test.stopFrame << " heard_source_frame=" << state.heard
            << " read_source_frame=" << state.read << " fade_at=" << test.fadeAt
            << " neutral_gain=1 pre_peak=" << state.peak << " held_peak=" << state.heldPeak
            << " brake_done=" << state.brakeDone << " boundary_peak=" << boundary
            << " violations=" << renderer.exitGateViolations() << " expected=" << test.violations << '\n';
}
}  // namespace

int main(int argc, char** argv) {
  if (argc == 3 && std::string(argv[1]) == "--pcm") {
    pcm.open(argv[2], std::ios::binary | std::ios::trunc);
    if (!pcm) {
      std::cerr << "Cannot open PCM evidence output\n";
      return 2;
    }
  } else if (argc != 1) {
    std::cerr << "Usage: completed_fade_exit_gate_test [--pcm output.f32]\n";
    return 2;
  }
  std::cout << std::setprecision(17);
  const std::vector<Case> cases = {
      {"completed_neutral_fade", Clock::Straight, 6000, 7200, 0, BoundaryAudio::Silent},
      {"absent_fade", Clock::Straight, -1, 8400, 1, BoundaryAudio::Audible},
      {"unfinished_fade", Clock::Straight, 6000, 6600, 1, BoundaryAudio::Audible},
      {"future_fade", Clock::Straight, 10000, 8400, 1, BoundaryAudio::Audible},
      // Physical fade finishes at source 31200, which is mix frame 7200.
      {"source_ahead_one_frame_before", Clock::Ahead, 30000, 7199, 1, BoundaryAudio::Audible},
      {"source_ahead_exact_completion", Clock::Ahead, 30000, 7200, 0, BoundaryAudio::Silent},
      // Held input: physical fade finishes at source 7200, mix frame 19200.
      // The read clock leads heardPos; using readPos would also mask negatives.
      {"held_unfinished_fade", Clock::Held, 6000, 18600, 1, BoundaryAudio::Audible},
      {"held_one_frame_before", Clock::Held, 6000, 19199, 1, BoundaryAudio::Audible},
      {"held_exact_completion", Clock::Held, 6000, 19200, 0, BoundaryAudio::Silent},
      {"completed_brake", Clock::Ahead, -1, 9600, 0, BoundaryAudio::Silent, Effect::Brake, true},
      {"incomplete_brake", Clock::Ahead, -1, 3600, 1, BoundaryAudio::Audible, Effect::Brake},
      {"completed_tape_stop", Clock::Ahead, -1, 9600, 0, BoundaryAudio::Silent, Effect::TapeStop, true},
      {"incomplete_tape_stop", Clock::Ahead, -1, 3600, 1, BoundaryAudio::Audible, Effect::TapeStop},
      {"completed_spinback", Clock::Ahead, -1, 9600, 0, BoundaryAudio::Silent, Effect::Spinback, true},
      {"incomplete_spinback", Clock::Ahead, -1, 3600, 1, BoundaryAudio::Audible, Effect::Spinback},
      {"completed_roll", Clock::Straight, -1, 9600, 0, BoundaryAudio::Silent, Effect::Roll, true},
      {"incomplete_roll", Clock::Straight, -1, 4800, 1, BoundaryAudio::Audible, Effect::Roll},
      {"roll_without_downbeat_stop", Clock::Straight, -1, 9600, 1, BoundaryAudio::Audible, Effect::RollWithoutStop},
  };
  for (const Case& test : cases) runCase(test);
  if (pcm.is_open()) {
    pcm.flush();
    if (!pcm) {
      std::cerr << "Cannot write PCM evidence output\n";
      return 2;
    }
  }
  std::cout << "cases=" << cases.size() << " assertion_failures=" << failures << '\n';
  return failures == 0 ? 0 : 1;
}
