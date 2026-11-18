// Sound-polish regressions against the existing production renderer.
// Build/run on cue only; no Android/JNI or substitute renderer.
#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <limits>
#include <vector>

using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {
constexpr double kPi = 3.14159265358979323846;
bool passed = true;
int checks = 0;
void check(bool condition, const char* name) {
  ++checks;
  if (!condition) { passed = false; std::printf("FAIL sound-polish %s\n", name); }
}

void shapedEnvelopes() {
  // Long arrays use the new bounded native lookup. These are the same
  // <=10 ms linear/log wire envelopes produced by TransitionMoves.
  Envelope fall, rise, cosine, hz;
  hz.exponential = true;
  const int steps = 800;
  for (int i = 0; i <= steps; ++i) {
    const double p = double(i) / steps;
    const double time = 12.0 + 4.0 * p; // L-8 beats to L, 120 BPM
    for (auto* e : {&fall, &rise, &cosine, &hz}) e->times.push_back(time);
    fall.values.push_back(float(std::cos(kPi * p / 2)));
    rise.values.push_back(float(std::sin(kPi * p / 2)));
    cosine.values.push_back(float((1 - std::cos(kPi * p)) / 2));
    hz.values.push_back(float(20 * std::pow(1000, (1 - std::cos(kPi * p)) / 2)));
  }
  for (double beat : {-8.0, -4.0, -2.0, 0.0, 2.0}) {
    const double time = 16 + beat * .5;
    const double p = std::clamp((time - 12) / 4, 0.0, 1.0);
    check(std::abs(fall.at(time) - std::cos(kPi * p / 2)) < 1e-6, "native equal-power fall at beat probe");
    check(std::abs(rise.at(time) - std::sin(kPi * p / 2)) < 1e-6, "native equal-power rise at beat probe");
    check(std::abs(fall.at(time) * fall.at(time) + rise.at(time) * rise.at(time) - 1) < 1e-6,
        "native equal-power sum is one");
  }
  for (int i = 0; i < 400; ++i) {
    const double time = 12 + (i + .37) * .01;
    const double p = (time - 12) / 4;
    const double weight = (1 - std::cos(kPi * p)) / 2;
    check(std::abs(cosine.at(time) - weight) < 1e-5, "native cosine send between baked knots");
    check(std::abs(hz.at(time) / (20 * std::pow(1000, weight)) - 1) < 2e-5,
        "native shaped log-frequency between baked knots");
  }
}

void absentRollBodies() {
  for (int kind = 0; kind < 7; ++kind) {
    Renderer r(8000);
    auto& d = r.deck(0);
    d.left.assign(5 * 8000, .1f); d.right.assign(d.left.size(), -.1f);
    d.loopStart = 8000; d.loopEnd = 3 * 8000;
    double beat = .5;
    if (kind == 0) d.loopEnd = d.loopStart;                  // old straight M1/M3 no-op
    if (kind == 1) d.loopEnd = 7 * 8000;                    // truncated body
    if (kind == 2) d.right.pop_back();                     // malformed stereo
    if (kind == 3) d.loopEnd = d.loopStart + 8000;          // only two beats
    if (kind == 4) beat = std::numeric_limits<double>::quiet_NaN();
    if (kind == 5) d.left.resize(3 * 8000), d.right.resize(3 * 8000); // missing seam continuation
    if (kind == 6) d.loopEnd = std::numeric_limits<int64_t>::max();  // invalid bounds cannot overflow seam arithmetic
    r.setLoopRoll(0, true, 2, beat, 10, 16);
    check(!d.loopRoll, "absent/partial/nonfinite native roll body is NONE");
  }
}

void straightRollBodies() {
  constexpr int sr = 8000;
  double maxEarlyDifference = 0;
  double minLateDifference = 1;
  for (int bpm : {70, 90, 120, 150}) for (int block : {257, 1021}) {
    const double beat = 60.0 / bpm;
    const double releaseMix = 24 * beat;
    const double startMix = 8 * beat;
    const int64_t init = sr;
    const int64_t bodyStart = init + std::llround(startMix * sr);
    const int64_t bodyEnd = bodyStart + std::llround(4 * beat * sr);
    const double releaseTrack = init + releaseMix * sr;
    const int count = int(std::ceil(releaseTrack + 2 * sr));
    Renderer roll(sr), dry(sr);
    roll.setHighQuality(false); dry.setHighQuality(false);
    auto& a = roll.deck(0); auto& b = dry.deck(0);
    a.left.resize(count); a.right.resize(count);
    for (int i = 0; i < count; ++i) {
      a.left[i] = float(.1 + .004 * i / sr);
      a.right[i] = -.6f * a.left[i];
    }
    b.left = a.left; b.right = a.right;
    a.loopStart = bodyStart; a.loopEnd = bodyEnd; a.releaseAt = releaseTrack;
    b.loopStart = b.loopEnd = init;
    for (auto* d : {&a, &b}) {
      d->knotTimes = {0, releaseMix * sr, (releaseMix + .5) * sr};
      d->knotPositions = {double(init), releaseTrack, releaseTrack + .5 * sr};
      d->stopAt = releaseMix + .5;
      d->gain.times = {0, releaseMix, releaseMix + .4};
      d->gain.values = {.7f, .7f, 0.f};
    }
    roll.setLoopRoll(0, true, startMix, beat, releaseMix, 16);
    check(a.loopRoll, "complete four-beat straight native roll is armed");
    check(std::abs(double(a.loopEnd - a.loopStart) / sr - 4 * beat) <= 1.0 / sr,
        "native body is four complete beats from 70 to 150 BPM");
    for (int64_t i = init; i < bodyStart; i += 79) check(a.sampleAtEffective(i) == i, "playback before roll stays straight");
    roll.prepare(); dry.prepare();
    const auto frozenL = a.left, frozenR = a.right;
    std::vector<float> aa(2 * block), bb(2 * block);
    double early = 0, late = 0;
    for (int64_t frame = roll.firstFrame(); frame < int64_t((releaseMix + .5) * sr); frame += block) {
      roll.render(frame, aa.data(), block); dry.render(frame, bb.data(), block);
      for (int i = 0; i < 2 * block; ++i) {
        check(std::isfinite(aa[i]), "native roll render stays finite");
        const double time = double(frame + i / 2) / sr;
        const double diff = std::abs(double(aa[i]) - bb[i]);
        if (time > 1 && time < startMix - .5) early = std::max(early, diff);
        if (time > startMix + 6 * beat && time < releaseMix - beat) late = std::max(late, diff);
      }
    }
    check(early < 1e-7, "native roll does not loop or color straight playback before its body");
    check(late > .001, "native roll actually repeats PCM, not a advertised no-op");
    check(a.left == frozenL && a.right == frozenR, "native frozen PCM is never overwritten");
    check(roll.exitGateViolations() == 0, "native roll exits already faded");
    maxEarlyDifference = std::max(maxEarlyDifference, early);
    minLateDifference = std::min(minLateDifference, late);
  }
  std::printf("native straight rolls: 8 tempo/block cases; maxPreRollDifference=%.9f minAudibleRollDifference=%.9f\n",
      maxEarlyDifference, minLateDifference);
}
} // namespace

int main() {
  shapedEnvelopes(); absentRollBodies(); straightRollBodies();
  std::printf("%s sound-polish native renderer: %d checks (shaped wire envelopes, complete frozen four-beat bodies, absent body -> NONE)\n",
      passed ? "ok" : "FAIL", checks);
  return passed ? 0 : 1;
}
