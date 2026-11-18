#include "stretch/pitch_stretch.h"

#include <cmath>
#include <cstdio>
#include <vector>

namespace {

float estimateHz(const std::vector<float>& interleaved, int channels, int sampleRate, int skipFrames) {
  const int n = static_cast<int>(interleaved.size() / channels) - skipFrames;
  if (n < sampleRate / 2) return 0.f;
  // Autocorrelation around the expected period of a mid-range tone.
  const int minLag = sampleRate / 1000;
  const int maxLag = sampleRate / 200;
  double best = -1e300;
  int bestLag = minLag;
  for (int lag = minLag; lag <= maxLag; ++lag) {
    double sum = 0;
    const int count = n - lag;
    for (int i = 0; i < count; i += 4) {
      const float a = interleaved[(skipFrames + i) * channels];
      const float b = interleaved[(skipFrames + i + lag) * channels];
      sum += static_cast<double>(a) * b;
    }
    if (sum > best) {
      best = sum;
      bestLag = lag;
    }
  }
  return static_cast<float>(sampleRate) / static_cast<float>(bestLag);
}

bool pitchFollowsTranspose(float tempo) {
  constexpr int kRate = 48000;
  constexpr float kHz = 440.f;
  constexpr int kSeconds = 3;
  bitchord::PitchStretch stretch;
  stretch.configure(2, kRate);
  stretch.setTempo(tempo);
  const int frames = kRate * kSeconds;
  std::vector<float> buf(frames * 2);
  for (int i = 0; i < frames; ++i) {
    const float s = std::sin(2.0 * 3.141592653589793 * kHz * i / kRate);
    buf[2 * i] = s;
    buf[2 * i + 1] = s;
  }
  stretch.process(buf.data(), frames);
  const float got = estimateHz(buf, 2, kRate, kRate);
  const float want = kHz / tempo;
  const float error = std::fabs(got - want) / want;
  std::printf("pitch tempo %.3f got %.1f Hz want %.1f Hz error %.3f\n", tempo, got, want, error);
  return error <= 0.03f;
}

bool blockwiseTempoRamp() {
  constexpr int kRate = 48000;
  constexpr int kFrames = kRate * 3;
  bitchord::PitchStretch stretch;
  stretch.configure(2, kRate);
  bool allFinite = true, allContinuous = true;
  int frame = 0;
  for (int block : {512, 1024}) {
    std::vector<float> buf(kFrames * 2);
    for (int i = 0; i < kFrames; ++i) {
      const float x = 0.45f * std::sin(2.0 * 3.141592653589793 * 478.3 * i / kRate);
      buf[2 * i] = x; buf[2 * i + 1] = x;
    }
    stretch.configure(2, kRate);
    frame = 0;
    float previousL = 0.f, previousR = 0.f;
    bool finite = true, continuous = true;
    double maxStep = 0.0;
    while (frame < kFrames) {
      const int n = std::min(block, kFrames - frame);
      const float u = float(frame) / float(kFrames);
      const float tempo = u < 1.f / 3.f ? 1.f + 0.08f * (u * 3.f)
                                        : (u < 2.f / 3.f ? 1.08f - 0.08f * (u * 3.f - 1.f) : 1.f);
      stretch.setTempo(tempo);
      stretch.process(buf.data() + frame * 2, n);
      for (int i = 0; i < n; ++i) {
        const float l = buf[2 * (frame + i)], r = buf[2 * (frame + i) + 1];
        finite = finite && std::isfinite(l) && std::isfinite(r);
        if (i == 0 && frame > 0) {
          const double step = std::max(std::abs(double(l) - previousL),
                                       std::abs(double(r) - previousR));
          maxStep = std::max(maxStep, step);
          continuous = continuous && step < 0.5;
        }
        previousL = l; previousR = r;
      }
      frame += n;
    }
    std::printf("tempo ramp block=%d finite=%s boundary_continuity=%s max_step=%.6f\n", block,
                finite ? "yes" : "no", continuous ? "yes" : "no", maxStep);
    allFinite = allFinite && finite;
    allContinuous = allContinuous && continuous;
  }
  return allFinite && allContinuous;
}

}  // namespace

int main() {
  const double factor = bitchord::stretchRealtimeFactor(48000, 8, 1.08f);
  std::printf("realtime_factor %.2f\n", factor);
  const bool pitch = pitchFollowsTranspose(1.08f) && pitchFollowsTranspose(0.92f);
  const bool ramp = blockwiseTempoRamp();
  return (factor > 1.0 && pitch && ramp) ? 0 : 1;
}
