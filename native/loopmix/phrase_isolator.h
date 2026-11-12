// BitChord phrase isolator. Linkwitz-Riley 4 crossovers at 200 Hz and 2.5 kHz.
// Three bands sum to an allpass of the input, so a unity mix is flat.
// No allocation. Original; the topology is the textbook LR4 cascade.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#pragma once

#include <algorithm>
#include <cmath>

namespace bitchord::loopmix {

struct Butter2 {
  float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
  float z1 = 0, z2 = 0;

  void low(double sr, double freq) { design(sr, freq, true); }
  void high(double sr, double freq) { design(sr, freq, false); }

  float tick(float x) {
    const float y = b0 * x + z1;
    z1 = b1 * x - a1 * y + z2;
    z2 = b2 * x - a2 * y;
    return y;
  }

  void design(double sr, double freq, bool lp) {
    const double w0 = 2.0 * 3.14159265358979323846 * freq / sr;
    const double cosw = std::cos(w0);
    const double sinw = std::sin(w0);
    const double alpha = sinw / (2.0 * 0.7071067811865476);
    const double a0 = 1.0 + alpha;
    if (lp) {
      b0 = float((1.0 - cosw) / 2.0 / a0);
      b1 = float((1.0 - cosw) / a0);
      b2 = b0;
    } else {
      b0 = float((1.0 + cosw) / 2.0 / a0);
      b1 = float(-(1.0 + cosw) / a0);
      b2 = b0;
    }
    a1 = float(-2.0 * cosw / a0);
    a2 = float((1.0 - alpha) / a0);
  }
};

/** LR4 = two Butterworth sections. Low, mid and high, per channel. */
struct PhraseIsolator {
  Butter2 lowA[2], lowB[2];
  Butter2 highA[2], highB[2];
  Butter2 midLpA[2], midLpB[2];
  Butter2 midHpA[2], midHpB[2];
  bool ready = false;

  void setup(double sr) {
    for (int c = 0; c < 2; ++c) {
      lowA[c].low(sr, 200);
      lowB[c].low(sr, 200);
      highA[c].high(sr, 200);
      highB[c].high(sr, 200);
      midLpA[c].low(sr, 2500);
      midLpB[c].low(sr, 2500);
      midHpA[c].high(sr, 2500);
      midHpB[c].high(sr, 2500);
    }
    ready = true;
  }

  void process(int channel, float x, float& low, float& mid, float& high) {
    const float lo = lowB[channel].tick(lowA[channel].tick(x));
    const float rest = highB[channel].tick(highA[channel].tick(x));
    const float md = midLpB[channel].tick(midLpA[channel].tick(rest));
    const float hi = midHpB[channel].tick(midHpA[channel].tick(rest));
    low = lo;
    mid = md;
    high = hi;
  }
};

/** Raised cosine from 1 to 0 over [samples]. Peak sample-to-sample step. */
inline float raisedCosineKillStep(int samples) {
  if (samples < 2) return 1.f;
  float prev = 1.f;
  float peak = 0.f;
  for (int i = 1; i <= samples; ++i) {
    const float p = 0.5f * (1.f + std::cos(3.14159265f * float(i) / float(samples)));
    peak = std::max(peak, std::abs(p - prev));
    prev = p;
  }
  return peak;
}

}  // namespace bitchord::loopmix
