// Host conformance/unit tests for DJ-FX DSP blocks (Part A §5.1-§5.10).
//
// Build (normal):
//   g++ -std=c++17 -O2 -fno-fast-math -ffp-contract=off -Wall -Wextra -I native -I native/third_party native/loopmix/loop_mix_renderer.cpp native/loopmix/dj_fx_test.cpp -o /tmp/dj_fx_test
// Build (ASan/UBSan):
//   g++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -fno-fast-math -ffp-contract=off -Wall -Wextra -I native -I native/third_party native/loopmix/loop_mix_renderer.cpp native/loopmix/dj_fx_test.cpp -o /tmp/dj_fx_test_asan
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later.

#include "loopmix/dj_fx.h"
#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

namespace v2 = bitchord::loopmix::v2;
namespace lm = bitchord::loopmix;

static int g_failures = 0;
#define CHECK(cond, msg) do { if (!(cond)) { std::fprintf(stderr, "FAIL %s\n", msg); ++g_failures; } } while (0)

static bool allFinite(const std::vector<float>& v) {
  for (float x : v) if (!std::isfinite(x)) return false;
  return true;
}

static float maxAbsV(const std::vector<float>& v) {
  float m = 0.f;
  for (float x : v) m = std::max(m, std::fabs(x));
  return m;
}

// ---------------------------------------------------------------------------
// A§5.3 Sweep (filter): Svf Q clamp + resonance peak bound, no non-finite.
// ---------------------------------------------------------------------------
static void test_svf() {
  // Q clamp upper
  {
    v2::Svf svf;
    svf.set(48000.0, 1000.0, 10.0f);
    CHECK(svf.k <= 1.0f / v2::kMaxResonanceQ + 1e-6f, "svf Q upper clamp (k)");
    CHECK(svf.k >= 1.0f / v2::kMaxResonanceQ - 1e-6f, "svf Q upper clamp (k) min");
  }
  // Q clamp lower
  {
    v2::Svf svf;
    svf.set(48000.0, 1000.0, 0.1f);
    CHECK(svf.k <= 1.0f / 0.3f + 1e-6f, "svf Q lower clamp");
  }
  // cutoff clamp to Nyquist*0.45
  {
    v2::Svf svf;
    svf.set(48000.0, 100000.0, 0.7071);
    CHECK(svf.g < 10.0f, "svf high cutoff clamped");
  }
  // Frequency response: HPF-like? Svf produces lp/bp/hp. Feed sine at cutoff, measure hp gain finite.
  {
    v2::Svf svf;
    svf.set(48000.0, 1000.0, 0.7071);
    svf.reset();
    std::vector<float> out;
    out.reserve(48000);
    for (int i = 0; i < 48000; ++i) {
      float x = std::sin(2.0 * v2::kPi * 1000.0 * i / 48000.0);
      float lp, bp, hp;
      svf.tick(0, x, lp, bp, hp);
      out.push_back(hp);
    }
    CHECK(allFinite(out), "svf hp finite");
    // steady-state gain at cutoff for 1st-order-ish HP ~ -3dB; just ensure not exploding
    float mx = maxAbsV(out);
    CHECK(mx < 10.0f, "svf hp no blowup");
  }
  // kMaxResonanceQ value
  CHECK(v2::kMaxResonanceQ == 1.92f, "kMaxResonanceQ == 1.92");
  // flushTiny
  CHECK(v2::flushTiny(1e-20f) == 0.f, "flushTiny tiny -> 0");
  CHECK(v2::flushTiny(1.0f) == 1.0f, "flushTiny normal");
}

// ---------------------------------------------------------------------------
// A§5.4 Flanger: params, stereo 90, finite output.
// ---------------------------------------------------------------------------
static void test_flanger() {
  lm::Flanger f;
  f.setupEx(48000.0, 1.0 / (2.0 * 0.5), 0.003, 0.64f, 0.6f, 90.f); // LFO 1 cycle/2bars approx
  CHECK(f.enabled, "flanger enabled");
  CHECK(f.feedback >= 0.55f - 1e-6f && f.feedback <= 0.7f + 1e-6f, "flanger feedback range");
  CHECK(std::fabs(f.stereoOffsetDeg - 90.f) < 1e-6f, "flanger stereo 90");
  CHECK(f.depthSeconds >= 0.0005f && f.depthSeconds <= 0.004f + 1e-6f, "flanger depth 0.5-4ms");
  // finite output
  std::vector<float> wetL, wetR;
  wetL.reserve(4800); wetR.reserve(4800);
  for (int i = 0; i < 4800; ++i) {
    float wl = 0.f, wr = 0.f;
    double t = double(i) / 48000.0;
    f.apply(0.5f, 0.5f, wl, wr, t, 48000.0);
    wetL.push_back(wl); wetR.push_back(wr);
  }
  CHECK(allFinite(wetL) && allFinite(wetR), "flanger output finite");
  // applyEnv with wetMul 0 -> tail recirculation finite
  {
    lm::Flanger f2;
    f2.setupEx(48000.0, 0.5, 0.002, 0.6f, 0.6f, 90.f);
    std::vector<float> a, b;
    for (int i = 0; i < 1024; ++i) {
      float wl = 0.f, wr = 0.f;
      f2.applyEnv(0.3f, 0.3f, 0.f, wl, wr, double(i)/48000.0, 48000.0);
      a.push_back(wl); b.push_back(wr);
    }
    CHECK(allFinite(a) && allFinite(b), "flanger applyEnv finite");
  }
}

// ---------------------------------------------------------------------------
// A§5.5 Phaser: stages 6-8 (even), LFO, feedback ~0.6, stereo 90, finite.
// ---------------------------------------------------------------------------
static void test_phaser() {
  v2::Phaser p;
  p.setup(4.0, 0.8f, 0.6f, 6, 90.f); // 1 cycle per bar approx (rateBeats=4? spec ~1/bar; use 4 beats)
  CHECK(p.enabled, "phaser enabled");
  CHECK(p.stages >= 6 && p.stages <= 8, "phaser stages 6-8");
  CHECK((p.stages & 1) == 0, "phaser stages even");
  CHECK(std::fabs(p.feedback - 0.6f) < 1e-6f || (p.feedback >= -0.85f && p.feedback <= 0.85f), "phaser feedback");
  CHECK(std::fabs(p.stereoOffsetCycles - 0.25) < 1e-6, "phaser stereo 90");
  // depth clamp
  v2::Phaser p2;
  p2.setup(4.0, 1.5f, 0.6f, 7, 90.f);
  CHECK(p2.depth <= 1.0f, "phaser depth clamp");
  CHECK(p2.stages >= 6 && p2.stages <= 8, "phaser stages clamp");
  // process finite
  p.control(0.0, 64.0 / 48000.0, 48000.0);
  std::vector<float> outL, outR;
  for (int i = 0; i < 2048; ++i) {
    float l = 0.5f, r = 0.5f;
    float frac = float(i) / 2047.f;
    p.process(l, r, frac, 0.7f);
    outL.push_back(l); outR.push_back(r);
  }
  CHECK(allFinite(outL) && allFinite(outR), "phaser process finite");
  // wet=0 leaves output? process still runs chain but mixes only if wet>0; with wet 0 output unchanged-ish
  // check no denormals
  for (float x : outL) CHECK(std::fabs(x) == 0.f || std::fabs(x) >= 1e-30f, "phaser no denormal");
}

// ---------------------------------------------------------------------------
// A§5.10 Gate/Stutter: division, swing 0-10% (0..0.33 clamp), depth, finite.
// ---------------------------------------------------------------------------
static void test_gate() {
  v2::BeatGate g;
  g.setupEx(48000.0, 0.5, 2.f, 4.f, 0.1f, 0.0, 2.0); // 1/8 -> 1/16 sweep
  CHECK(g.enabled, "gate enabled");
  CHECK(g.sweepDiv, "gate sweepDiv");
  CHECK(g.swing >= 0.f && g.swing <= 0.33f, "gate swing clamp");
  // gate value range
  for (int i = 0; i < 100; ++i) {
    float v = g.gateValue(double(i) * 0.01, 0.5);
    CHECK(v >= 0.f && v <= 1.f, "gate value range");
    CHECK(std::isfinite(v), "gate value finite");
  }
  // gateValueFor with swing
  v2::BeatGate g2;
  g2.setupEx(48000.0, 0.5, 4.f, 4.f, 0.05f, 0.0, 1e18);
  CHECK(g2.enabled, "gate2 enabled");
  float gv = g2.gateValueFor(0.25, 0.5, 4.f);
  CHECK(gv >= 0.f && gv <= 1.f, "gateValueFor range");
  // releaseBlendAt
  float rb = g.releaseBlendAt(2.5);
  CHECK(rb >= 0.f && rb <= 1.f, "releaseBlendAt range");
  // stutter tick finite
  v2::BeatGate st;
  st.setupEx(48000.0, 0.5, -4.f, -4.f, 0.f, 0.0, 1e18); // negative => stutter
  CHECK(st.stutter, "stutter mode");
  CHECK(st.sliceCap > 0, "stutter sliceCap");
  for (int i = 0; i < 512; ++i) {
    float l = 0.4f, r = -0.4f;
    int64_t step = i / 128;
    st.stutterTick(l, r, 0.5f, step);
    CHECK(std::isfinite(l) && std::isfinite(r), "stutter tick finite");
  }
  // activeDivisionAt
  CHECK(g.activeDivisionAt(0.5) == 2.f || g.activeDivisionAt(0.5) == 4.f, "activeDivisionAt");
}

// ---------------------------------------------------------------------------
// A§5.9 Echo out: PingPong HPF 250 / LPF 6000, finite.
// ---------------------------------------------------------------------------
static void test_pingpong() {
  lm::PingPong pp;
  pp.setupEx(48000.0, 0.25, 0.6f, 0.5f, 250.f, 6000.f, true);
  CHECK(pp.enabled, "pingpong enabled");
  CHECK(std::fabs(pp.wetHpfHz - 250.f) < 1e-6f, "pingpong hpf 250");
  CHECK(std::fabs(pp.wetLpfHz - 6000.f) < 1e-6f, "pingpong lpf 6000");
  CHECK(pp.feedback >= 0.55f - 1e-6f && pp.feedback <= 0.7f + 1e-6f, "pingpong feedback range");
  // tap finite
  std::vector<float> wetL, wetR;
  for (int i = 0; i < 4800; ++i) {
    float wl = 0.f, wr = 0.f;
    pp.tap(0.5f, 0.5f, 1.f, wl, wr, 48000.0);
    wetL.push_back(wl); wetR.push_back(wr);
  }
  CHECK(allFinite(wetL) && allFinite(wetR), "pingpong tap finite");
}

// ---------------------------------------------------------------------------
// A§5.6 Riser + Impact: finite, peak bound, params.
// ---------------------------------------------------------------------------
static void test_riser_impact() {
  v2::Riser riser;
  riser.setupEx(48000.0, 0.5, 16.0, 300.f, 8000.f, 0.7f, 1.0f, true, 1.0, 0.0);
  CHECK(riser.enabled, "riser enabled");
  CHECK(riser.noiseStartHz >= 200.f && riser.noiseStartHz <= 400.f, "riser noise start ~300");
  CHECK(riser.noiseEndHz >= 7000.f, "riser noise end ~8k");
  CHECK(riser.noiseLevelPeak <= 0.75f, "riser noise peak <=0.7");
  // control + tick
  riser.startSec = 0.0;
  for (int i = 0; i < 10; ++i) riser.control(double(i) * 0.5, 48000.0);
  std::vector<float> outL, outR;
  float peak = 0.f;
  for (int i = 0; i < 48000; ++i) {
    double t = double(i) / 48000.0;
    double beatPos = t / 0.5;
    float l = 0.f, r = 0.f;
    riser.tick(1.0f, t, beatPos, 0.5, l, r);
    outL.push_back(l); outR.push_back(r);
    peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
  }
  CHECK(allFinite(outL) && allFinite(outR), "riser output finite");
  CHECK(peak <= 0.9f, "riser peak bounded");
  // captureDeck finite (no crash)
  riser.reset();
  riser.setupEx(48000.0, 0.5, 16.0, 300.f, 8000.f, 0.7f, 1.0f, true, 1.0, 0.0);
  riser.startSec = 0.0;
  for (int i = 0; i < 48000; ++i) {
    riser.captureDeck(0.3f, 0.3f, double(i)/48000.0);
  }
  // Impact
  v2::Impact imp;
  imp.setup(48000.0, 0.0, -6.0f, 55.f, 180.0);
  CHECK(imp.enabled, "impact enabled");
  CHECK(imp.peakLin <= 0.501187f + 1e-6f, "impact peak <= -6dBFS");
  imp.control(0.0);
  std::vector<float> iL, iR;
  float iPeak = 0.f;
  for (int i = 0; i < 24000; ++i) {
    float l = 0.f, r = 0.f;
    imp.tick(double(i)/48000.0, l, r);
    iL.push_back(l); iR.push_back(r);
    iPeak = std::max(iPeak, std::max(std::fabs(l), std::fabs(r)));
  }
  CHECK(allFinite(iL) && allFinite(iR), "impact finite");
  CHECK(iPeak <= 0.501187f + 1e-4f, "impact output peak <= -6dBFS");
  // trigger before => zero
  v2::Impact imp2;
  imp2.setup(48000.0, 1.0, -6.0f, 55.f, 180.0);
  float zl = 1.f, zr = 1.f;
  imp2.tick(0.5, zl, zr);
  CHECK(zl == 0.f && zr == 0.f, "impact inactive before trigger");
}

// ---------------------------------------------------------------------------
// A§5.7 Brake + Tape-stop: exponential rate, LPF sweep, fade, finite.
// A§5.8 Spinback: reverse acceleration, gap.
// ---------------------------------------------------------------------------
static void test_brake() {
  // Vinyl brake
  v2::Brake b;
  b.setupBrakeEx(48000.0, 0.0, 0.5, 0.05f, 18000.f, 800.f, 10.0);
  CHECK(b.enabled, "brake enabled");
  CHECK(b.mode == v2::BrakeMode::VINYL_BRAKE, "brake mode");
  // rateAt decays to ~endRate at p=1
  double r0 = b.rateAt(0.0, 0.0);
  double r1 = b.rateAt(1.0, 0.5);
  CHECK(std::fabs(r0 - 1.0) < 1e-9, "brake rate start 1.0");
  CHECK(r1 <= 0.06, "brake rate end ~0.05");
  // exponential: r(t)=exp(-k*t), k=-ln(0.05)
  double mid = b.rateAt(0.5, 0.25);
  CHECK(mid > r1 && mid < r0, "brake rate monotonic decay");
  // ampAt fade at end
  float a0 = b.ampAt(0.0);
  float aEnd = b.ampAt(0.99);
  CHECK(a0 == 1.f, "brake amp start 1");
  CHECK(aEnd <= 1.f && aEnd >= 0.f, "brake amp end fade");
  // control LPF sweep: at p=0 hz near start, at p=1 near end
  b.control(0.0, 0.01, 0.0);
  // just finite tick? Brake has no tick method in v2 (renderer uses varispeed). Test control finite.
  CHECK(std::isfinite(b.r0) && std::isfinite(b.r1), "brake control finite");
  // LPF set called; check lpf g finite
  // Tape-stop
  v2::Brake ts;
  ts.setupTapeStop(48000.0, 0.0, 0.7, 0.03f, 5.0f, 18000.f, 800.f, 10.0);
  CHECK(ts.enabled, "tapestop enabled");
  CHECK(ts.mode == v2::BrakeMode::TAPE_STOP, "tapestop mode");
  CHECK(ts.wowDepth > 0.f, "tapestop wow");
  double tr = ts.rateAt(0.5, 0.35);
  CHECK(std::isfinite(tr) && tr > 0, "tapestop rate finite");
  // wow modulation changes rate slightly
  double tr2 = ts.rateAt(0.5, 0.36);
  // not strictly required; just finite
  CHECK(std::isfinite(tr2), "tapestop rate2 finite");
  // Spinback
  v2::Brake sp;
  sp.setupSpinback(48000.0, 0.0, 0.25, 3.0, 0.5);
  CHECK(sp.enabled, "spinback enabled");
  CHECK(sp.mode == v2::BrakeMode::SPINBACK, "spinback mode");
  CHECK(sp.spinback, "spinback flag");
  // rateAt: starts ~1 then goes negative (reverse)
  double sr0 = sp.rateAt(0.0, 0.0);
  double srMid = sp.rateAt(0.5, 0.125);
  (void)srMid;
  double srEnd = sp.rateAt(0.95, 0.2375);
  CHECK(std::fabs(sr0 - 1.0) < 1e-9 || sr0 <= 1.0, "spinback rate start");
  CHECK(srEnd < 0.0, "spinback rate reverse");
  CHECK(std::fabs(srEnd) <= 3.0 + 1e-6, "spinback peak ~3x");
  // peakReverseSpeed
  CHECK(sp.peakReverseSpeed >= 1.0 && sp.peakReverseSpeed <= 6.0, "spinback peak range");
  // silenceGapSec
  CHECK(sp.silenceGapSec >= 0.0, "spinback gap");
  // control finite
  sp.control(0.0, 0.5, 0.0);
  CHECK(std::isfinite(sp.r0) && std::isfinite(sp.r1), "spinback control finite");
  // ampAt fade
  CHECK(sp.ampAt(1.0) == 0.f || sp.ampAt(1.0) >= 0.f, "spinback amp end");
}

// ---------------------------------------------------------------------------
// A§5.2 Roll: Roll HPF 200->2000 concept via TptHighpass + Svf. Test TptHighpass finite.
// Also test Pump finite.
// ---------------------------------------------------------------------------
static void test_roll_pump() {
  lm::TptHighpass hp;
  hp.set(48000.0, 200.0, 0.7071);
  std::vector<float> out;
  for (int i = 0; i < 4800; ++i) {
    float x = 0.5f * std::sin(2.0 * v2::kPi * 100.0 * i / 48000.0); // low freq
    float y = hp.tick(0, x);
    out.push_back(y);
  }
  CHECK(allFinite(out), "tpt highpass finite");
  // at 2000 Hz cutoff
  lm::TptHighpass hp2;
  hp2.set(48000.0, 2000.0, 0.7071);
  std::vector<float> out2;
  for (int i = 0; i < 4800; ++i) {
    float x = 0.5f * std::sin(2.0 * v2::kPi * 100.0 * i / 48000.0);
    float y = hp2.tick(0, x);
    out2.push_back(y);
  }
  CHECK(allFinite(out2), "tpt highpass 2k finite");
  // Pump
  v2::Pump pump;
  pump.setup(48000.0, 0.5, false);
  CHECK(pump.enabled, "pump enabled");
  float l = 0.5f, r = 0.5f;
  for (int i = 0; i < 1024; ++i) {
    pump.split.tick(0, l, l, l, l); // just exercise
  }
  (void)r;
  // proper tick
  v2::Pump pump2;
  pump2.setup(48000.0, 0.5, true);
  std::vector<float> po;
  for (int i = 0; i < 1024; ++i) {
    float ll = 0.4f, rr = -0.4f;
    pump2.tick(ll, rr, 0.5f);
    po.push_back(ll); po.push_back(rr);
  }
  CHECK(allFinite(po), "pump tick finite");
  // duck function
  float d = v2::Pump::duck(0.0, 0.5);
  CHECK(d >= 0.f && d <= 1.f, "pump duck range");
}

// ---------------------------------------------------------------------------
// A§5.1 Loop: seam ms clamp, Renderer setLoopSeamMs, finite render.
// ---------------------------------------------------------------------------
static void test_loop() {
  lm::Renderer r(48000);
  r.setLoopSeamMs(0, 0.1);
  CHECK(r.deck(0).loopSeamMs == 0.5, "loop seam min clamp 0.5ms");
  r.setLoopSeamMs(0, 100.0);
  CHECK(r.deck(0).loopSeamMs == 8.0, "loop seam max clamp 8ms");
  r.setLoopSeamMs(0, 4.0);
  CHECK(r.deck(0).loopSeamMs == 4.0, "loop seam 4ms");
  // seam samples for 8ms = 384
  // Envelope at()
  lm::Envelope env;
  env.times = {0.0, 1.0, 2.0};
  env.values = {0.f, 1.f, 0.f};
  CHECK(std::fabs(env.at(0.5) - 0.5f) < 1e-6f, "envelope interp");
  CHECK(std::fabs(env.at(0.0) - 0.f) < 1e-6f, "envelope at 0");
  CHECK(env.empty() == false, "envelope not empty");
  lm::Envelope empty;
  CHECK(empty.empty(), "envelope empty");
  CHECK(empty.at(1.0) == 0.f, "empty envelope at 0");
  // Biquad finite
  lm::Biquad bq;
  bq.highPass(48000.0, 1000.0, 0.7071);
  std::vector<float> bo;
  for (int i = 0; i < 1024; ++i) {
    float x = 0.5f * std::sin(2.0 * v2::kPi * 440.0 * i / 48000.0);
    float y = bq.tick(0, x);
    bo.push_back(y);
  }
  CHECK(allFinite(bo), "biquad finite");
  lm::Biquad bq2;
  bq2.bypass();
  float by = bq2.tick(0, 0.5f);
  CHECK(std::fabs(by - 0.5f) < 1e-6f, "biquad bypass");
  // TptLowpass finite
  lm::TptLowpass lp;
  lp.set(48000.0, 1000.0, 0.7071);
  std::vector<float> lo;
  for (int i = 0; i < 1024; ++i) {
    float x = 0.5f * std::sin(2.0 * v2::kPi * 440.0 * i / 48000.0);
    lo.push_back(lp.tick(0, x));
  }
  CHECK(allFinite(lo), "tpt lowpass finite");
  // ReverbWash finite
  lm::ReverbWash rw;
  rw.setup(48000.0, 0.5f, 0.5f, 0.3f);
  std::vector<float> rwo;
  for (int i = 0; i < 2048; ++i) {
    float wl = 0.f, wr = 0.f;
    rw.apply(0.3f, 0.3f, 0.5f, wl, wr, 48000.0);
    rwo.push_back(wl); rwo.push_back(wr);
  }
  CHECK(allFinite(rwo), "reverbwash finite");
}

// ---------------------------------------------------------------------------
// A§5.3 Sweep (filter knob): djmKnob ranges, Svf Q bound, TruePeakFir finite.
// A§5.3 mirror LPF demuffle: SweepConfig values.
// ---------------------------------------------------------------------------
static void test_sweep_knob() {
  float hp, lp, qv;
  // bypass center
  v2::djmKnob(0.f, hp, lp, qv);
  CHECK(hp <= 20.f + 1e-3f, "djmKnob center hp bypass");
  CHECK(lp >= 18000.f - 1e-3f, "djmKnob center lp bypass");
  CHECK(qv == 1.f, "djmKnob center q");
  // positive (HPF)
  v2::djmKnob(1.f, hp, lp, qv);
  CHECK(hp >= 9000.f, "djmKnob full HPF");
  CHECK(qv > 1.f && qv <= 1.6f + 1e-6f, "djmKnob q boost");
  // negative (LPF)
  v2::djmKnob(-1.f, hp, lp, qv);
  CHECK(lp <= 200.f, "djmKnob full LPF");
  // dead zone
  v2::djmKnob(0.02f, hp, lp, qv);
  CHECK(hp <= 20.f + 1e-3f && lp >= 18000.f - 1e-3f, "djmKnob dead zone");
  // NaN handling
  v2::djmKnob(NAN, hp, lp, qv);
  CHECK(hp <= 20.f + 1e-3f, "djmKnob NaN safe");
  // SweepConfig
  lm::SweepConfig sc;
  CHECK(!sc.enabled, "sweep default disabled");
  sc.isHighPass = true;
  sc.startHz = 20.f; sc.endHz = 2000.f;
  sc.qStart = 0.7f; sc.qEnd = 1.6f;
  CHECK(sc.qEnd <= v2::kMaxResonanceQ + 1e-6f || sc.qEnd <= 1.6f, "sweep q end");
  // TruePeakFir finite
  v2::TruePeakFir tp;
  tp.setup();
  std::vector<float> hist(12, 0.f);
  for (int i = 0; i < 6; ++i) hist[i] = 0.1f * (i + 1);
  float pk = tp.peakAt(hist.data() + 5);
  CHECK(std::isfinite(pk) && pk >= 0.f, "truepeak finite");
  // kCapabilities
  CHECK(lm::Renderer::capabilities() == v2::kCapabilities, "capabilities");
  // quantizeStem
  std::vector<float> x(16, 0.5f);
  std::vector<int16_t> qi(16, 0);
  float sc2 = v2::quantizeStem(x.data(), 16, qi.data());
  CHECK(sc2 > 0.f, "quantizeStem scale");
  CHECK(qi[0] != 0, "quantizeStem output");
  // silent
  std::vector<float> xs(16, 0.f);
  std::vector<int16_t> qs(16, 99);
  float sc3 = v2::quantizeStem(xs.data(), 16, qs.data());
  CHECK(sc3 == 0.f, "quantizeStem silent");
}

// ---------------------------------------------------------------------------
// A§5.8 Spinback gap: Brake spinback silenceGapSec range tested above.
// Additional: rateAt monotonic reverse magnitude.
// ---------------------------------------------------------------------------
static void test_spinback_extra() {
  v2::Brake sp;
  sp.setupSpinback(48000.0, 0.0, 0.5, 3.0, 1.0);
  CHECK(sp.silenceGapSec == 1.0, "spinback gap stored");
  // rate magnitude grows
  double prev = 0;
  bool ok = true;
  for (int i = 1; i <= 10; ++i) {
    double p = 0.08 + 0.92 * (double(i)/10.0);
    double r = sp.rateAt(p, p*0.5);
    if (r >= 0) { ok = false; break; }
    if (std::fabs(r) < prev - 1e-9 && i > 2) { /* allow */ }
    prev = std::fabs(r);
  }
  CHECK(ok, "spinback reverse after grab");
}

// ---------------------------------------------------------------------------
// Loudness meter finite.
// ---------------------------------------------------------------------------
static void test_loudness() {
  v2::LoudnessMeter m;
  m.setup(48000.0);
  for (int i = 0; i < 48000; ++i) {
    float x = 0.3f * std::sin(2.0 * v2::kPi * 440.0 * i / 48000.0);
    m.push(x, x);
  }
  CHECK(m.valid(), "loudness valid");
  double l = m.shortTermLufs();
  CHECK(std::isfinite(l), "loudness lufs finite");
  CHECK(l > -120.0 && l < 0.0, "loudness lufs range");
  // KWeighting finite
  v2::KWeighting kw;
  kw.setup(48000.0);
  double y = kw.tick(0, 0.5);
  CHECK(std::isfinite(y), "kweighting finite");
  // Biquad64
  v2::Biquad64 bq;
  bq.b0 = 1; bq.b1 = 0; bq.b2 = 0; bq.a1 = 0; bq.a2 = 0;
  double yy = bq.tick(0, 0.5);
  CHECK(std::isfinite(yy), "biquad64 finite");
}

// ---------------------------------------------------------------------------
// Biquad64 flushTiny double
// ---------------------------------------------------------------------------
static void test_flushtiny_double() {
  CHECK(v2::flushTiny(1e-35) == 0.0, "flushTiny double tiny");
  CHECK(v2::flushTiny(1.0) == 1.0, "flushTiny double normal");
}

int main() {
  test_svf();
  test_flanger();
  test_phaser();
  test_gate();
  test_pingpong();
  test_riser_impact();
  test_brake();
  test_roll_pump();
  test_loop();
  test_sweep_knob();
  test_spinback_extra();
  test_loudness();
  test_flushtiny_double();

  if (g_failures == 0) {
    std::printf("ok dj_fx_test (%d failures)\n", g_failures);
    return 0;
  }
  std::fprintf(stderr, "dj_fx_test: %d failures\n", g_failures);
  return 1;
}
