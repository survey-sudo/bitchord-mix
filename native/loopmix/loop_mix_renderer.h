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
 * Cytomic TPT SVF high-pass (Simper zero-delay-feedback topology,
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

/** Pre-allocated stereo plate reverb wash (Freeverb-class comb/allpass). */
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
  float hpL = 0, hpR = 0, lpL = 0, lpR = 0;
  bool enabled = false;

  void setup(double sampleRate, double delaySeconds, float feedbackGain, float wetGain);
  /**
   * Writes the delayed signal into [wetL]/[wetR].
   * The delay is fed `dry × send`. A send of 0 still recirculates what is
   * already in the line, so a tail can ring after the dry input stops.
   */
  void tap(float inL, float inR, float send, float& wetL, float& wetR, double sampleRate);
};

/**
 * Stereo Jet Flanger (DJM-style): true stereo quadrature LFO
 * (90 deg / 0.25 cycle offset, wide jet while mono-compatible), delay
 * sweep 0.65 ms .. 5.2 ms, metallic resonant feedback 0.64 with 1-pole
 * ~10 kHz HF damping + denormal guard + hard clamp [-0.78, 0.78], wet
 * 0.48 energy-balanced. History is cleared at prepare() (worker thread).
 */
struct Flanger {
  std::vector<float> left;
  std::vector<float> right;
  int pos = 0;
  float depthSeconds = 0.0f;
  float feedback = 0.0f;
  float wet = 0.0f;
  double lfoHz = 0.0;
  bool enabled = false;
  float dampL = 0.f, dampR = 0.f;

  void setup(double sampleRate, double lfoHzIn, double depthSecondsIn, float feedbackGain, float wetGain);
  /** Wet comb output for one frame pair, given absolute mix-frame time. */
  void apply(float inL, float inR, float& wetL, float& wetR, double frameTimeSec, double sampleRate);
  /** Wet with envelope multiplier (KIND_FLANGER_WET) + tail ring. */
  void applyEnv(float inL, float inR, float wetMul, float& wetL, float& wetR, double frameTimeSec, double sampleRate);
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
  /** 0..1 multiplier on flanger wet (rising ramp into releaseAt + tail). */
  Envelope flangerWet;
  /** 0..1 send into the reverb wash. */
  Envelope reverbSend;
  /** Beat-quantized tightening EDM buildup loop roll. */
  bool loopRoll = false;
  double rollStartMixSec = 0.0;
  double rollBeatSec = 0.5;
  double rollReleaseMixSec = 0.0;
  double rollTotalBeats = 16.0;
  /** Cytomic SVF Q (0.707 default, 0.95 buildup). */
  float svfQ = 0.707f;
  /** Effective loop end for the current block (samples); -1 = use loopEnd. */
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
  /** Phrase mixes run the LR4 isolator. The legacy path leaves this off. */
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
};

class Renderer {
 public:
  Renderer(int sampleRate);

  Deck& deck(int index) { return decks_[index]; }
  int sampleRate() const { return sampleRate_; }

  /** [high] selects Signalsmith's default preset; otherwise the cheaper one. Call before [prepare]. */
  void setHighQuality(bool high) { highQuality_ = high; }
  /** Tempo-synced echo on [index]. A non-positive delay turns it off. */
  void setEcho(int index, double delaySeconds, float feedback, float wet);
  void setIsolate(int index, bool on);
  /** Beat-quantized tightening loop roll. */
  void setLoopRoll(int index, bool enabled, double rollStartSec, double beatSec, double releaseSec, double totalRollBeats);
  /** Stereo plate reverb wash. */
  void setReverb(int index, float roomSize, float damping, float wet);
  /** Cytomic SVF Q. */
  void setSvfQ(int index, float q);

  /** Call once both decks are filled in, before the first [render]. */
  void prepare();

  /** The earliest mix frame anything renders at (negative: before t0). */
  /**
   * Beat-synced flanger on [index]'s tail (graceful exit).
   * Depth is the delay's sweep in seconds (max 4 ms); lfoHz is one cycle
   * per 8 beats, so the phasing reads as a dissolve, never a wobble.
   */
  void setFlanger(int index, double lfoHz, double depthSeconds, float feedback, float wet);
  /** How many times a deck went silent while still audible at stopAt (the exit gate's trip counter). */
  int exitGateViolations() const { return exitGateViolations_.load(std::memory_order_relaxed); }

  int64_t firstFrame() const;

  /**
   * Renders [frames] stereo frames starting at mix frame [frame] into
   * [interleaved] (overwritten). Frames must be asked for in order.
   */
  void render(int64_t frame, float* interleaved, int frames);

 private:
  void renderDeck(Deck& deck, int64_t frame, int frames);
  void limit(int frames);

  int sampleRate_;
  bool highQuality_ = true;
  float limitGain_ = 1;
  Deck decks_[2];
  std::atomic<int> exitGateViolations_{0};
  std::vector<float> inLeft_, inRight_, outLeft_, outRight_;
  std::vector<float> mixLeft_, mixRight_;
  // Legacy input history; limit() detects sample peaks within each callback.
  std::vector<float> limitRingL_, limitRingR_;
  int limitRingPos_ = 0;
};

}  // namespace bitchord::loopmix
