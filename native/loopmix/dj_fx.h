// BitChord Automix: DJ effects, stems storage, loudness and true-peak helpers
// for the loop-mix renderer. Header-only on purpose: the CI native gate compiles
// only loop_mix_renderer.cpp + the A/B harness. Everything here is allocation-free
// after setup() (setup runs on the worker thread, before Renderer::prepare()).
//
// Parameter ranges follow Part A §5 and the Pioneer DJM-900NXS2 BEAT FX /
// SOUND COLOR FX tables:
//   TRANS (gate)      1/16 .. 16 beats (or 1/8 -> 1/16 sweep), swing 0..33 %
//   PHASER            6..8 all-pass stages, 1/16 .. 64 beat cycle, stereo 90 deg
//   VINYL BRAKE       pre-stretch exponential decay exp(-k*t) to 0.05, 12 dB/oct LPF, 10 ms fade
//   TAPE STOP         longer exponential decay with subtle wow modulation
//   SPINBACK          reverse accelerating to ~3x over 1/2 beat + 1-beat silence gap
//   RISER + IMPACT    3-layer build (BP noise, octave uplifter, snare roll) + 1-beat rev-reverb + beat-1 impact
//   COLOR FX FILTER   CCW = LPF down, CW = HPF up, PARAMETER = resonance (Q <= 1.92, <= +6 dB peak)
// Loudness: ITU-R BS.1770-4 K-weighting, EBU R128 short-term (3 s) window.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace bitchord::loopmix::v2 {

constexpr double kPi = 3.14159265358979323846;

/** Envelope kinds added by v2. Mirrored 1:1 in LoopMixRenderer.kt and TransitionScriptV1.kt. */
enum Kind : int {
  KIND_STEM_VOCALS = 9,
  KIND_STEM_DRUMS = 10,
  KIND_STEM_BASS = 11,
  KIND_STEM_OTHER = 12,
  KIND_FILTER_RES = 13,
  KIND_PHASER_WET = 14,
  KIND_GATE_DEPTH = 15,
  KIND_RISER_LEVEL = 16,
  KIND_PUMP_DEPTH = 17,
  KIND_BRAKE = 18,
  KIND_FILTER_KNOB = 19,
};

/** Capability bits (LoopMixRenderer.V2_CAPABILITIES / FxCapability mirror these). */
enum Capability : int {
  CAP_STEMS = 1 << 0,
  CAP_FILTER_RES = 1 << 1,
  CAP_FILTER_KNOB = 1 << 2,
  CAP_PHASER = 1 << 3,
  CAP_GATE = 1 << 4,
  CAP_STUTTER = 1 << 5,
  CAP_RISER = 1 << 6,
  CAP_PUMP = 1 << 7,
  CAP_BRAKE = 1 << 8,
  CAP_SPINBACK = 1 << 9,
  CAP_LOUDNESS = 1 << 10,
  CAP_TRUE_PEAK = 1 << 11,
};
constexpr int kCapabilities = (1 << 12) - 1;

/** Max SVF Q so resonance peak H_peak = Q^2 / sqrt(Q^2 - 0.25) stays <= +6.0 dB (<= 1.995x). */
constexpr float kMaxResonanceQ = 1.92f;

inline float flushTiny(float v) { return std::fabs(v) < 1e-15f ? 0.f : v; }
inline double flushTiny(double v) { return std::fabs(v) < 1e-30 ? 0.0 : v; }

/**
 * DJM-style single filter knob: [knob] in -1..+1, negative = low-pass
 * ("muffle"), positive = high-pass ("thin"), a 4 % centre dead zone like the
 * hardware detent. The travel is shaped (u^1.4) so the first half of the
 * throw is gentle and the second half dives, which is how the DJM Color FX
 * filter feels. LPF 20 kHz -> 150 Hz, HPF 20 Hz -> 10 kHz. [qBoost] rises
 * to 1.6x at full throw (the built-in resonance of the colour filter).
 * hpHz <= 20 and lpHz >= 18000 mean "bypass".
 */
inline void djmKnob(float knob, float& hpHz, float& lpHz, float& qBoost) {
  hpHz = 0.f;
  lpHz = 20000.f;
  qBoost = 1.f;
  const float k = std::clamp(knob, -1.f, 1.f);
  const float a = std::fabs(k);
  constexpr float kDead = 0.04f;
  if (!(a > kDead)) return;  // also NaN
  const float u = (a - kDead) / (1.f - kDead);
  const float c = std::pow(u, 1.4f);
  qBoost = 1.f + 0.6f * c;
  if (k < 0.f) {
    lpHz = 20000.f * std::pow(150.f / 20000.f, c);
  } else {
    hpHz = 20.f * std::pow(10000.f / 20.f, c);
  }
}

/** Quantizes a planar stem to int16 with a per-channel peak scale. Returns the scale (0 = silent). */
inline float quantizeStem(const float* x, int n, int16_t* q) {
  float peak = 0.f;
  for (int i = 0; i < n; ++i) {
    const float a = std::fabs(x[i]);
    if (a > peak && a < 1e6f) peak = a;  // a NaN compares false and is skipped
  }
  if (!(peak > 1e-9f)) {
    for (int i = 0; i < n; ++i) q[i] = 0;
    return 0.f;
  }
  const float scale = peak / 32767.f;
  const float inv = 1.f / scale;
  for (int i = 0; i < n; ++i) {
    float v = x[i] * inv;
    if (!(v > -32767.f)) v = (v <= -32767.f) ? -32767.f : 0.f;  // NaN -> 0
    if (v > 32767.f) v = 32767.f;
    q[i] = int16_t(std::lround(v));
  }
  return scale;
}

/** Cytomic / Simper ZDF SVF with low, band and high outputs (two channels). */
struct Svf {
  float g = 0, k = 1.414f, a1 = 1, a2 = 0, a3 = 0;
  float ic1[2] = {0, 0};
  float ic2[2] = {0, 0};

  void set(double sr, double hz, double q) {
    const double f = std::clamp(hz, 10.0, sr * 0.45);
    g = float(std::tan(kPi * f / sr));
    k = float(1.0 / std::clamp(q, 0.3, double(kMaxResonanceQ)));
    a1 = 1.f / (1.f + g * (g + k));
    a2 = g * a1;
    a3 = g * a2;
  }
  void reset() { ic1[0] = ic1[1] = ic2[0] = ic2[1] = 0.f; }
  void flush() {
    for (int c = 0; c < 2; ++c) { ic1[c] = flushTiny(ic1[c]); ic2[c] = flushTiny(ic2[c]); }
  }
  inline void tick(int ch, float v0, float& lp, float& bp, float& hp) {
    const float v3 = v0 - ic2[ch];
    const float v1 = a1 * ic1[ch] + a2 * v3;
    const float v2 = ic2[ch] + a2 * ic1[ch] + a3 * v3;
    ic1[ch] = 2.f * v1 - ic1[ch];
    ic2[ch] = 2.f * v2 - ic2[ch];
    lp = v2;
    bp = v1;
    hp = v0 - k * v1 - v2;
  }
};

// ---------------------------------------------------------------------------
// Loudness (BS.1770-4 / EBU R128)
// ---------------------------------------------------------------------------

struct Biquad64 {
  double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
  double z1[2] = {0, 0};
  double z2[2] = {0, 0};
  inline double tick(int ch, double x) {
    const double y = b0 * x + z1[ch];
    z1[ch] = b1 * x - a1 * y + z2[ch];
    z2[ch] = b2 * x - a2 * y;
    return y;
  }
  void reset() { z1[0] = z1[1] = z2[0] = z2[1] = 0.0; }
  void flush() {
    for (int c = 0; c < 2; ++c) { z1[c] = flushTiny(z1[c]); z2[c] = flushTiny(z2[c]); }
  }
};

/** BS.1770-4 K-weighting (shelf + RLB high-pass), re-derived for any rate; equals the 48 kHz table. */
struct KWeighting {
  Biquad64 shelf, rlb;
  void setup(double sr) {
    {
      const double f0 = 1681.974450955533, gDb = 3.999843853973347, q = 0.7071752369554196;
      const double k = std::tan(kPi * f0 / sr);
      const double vh = std::pow(10.0, gDb / 20.0);
      const double vb = std::pow(vh, 0.4996667741545416);
      const double a0 = 1.0 + k / q + k * k;
      shelf.b0 = (vh + vb * k / q + k * k) / a0;
      shelf.b1 = 2.0 * (k * k - vh) / a0;
      shelf.b2 = (vh - vb * k / q + k * k) / a0;
      shelf.a1 = 2.0 * (k * k - 1.0) / a0;
      shelf.a2 = (1.0 - k / q + k * k) / a0;
    }
    {
      const double f0 = 38.13547087602444, q = 0.5003270373238773;
      const double k = std::tan(kPi * f0 / sr);
      const double a0 = 1.0 + k / q + k * k;
      rlb.b0 = 1.0;
      rlb.b1 = -2.0;
      rlb.b2 = 1.0;
      rlb.a1 = 2.0 * (k * k - 1.0) / a0;
      rlb.a2 = (1.0 - k / q + k * k) / a0;
    }
    reset();
  }
  void reset() { shelf.reset(); rlb.reset(); }
  void flush() { shelf.flush(); rlb.flush(); }
  inline double tick(int ch, double x) { return rlb.tick(ch, shelf.tick(ch, x)); }
};

/**
 * Sliding K-weighted loudness: 100 ms sub-blocks in a 30-slot ring, so the
 * 3 s EBU R128 short-term value and shorter control windows come from the
 * same data. Push per sample; read anywhere (render thread only).
 */
struct LoudnessMeter {
  static constexpr int kSlots = 30;
  KWeighting kw;
  int subLen = 4800;
  int count = 0;
  double acc = 0;
  double ring[kSlots] = {};
  int pos = 0;
  int filled = 0;

  void setup(double sr) {
    kw.setup(sr);
    subLen = std::max(1, int(std::lround(sr * 0.1)));
    reset();
  }
  void reset() {
    kw.reset();
    count = 0;
    acc = 0;
    for (double& v : ring) v = 0;
    pos = 0;
    filled = 0;
  }
  inline void push(float l, float r) {
    const double yl = kw.tick(0, double(l));
    const double yr = kw.tick(1, double(r));
    acc += yl * yl + yr * yr;
    if (++count >= subLen) {
      ring[pos] = std::isfinite(acc) ? acc / double(count) : 0.0;
      pos = (pos + 1) % kSlots;
      if (filled < kSlots) ++filled;
      acc = 0;
      count = 0;
      kw.flush();
    }
  }
  bool valid() const { return filled > 0; }
  /** Mean channel-summed K-weighted power over the last [slots] sub-blocks (0 if none). */
  double meanPower(int slots) const {
    const int m = std::min(std::clamp(slots, 1, kSlots), filled);
    if (m <= 0) return 0.0;
    double s = 0;
    for (int i = 1; i <= m; ++i) s += ring[(pos - i + kSlots) % kSlots];
    return s / double(m);
  }
  static double lufs(double power) { return power > 1e-12 ? -0.691 + 10.0 * std::log10(power) : -120.0; }
  double shortTermLufs() const { return lufs(meanPower(kSlots)); }
};

// ---------------------------------------------------------------------------
// Phaser: 6-8 first-order all-pass stages, beat-synced LFO (1 cycle/bar default),
// stereo quadrature (90 deg default).
// ---------------------------------------------------------------------------

struct Phaser {
  static constexpr int kMaxStages = 8;
  bool enabled = false;
  int stages = 6;
  double rateBeats = 4.0;
  double stereoOffsetCycles = 0.25;
  float depth = 0.8f;
  float feedback = 0.6f;
  float zL[kMaxStages] = {};
  float zR[kMaxStages] = {};
  float lastL = 0, lastR = 0;
  float aL0 = 0, aL1 = 0, aR0 = 0, aR1 = 0;

  void setup(double rateBeatsIn, float depthIn, float feedbackIn, int stagesIn = 6, float stereoOffsetDeg = 90.f) {
    enabled = std::isfinite(rateBeatsIn) && rateBeatsIn > 0.0 && depthIn > 0.f;
    rateBeats = std::clamp(enabled ? rateBeatsIn : 4.0, 1.0 / 16.0, 64.0);
    stages = std::clamp(stagesIn, 4, kMaxStages);
    if ((stages & 1) != 0) stages = std::clamp(stages + 1, 6, kMaxStages);
    stereoOffsetCycles = std::isfinite(stereoOffsetDeg) ? double(stereoOffsetDeg) / 360.0 : 0.25;
    depth = std::clamp(depthIn, 0.f, 1.f);
    feedback = std::isfinite(feedbackIn) ? std::clamp(feedbackIn, -0.85f, 0.85f) : 0.f;
    reset();
  }
  void reset() {
    for (int s = 0; s < kMaxStages; ++s) zL[s] = zR[s] = 0.f;
    lastL = lastR = 0.f;
    aL0 = aL1 = aR0 = aR1 = 0.f;
  }
  static float coefFor(double phase, float depth, double sr) {
    const double lfo = 0.5 - 0.5 * std::cos(2.0 * kPi * phase);
    const double octaves = 0.5 + 4.0 * double(depth);  // 220 Hz .. ~5 kHz at depth 1
    const double fc = std::min(220.0 * std::pow(2.0, octaves * lfo), sr * 0.42);
    const double t = std::tan(kPi * fc / sr);
    return float((t - 1.0) / (t + 1.0));
  }
  /** Block-rate: coefficient endpoints from the beat positions at the block's ends. */
  void control(double beatPos0, double beatPos1, double sr) {
    const double p0 = beatPos0 / rateBeats, p1 = beatPos1 / rateBeats;
    aL0 = coefFor(p0, depth, sr);
    aL1 = coefFor(p1, depth, sr);
    aR0 = coefFor(p0 + stereoOffsetCycles, depth, sr);
    aR1 = coefFor(p1 + stereoOffsetCycles, depth, sr);
  }
  /** Always runs the all-pass chain (state continuity); mixes only when [wet] > 0. */
  inline void process(float& l, float& r, float frac, float wet) {
    const float aL = aL0 + (aL1 - aL0) * frac;
    const float aR = aR0 + (aR1 - aR0) * frac;
    float xl = l + feedback * lastL;
    float xr = r + feedback * lastR;
    for (int s = 0; s < stages; ++s) {
      const float yl = aL * xl + zL[s];
      zL[s] = xl - aL * yl;
      xl = yl;
      const float yr = aR * xr + zR[s];
      zR[s] = xr - aR * yr;
      xr = yr;
    }
    lastL = flushTiny(std::clamp(xl, -4.f, 4.f));
    lastR = flushTiny(std::clamp(xr, -4.f, 4.f));
    if (wet > 0.f) {
      const float w = std::min(wet, 1.f);
      const float comp = 1.f + 0.15f * w;  // notches remove ~1.2 dB on average at full depth
      l = flushTiny((l + w * 0.5f * (xl - l)) * comp);
      r = flushTiny((r + w * 0.5f * (xr - r)) * comp);
    }
  }
  void flush() {
    for (int s = 0; s < kMaxStages; ++s) { zL[s] = flushTiny(zL[s]); zR[s] = flushTiny(zR[s]); }
    lastL = flushTiny(lastL);
    lastR = flushTiny(lastR);
  }
};

// ---------------------------------------------------------------------------
// Beat gate (DJM TRANS / trance gate: fixed or 1/8 -> 1/16) and stutter (division < 0).
// ---------------------------------------------------------------------------

struct BeatGate {
  bool enabled = false;
  bool stutter = false;
  bool sweepDiv = false;
  double beatSec = 0.5;
  float division = 4.f;     // gate steps per beat (2 = 1/8, 4 = 1/16)
  float divisionEnd = 4.f;  // used when sweepDiv is true (e.g. 2 -> 4 over 2 bars)
  float swing = 0.f;        // 0 .. 0.33, delays every second step (0..10% typical)
  double startSec = 0.0;
  double endSec = 1e18;
  // Stutter slice (captured live during the first step, then repeated).
  std::vector<float> sliceL, sliceR;
  int sliceCap = 0, sliceLen = 0, sliceWrite = 0, sliceRead = 0, fade = 64;
  int state = 0;  // 0 idle, 1 capturing, 2 repeating
  int64_t lastStep = INT64_MIN;

  void setup(double sr, double beatSecIn, float divisionIn, float swingIn) {
    setupEx(sr, beatSecIn, divisionIn, divisionIn, swingIn, 0.0, 1e18);
  }

  void setupEx(double sr, double beatSecIn, float divStartIn, float divEndIn, float swingIn,
               double startSecIn, double endSecIn) {
    enabled = false;
    sweepDiv = false;
    sliceL.clear();
    sliceR.clear();
    sliceCap = 0;
    const float dv = std::fabs(divStartIn);
    const float dvEnd = std::fabs(divEndIn);
    if (!(sr > 0) || !(beatSecIn > 0.1 && beatSecIn < 4.0) || !(dv >= 1.f / 16.f && dv <= 16.f)) return;
    beatSec = beatSecIn;
    division = dv;
    divisionEnd = (dvEnd >= 1.f / 16.f && dvEnd <= 16.f) ? dvEnd : dv;
    stutter = divStartIn < 0.f;
    sweepDiv = !stutter && std::fabs(divisionEnd - division) > 0.01f && endSecIn > startSecIn && endSecIn < 1e15;
    swing = std::isfinite(swingIn) ? std::clamp(swingIn, 0.f, 0.33f) : 0.f;
    startSec = std::isfinite(startSecIn) ? startSecIn : 0.0;
    endSec = std::isfinite(endSecIn) ? endSecIn : 1e18;
    fade = std::max(8, int(std::lround(0.0015 * sr)));
    if (stutter) {
      sliceCap = std::min(int(std::ceil(beatSec / dv * sr * 1.5)) + 64, int(sr * 6.0));
      sliceL.assign(sliceCap, 0.f);
      sliceR.assign(sliceCap, 0.f);
    }
    enabled = true;
    reset();
  }

  void reset() {
    state = 0;
    sliceLen = sliceWrite = sliceRead = 0;
    lastStep = INT64_MIN;
    std::fill(sliceL.begin(), sliceL.end(), 0.f);
    std::fill(sliceR.begin(), sliceR.end(), 0.f);
  }

  /** Effective gate division at mix time [tMix]; supports 1/8 -> 1/16 step on midpoint beat. */
  float activeDivisionAt(double tMix) const {
    if (!sweepDiv) return division;
    const double mid = 0.5 * (startSec + endSec);
    return tMix < mid ? division : divisionEnd;
  }

  /** Gate gain 0..1 at [beatPos]; [beatDur] is local beat length in seconds. Duty 50 %, 2 ms / 8 ms edges. */
  float gateValueFor(double beatPos, double beatDur, float div) const {
    const double steps = beatPos * double(div);
    double stepSec = beatDur / double(div);
    double q;
    if (swing > 0.f) {
      const double pair = steps * 0.5;
      const double ph = pair - std::floor(pair);
      const double first = 0.5 + double(swing) * 0.5;
      if (ph < first) {
        q = ph / first;
        stepSec *= 2.0 * first;
      } else {
        q = (ph - first) / (1.0 - first);
        stepSec *= 2.0 * (1.0 - first);
      }
    } else {
      q = steps - std::floor(steps);
    }
    const double t = q * stepSec;
    const double open = 0.5 * stepSec;
    const double att = std::min(0.002, stepSec * 0.2);
    const double rel = std::min(0.008, stepSec * 0.25);
    if (t < att) return float(0.5 - 0.5 * std::cos(kPi * t / att));
    if (t < open) return 1.f;
    if (t < open + rel) return float(0.5 + 0.5 * std::cos(kPi * (t - open) / rel));
    return 0.f;
  }

  float gateValue(double beatPos, double beatDur) const {
    return gateValueFor(beatPos, beatDur, division);
  }

  /** Clean release multiplier on beat 1 when an explicit endSec is active. */
  float releaseBlendAt(double tMix) const {
    if (!(endSec < 1e15) || tMix < endSec) return 1.f;
    constexpr double kRelFade = 0.004;
    if (tMix >= endSec + kRelFade) return 0.f;
    const double u = (tMix - endSec) / kRelFade;
    return float(0.5 * (1.0 + std::cos(kPi * u)));
  }

  /** Stutter: [step] = floor(beatPos * division). Live passes until a slice exists. */
  inline void stutterTick(float& l, float& r, float depth, int64_t step) {
    if (step != lastStep) {
      lastStep = step;
      if (state == 1) {
        sliceLen = sliceWrite;
        state = sliceLen > 2 * fade ? 2 : 0;
      }
      if (state == 0 && depth > 0.f) {
        state = 1;
        sliceWrite = 0;
      }
      sliceRead = 0;
    }
    if (!(depth > 0.f)) {
      state = 0;
      return;
    }
    if (state == 1) {
      if (sliceWrite < sliceCap) {
        sliceL[sliceWrite] = l;
        sliceR[sliceWrite] = r;
        ++sliceWrite;
      }
      return;
    }
    if (state == 2) {
      float sl = 0.f, sr = 0.f, w = 0.f;
      if (sliceRead < sliceLen) {
        sl = sliceL[sliceRead];
        sr = sliceR[sliceRead];
        w = 1.f;
        if (sliceRead < fade) w = 0.5f - 0.5f * std::cos(float(kPi) * float(sliceRead) / float(fade));
        else if (sliceLen - sliceRead <= fade) w = 0.5f - 0.5f * std::cos(float(kPi) * float(sliceLen - sliceRead) / float(fade));
      }
      ++sliceRead;
      const float d = std::min(depth, 1.f);
      l = flushTiny(l + d * (sl * w - l));
      r = flushTiny(r + d * (sr * w - r));
    }
  }
};

// ---------------------------------------------------------------------------
// Sidechain-style pump: beat-locked duck of the deck's low band (< 120 Hz),
// so the incoming kick never fights the outgoing bass; fullBand = EDM pump.
// ---------------------------------------------------------------------------

struct Pump {
  bool enabled = false;
  bool fullBand = false;
  double beatSec = 0.5;
  Svf split;

  void setup(double sr, double beatSecIn, bool fullBandIn) {
    enabled = sr > 0 && beatSecIn > 0.1 && beatSecIn < 4.0;
    beatSec = enabled ? beatSecIn : 0.5;
    fullBand = fullBandIn;
    if (sr > 0) split.set(sr, 120.0, 0.7071);
    split.reset();
  }
  void reset() { split.reset(); }
  /** Duck 0..1 at beat phase [ph] (0 = on the beat): 4 ms raised-cosine attack, cosine release over 0.6 beat. */
  static float duck(double ph, double beatDur) {
    const double att = std::min(0.15, 0.004 / std::max(0.05, beatDur));
    if (ph < att) return float(0.5 - 0.5 * std::cos(kPi * ph / att));
    const double u = (ph - att) / 0.6;
    if (u >= 1.0) return 0.f;
    return float(0.5 + 0.5 * std::cos(kPi * u));
  }
  /** [amount] = depth * duck, 0..1. The split runs every sample for continuity. */
  inline void tick(float& l, float& r, float amount) {
    float lpL, bpL, hpL, lpR, bpR, hpR;
    split.tick(0, l, lpL, bpL, hpL);
    split.tick(1, r, lpR, bpR, hpR);
    if (!(amount > 0.f)) return;
    const float a = std::min(amount, 1.f);
    if (fullBand) {
      const float g = 1.f - 0.7f * a;  // -10.5 dB at full depth
      l = flushTiny(l * g);
      r = flushTiny(r * g);
    } else {
      l = flushTiny(l - a * lpL);  // x - a*low: phase-coherent complement of the split
      r = flushTiny(r - a * lpR);
    }
  }
};

// ---------------------------------------------------------------------------
// 3-layer tempo-synced Riser (Part A §5.6):
//   1. Band-pass white-noise sweep 300 Hz -> 8 kHz exp (level 0 -> 0.7)
//   2. Pitch-rising saw/sine uplifter (+1 octave over build)
//   3. OUT track's snare/hat roll in last 2 bars (1/4 -> 1/8 -> 1/16 -> 1/32)
//   + 1-beat reverse-reverb tail into drop, cut cleanly on beat 1.
// ---------------------------------------------------------------------------

struct Riser {
  bool enabled = false;
  double sr = 48000.0;
  double beatSec = 0.5;
  double lengthBeats = 32.0;
  double startSec = 1e18;        // from KIND_RISER_LEVEL or explicit startSec
  double explicitStartSec = -1.0;
  float noiseStartHz = 300.f;
  float noiseEndHz = 8000.f;
  float noiseLevelPeak = 0.7f;
  float uplifterOctaves = 1.0f;
  bool includeSnareRoll = true;
  double reverseReverbBeats = 1.0;

  uint32_t seedL = 0x2545F491u, seedR = 0x9E3779B9u;
  Svf bpNoise, hpSnare, lpUp;
  double progress = 0.0;
  double upPhaseL = 0.0, upPhaseR = 0.0;
  double upFreq = 220.0;
  float capHpL = 0.f, capHpR = 0.f;
  float capHpA = 0.04f;

  // Pre-allocated 1-beat capture & reverse-reverb buffers (worker thread only).
  std::vector<float> snareBufL, snareBufR;
  int snareCap = 0, snareWrite = 0, snareBestOffset = 0;
  bool snareLocked = false;
  int64_t lastRollStep = INT64_MIN;
  int snarePlayIdx = 0;
  double snareStepStartSec = 0.0;

  std::vector<float> revVerbL, revVerbR;
  int revCap = 0, revWrite = 0, revCapturedLen = 0;
  std::vector<float> revCombL[2], revCombR[2];
  int revCombPos[2] = {0, 0};

  void setup(double beatSecIn, double lengthBeatsIn) {
    setupEx(48000.0, beatSecIn, lengthBeatsIn, 300.f, 8000.f, 0.7f, 1.0f, true, 1.0, -1.0);
  }

  void setupEx(double srIn, double beatSecIn, double lengthBeatsIn,
               float noiseStartIn, float noiseEndIn, float noisePeakIn,
               float octavesIn, bool snareRollIn, double revBeatsIn, double startSecIn) {
    enabled = false;
    snareBufL.clear(); snareBufR.clear();
    revVerbL.clear(); revVerbR.clear();
    for (int k = 0; k < 2; ++k) { revCombL[k].clear(); revCombR[k].clear(); }
    snareCap = revCap = 0;
    sr = srIn > 0.0 ? srIn : 48000.0;
    if (!(beatSecIn > 0.1 && beatSecIn < 4.0 && lengthBeatsIn >= 0.25 && lengthBeatsIn <= 256.0)) return;
    beatSec = beatSecIn;
    lengthBeats = lengthBeatsIn;
    noiseStartHz = std::clamp(std::isfinite(noiseStartIn) ? noiseStartIn : 300.f, 60.f, 12000.f);
    noiseEndHz = std::clamp(std::isfinite(noiseEndIn) ? noiseEndIn : 8000.f, noiseStartHz + 50.f, float(sr * 0.42));
    noiseLevelPeak = std::clamp(std::isfinite(noisePeakIn) ? noisePeakIn : 0.7f, 0.f, 1.5f);
    uplifterOctaves = std::clamp(std::isfinite(octavesIn) ? octavesIn : 1.0f, 0.25f, 3.0f);
    includeSnareRoll = snareRollIn;
    reverseReverbBeats = std::clamp(std::isfinite(revBeatsIn) ? revBeatsIn : 1.0, 0.0, 2.0);
    explicitStartSec = (std::isfinite(startSecIn) && startSecIn >= 0.0) ? startSecIn : -1.0;

    const int beatFrames = std::clamp(int(std::ceil(beatSec * sr)) + 64, 1024, int(sr * 2.5));
    snareCap = beatFrames;
    snareBufL.assign(snareCap, 0.f);
    snareBufR.assign(snareCap, 0.f);
    revCap = beatFrames;
    revVerbL.assign(revCap, 0.f);
    revVerbR.assign(revCap, 0.f);
    const int c0 = std::max(64, int(std::lround(0.0297 * sr)));
    const int c1 = std::max(64, int(std::lround(0.0411 * sr)));
    revCombL[0].assign(c0, 0.f); revCombR[0].assign(c0 + 13, 0.f);
    revCombL[1].assign(c1, 0.f); revCombR[1].assign(c1 + 17, 0.f);
    capHpA = float(1.0 - std::exp(-2.0 * kPi * 300.0 / sr));
    enabled = true;
    reset();
  }

  void reset() {
    seedL = 0x2545F491u;
    seedR = 0x9E3779B9u;
    bpNoise.reset();
    hpSnare.reset();
    lpUp.reset();
    progress = 0.0;
    upPhaseL = upPhaseR = 0.0;
    upFreq = 220.0;
    capHpL = capHpR = 0.f;
    snareWrite = snareBestOffset = snarePlayIdx = 0;
    snareLocked = false;
    lastRollStep = INT64_MIN;
    snareStepStartSec = 0.0;
    revWrite = revCapturedLen = 0;
    revCombPos[0] = revCombPos[1] = 0;
    std::fill(snareBufL.begin(), snareBufL.end(), 0.f);
    std::fill(snareBufR.begin(), snareBufR.end(), 0.f);
    std::fill(revVerbL.begin(), revVerbL.end(), 0.f);
    std::fill(revVerbR.begin(), revVerbR.end(), 0.f);
    for (int k = 0; k < 2; ++k) {
      std::fill(revCombL[k].begin(), revCombL[k].end(), 0.f);
      std::fill(revCombR[k].begin(), revCombR[k].end(), 0.f);
    }
  }

  double lengthSec() const { return std::max(0.25, lengthBeats * beatSec); }
  double endSec() const { return startSec + lengthSec(); }

  static inline float white(uint32_t& s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return float(int32_t(s)) * (1.f / 2147483648.f);
  }

  static inline float polyBlep(double t, double dt) {
    if (t < dt) {
      const double x = t / dt;
      return float(x + x - x * x - 1.0);
    }
    if (t > 1.0 - dt) {
      const double x = (t - 1.0) / dt;
      return float(x * x + x + x + 1.0);
    }
    return 0.f;
  }

  void control(double tSec, double sampleRate) {
    sr = sampleRate > 0.0 ? sampleRate : sr;
    const double len = lengthSec();
    progress = std::clamp((tSec - startSec) / len, 0.0, 1.0);
    const double p = progress;
    // Layer 1: band-pass white-noise sweep 300 Hz -> 8 kHz exp.
    const double fc = double(noiseStartHz) * std::pow(double(noiseEndHz) / double(noiseStartHz), p);
    bpNoise.set(sr, fc, 1.5 + 1.1 * p);
    bpNoise.flush();
    // Layer 2: pitch-rising saw/sine uplifter (+1 octave over build).
    upFreq = 220.0 * std::pow(2.0, double(uplifterOctaves) * p);
    lpUp.set(sr, std::min(upFreq * 5.0, sr * 0.42), 0.7071);
    lpUp.flush();
    // Layer 3: rising HPF on OUT snare/hat roll.
    const double remBeats = std::max(0.0, (endSec() - tSec) / std::max(0.1, beatSec));
    const double rollFrac = std::clamp(1.0 - remBeats / 8.0, 0.0, 1.0);
    hpSnare.set(sr, 260.0 * std::pow(1600.0 / 260.0, rollFrac), 0.8);
    hpSnare.flush();
  }

  /** Capture OUT deck's high-passed transient slice before the last 2 bars lock. */
  inline void captureDeck(float inL, float inR, double tSec) {
    if (!enabled || snareCap <= 0 || snareLocked) return;
    capHpL += capHpA * (inL - capHpL);
    capHpR += capHpA * (inR - capHpR);
    const float hL = flushTiny(inL - capHpL);
    const float hR = flushTiny(inR - capHpR);
    const double lockAt = endSec() - std::min(8.0, lengthBeats * 0.5) * beatSec;
    if (startSec < 1e15 && tSec >= lockAt) {
      // Lock and pick the loudest 10 ms transient window in the 1-beat buffer.
      snareLocked = true;
      const int win = std::max(1, int(std::lround(0.010 * sr)));
      const int step = std::max(1, win / 2);
      double bestE = -1.0;
      int bestPos = 0;
      for (int off = 0; off < snareCap; off += step) {
        double e = 0.0;
        for (int k = 0; k < win; ++k) {
          const int idx = (off + k) % snareCap;
          e += double(snareBufL[idx]) * snareBufL[idx] + double(snareBufR[idx]) * snareBufR[idx];
        }
        if (e > bestE) { bestE = e; bestPos = off; }
      }
      snareBestOffset = bestPos;
      return;
    }
    snareBufL[snareWrite] = hL;
    snareBufR[snareWrite] = hR;
    if (++snareWrite >= snareCap) snareWrite = 0;
  }

  /** Tremolo modulation for noise layer: gentle tempo pulse. */
  float tremolo(double beatPos) const {
    const double steps = progress < 0.5 ? 2.0 : (progress < 0.75 ? 4.0 : 8.0);
    const double x = beatPos * steps;
    const double ph = x - std::floor(x);
    return float(0.78 + 0.22 * (0.5 + 0.5 * std::cos(2.0 * kPi * ph)));
  }

  inline void tick(float gainEnv, double tSec, double beatPos, double beatDur, float& outL, float& outR) {
    outL = 0.f;
    outR = 0.f;
    if (!enabled || !(gainEnv > 0.f)) return;
    const double tEnd = endSec();
    if (tSec >= tEnd) return;  // Clean cut on beat 1

    const double len = lengthSec();
    const double p = std::clamp((tSec - startSec) / len, 0.0, 1.0);
    const double bDur = beatDur > 0.1 ? beatDur : beatSec;
    const double remBeats = std::max(0.0, (tEnd - tSec) / bDur);

    // Layer 1: band-pass white-noise sweep 300 Hz -> 8 kHz (level 0 -> noiseLevelPeak).
    const float wL = white(seedL);
    const float wR = white(seedR);
    float lp, bpL, bpR, hp;
    bpNoise.tick(0, wL, lp, bpL, hp);
    bpNoise.tick(1, wR, lp, bpR, hp);
    const float noiseAmp = float(p) * noiseLevelPeak * 0.24f * tremolo(beatPos);
    const float l1L = bpL * noiseAmp;
    const float l1R = bpR * noiseAmp;

    // Layer 2: pitch-rising PolyBLEP saw + sine uplifter (+1 octave over build).
    const double dtL = upFreq / sr;
    const double dtR = (upFreq + 0.35) / sr;
    upPhaseL += dtL;
    if (upPhaseL >= 1.0) upPhaseL -= std::floor(upPhaseL);
    upPhaseR += dtR;
    if (upPhaseR >= 1.0) upPhaseR -= std::floor(upPhaseR);
    const float sawL = float(2.0 * upPhaseL - 1.0) - polyBlep(upPhaseL, dtL);
    const float sawR = float(2.0 * upPhaseR - 1.0) - polyBlep(upPhaseR, dtR);
    const float sinL = float(std::sin(2.0 * kPi * upPhaseL));
    const float sinR = float(std::sin(2.0 * kPi * upPhaseR));
    float upL, upR, dumA, dumB;
    lpUp.tick(0, 0.55f * sinL + 0.45f * sawL, upL, dumA, dumB);
    lpUp.tick(1, 0.55f * sinR + 0.45f * sawR, upR, dumA, dumB);
    const float upAmp = 0.09f * std::pow(float(p), 1.3f);
    const float l2L = upL * upAmp;
    const float l2R = upR * upAmp;

    // Layer 3: OUT track's snare/hat roll in last 2 bars (<= 8 beats before drop).
    float l3L = 0.f, l3R = 0.f;
    if (includeSnareRoll && remBeats <= 8.0 && remBeats > 0.0 && lengthBeats >= 4.0 && snareCap > 0) {
      const double stepsPerBeat = remBeats > 4.0 ? 1.0 : (remBeats > 2.0 ? 2.0 : (remBeats > 1.0 ? 4.0 : 8.0));
      const int64_t step = int64_t(std::floor(beatPos * stepsPerBeat));
      if (step != lastRollStep) {
        lastRollStep = step;
        snarePlayIdx = 0;
        snareStepStartSec = tSec;
      }
      const double stepElapsed = std::max(0.0, tSec - snareStepStartSec);
      const double stepDur = bDur / stepsPerBeat;
      const float env = float(std::exp(-stepElapsed / std::min(0.085, stepDur * 0.75)));
      const int idx = (snareBestOffset + snarePlayIdx) % snareCap;
      if (++snarePlayIdx >= snareCap) snarePlayIdx = 0;
      const float rawL = snareBufL[idx] + 0.08f * wL;
      const float rawR = snareBufR[idx] + 0.08f * wR;
      float sHpL, sHpR;
      hpSnare.tick(0, rawL * env, dumA, dumB, sHpL);
      hpSnare.tick(1, rawR * env, dumA, dumB, sHpR);
      const float rollRamp = float(std::clamp(1.0 - remBeats / 8.0, 0.0, 1.0));
      const float rollGain = 0.28f * (0.35f + 0.65f * rollRamp);
      l3L = sHpL * rollGain;
      l3R = sHpR * rollGain;
    }

    // 1-beat reverse-reverb tail into drop:
    //   Feed mini-diffuser during remBeats in [reverseReverbBeats, 2*reverseReverbBeats]
    //   Play diffused tail BACKWARDS during final [0, reverseReverbBeats] into beat 1.
    float revOutL = 0.f, revOutR = 0.f;
    if (reverseReverbBeats > 0.05 && revCap > 0) {
      const double rB = std::min(reverseReverbBeats, lengthBeats * 0.45);
      if (remBeats > rB && remBeats <= 2.0 * rB) {
        const float srcL = l1L + l3L;
        const float srcR = l1R + l3R;
        float accL = 0.f, accR = 0.f;
        for (int k = 0; k < 2; ++k) {
          const int szL = int(revCombL[k].size());
          const int szR = int(revCombR[k].size());
          const int pL = revCombPos[k] % szL;
          const int pR = revCombPos[k] % szR;
          const float yL = revCombL[k][pL];
          const float yR = revCombR[k][pR];
          revCombL[k][pL] = flushTiny(srcL + 0.72f * yL);
          revCombR[k][pR] = flushTiny(srcR + 0.72f * yR);
          revCombPos[k] = (revCombPos[k] + 1);
          if (revCombPos[k] >= std::max(szL, szR) * 64) revCombPos[k] = 0;
          accL += yL;
          accR += yR;
        }
        if (revWrite < revCap) {
          revVerbL[revWrite] = accL * 0.5f;
          revVerbR[revWrite] = accR * 0.5f;
          ++revWrite;
          revCapturedLen = revWrite;
        }
      } else if (remBeats <= rB) {
        const double u = std::clamp(1.0 - remBeats / rB, 0.0, 1.0);
        const float swell = float(std::exp(3.0 * (u - 1.0)));  // ~0.05 -> 1.0 into beat 1
        if (revCapturedLen > 16) {
          const int rIdx = std::clamp(int(std::llround((1.0 - u) * double(revCapturedLen - 1))), 0, revCapturedLen - 1);
          revOutL = revVerbL[rIdx] * swell * 0.45f;
          revOutR = revVerbR[rIdx] * swell * 0.45f;
        } else {
          // Short riser (< 2 beats): filtered noise swell fallback
          revOutL = l1L * swell * 0.5f;
          revOutR = l1R * swell * 0.5f;
        }
      }
    }

    // 1.5 ms clean edge into beat 1 so cut at tEnd never clicks.
    const double remSec = tEnd - tSec;
    const float cutEdge = remSec < 0.0015 ? float(0.5 - 0.5 * std::cos(kPi * remSec / 0.0015)) : 1.f;
    const float g = std::min(gainEnv, 2.f) * cutEdge;
    outL = flushTiny(std::clamp((l1L + l2L + l3L + revOutL) * g, -0.85f, 0.85f));
    outR = flushTiny(std::clamp((l1R + l2R + l3R + revOutR) * g, -0.85f, 0.85f));
  }
};

// ---------------------------------------------------------------------------
// Drop Impact (Part A §5.6): low thump + short noise burst on beat 1, <= -6 dBFS.
// ---------------------------------------------------------------------------

struct Impact {
  bool enabled = false;
  double sr = 48000.0;
  double triggerMixSec = 0.0;
  float peakLin = 0.501187f;  // -6 dBFS max
  float lowThumpHz = 55.f;
  double noiseBurstSec = 0.180;
  double phase = 0.0;
  uint32_t seedL = 0x517CC1B7u, seedR = 0x85EBCA6Bu;
  Svf noiseLp;

  void setup(double srIn, double triggerSec, float peakDbfs, float thumpHz, double burstMs) {
    enabled = false;
    sr = srIn > 0.0 ? srIn : 48000.0;
    if (!std::isfinite(triggerSec)) return;
    triggerMixSec = triggerSec;
    const float safeDb = std::isfinite(peakDbfs) ? std::min(peakDbfs, -6.0f) : -6.0f;
    peakLin = std::clamp(float(std::pow(10.0, double(safeDb) / 20.0)), 0.01f, 0.501187f);
    lowThumpHz = std::clamp(std::isfinite(thumpHz) ? thumpHz : 55.f, 28.f, 140.f);
    noiseBurstSec = std::clamp(std::isfinite(burstMs) ? burstMs * 0.001 : 0.18, 0.03, 1.5);
    enabled = true;
    reset();
  }

  void reset() {
    phase = 0.0;
    seedL = 0x517CC1B7u;
    seedR = 0x85EBCA6Bu;
    noiseLp.reset();
  }

  void control(double tSec) {
    if (!enabled || tSec < triggerMixSec) return;
    const double dt = tSec - triggerMixSec;
    const double u = std::clamp(dt / noiseBurstSec, 0.0, 1.0);
    const double hz = 8000.0 * std::pow(700.0 / 8000.0, u);
    noiseLp.set(sr, hz, 0.7071);
    noiseLp.flush();
  }

  inline void tick(double tSec, float& outL, float& outR) {
    outL = 0.f;
    outR = 0.f;
    if (!enabled || tSec < triggerMixSec) return;
    const double dt = tSec - triggerMixSec;
    const double totalDur = std::max(0.55, noiseBurstSec * 3.5);
    if (dt >= totalDur) return;

    const float att = dt < 0.002 ? float(0.5 - 0.5 * std::cos(kPi * dt / 0.002)) : 1.f;
    // Pitch-dropping sub thump: 135 Hz -> lowThumpHz
    const double f = double(lowThumpHz) + (135.0 - double(lowThumpHz)) * std::exp(-dt / 0.030);
    phase += f / sr;
    if (phase >= 1.0) phase -= std::floor(phase);
    const float thump = float(std::sin(2.0 * kPi * phase) * std::exp(-dt / 0.135)) * 0.70f;

    // Short decaying stereo noise burst
    const float wL = Riser::white(seedL);
    const float wR = Riser::white(seedR);
    float nL, nR, dumA, dumB;
    noiseLp.tick(0, wL, nL, dumA, dumB);
    noiseLp.tick(1, wR, nR, dumA, dumB);
    const float nEnv = float(std::exp(-dt / std::max(0.02, noiseBurstSec * 0.45))) * 0.40f;

    const float scale = att * peakLin;
    outL = flushTiny(std::clamp((thump + nL * nEnv) * scale, -peakLin, peakLin));
    outR = flushTiny(std::clamp((thump + nR * nEnv) * scale, -peakLin, peakLin));
  }
};

// ---------------------------------------------------------------------------
// Pre-stretch Vinyl Brake / Tape-Stop / Spinback (Part A §5.7, §5.8):
// Drives the pre-stretch read pointer directly (pitch follows speed, zero time-stretch).
//   VINYL_BRAKE: r(t) = exp(-k*t) to 0.05 over 1-2 beats, 12 dB/oct LPF 18k -> 800 Hz, 10 ms end fade.
//   TAPE_STOP:   longer exponential decay with subtle wow modulation + LPF + 10 ms end fade.
//   SPINBACK:    reverse accelerating to ~3x over 1/2 beat + clean 1-beat silence gap.
// ---------------------------------------------------------------------------

enum class BrakeMode : int {
  VINYL_BRAKE = 0,
  TAPE_STOP = 1,
  SPINBACK = 2,
};

struct Brake {
  static constexpr double kSpin = 3.0;
  bool enabled = false;
  BrakeMode mode = BrakeMode::VINYL_BRAKE;
  bool spinback = false;
  bool done = false;
  bool active = false;
  double sr = 48000.0;
  double startSec = 0.0;
  double lengthSec = 0.5;
  double silenceGapSec = 0.0;
  double endRate = 0.05;
  double kExp = 2.995732273553991;  // -ln(0.05)
  float wowDepth = 0.0f;
  float wowRateHz = 5.0f;
  float lpfStartHz = 18000.f;
  float lpfEndHz = 800.f;
  double endFadeSec = 0.010;
  double peakReverseSpeed = kSpin;

  // Pre-stretch varispeed playback state (render thread only).
  double readCursor = 0.0;
  int handoffSamples = 0;
  int handoffTotal = 0;
  double r0 = 1.0, r1 = 1.0;
  float amp0 = 1.f, amp1 = 1.f;
  Svf lpf;

  void setup(double srIn, double startIn, double lengthIn, bool spin) {
    if (spin) {
      setupSpinback(srIn, startIn, lengthIn, kSpin, 0.0);
    } else {
      setupBrakeEx(srIn, startIn, lengthIn, 0.05f, 18000.f, 800.f, 10.0);
    }
  }

  void setupBrakeEx(double srIn, double startIn, double lengthIn,
                    float endRateIn, float lpfStartIn, float lpfEndIn, double endFadeMsIn) {
    enabled = false;
    sr = srIn > 0.0 ? srIn : 48000.0;
    if (!(srIn > 0) || !std::isfinite(startIn) || !(lengthIn > 0.02)) return;
    mode = BrakeMode::VINYL_BRAKE;
    spinback = false;
    startSec = startIn;
    lengthSec = std::clamp(lengthIn, 0.02, 8.0);
    silenceGapSec = 0.0;
    endRate = std::clamp(std::isfinite(endRateIn) ? double(endRateIn) : 0.05, 0.01, 0.45);
    kExp = -std::log(endRate);
    wowDepth = 0.0f;
    wowRateHz = 5.0f;
    lpfStartHz = std::clamp(std::isfinite(lpfStartIn) ? lpfStartIn : 18000.f, 2000.f, float(sr * 0.45));
    lpfEndHz = std::clamp(std::isfinite(lpfEndIn) ? lpfEndIn : 800.f, 100.f, lpfStartHz);
    endFadeSec = std::clamp(std::isfinite(endFadeMsIn) ? endFadeMsIn * 0.001 : 0.010, 0.001, lengthSec * 0.5);
    peakReverseSpeed = kSpin;
    enabled = true;
    reset();
  }

  void setupTapeStop(double srIn, double startIn, double lengthIn,
                     float wowDepthIn, float wowRateIn,
                     float lpfStartIn, float lpfEndIn, double endFadeMsIn) {
    setupBrakeEx(srIn, startIn, lengthIn, 0.03f, lpfStartIn, lpfEndIn, endFadeMsIn);
    if (!enabled) return;
    mode = BrakeMode::TAPE_STOP;
    wowDepth = std::clamp(std::isfinite(wowDepthIn) ? wowDepthIn : 0.03f, 0.f, 0.12f);
    wowRateHz = std::clamp(std::isfinite(wowRateIn) ? wowRateIn : 5.0f, 0.5f, 15.0f);
  }

  void setupSpinback(double srIn, double startIn, double lengthIn,
                     double peakRevIn, double silenceGapSecIn) {
    enabled = false;
    sr = srIn > 0.0 ? srIn : 48000.0;
    if (!(srIn > 0) || !std::isfinite(startIn) || !(lengthIn > 0.02)) return;
    mode = BrakeMode::SPINBACK;
    spinback = true;
    startSec = startIn;
    lengthSec = std::clamp(lengthIn, 0.02, 3.0);
    silenceGapSec = std::clamp(std::isfinite(silenceGapSecIn) ? silenceGapSecIn : 0.0, 0.0, 4.0);
    endRate = 0.05;
    kExp = -std::log(endRate);
    wowDepth = 0.0f;
    wowRateHz = 5.0f;
    lpfStartHz = 18000.f;
    lpfEndHz = 10000.f;
    endFadeSec = std::clamp(std::min(0.015, lengthSec * 0.12), 0.002, lengthSec * 0.4);
    peakReverseSpeed = std::clamp(std::isfinite(peakRevIn) ? peakRevIn : kSpin, 1.0, 6.0);
    enabled = true;
    reset();
  }

  void reset() {
    done = false;
    active = false;
    readCursor = 0.0;
    handoffSamples = 0;
    handoffTotal = 0;
    r0 = r1 = 1.0;
    amp0 = amp1 = 1.f;
    lpf.reset();
  }

  double rateAt(double p, double elapsedSec) const {
    p = std::clamp(p, 0.0, 1.0);
    if (mode == BrakeMode::SPINBACK) {
      constexpr double grab = 0.08;
      if (p < grab) {
        const double u = p / grab;
        return 1.0 - (u * u * (3.0 - 2.0 * u));
      }
      const double u = (p - grab) / (1.0 - grab);
      return -peakReverseSpeed * std::pow(u, 1.35);
    }
    double r = std::exp(-kExp * p);
    if (mode == BrakeMode::TAPE_STOP && wowDepth > 0.f) {
      const double wow = 1.0 + double(wowDepth) * std::sin(2.0 * kPi * double(wowRateHz) * elapsedSec) * (0.3 + 0.7 * p);
      r = std::max(0.01, r * wow);
    }
    return r;
  }

  float ampAt(double p) const {
    if (p >= 1.0 || done) return 0.f;
    const double fadeFrac = std::clamp(endFadeSec / std::max(0.02, lengthSec), 0.0, 0.5);
    if (fadeFrac > 0.0 && p > 1.0 - fadeFrac) {
      const double u = (p - (1.0 - fadeFrac)) / fadeFrac;
      return float(0.5 * (1.0 + std::cos(kPi * u)));
    }
    return 1.f;
  }

  void control(double p0, double p1, double elapsed0) {
    r0 = rateAt(p0, elapsed0);
    r1 = rateAt(p1, elapsed0 + 64.0 / sr);
    amp0 = ampAt(p0);
    amp1 = ampAt(p1);
    if (p1 >= 1.0) done = true;
    if (mode != BrakeMode::SPINBACK) {
      const double rMid = 0.5 * (r0 + r1);
      const double norm = std::clamp((rMid - endRate) / std::max(1e-6, 1.0 - endRate), 0.0, 1.0);
      const double hz = double(lpfEndHz) * std::pow(double(lpfStartHz) / double(lpfEndHz), norm);
      lpf.set(sr, hz, 0.7071);
      lpf.flush();
    } else {
      const double pMid = std::clamp(0.5 * (p0 + p1), 0.0, 1.0);
      const double hz = double(lpfStartHz) * std::pow(double(lpfEndHz) / double(lpfStartHz), pMid);
      lpf.set(sr, hz, 0.7071);
      lpf.flush();
    }
  }
};

// ---------------------------------------------------------------------------
// True-peak detection: 4x polyphase interpolation (12 taps per phase,
// Hann-windowed sinc), the BS.1770-4 Annex 2 approach.
// ---------------------------------------------------------------------------

struct TruePeakFir {
  static constexpr int kTaps = 12;
  static constexpr int kHistory = 6;
  float coef[3][kTaps] = {};
  void setup() {
    for (int p = 1; p <= 3; ++p) {
      const double frac = double(p) / 4.0;
      double sum = 0;
      for (int k = 0; k < kTaps; ++k) {
        const double d = double(k - 5) - frac;
        const double sinc = std::fabs(d) < 1e-12 ? 1.0 : std::sin(kPi * d) / (kPi * d);
        const double w = std::fabs(d / 6.0) < 1.0 ? 0.5 * (1.0 + std::cos(kPi * d / 6.0)) : 0.0;
        coef[p - 1][k] = float(sinc * w);
        sum += sinc * w;
      }
      for (int k = 0; k < kTaps; ++k) coef[p - 1][k] = float(coef[p - 1][k] / sum);
    }
  }
  /** Peak over x[0] and the three inter-sample points to x[1]; [x] points at a sample with 5 behind / 6 ahead readable. */
  inline float peakAt(const float* x) const {
    float peak = std::fabs(x[0]);
    for (int p = 0; p < 3; ++p) {
      float y = 0.f;
      for (int k = 0; k < kTaps; ++k) y += coef[p][k] * x[k - 5];
      peak = std::max(peak, std::fabs(y));
    }
    return peak;
  }
};

}  // namespace bitchord::loopmix::v2
