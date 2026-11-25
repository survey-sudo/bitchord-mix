// Pitch correction for a beat-matched blend.
//
// AudioTrack playback params change tempo by resampling, which also shifts
// pitch. This runs in the sink before that resample and lowers the pitch by
// the same ratio, so the tempo change lands at the original pitch. Equal
// input and output lengths: the player's clock stays on the playback-rate
// timeline, and nothing is allocated in the audio callback.
//
// Signalsmith Stretch is MIT. Copyright (c) Signalsmith Audio Ltd.
// See THIRD_PARTY_NOTICES.md.

#pragma once

#include <cstddef>

namespace bitchord {

class PitchStretch {
 public:
  PitchStretch();
  ~PitchStretch();

  void configure(int channels, int sampleRate);
  /** Tempo the AudioTrack will apply. 1 leaves the audio untouched. */
  void setTempo(float tempo);
  void process(float* interleaved, int frames);

  /**
   * Channel count accepted by the last configure(), or 0 before any.
   * process() walks frames * configuredChannels() samples, so callers that
   * hand it an interleaved buffer must supply that same count. Read-only,
   * no allocation. A concurrent configure() racing process() is not covered.
   */
  int configuredChannels() const;

 private:
  struct State;
  State* state_;
};

/** Seconds of audio processed per second of CPU. 0 if the run failed. */
double stretchRealtimeFactor(int sampleRate, int seconds, float tempo);

}  // namespace bitchord
