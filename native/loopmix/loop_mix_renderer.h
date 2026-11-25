// BitChord: the Automix renderer, evolved from the loop-mix renderer. Two
// decks, each a decoded region of one song played through Signalsmith Stretch
// on one clock, looped or played straight, mixed into one stereo stream.
//
// Everything is on one clock: mix frame 0 is the moment the outgoing song's
// loop starts (t0). Each deck follows "knots" -- (time since t0, track
// position) for every beat of the mix -- so its tempo changes on the beat and
// its loop seams land on the exact sample. The renderer runs inside the
// outgoing player's audio sink, so the start of the mix is sample-exact
// against the song it takes over from, and the two decks can't drift apart.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "loopmix/dj_fx.h"
#include "loopmix/phrase_isolator.h"
#include "signalsmith-stretch/signalsmith-stretch.h"

namespace bitchord::loopmix {

/** A piecewise-linear automation curve over mix time (seconds since t0). */
struct Envelope {
  std::vector<double> times;
  std::vector<float> values;
  /** Interpolate in the log domain (a frequency sweep). */
  bool exponential = false;

  bool empty() const { return times.empty(); }
  float at(double time) const;
};

/** RBJ biquad, transposed direct form II, two channels. */
struct Biquad {
  float b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0;
  float z1[2] = {0, 0};
  float z2[2] = {0, 0};

  void lowShelf(double sampleRate, double frequency, double gainDb);
  void highPass(double sampleRate, double frequency, double q);
  void bypass() { b0 = 1; b1 = b2 = a1 = a2 = 0; }
  inline float tick(int channel, float x) {
    const float y = b0 * x + z1[channel];
    z1[channel] = b1 * x - a1 * y + z2[channel];
    z2[channel] = b2 * x - a2 * y;
    return y;
  }
};

/**
 * Cytomic / TPT state-variable filter, low-pass output. Stable up to Nyquist,
 * which a biquad's coefficient update is not when the cutoff is moving.
 */
struct TptLowpass {
  float g = 0;
  float a1 = 0, a2 = 0, a3 = 0;
  float ic1eq[2] = {0, 0};
  float ic2eq[2] = {0, 0};

  void set(double sampleRate, double frequency, double q);
  // Zero the integrators so re-engagement starts from clean state
  // (stale head would pop at the bypass boundary).
  void bypass() { g = 0; ic1eq[0] = ic1eq[1] = 0; ic2eq[0] = ic2eq[1] = 0; }
  inline float tick(int channel, float v0) {
    if (g <= 0.f) return v0;
    const float v3 = v0 - ic2eq[channel];
    const float v1 = a1 * ic1eq[channel] + a2 * v3;
    const float v2 = ic2eq[channel] + a2 * ic1eq[channel] + a3 * v3;
    ic1eq[channel] = 2.f * v1 - ic1eq[channel];
    ic2eq[channel] = 2.f * v2 - ic2eq[channel];
    return v2;
  }
};

/**
 * djmix24 Cytomic TPT SVF high-pass (Simper zero-delay-feedback topology,
 * allocation-free, stable to Nyquist). Q configurable (0.707 default,
 * ~0.95 musical DJM resonance during buildup sweeps).
 */
struct TptHighpass {
  float g = 0;
  float a1 = 0, a2 = 0, a3 = 0;
  float k = 1.414f;
  float ic1eq[2] = {0, 0};
  float ic2eq[2] = {0, 0};

  void set(double sampleRate, double frequency, double q);
  // Zero the integrators so re-engagement starts from clean state
  // (stale head would pop at the bypass boundary).
  void bypass() { g = 0; k = 1.414f; ic1eq[0] = ic1eq[1] = 0; ic2eq[0] = ic2eq[1] = 0; }
  inline float tick(int channel, float v0) {
    if (g <= 0.f) return v0;
    const float v3 = v0 - ic2eq[channel];
    const float v1 = a1 * ic1eq[channel] + a2 * v3;
    const float v2 = ic2eq[channel] + a2 * ic1eq[channel] + a3 * v3;
    ic1eq[channel] = 2.f * v1 - ic1eq[channel];
    ic2eq[channel] = 2.f * v2 - ic2eq[channel];
    // High output: input minus low minus band*k.
    return v0 - v2 - k * v1;
  }
};

/** djmix24 pre-allocated stereo plate reverb wash (Freeverb-class comb/allpass). */
struct ReverbWash {
  std::vector<float> combL0, combL1, combL2, combL3, combR0, combR1, combR2, combR3;
  std::vector<float> apL0, apL1, apR0, apR1;
  int combIdxL[4]{}; int combIdxR[4]{}; int apIdxL[2]{}; int apIdxR[2]{};
  float damping = 0.55f;
  float wet = 0.f;
  float hpL = 0.f, hpR = 0.f;
  bool enabled = false;
  void setup(double sampleRate, float roomSize, float dampingIn, float wetIn);
  void apply(float inL, float inR, float send, float& wetL, float& wetR, double sampleRate);
};

/** Tempo-synced stereo ping-pong echo. The wet signal is not gated by the deck's gain. */
struct PingPong {
  std::vector<float> left;
  std::vector<float> right;
  int pos = 0;
  int delay = 0;
  float feedback = 0;
  float wet = 0;
  float wetHpfHz = 250.f;
  float wetLpfHz = 6000.f;
  bool pingPong = true;
  float hpA = 0.f;
  float lpA = 0.f;
  float hpL = 0, hpR = 0, lpL = 0, lpR = 0;
  bool enabled = false;

  void setup(double sampleRate, double delaySeconds, float feedbackGain, float wetGain);
  void setupEx(double sampleRate, double delaySeconds, float feedbackGain, float wetGain,
               float hpfHz, float lpfHz, bool pingPongIn);
  /**
   * Writes the delayed signal into [wetL]/[wetR].
   * The delay is fed `dry × send`. A send of 0 still recirculates what is
   * already in the line, so a tail can ring after the dry input stops.
   */
  void tap(float inL, float inR, float send, float& wetL, float& wetR, double sampleRate);
};

/**
 * Tempo-synced through-zero-style stereo jet flanger:
 * fixed center reference delay crossed twice per LFO cycle by the swept tap,
 * stereo quadrature offset (default 90 deg), damped resonant feedback,
 * denormal-flushed and clamped [-0.78, 0.78].
 */
struct Flanger {
  std::vector<float> left;
  std::vector<float> right;
  int pos = 0;
  float depthSeconds = 0.0f;
  float feedback = 0.0f;
  float wet = 0.0f;
  double lfoHz = 0.0;
  float stereoOffsetDeg = 90.f;
  double centerDelaySec = 0.0025;
  float dampA = 0.f;
  bool throughZero = true;
  bool enabled = false;
  float dampL = 0.f, dampR = 0.f;

  void setup(double sampleRate, double lfoHzIn, double depthSecondsIn, float feedbackGain, float wetGain);
  void setupEx(double sampleRate, double lfoHzIn, double depthSecondsIn, float feedbackGain,
               float wetGain, float stereoOffsetDegIn, bool throughZeroIn = true);
  /** Wet comb output for one frame pair, given absolute mix-frame time. */
  void apply(float inL, float inR, float& wetL, float& wetR, double frameTimeSec, double sampleRate);
  /** Wet with envelope multiplier (KIND_FLANGER_WET) + tail ring. */
  void applyEnv(float inL, float inR, float wetMul, float& wetL, float& wetR, double frameTimeSec, double sampleRate);
};

/** Automatic HPF/LPF resonant filter sweep curve over mix seconds. */
struct SweepConfig {
  bool enabled = false;
  bool isHighPass = true;
  float startHz = 20.f;
  float endHz = 1500.f;
  float qStart = 0.7071f;
  float qEnd = 1.6f;
  double startSec = 0.0;
  double endSec = 0.0;
  bool exponential = true;
};

/**
 * AUTOMIX-V2(C): per-deck v2 state. Everything is inert until its setter /
 * envelope is used, so a vc80 script renders bit-identically.
 */
struct DeckV2 {
  // Stems: VOCALS, DRUMS, BASS stored as int16 + per-channel scale, aligned to
  // the region. OTHER is never stored: it is dry - (V + D + B), so the deck
  // renders gO*dry + sum_k (g_k - gO)*stem_k, which is the dry region exactly
  // when every stem gain is 1 (the terms vanish), and needs 25 % less memory.
  bool stemHas[3] = {false, false, false};
  std::vector<int16_t> stemQL[3], stemQR[3];
  float stemScaleL[3] = {0, 0, 0};
  float stemScaleR[3] = {0, 0, 0};
  /** Linear stem gains (KIND_STEM_VOCALS..OTHER), evaluated at the time the input is heard. */
  Envelope stemGain[4];
  bool stemsOn = false;  // validated in prepare()

  // Filter sweep: Q multiplier and the DJM single knob (-1 LPF .. +1 HPF).
  Envelope filterRes;
  Envelope filterKnob;
  bool filterV2 = false;
  float hpQLast = -1.f, lpQLast = -1.f;
  SweepConfig sweep;

  v2::Phaser phaser;
  Envelope phaserWet;
  v2::BeatGate gate;
  Envelope gateDepth;
  v2::Riser riser;
  Envelope riserLevel;
  v2::Impact impact;
  bool impactActive = false;
  v2::Pump pump;
  Envelope pumpDepth;
  v2::Brake brake;
  Envelope brakeProgress;
  bool fxActive = false;

  // Loudness: K-weighted programme loudness of the deck (post-stretch, pre-EQ, pre-fader, x level).
  v2::LoudnessMeter meter;
  float matchDb = 0.f;
  float match0 = 1.f, match1 = 1.f;
};

struct Deck {
  // The decoded region, planar, at the renderer's rate.
  std::vector<float> left;
  std::vector<float> right;
  /** Track seconds of region sample 0. */
  double regionStart = 0;

  /** Loop bounds in region samples, and where the loop lets go (unwrapped samples; huge = never). */
  int64_t loopStart = 0;
  int64_t loopEnd = 0;
  double releaseAt = 1e18;

  /** Beat knots: mix frames, and the unwrapped position heard then (in region samples). */
  std::vector<double> knotTimes;
  std::vector<double> knotPositions;
  /** After the last knot: carry on at rate 1 (the incoming song), or stop (the outgoing one). */
  bool runOn = false;
  /** Mix seconds after which the deck renders nothing at all. */
  double stopAt = 1e18;
  /** Seconds of pre-roll before its first knot, so its first audible sample is already settled. */
  double preroll = 0.4;
  /** A plain gain on the deck (loudness matching between the two songs). */
  float level = 1;

  Envelope gain;
  Envelope lowDb;
  Envelope midDb;
  Envelope highDb;
  Envelope highPassHz;
  Envelope lowPassHz;
  /** 0..1 send into the echo. Empty means shut during pre-roll and open after the first knot. */
  Envelope echoSend;
  /** djmix24 0..1 multiplier on flanger wet (rising ramp into releaseAt + tail). */
  Envelope flangerWet;
  /** djmix24 0..1 send into the reverb wash. */
  Envelope reverbSend;
  /** djmix24 beat-quantized tightening EDM buildup loop roll. */
  bool loopRoll = false;
  double rollStartMixSec = 0.0;
  double rollBeatSec = 0.5;
  double rollReleaseMixSec = 0.0;
  double rollTotalBeats = 16.0;
  double loopSeamMs = 8.0;
  bool rollFxActive = false;
  float rollHpfStartHz = 200.f;
  float rollHpfEndHz = 2000.f;
  float rollBoostDb = 2.f;
  bool rollStopOnDownbeat = true;
  std::vector<double> rollScheduleBeats;
  /** djmix24 Cytomic SVF Q (0.707 default, 0.95 buildup). */
  float svfQ = 0.707f;
  /** djmix24 effective loop end for the current block (samples); -1 = use loopEnd. */
  int64_t effectiveLoopEnd = -1;
  double effectiveLoopLenSamples = -1.0;
  int64_t sampleAtEffective(int64_t unwrapped) const;
  void readEffective(int64_t unwrapped, int seamSamples, double mixSec, float& leftOut, float& rightOut) const;

  /**
   * Unwrapped region-sample position at which the deck fades out over
   * [fadeSamples]; negative = never. For a straight deck this is the region
   * sample of the track time. For a looping deck it is the unwrapped position
   * after the release, not the looped sample.
   */
  std::atomic<double> fadeOutAt{-1.0};
  /** Track seconds heard at the end of the last rendered block. */
  std::atomic<double> heard{0.0};

  // Stretch state is configured/held-start primed in prepare(), then advanced
  // by render(); all mutable playback state is otherwise render-thread only.
  signalsmith::stretch::SignalsmithStretch<float> stretch;
  bool started = false;
  bool finished = false;
  bool heldStartup = false;
  bool heldPrimed = false;
  int64_t heldPreparedFrame = 0;
  double readPos = 0;      // unwrapped region samples, the next input sample to feed
  double heardPos = 0;     // unwrapped region samples heard at the end of the last block
  double lastKnotHeard = 0;
  double activeStartFrame = 0;
  Biquad shelf;
  TptHighpass tptHighPass;
  TptLowpass lowPass;
  PingPong echo;
  ReverbWash reverb;
  Flanger flanger;
  /** Phrase mixes run the LR4 isolator. The djmix6 path leaves this off. */
  bool isolate = false;
  PhraseIsolator isolator;
  float shelfDb = 0;
  float highPassFreq = 0;
  float lowPassFreq = -1;

  /** Track seconds an unwrapped position plays (continuous; the loop and its release applied). */
  double trackSecondsAt(double unwrapped, double sampleRate) const;
  /** The unwrapped position, after the release, that plays track second [seconds]. */
  double releasedPositionOf(double seconds, double sampleRate) const;
  /** The region sample an unwrapped position reads, or -1 outside the region. */
  int64_t sampleAt(int64_t unwrapped) const;
  /** End of available input in the monotonic, unwrapped source coordinate. */
  double inputEnd() const;
  /**
   * Reads [unwrapped], crossfading the first 8 ms of a loop with the audio
   * just past the loop end so the seam isn't a click.
   */
  void read(int64_t unwrapped, int seamSamples, float& leftOut, float& rightOut) const;
  /** Heard position (unwrapped samples) at mix frame [t], before the run-on. */
  double knotHeard(double t) const;
  /** Input samples per output sample around mix frame [t]. */
  double knotRate(double t) const;

  // AUTOMIX-V2(C)
  DeckV2 v2;
  /** The exact sample/tail/weights read() uses for [unwrapped]; false = silence. */
  bool tapsPlain(int64_t unwrapped, int seamSamples, int64_t& index, int64_t& tail, float& wIndex, float& wTail) const;
  /** The exact sample/tail/weights readEffective() uses. */
  bool tapsEffective(int64_t unwrapped, int seamSamples, double mixSec, int64_t& index, int64_t& tail, float& wIndex,
                     float& wTail) const;
  /** Stem-weighted read; [gains] = V, D, B, O. All-unity gains take the plain dry read (bit-identical). */
  void readStems(int64_t unwrapped, int seamSamples, double mixSec, const float* gains, float& leftOut,
                 float& rightOut) const;
};

/** AUTOMIX-V2(C): the v2 envelope for [kind] (9..19), or nullptr. */
inline Envelope* v2Envelope(Deck& d, int kind) {
  switch (kind) {
    case v2::KIND_STEM_VOCALS: return &d.v2.stemGain[0];
    case v2::KIND_STEM_DRUMS: return &d.v2.stemGain[1];
    case v2::KIND_STEM_BASS: return &d.v2.stemGain[2];
    case v2::KIND_STEM_OTHER: return &d.v2.stemGain[3];
    case v2::KIND_FILTER_RES: return &d.v2.filterRes;
    case v2::KIND_PHASER_WET: return &d.v2.phaserWet;
    case v2::KIND_GATE_DEPTH: return &d.v2.gateDepth;
    case v2::KIND_RISER_LEVEL: return &d.v2.riserLevel;
    case v2::KIND_PUMP_DEPTH: return &d.v2.pumpDepth;
    case v2::KIND_BRAKE: return &d.v2.brakeProgress;
    case v2::KIND_FILTER_KNOB: return &d.v2.filterKnob;
    default: return nullptr;
  }
}

class Renderer {
 public:
  Renderer(int sampleRate);

  Deck& deck(int index) { return decks_[index]; }
  int sampleRate() const { return sampleRate_; }

  /** [high] selects Signalsmith's default preset; otherwise the cheaper one. Call before [prepare]. */
  void setHighQuality(bool high) { highQuality_ = high; }
  /** Tempo-synced echo on [index]. A non-positive delay turns it off. */
  void setEcho(int index, double delaySeconds, float feedback, float wet);
  void setEchoEx(int index, double delaySeconds, float feedback, float wet,
                 float wetHpfHz, float wetLpfHz, bool pingPong);
  void setIsolate(int index, bool on);
  /** Loop seam crossfade duration in milliseconds (clamped [0.5, 8.0] ms). */
  void setLoopSeamMs(int index, double seamMs);
  /** djmix24 beat-quantized tightening loop roll. */
  void setLoopRoll(int index, bool enabled, double rollStartSec, double beatSec, double releaseSec, double totalRollBeats);
  /** Part A §5.2 loop roll with schedule, rising HPF, gain boost, and hard downbeat stop. */
  void setLoopRollEx(int index, bool enabled, double rollStartSec, double beatSec, double releaseSec,
                     double totalRollBeats, float hpfStartHz, float hpfEndHz, float boostDb,
                     bool stopOnDownbeat, const double* scheduleBeats = nullptr, int scheduleCount = 0);
  /** djmix24 stereo plate reverb wash. */
  void setReverb(int index, float roomSize, float damping, float wet);
  /** djmix24 Cytomic SVF Q. */
  void setSvfQ(int index, float q);

  /** Call once both decks are filled in, before the first [render]. */
  void prepare();

  /**
   * Beat-synced through-zero jet flanger on [index]'s tail.
   */
  void setFlanger(int index, double lfoHz, double depthSeconds, float feedback, float wet);
  void setFlangerEx(int index, double lfoHz, double depthSeconds, float feedback, float wet,
                    float stereoOffsetDeg);
  /** How many times a deck went silent while still audible at stopAt (the exit gate's trip counter). */
  int exitGateViolations() const { return exitGateViolations_.load(std::memory_order_relaxed); }

  // ---- AUTOMIX-V2(C) ------------------------------------------------------
  /** Native capability bitmask (v2::Capability). */
  static int capabilities() { return v2::kCapabilities; }
  /**
   * One stem of [index] (kind 0 VOCALS, 1 DRUMS, 2 BASS; 3 OTHER is accepted
   * and ignored, being derived). Planar, [frames] must equal the region;
   * checked again in prepare(). Worker thread only.
   */
  bool setDeckStem(int index, int kind, const float* left, const float* right, int frames);
  void clearDeckStems(int index);
  /** Phaser: LFO cycle [rateBeats] (1/16..64), sweep [depth] 0..1, [feedback] -0.85..0.85. Wet = KIND_PHASER_WET. */
  void setPhaser(int index, double rateBeats, float depth, float feedback);
  void setPhaserEx(int index, double rateBeats, float depth, float feedback, int stages, float stereoOffsetDeg);
  /**
   * Beat gate: [division] gate steps per beat (1/16..16; 4 = 1/16 notes, DJM
   * TRANS); a negative division selects stutter (repeat the first step's slice).
   * [swing] 0..0.33. Depth = KIND_GATE_DEPTH. [beatSec] is the fallback grid.
   */
  void setGate(int index, double beatSec, float division, float swing);
  void setGateEx(int index, double beatSec, float divStart, float divEnd, float swing,
                 double startSec, double endSec);
  /** 3-layer tempo-synced riser + reverse-reverb tail, cut cleanly on beat 1. */
  void setRiser(int index, double beatSec, double lengthBeats);
  void setRiserEx(int index, double beatSec, double lengthBeats, float noiseStartHz, float noiseEndHz,
                  float noiseLevelPeak, float uplifterOctaves, bool includeSnareRoll,
                  double reverseReverbBeats, double startSec = -1.0);
  /** Drop impact on beat 1 (low thump + noise burst, <= -6 dBFS). */
  void setImpact(int index, double triggerMixSec, float peakDbfs, float lowThumpHz, double noiseBurstMs);
  /** Beat-locked pump on the deck's low band (< 120 Hz), or the whole deck when [fullBand]. Depth = KIND_PUMP_DEPTH. */
  void setPump(int index, double beatSec, bool fullBand = false);
  /** Pre-stretch vinyl brake / tape-stop / spinback. */
  void setBrake(int index, double startSec, double lengthSec, bool spinback = false);
  void setBrakeEx(int index, double startSec, double lengthSec, float endRate,
                  float lpfStartHz, float lpfEndHz, double endFadeMs);
  void setTapeStop(int index, double startSec, double lengthSec, float wowDepth, float wowRateHz,
                   float lpfStartHz, float lpfEndHz, double endFadeMs);
  void setSpinback(int index, double startSec, double lengthSec, double peakReverseSpeed, double silenceGapSec);
  /** Automatic HPF/LPF resonant filter sweep over mix seconds [startSec, endSec]. */
  void setSweep(int index, bool isHighPass, float startHz, float endHz,
                float qStart, float qEnd, double startSec, double endSec, bool exponential);
  /** True-peak limiter ceiling in dBTP (default -1.0 dBTP). */
  void setTruePeakCeilingDb(float ceilingDbtp);
  /** Enables 40 s pre-allocated capture ring buffer (call before prepare()). */
  void setCaptureEnabled(bool enabled);
  /** Request capture freeze (controller/worker thread). No-op if not armed or already frozen/requested. */
  void requestCaptureFreeze();
  /** Capture state for polling. */
  enum class CaptureReadStatus : int {
    kUnavailable = 0,      // not armed/enabled for this session
    kPending = 1,          // armed but not yet frozen (or freeze requested, awaiting render ack)
    kFrozen = 2,           // frozen snapshot ready
    kNoData = 3            // frozen but no frames captured (empty)
  };
  struct CaptureSnapshotInfo {
    CaptureReadStatus status = CaptureReadStatus::kUnavailable;
    int64_t snapshotId = 0;
    int sampleRate = 0;
    int channels = 2;
    int64_t firstMixFrame = -1;   // mix frame of first sample in snapshot (chronological)
    int64_t lastMixFrame = -1;    // mix frame of last sample in snapshot (chronological)
    int frameCount = 0;           // number of frames in snapshot (<= cap)
    int64_t totalWrittenFrames = 0; // total frames written before freeze (for diagnostics)
  };
  /** Poll snapshot info (thread-safe). Does not copy samples. */
  CaptureSnapshotInfo getCaptureSnapshotInfo() const;
  /**
   * Copy frozen snapshot samples into outInterleaved (interleaved float). Returns frames copied, or 0 if not frozen/unavailable.
   * Only valid after status is Frozen (and will remain valid for this frozen snapshot under current API contract:
   * no rearm/reset allowed while reader may consume in this design; caller should treat snapshot as immutable).
   */
  int readFrozenCapture(float* outInterleaved, int maxFrames) const;
  /** Legacy compatibility: returns frozen snapshot frame count; 0 when not frozen/unavailable. */
  int64_t capturedFrames() const;
  /**
   * Short-term loudness tracking with per-deck matching and the summed-bus
   * "no dip / no bump" guard during overlaps; [targetLufs] (e.g. -14, the
   * static normalisation reference) caps bumps; NaN/0 = no cap. Turns the
   * true-peak limiter on.
   */
  void setLoudnessMatch(bool enabled, float targetLufs);
  /**
   * Festival loudness drop exemption: during [exemptStartSec, exemptEndSec]
   * (pre-drop build tension), exempts from the "no dip" upward boost; during
   * the following drop impact bar [exemptEndSec, exemptEndSec + bar], allows
   * +[dropBumpLu] LU above the ceiling and A->B line. Inactive by default.
   */
  void setLoudnessDropWindow(double exemptStartSec, double exemptEndSec, float dropBumpLu);
  /** 4x oversampled true-peak detection in the limiter (-1 dBTP). */
  void setTruePeak(bool on) { truePeakExplicit_ = on; }
  /** EBU R128 short-term (3 s) loudness: 0/1 = deck programme, 2 = output bus. -120 when unknown. */
  double shortTermLufs(int which) const {
    return which >= 0 && which < 3 ? stLufs_[which].load(std::memory_order_relaxed) : -120.0;
  }
  /** The bus guard's current gain (dB). */
  float loudnessGuardDb() const { return guardDbOut_.load(std::memory_order_relaxed); }
  /** Beat position (beats since mix beat 0) at mix second [t], from the knot grid or the fallback beat. */
  double beatPosAt(double t) const;
  /** Local beat length (seconds) at mix second [t]. */
  double beatDurAt(double t) const;

  int64_t firstFrame() const;

  /**
   * Renders [frames] stereo frames starting at mix frame [frame] into
   * [interleaved] (overwritten). Frames must be asked for in order.
   */
  void render(int64_t frame, float* interleaved, int frames);

 private:
  void renderDeck(Deck& deck, int64_t frame, int frames);
  void limit(int frames);
  // AUTOMIX-V2(C)
  void prepareV2();
  void fillStemInput(Deck& deck, int64_t from, int count, int64_t mixFrameBase, double refFrame, double refHeard,
                     double rate, int available);
  void loudnessPreBlock(int64_t frame, int frames);
  void loudnessGuard(int64_t frame, int frames);
  void limitTruePeak(int frames);
  void sampleDeckVarispeed(Deck& deck, double unwrapped, int seam, double mixSec, float& outL, float& outR) const;

  int sampleRate_;
  bool highQuality_ = true;
  float limitGain_ = 1;
  float truePeakCeiling_ = 0.89125094f;  // -1.0 dBTP default
  Deck decks_[2];
  std::atomic<int> exitGateViolations_{0};
  std::vector<float> inLeft_, inRight_, outLeft_, outRight_;
  std::vector<float> mixLeft_, mixRight_;
  // Legacy input history; limit() detects sample peaks within each callback.
  std::vector<float> limitRingL_, limitRingR_;
  int limitRingPos_ = 0;
  // Optional 40 s audio ring-buffer capture (enabled/allocated in prepare() when requested).
  std::atomic<bool> captureEnabled_{false};  // intent flag; config frozen after prepare()
  std::atomic<bool> captureConfigFrozen_{false};  // set in prepare(); refuses post-prepare rearm/races
  std::vector<float> captureRing_;
  int captureCapFrames_ = 0;
  std::atomic<int64_t> captureWriteFrames_{0};

  /** Capture protocol state machine (render-thread + reader safe via atomics). */
  enum class CaptureState : int {
    kDisabled = 0,
    kArmed = 1,
    kFrozen = 2
  };
  std::atomic<CaptureState> captureState_{CaptureState::kDisabled};
  std::atomic<bool> captureFreezeRequested_{false};
  // Set at freeze time (written by render thread before publishing Frozen with release).
  int64_t captureSessionStartMixFrame_ = -1;
  int64_t captureFrozenTotalWritten_ = 0;
  int64_t captureFrozenStartMixFrame_ = -1;
  int64_t captureSnapshotId_ = 0;

  // AUTOMIX-V2(C) state. Pre-allocated in prepare(); render thread only after that.
  bool loudnessOn_ = false;
  float targetLufs_ = 0.f;
  double dropExemptStartSec_ = 1e18;
  double dropExemptEndSec_ = -1e18;
  float dropBumpLu_ = 0.f;
  bool truePeakExplicit_ = false;
  v2::LoudnessMeter busMeter_, outMeter_;
  float guardDb_ = 0.f;
  float guardLin_ = 1.f;
  double lineLufs_ = -120.0;
  float overlap_ = 0.f;
  double deckLufs_[2] = {-120.0, -120.0};
  std::atomic<float> guardDbOut_{0.f};
  std::atomic<double> stLufs_[3];
  v2::TruePeakFir tpFir_;
  std::vector<float> tpBufL_, tpBufR_, tpPeak_;
  float tpHistL_[v2::TruePeakFir::kHistory] = {};
  float tpHistR_[v2::TruePeakFir::kHistory] = {};
  std::vector<double> gridTimes_;
  double gridOffset_ = 0.0;
  double fxBeatSec_ = 0.5;
};

}  // namespace bitchord::loopmix
