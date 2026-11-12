#include "stretch/pitch_stretch.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <vector>

#include "signalsmith-stretch/signalsmith-stretch.h"

namespace bitchord {

struct PitchStretch::State {
  signalsmith::stretch::SignalsmithStretch<float> stretch{1};
  int channels = 0;
  int sampleRate = 0;
  float tempo = 1.f;
  int cap = 0;
  std::vector<float> in[2];
  std::vector<float> out[2];
};

PitchStretch::PitchStretch() : state_(new State) {}
PitchStretch::~PitchStretch() { delete state_; }

void PitchStretch::configure(int channels, int sampleRate) {
  if (channels < 1 || channels > 2 || sampleRate < 8000) return;
  auto* s = state_;
  s->channels = channels;
  s->sampleRate = sampleRate;
  // Cheaper preset: block ~100 ms, interval ~40 ms. Fine for a transition,
  // and it stays under a realtime budget on a phone core.
  // splitComputation off. Spreading one spectrum across later callbacks
  // made the stretcher treat a normal block as a huge time ratio, which is
  // the pitch-and-tempo runaway. Pitch only: equal in and out lengths.
  s->stretch.presetCheaper(channels, static_cast<float>(sampleRate), false);
  s->cap = std::max(s->stretch.blockSamples(), 4096);
  for (int c = 0; c < 2; ++c) {
    s->in[c].assign(s->cap, 0.f);
    s->out[c].assign(s->cap, 0.f);
  }
  s->stretch.setTransposeFactor(1.f / s->tempo);
}

void PitchStretch::setTempo(float tempo) {
  // A ratio past a small nudge is not a beatmatch. 100x, a BPM, or NaN
  // all land on 1. Anything still inside half-speed..double-speed is pulled
  // into the same audible window the deck plays at.
  if (!(tempo >= 0.5f && tempo <= 2.f)) tempo = 1.f;
  else if (tempo < 0.94f) tempo = 0.94f;
  else if (tempo > 1.09f) tempo = 1.09f;
  if (std::fabs(tempo - state_->tempo) < 1e-4f) return;
  state_->tempo = tempo;
  if (state_->sampleRate > 0) {
    // No reset. Resetting clears the stretch buffer, and the ease back to
    // the listener's speed steps the tempo every beat for the first seconds
    // of the incoming song. Each reset was a dropout there.
    state_->stretch.setTransposeFactor(1.f / tempo);
  }
}

void PitchStretch::process(float* interleaved, int frames) {
  auto* s = state_;
  if (interleaved == nullptr || frames <= 0 || s->channels < 1 || s->cap <= 0) return;
  if (std::fabs(s->tempo - 1.f) <= 0.004f) return;
  const int ch = s->channels;
  int done = 0;
  while (done < frames) {
    const int n = std::min(frames - done, s->cap);
    for (int i = 0; i < n; ++i) {
      for (int c = 0; c < ch; ++c) s->in[c][i] = interleaved[(done + i) * ch + c];
    }
    float* inPtr[2] = {s->in[0].data(), s->in[1].data()};
    float* outPtr[2] = {s->out[0].data(), s->out[1].data()};
    s->stretch.process(inPtr, n, outPtr, n);
    for (int i = 0; i < n; ++i) {
      for (int c = 0; c < ch; ++c) interleaved[(done + i) * ch + c] = outPtr[c][i];
    }
    done += n;
  }
}

double stretchRealtimeFactor(int sampleRate, int seconds, float tempo) {
  if (sampleRate < 8000 || seconds < 1) return 0;
  PitchStretch stretch;
  stretch.configure(2, sampleRate);
  stretch.setTempo(tempo);
  const int block = 512;
  std::vector<float> buf(block * 2);
  for (int i = 0; i < block; ++i) {
    buf[2 * i] = std::sin(2.0 * 3.141592653589793 * 440.0 * i / sampleRate);
    buf[2 * i + 1] = buf[2 * i];
  }
  const int total = sampleRate * seconds;
  auto start = std::chrono::steady_clock::now();
  for (int done = 0; done < total; done += block) stretch.process(buf.data(), block);
  auto elapsed = std::chrono::steady_clock::now() - start;
  const double cpu = std::chrono::duration<double>(elapsed).count();
  return cpu > 0 ? static_cast<double>(seconds) / cpu : 0;
}

}  // namespace bitchord
