// Standalone startup-priming probe for the held incoming deck.
//
// Host build from the repository root:
//   g++ -std=c++17 -O2 -I native -I native/third_party native/loopmix/loop_mix_renderer.cpp native/loopmix/loop_mix_renderer_startup_probe.cpp -o /tmp/loopmix_startup_probe
//   /tmp/loopmix_startup_probe

#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace {

constexpr int kRate = 48000;
constexpr double kPi = 3.14159265358979323846;

struct Case {
  double holdSec;
  int sourceStart;
  int block;
  bool highQuality;
  double fractionalFrame = 0.0;
};

float rms(const std::vector<float>& pcm, int first, int last) {
  first = std::max(0, first);
  last = std::min(last, int(pcm.size() / 2));
  if (last <= first) return 0.f;
  double sum = 0;
  for (int i = first; i < last; ++i) {
    const double l = pcm[2 * i];
    const double r = pcm[2 * i + 1];
    sum += 0.5 * (l * l + r * r);
  }
  return float(std::sqrt(sum / double(last - first)));
}

float maxAbs(const std::vector<float>& pcm, int first, int last) {
  first = std::max(0, first);
  last = std::min(last, int(pcm.size() / 2));
  float result = 0;
  for (int i = first; i < last; ++i) {
    result = std::max(result, std::abs(pcm[2 * i]));
    result = std::max(result, std::abs(pcm[2 * i + 1]));
  }
  return result;
}

int leadingSilenceFrames(const std::vector<float>& pcm, int first, int last, float threshold) {
  first = std::max(0, first);
  last = std::min(last, int(pcm.size() / 2));
  for (int i = first; i < last; ++i) {
    const float left = pcm[2 * i];
    const float right = pcm[2 * i + 1];
    if (!std::isfinite(left) || !std::isfinite(right)) return last - first + 1;
    if (std::abs(left) >= threshold || std::abs(right) >= threshold) {
      return i - first;
    }
  }
  return last - first;
}

bool run(const Case& test) {
  using bitchord::loopmix::Renderer;
  const double cueFrame = test.holdSec * kRate + test.fractionalFrame;
  const int hold = int(std::floor(cueFrame));
  const int renderFrames = hold + int(0.12 * kRate);
  const int sourceFrames = test.sourceStart + int(0.12 * kRate) + 8192;
  std::vector<float> source(sourceFrames, 0.f);
  // A one-sample impulse plus a short 6 kHz attack makes both alignment and
  // early spectral energy observable without relying on raw PCM fallback.
  for (int i = test.sourceStart; i < sourceFrames; ++i) {
    const int relative = i - test.sourceStart;
    if (relative == 0) source[i] = 1.f;
    if (relative < int(0.06 * kRate)) {
      const float fade = 1.f - float(relative) / float(0.06 * kRate);
      source[i] += 0.55f * fade * float(std::sin(2.0 * kPi * 6000.0 * relative / kRate));
    }
  }

  Renderer renderer(kRate);
  renderer.setHighQuality(test.highQuality);
  auto& deck = renderer.deck(0);
  deck.left = source;
  deck.right = source;
  deck.knotTimes = {cueFrame - 1.0, cueFrame, cueFrame + kRate};
  deck.knotPositions = {double(test.sourceStart), double(test.sourceStart),
                        double(test.sourceStart + kRate)};
  deck.runOn = true;
  deck.stopAt = 1e18;
  renderer.prepare();
  const bool primedOnPrepare = deck.heldPrimed;

  std::vector<float> output(size_t(renderFrames) * 2, 0.f);
  std::vector<float> block(size_t(test.block) * 2, 0.f);
  for (int frame = 0; frame < renderFrames;) {
    const int count = std::min(test.block, renderFrames - frame);
    renderer.render(frame, block.data(), count);
    std::copy(block.begin(), block.begin() + size_t(count) * 2,
              output.begin() + size_t(frame) * 2);
    frame += count;
  }

  const int attackStart = hold + int(0.003 * kRate);
  const int attackEnd = hold + int(0.018 * kRate);
  const int laterStart = hold + int(0.025 * kRate);
  const int laterEnd = hold + int(0.045 * kRate);
  const float early = maxAbs(output, 0, hold);
  const int leading = leadingSilenceFrames(output, hold, hold + int(0.020 * kRate), 0.005f);
  const float attack = rms(output, attackStart, attackEnd);
  const float later = rms(output, laterStart, laterEnd);
  const int leadingBound = int(0.005 * kRate);
  const bool passed = primedOnPrepare && early < 1e-7f && leading <= leadingBound &&
      attack > 0.01f && attack > later * 0.70f;
  std::printf("hold=%.3f source=%d block=%d quality=%s primedOnPrepare=%s preCueMax=%.9g leadingSilence=%d(bound=%d) attack=%.9g later=%.9g %s\n",
              cueFrame / kRate, test.sourceStart, test.block,
              test.highQuality ? "default" : "cheaper", primedOnPrepare ? "yes" : "no",
              early, leading, leadingBound, attack, later,
              passed ? "PASS" : "FAIL");
  return passed;
}

bool runMalformedHeldInputs() {
  using bitchord::loopmix::Deck;
  using bitchord::loopmix::Renderer;

  Deck malformedSlope;
  malformedSlope.knotTimes = {0.0, 1.0, 2.0};
  malformedSlope.knotPositions = {0.0, 0.0, INFINITY};
  const double normalizedRate = malformedSlope.knotRate(1.5);

  Renderer renderer(kRate);
  auto& deck = renderer.deck(0);
  deck.left.assign(kRate, 0.1f);
  deck.right = deck.left;
  deck.knotTimes = {0.0, INFINITY, double(kRate)};
  deck.knotPositions = {0.0, 0.0, double(kRate)};
  deck.runOn = true;
  renderer.prepare();
  std::vector<float> output(512 * 2, 1.f);
  renderer.render(0, output.data(), 512);
  const bool finiteSilence = std::all_of(output.begin(), output.end(), [](float x) {
    return std::isfinite(x) && x == 0.f;
  });
  const bool passed = std::isfinite(normalizedRate) && normalizedRate == 1.0 &&
      deck.heldStartup && !deck.heldPrimed && finiteSilence;
  std::printf("malformedRateNormalized=%.1f invalidCuePrimed=%s finiteSilent=%s %s\n",
              normalizedRate, deck.heldPrimed ? "yes" : "no",
              finiteSilence ? "yes" : "no", passed ? "PASS" : "FAIL");
  return passed;
}

}  // namespace

int main() {
  const Case cases[] = {
      {2.0, 0, 127, false},   {2.0, 0, 512, false},   {2.0, 0, 4096, false},
      {2.0, 12000, 127, false}, {2.0, 12000, 512, false}, {2.0, 12000, 4096, false},
      {2.4, 0, 127, true},    {2.4, 0, 512, true},    {2.4, 0, 4096, true},
      {2.4, 12000, 127, true}, {2.4, 12000, 512, true}, {2.4, 12000, 4096, true},
      {2.0, 0, 127, false, 0.25}, {2.4, 12000, 512, true, 0.75},
  };
  bool passed = true;
  for (const Case& test : cases) passed = run(test) && passed;
  passed = runMalformedHeldInputs() && passed;
  return passed ? 0 : 1;
}
