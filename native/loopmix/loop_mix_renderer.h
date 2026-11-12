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
  void bypass() { g = 0; }
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
  Envelope highPassHz;
  Envelope lowPassHz;
  /** 0..1 send into the echo. Empty means shut during pre-roll and open after the first knot. */
  Envelope echoSend;

  /**
   * Unwrapped region-sample position at which the deck fades out over
   * [fadeSamples]; negative = never. For a straight deck this is the region
   * sample of the track time. For a looping deck it is the unwrapped position
   * after the release, not the looped sample.
   */
  std::atomic<double> fadeOutAt{-1.0};
  /** Track seconds heard at the end of the last rendered block. */
  std::atomic<double> heard{0.0};

  // Live state, render thread only.
  signalsmith::stretch::SignalsmithStretch<float> stretch;
  bool started = false;
  bool finished = false;
  double readPos = 0;      // unwrapped region samples, the next input sample to feed
  double heardPos = 0;     // unwrapped region samples heard at the end of the last block
  double lastKnotHeard = 0;
  Biquad shelf;
  Biquad highPass;
  TptLowpass lowPass;
  PingPong echo;
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

  /** Call once both decks are filled in, before the first [render]. */
  void prepare();

  /** The earliest mix frame anything renders at (negative: before t0). */
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
  std::vector<float> inLeft_, inRight_, outLeft_, outRight_;
  std::vector<float> mixLeft_, mixRight_;
};

}  // namespace bitchord::loopmix
