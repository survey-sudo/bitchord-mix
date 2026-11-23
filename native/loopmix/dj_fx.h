// BitChord Automix v2 (Stream C): DJ effects, stems storage, loudness and
// true-peak helpers for the loop-mix renderer. Header-only on purpose: the CI
// native gate compiles only loop_mix_renderer.cpp + the A/B harness, and this
// keeps the build lines untouched. Everything here is allocation-free after
// setup() (setup runs on the worker thread, before Renderer::prepare()).
//
// Parameter ranges follow the Pioneer DJM-900NXS2 BEAT FX / SOUND COLOR FX
// tables (see workspace/work/C/NOTES.md for sources):
//   TRANS (gate)      1/16 .. 16 beats, duty + balance on LEVEL/DEPTH
//   PHASER            1/16 .. 64 beat cycle
//   VINYL BRAKE       1/16 .. 16 beats, speed slows steadily until stop
//   COLOR FX FILTER   CCW = LPF down, CW = HPF up, PARAMETER = resonance
//   COLOR FX NOISE    white noise through a sweeping filter
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

/** Envelope kinds added by v2. Mirrored 1:1 in LoopMixRenderer.kt. 0..8 are the vc80 kinds. */
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

/** Capability bits (LoopMixRenderer.V2_CAPABILITIES mirrors these). */
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
    k = float(1.0 / std::max(0.3, q));
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
// Phaser: 6 first-order all-pass stages, beat-synced LFO, stereo quadrature.
// ---------------------------------------------------------------------------

struct Phaser {
  static constexpr int kStages = 6;
  bool enabled = false;
  double rateBeats = 4.0;
  float depth = 0.8f;
  float feedback = 0.5f;
  float zL[kStages] = {};
  float zR[kStages] = {};
  float lastL = 0, lastR = 0;
  float aL0 = 0, aL1 = 0, aR0 = 0, aR1 = 0;

  void setup(double rateBeatsIn, float depthIn, float feedbackIn) {
    enabled = std::isfinite(rateBeatsIn) && rateBeatsIn > 0.0 && depthIn > 0.f;
    rateBeats = std::clamp(enabled ? rateBeatsIn : 4.0, 1.0 / 16.0, 64.0);
    depth = std::clamp(depthIn, 0.f, 1.f);
    feedback = std::isfinite(feedbackIn) ? std::clamp(feedbackIn, -0.85f, 0.85f) : 0.f;
    reset();
  }
  void reset() {
    for (int s = 0; s < kStages; ++s) zL[s] = zR[s] = 0.f;
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
    aR0 = coefFor(p0 + 0.25, depth, sr);
    aR1 = coefFor(p1 + 0.25, depth, sr);
  }
  /** Always runs the all-pass chain (state continuity); mixes only when [wet] > 0. */
  inline void process(float& l, float& r, float frac, float wet) {
    const float aL = aL0 + (aL1 - aL0) * frac;
    const float aR = aR0 + (aR1 - aR0) * frac;
    float xl = l + feedback * lastL;
    float xr = r + feedback * lastR;
    for (int s = 0; s < kStages; ++s) {
      const float yl = aL * xl + zL[s];
      zL[s] = xl - aL * yl;
      xl = yl;
      const float yr = aR * xr + zR[s];
      zR[s] = xr - aR * yr;
      xr = yr;
    }
    lastL = std::clamp(xl, -4.f, 4.f);
    lastR = std::clamp(xr, -4.f, 4.f);
    if (wet > 0.f) {
      const float w = std::min(wet, 1.f);
      const float comp = 1.f + 0.15f * w;  // notches remove ~1.2 dB on average at full depth
      l = (l + w * 0.5f * (xl - l)) * comp;
      r = (r + w * 0.5f * (xr - r)) * comp;
    }
  }
  void flush() {
    for (int s = 0; s < kStages; ++s) { zL[s] = flushTiny(zL[s]); zR[s] = flushTiny(zR[s]); }
    lastL = flushTiny(lastL);
    lastR = flushTiny(lastR);
  }
};

// ---------------------------------------------------------------------------
// Beat gate (DJM TRANS / trance gate) and stutter (division < 0).
// ---------------------------------------------------------------------------

struct BeatGate {
  bool enabled = false;
  bool stutter = false;
  double beatSec = 0.5;
  float division = 4.f;  // gate steps per beat (4 = 1/16 notes)
  float swing = 0.f;     // 0 .. 0.33, delays every second step
  // Stutter slice (captured live during the first step, then repeated).
  std::vector<float> sliceL, sliceR;
  int sliceCap = 0, sliceLen = 0, sliceWrite = 0, sliceRead = 0, fade = 64;
  int state = 0;  // 0 idle, 1 capturing, 2 repeating
  int64_t lastStep = INT64_MIN;

  void setup(double sr, double beatSecIn, float divisionIn, float swingIn) {
    enabled = false;
    sliceL.clear();
    sliceR.clear();
    sliceCap = 0;
    const float dv = std::fabs(divisionIn);
    if (!(sr > 0) || !(beatSecIn > 0.1 && beatSecIn < 4.0) || !(dv >= 1.f / 16.f && dv <= 16.f)) return;
    beatSec = beatSecIn;
    division = dv;
    stutter = divisionIn < 0.f;
    swing = std::isfinite(swingIn) ? std::clamp(swingIn, 0.f, 0.33f) : 0.f;
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
  /** Gate gain 0..1 at [beatPos]; [beatDur] is the local beat length in seconds. Duty 50 %, 2 ms / 8 ms raised-cosine edges. */
  float gateValue(double beatPos, double beatDur) const {
    const double steps = beatPos * double(division);
    double stepSec = beatDur / double(division);
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
      l = l + d * (sl * w - l);
      r = r + d * (sr * w - r);
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
      l *= g;
      r *= g;
    } else {
      l -= a * lpL;  // x - a*low: phase-coherent complement of the split
      r -= a * lpR;
    }
  }
};

// ---------------------------------------------------------------------------
// Noise riser: decorrelated white noise through a rising resonant band
// (HPF 350 Hz -> 9 kHz, LPF 2.5 -> 16 kHz) with accelerating beat tremolo.
// ---------------------------------------------------------------------------

struct Riser {
  bool enabled = false;
  double beatSec = 0.5;
  double lengthBeats = 16.0;
  double startSec = 1e18;  // from the KIND_RISER_LEVEL envelope, in prepare()
  uint32_t seedL = 0x2545F491u, seedR = 0x9E3779B9u;
  Svf hp, lp;
  double progress = 0;

  void setup(double beatSecIn, double lengthBeatsIn) {
    enabled = beatSecIn > 0.1 && beatSecIn < 4.0 && lengthBeatsIn >= 0.25 && lengthBeatsIn <= 256.0;
    beatSec = enabled ? beatSecIn : 0.5;
    lengthBeats = enabled ? lengthBeatsIn : 16.0;
    reset();
  }
  void reset() {
    seedL = 0x2545F491u;
    seedR = 0x9E3779B9u;
    hp.reset();
    lp.reset();
    progress = 0;
  }
  double lengthSec() const { return std::max(0.25, lengthBeats * beatSec); }
  static inline float white(uint32_t& s) {
    s ^= s << 13;
    s ^= s >> 17;
    s ^= s << 5;
    return float(int32_t(s)) * (1.f / 2147483648.f);
  }
  void control(double tSec, double sr) {
    progress = std::clamp((tSec - startSec) / lengthSec(), 0.0, 1.0);
    const double p = progress;
    hp.set(sr, 350.0 * std::pow(9000.0 / 350.0, p), 0.8 + 1.7 * p);
    lp.set(sr, std::min(2500.0 * std::pow(16000.0 / 2500.0, p), sr * 0.45), 0.7071);
  }
  /** Tremolo depth 0.3, 1/8 -> 1/16 -> 1/32 notes as the build progresses. */
  float tremolo(double beatPos) const {
    const double steps = progress < 0.5 ? 2.0 : (progress < 0.75 ? 4.0 : 8.0);
    const double x = beatPos * steps;
    const double ph = x - std::floor(x);
    return float(0.7 + 0.3 * (0.5 + 0.5 * std::cos(2.0 * kPi * ph)));
  }
  inline void tick(float gain, float& outL, float& outR) {
    float a, b, hL, hR, lL, lR;
    hp.tick(0, white(seedL), a, b, hL);
    hp.tick(1, white(seedR), a, b, hR);
    lp.tick(0, hL, lL, a, b);
    lp.tick(1, hR, lR, a, b);
    outL = lL * gain;
    outR = lR * gain;
  }
};

// ---------------------------------------------------------------------------
// Vinyl brake / spinback: a varispeed read of the deck's own post-stretch
// output (a platter slowing down IS resampling), so pitch falls with speed and
// the output amplitude follows stylus velocity to exactly 0. Glitch-free: the
// read pointer is C1-continuous, the 4-point Hermite read is smooth, and the
// path is bit-identical to the dry signal until the brake starts.
// ---------------------------------------------------------------------------

struct Brake {
  static constexpr double kSpin = 3.0;  // backspin peak speed (x normal)
  bool enabled = false;
  bool spinback = false;
  bool done = false;
  double startSec = 0, lengthSec = 1;
  std::vector<float> ringL, ringR;
  int size = 0;
  int pos = 0;
  double lag = 0;
  float r0 = 1, r1 = 1, amp0 = 1, amp1 = 1;

  void setup(double sr, double startIn, double lengthIn, bool spin) {
    enabled = false;
    ringL.clear();
    ringR.clear();
    size = 0;
    if (!(sr > 0) || !std::isfinite(startIn) || !(lengthIn > 0.02)) return;
    spinback = spin;
    lengthSec = std::clamp(lengthIn, 0.02, spin ? 3.0 : 8.0);
    startSec = startIn;
    const double need = lengthSec * (spin ? (1.0 + kSpin) : 1.0) + 0.1;
    size = int(std::ceil(need * sr)) + 8;
    ringL.assign(size, 0.f);
    ringR.assign(size, 0.f);
    enabled = true;
    reset();
  }
  void reset() {
    std::fill(ringL.begin(), ringL.end(), 0.f);
    std::fill(ringR.begin(), ringR.end(), 0.f);
    pos = 0;
    lag = 0;
    done = false;
    r0 = r1 = amp0 = amp1 = 1.f;
  }
  /** Platter speed at brake progress [p] (1 = normal, negative = backwards). */
  static double rateAt(double p, bool spin) {
    p = std::clamp(p, 0.0, 1.0);
    if (!spin) return std::pow(1.0 - p, 1.35);
    constexpr double grab = 0.06;  // hand stops the record and throws it back
    if (p < grab) {
      const double u = p / grab;
      return 1.0 - (1.0 + kSpin) * (u * u * (3.0 - 2.0 * u));
    }
    const double u = (p - grab) / (1.0 - grab);
    return -kSpin * (1.0 - u) * (1.0 - u);
  }
  static double ampAt(double rate) { return std::pow(std::min(1.0, std::fabs(rate)), 0.6); }
  void control(double p0, double p1) {
    r0 = float(rateAt(p0, spinback));
    r1 = float(rateAt(p1, spinback));
    amp0 = float(ampAt(r0));
    amp1 = float(ampAt(r1));
    if (p1 >= 1.0) done = true;
  }
  inline void tick(float& l, float& r, float frac) {
    ringL[pos] = l;
    ringR[pos] = r;
    const float rate = r0 + (r1 - r0) * frac;
    if (lag <= 0.0 && rate >= 1.f) {
      if (++pos >= size) pos = 0;
      return;  // untouched before the brake
    }
    if (done && amp0 <= 0.f && amp1 <= 0.f) {
      l = 0.f;
      r = 0.f;
      if (++pos >= size) pos = 0;
      return;
    }
    lag += 1.0 - double(rate);
    if (lag < 0.0) lag = 0.0;
    const double maxLag = double(size - 4);
    if (lag > maxLag) lag = maxLag;
    const double p = -lag;
    const double fl = std::floor(p);
    const float f = float(p - fl);
    const int base = int(fl);
    float xl[4], xr[4];
    for (int k = 0; k < 4; ++k) {
      int rel = base - 1 + k;
      if (rel > 0) rel = 0;
      int idx = pos + rel;
      if (idx < 0) idx += size;
      xl[k] = ringL[idx];
      xr[k] = ringR[idx];
    }
    auto hermite = [f](const float* x) {
      const float c0 = x[1];
      const float c1 = 0.5f * (x[2] - x[0]);
      const float c2 = x[0] - 2.5f * x[1] + 2.f * x[2] - 0.5f * x[3];
      const float c3 = 0.5f * (x[3] - x[0]) + 1.5f * (x[1] - x[2]);
      return ((c3 * f + c2) * f + c1) * f + c0;
    };
    const float amp = amp0 + (amp1 - amp0) * frac;
    l = flushTiny(hermite(xl) * amp);
    r = flushTiny(hermite(xr) * amp);
    if (++pos >= size) pos = 0;
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
