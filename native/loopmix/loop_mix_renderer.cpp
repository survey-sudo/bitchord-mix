// BitChord: the Automix loop mix's renderer. See loop_mix_renderer.h.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <cmath>

namespace bitchord::loopmix {

namespace {

/** Filter coefficients are recomputed at most this often; gains ramp per sample. */
constexpr int kControlFrames = 64;
/** The incoming deck's last fade, to its own player: identical audio crossed this fast is heard as one. */
constexpr double kFadeSeconds = 0.025;
constexpr double kLoopSeamSeconds = 0.008;
constexpr double kPi = 3.14159265358979323846;

}  // namespace

float Envelope::at(double time) const {
  if (times.empty()) return 0;
  if (time <= times.front()) return values.front();
  if (time >= times.back()) return values.back();
  // Envelopes are a handful of points; a linear scan beats a search's branches.
  size_t i = 1;
  while (i < times.size() && times[i] < time) ++i;
  const double t0 = times[i - 1], t1 = times[i];
  const double f = t1 > t0 ? (time - t0) / (t1 - t0) : 1.0;
  const double v0 = values[i - 1], v1 = values[i];
  if (exponential && v0 > 0 && v1 > 0) return float(v0 * std::pow(v1 / v0, f));
  return float(v0 + (v1 - v0) * f);
}

void Biquad::lowShelf(double sampleRate, double frequency, double gainDb) {
  // RBJ cookbook, shelf slope 1: the Web Audio lowshelf the web player's EQ uses.
  const double a = std::pow(10.0, gainDb / 40.0);
  const double w0 = 2 * kPi * frequency / sampleRate;
  const double cosw = std::cos(w0), sinw = std::sin(w0);
  const double alpha = sinw / 2 * std::sqrt(2.0);
  const double sq = 2 * std::sqrt(a) * alpha;
  const double a0 = (a + 1) + (a - 1) * cosw + sq;
  b0 = float(a * ((a + 1) - (a - 1) * cosw + sq) / a0);
  b1 = float(2 * a * ((a - 1) - (a + 1) * cosw) / a0);
  b2 = float(a * ((a + 1) - (a - 1) * cosw - sq) / a0);
  a1 = float(-2 * ((a - 1) + (a + 1) * cosw) / a0);
  a2 = float(((a + 1) + (a - 1) * cosw - sq) / a0);
}

void Biquad::highPass(double sampleRate, double frequency, double q) {
  const double w0 = 2 * kPi * frequency / sampleRate;
  const double cosw = std::cos(w0), sinw = std::sin(w0);
  const double alpha = sinw / (2 * q);
  const double a0 = 1 + alpha;
  b0 = float((1 + cosw) / 2 / a0);
  b1 = float(-(1 + cosw) / a0);
  b2 = b0;
  a1 = float(-2 * cosw / a0);
  a2 = float((1 - alpha) / a0);
}

void TptLowpass::set(double sampleRate, double frequency, double q) {
  if (!(sampleRate > 0) || !(frequency > 20) || frequency >= sampleRate * 0.45) {
    bypass();
    return;
  }
  const double safeQ = std::max(0.5, q);
  const double T = 1.0 / sampleRate;
  const double wa = (2.0 / T) * std::tan(kPi * frequency * T);
  g = float(wa * T / 2.0);
  const float k = float(1.0 / safeQ);
  a1 = 1.f / (1.f + g * (g + k));
  a2 = g * a1;
  a3 = g * a2;
}

void PingPong::setup(double sampleRate, double delaySeconds, float feedbackGain, float wetGain) {
  enabled = false;
  left.clear();
  right.clear();
  pos = 0;
  hpL = hpR = lpL = lpR = 0;
  if (!(sampleRate > 0) || !(delaySeconds > 0.01) || wetGain <= 0.f) return;
  delay = std::clamp(int(std::lround(delaySeconds * sampleRate)), 1, int(sampleRate * 2.0));
  left.assign(delay, 0.f);
  right.assign(delay, 0.f);
  feedback = feedbackGain;
  wet = wetGain;
  enabled = true;
}

void PingPong::tap(float inL, float inR, float send, float& wetL, float& wetR, double sampleRate) {
  wetL = 0;
  wetR = 0;
  if (!enabled || delay <= 0 || !(sampleRate > 0)) return;
  const float delayedL = left[pos];
  const float delayedR = right[pos];
  // Inside the feedback loop, so each repeat is darker than the last.
  // 300 Hz high-pass and 6 kHz low-pass. PhraseConfig.ECHO_HPF_HZ / ECHO_LPF_HZ.
  const float hpA = float(1.0 - std::exp(-2.0 * kPi * 300.0 / sampleRate));
  const float lpA = float(1.0 - std::exp(-2.0 * kPi * 6000.0 / sampleRate));
  hpL += hpA * (delayedL - hpL);
  hpR += hpA * (delayedR - hpR);
  const float hpOutL = delayedL - hpL;
  const float hpOutR = delayedR - hpR;
  lpL += lpA * (hpOutL - lpL);
  lpR += lpA * (hpOutR - lpR);
  const float gated = std::clamp(send, 0.f, 1.f);
  // Ping-pong: each side's feedback lands in the other channel.
  // The dry input is gated by [send], so pre-roll and the body of the
  // phrase never enter the line.
  left[pos] = inL * gated + std::tanh(lpR * feedback);
  right[pos] = inR * gated + std::tanh(lpL * feedback);
  pos++;
  if (pos >= delay) pos = 0;
  wetL = lpL * wet;
  wetR = lpR * wet;
}

int64_t Deck::sampleAt(int64_t unwrapped) const {
  int64_t index = unwrapped;
  const int64_t length = loopEnd - loopStart;
  if (length > 0 && unwrapped >= loopEnd) {
    if (unwrapped < int64_t(releaseAt)) {
      index = loopStart + (unwrapped - loopStart) % length;
    } else {
      // Let go: the song carries on from the loop's end.
      index = loopEnd + (unwrapped - int64_t(releaseAt));
    }
  }
  return (index >= 0 && index < int64_t(left.size())) ? index : -1;
}

void Deck::read(int64_t unwrapped, int seamSamples, float& leftOut, float& rightOut) const {
  const int64_t index = sampleAt(unwrapped);
  if (index < 0) {
    leftOut = 0;
    rightOut = 0;
    return;
  }
  leftOut = left[index];
  rightOut = right[index];
  const int64_t length = loopEnd - loopStart;
  if (length <= seamSamples || seamSamples <= 1) return;
  if (unwrapped < loopEnd || unwrapped >= static_cast<int64_t>(releaseAt)) return;
  const int64_t pos = (unwrapped - loopStart) % length;
  if (pos < 0 || pos >= seamSamples) return;
  const int64_t tail = loopEnd + pos;
  if (tail < 0 || tail >= int64_t(left.size()) || tail >= int64_t(right.size())) return;
  const float t = float(pos) / float(seamSamples);
  const float intoLoop = std::sin(t * float(kPi / 2.0));
  const float fromTail = std::cos(t * float(kPi / 2.0));
  leftOut = left[index] * intoLoop + left[tail] * fromTail;
  rightOut = right[index] * intoLoop + right[tail] * fromTail;
}

double Deck::trackSecondsAt(double unwrapped, double sampleRate) const {
  double position = unwrapped;
  const double length = double(loopEnd - loopStart);
  if (length > 0 && unwrapped >= double(loopEnd)) {
    if (unwrapped < releaseAt) {
      position = loopStart + std::fmod(unwrapped - loopStart, length);
    } else {
      position = loopEnd + (unwrapped - releaseAt);
    }
  }
  return regionStart + position / sampleRate;
}

double Deck::releasedPositionOf(double seconds, double sampleRate) const {
  // [seconds] is a track time. The fade is compared with the unwrapped region
  // sample the renderer is hearing.
  const double trackSample = (seconds - regionStart) * sampleRate;
  // A straight deck (Echo, Drop, beat blend) never wraps: its knots already
  // address the region, so the fade point is that sample. Treating it like a
  // loop — run-on measured from loopEnd — schedules the fade several seconds
  // late. The deck then stays at full level while the player is already
  // playing the same bars, and the two copies flam until stopAt.
  if (loopEnd <= loopStart) return trackSample;
  // A looping deck lets go from the loop's end. [seconds] is the continuing
  // track time past that end, and the unwrapped position is releaseAt plus
  // however far past the loop that time is.
  return releaseAt + (trackSample - double(loopEnd));
}

double Deck::knotHeard(double t) const {
  if (knotTimes.empty()) return 0;
  if (t <= knotTimes.front()) {
    return knotPositions.front() + (t - knotTimes.front()) * knotRate(knotTimes.front());
  }
  if (t >= knotTimes.back()) return knotPositions.back();
  size_t i = std::upper_bound(knotTimes.begin(), knotTimes.end(), t) - knotTimes.begin();
  const double f = (t - knotTimes[i - 1]) / (knotTimes[i] - knotTimes[i - 1]);
  return knotPositions[i - 1] + (knotPositions[i] - knotPositions[i - 1]) * f;
}

double Deck::knotRate(double t) const {
  if (knotTimes.size() < 2) return 1;
  size_t i = std::upper_bound(knotTimes.begin(), knotTimes.end(), t) - knotTimes.begin();
  i = std::clamp<size_t>(i, 1, knotTimes.size() - 1);
  const double span = knotTimes[i] - knotTimes[i - 1];
  if (!(span > 0.0)) return 1.0;
  const double rate = (knotPositions[i] - knotPositions[i - 1]) / span;
  // A loop follows its ramp. It does not play a song at a hundred times
  // speed because two knots were a sample apart.
  if (!(rate >= 0.5 && rate <= 2.0)) return 1.0;
  return rate;
}

Renderer::Renderer(int sampleRate) : sampleRate_(sampleRate) {}

void Renderer::setEcho(int index, double delaySeconds, float feedback, float wet) {
  if (index < 0 || index > 1) return;
  decks_[index].echo.setup(sampleRate_, delaySeconds, feedback, wet);
}

void Renderer::setIsolate(int index, bool on) {
  if (index < 0 || index > 1) return;
  decks_[index].isolate = on;
}

void Renderer::prepare() {
  limitGain_ = 1.f;
  for (Deck& deck : decks_) {
    // Default quality outside efficient mode: the mix is written ahead of the
    // speaker, so the longer window costs nothing on the playback thread.
    // Efficient mode keeps the cheaper preset. Either way the work is spread
    // across the block so one callback doesn't take the whole window.
    if (highQuality_) deck.stretch.presetDefault(2, float(sampleRate_), true);
    else deck.stretch.presetCheaper(2, float(sampleRate_), true);
    deck.started = false;
    deck.finished = deck.left.empty() || deck.knotTimes.size() < 2;
    deck.shelf.bypass();
    deck.highPass.bypass();
    deck.lowPass.bypass();
    if (deck.isolate) deck.isolator.setup(sampleRate_);
    deck.shelfDb = 0;
    deck.highPassFreq = 0;
    deck.lowPassFreq = -1;
  }
}

int64_t Renderer::firstFrame() const {
  double first = 0;
  for (const Deck& deck : decks_) {
    if (deck.knotTimes.empty()) continue;
    const double latency = deck.stretch.outputLatency() / double(sampleRate_);
    first = std::min(first, deck.knotTimes.front() / sampleRate_ - deck.preroll - latency);
  }
  return int64_t(std::floor(first * sampleRate_));
}

void Renderer::render(int64_t frame, float* interleaved, int frames) {
  if (int(mixLeft_.size()) < frames) {
    mixLeft_.resize(frames);
    mixRight_.resize(frames);
  }
  std::fill(mixLeft_.begin(), mixLeft_.begin() + frames, 0.f);
  std::fill(mixRight_.begin(), mixRight_.begin() + frames, 0.f);
  for (Deck& deck : decks_) renderDeck(deck, frame, frames);
  limit(frames);
  for (int i = 0; i < frames; ++i) {
    interleaved[2 * i] = mixLeft_[i];
    interleaved[2 * i + 1] = mixRight_[i];
  }
}

void Renderer::renderDeck(Deck& deck, int64_t frame, int frames) {
  if (deck.finished) return;
  const double sr = sampleRate_;
  const double blockStart = frame / sr;
  const double blockEnd = (frame + frames) / sr;
  if (blockStart >= deck.stopAt) {
    deck.finished = true;
    return;
  }
  const double inLatency = deck.stretch.inputLatency();
  const double outLatency = deck.stretch.outputLatency();
  const double startTime = deck.knotTimes.front() / sr - deck.preroll - outLatency / sr;
  const double frameStart = double(frame);
  const double frameEnd = double(frame + frames);
  if (!deck.started) {
    if (blockEnd <= startTime) return;
    // Seeded at this block's start, its read pointer the stretch's own latency
    // ahead of what will be heard: the first outLatency samples are pre-roll,
    // over by the time the deck's gain opens.
    const double rate = deck.knotRate(frameStart);
    deck.heardPos = deck.knotHeard(frameStart);
    deck.readPos = deck.heardPos + inLatency + rate * outLatency;
    const int seekLength = deck.stretch.seekLength();
    if (int(inLeft_.size()) < seekLength) {
      inLeft_.resize(seekLength);
      inRight_.resize(seekLength);
    }
    const int seam = std::max(1, int(std::lround(kLoopSeamSeconds * sr)));
    const int64_t first = int64_t(std::llround(deck.readPos)) - seekLength;
    for (int i = 0; i < seekLength; ++i) deck.read(first + i, seam, inLeft_[i], inRight_[i]);
    float* seekInput[2] = {inLeft_.data(), inRight_.data()};
    deck.stretch.reset();
    deck.stretch.seek(seekInput, seekLength, rate);
    deck.readPos = double(first + seekLength);
    deck.started = true;
  }

  // Where the block must end, heard: on the knots, or (past the last one, the
  // incoming song running on) at rate 1.
  double heardEnd;
  double rateEnd;
  const double lastKnot = deck.knotTimes.back();
  if (frameEnd <= lastKnot) {
    heardEnd = deck.knotHeard(frameEnd);
    rateEnd = deck.knotRate(frameEnd - 1);
  } else if (deck.runOn) {
    const double from = std::max(frameStart, lastKnot);
    const double base = frameStart < lastKnot ? deck.knotPositions.back() : deck.heardPos;
    heardEnd = base + (frameEnd - from);
    rateEnd = 1;
  } else {
    heardEnd = deck.knotPositions.back();
    rateEnd = deck.knotRate(lastKnot - 1);
  }
  if (deck.started) {
    // A bad knot used to jump the read pointer by hundreds of times in one
    // block. Two samples of song per sample of mix is as fast as a loop goes.
    const double advance = heardEnd - deck.heardPos;
    const double maxAdvance = double(frames) * 2.0;
    if (advance > maxAdvance) heardEnd = deck.heardPos + maxAdvance;
  }
  const double readEnd = heardEnd + inLatency + rateEnd * outLatency;
  int inputSamples = int(std::floor(readEnd - deck.readPos));
  inputSamples = std::clamp(inputSamples, 0, frames * 8);
  if (int(inLeft_.size()) < inputSamples) {
    inLeft_.resize(inputSamples);
    inRight_.resize(inputSamples);
  }
  const int seam = std::max(1, int(std::lround(kLoopSeamSeconds * sr)));
  const int64_t readFrom = int64_t(deck.readPos);
  for (int i = 0; i < inputSamples; ++i) deck.read(readFrom + i, seam, inLeft_[i], inRight_[i]);
  deck.readPos += inputSamples;

  if (int(outLeft_.size()) < frames) {
    outLeft_.resize(frames);
    outRight_.resize(frames);
  }
  float* input[2] = {inLeft_.data(), inRight_.data()};
  float* output[2] = {outLeft_.data(), outRight_.data()};
  deck.stretch.process(input, inputSamples, output, frames);

  const double heardStart = deck.heardPos;
  deck.heardPos = heardEnd;
  deck.heard.store(deck.trackSecondsAt(heardEnd, sr), std::memory_order_relaxed);

  // Gain, bass shelf and high-pass, in control blocks; the gain ramps per sample.
  const double fadeAt = deck.fadeOutAt.load(std::memory_order_relaxed);
  const double fadeSamples = kFadeSeconds * sr;
  for (int at = 0; at < frames; at += kControlFrames) {
    const int count = std::min(kControlFrames, frames - at);
    const double t0 = (frame + at) / sr;
    const double t1 = (frame + at + count) / sr;
    if (!deck.lowDb.empty()) {
      const float db = deck.lowDb.at((t0 + t1) / 2);
      if (std::abs(db - deck.shelfDb) > 0.01f) {
        deck.shelfDb = db;
        if (std::abs(db) < 0.01f) {
          deck.shelf.bypass();
        } else {
          deck.shelf.lowShelf(sr, 250, db);
        }
      }
    }
    if (!deck.highPassHz.empty()) {
      const float hz = deck.highPassHz.at((t0 + t1) / 2);
      if (std::abs(hz - deck.highPassFreq) > 0.5f) {
        deck.highPassFreq = hz;
        if (hz <= 20.5f) {
          deck.highPass.bypass();
        } else {
          deck.highPass.highPass(sr, hz, 0.7);
        }
      }
    }
    if (!deck.lowPassHz.empty()) {
      const float hz = deck.lowPassHz.at((t0 + t1) / 2);
      if (std::abs(hz - deck.lowPassFreq) > 1.f) {
        deck.lowPassFreq = hz;
        if (hz >= 18000.f) deck.lowPass.bypass();
        else deck.lowPass.set(sr, hz, 0.707);
      }
    }
    const float g0 = deck.gain.empty() ? 1.f : deck.gain.at(t0);
    const float g1 = deck.gain.empty() ? 1.f : deck.gain.at(t1);
    for (int i = 0; i < count; ++i) {
      const int n = at + i;
      float g = (g0 + (g1 - g0) * (float(i) / count)) * deck.level;
      if (fadeAt >= 0) {
        const double heard = heardStart + (heardEnd - heardStart) * (double(n) / frames);
        const double fade = std::clamp((fadeAt + fadeSamples - heard) / fadeSamples, 0.0, 1.0);
        g *= float(fade);
      }
      float l = deck.shelf.tick(0, outLeft_[n]);
      float r = deck.shelf.tick(1, outRight_[n]);
      l = deck.highPass.tick(0, l);
      r = deck.highPass.tick(1, r);
      l = deck.lowPass.tick(0, l);
      r = deck.lowPass.tick(1, r);
      if (deck.isolate && deck.isolator.ready) {
        float loL, midL, hiL, loR, midR, hiR;
        deck.isolator.process(0, l, loL, midL, hiL);
        deck.isolator.process(1, r, loR, midR, hiR);
        // Unity band gains. The shelf and the filters above carry the score sheet.
        // The sum is an allpass, flat within a tenth of a dB.
        l = loL + midL + hiL;
        r = loR + midR + hiR;
      }
      float wetL = 0, wetR = 0;
      float send = 0.f;
      if (deck.echo.enabled) {
        if (!deck.echoSend.empty()) {
          send = deck.echoSend.at((frame + n) / sr);
        } else if (!deck.knotTimes.empty() && (frame + n) >= deck.knotTimes.front()) {
          send = 1.f;
        }
      }
      deck.echo.tap(l, r, send, wetL, wetR, sr);
      mixLeft_[n] += l * g + wetL;
      mixRight_[n] += r * g + wetR;
    }
  }
  if (fadeAt >= 0 && heardStart > fadeAt + fadeSamples) deck.finished = true;
}

void Renderer::limit(int frames) {
  // -1 dBTP, stereo linked. The 1.5 ms lookahead is the rest of this block,
  // so the splice the sink asked for does not move.
  constexpr float kCeiling = 0.89125094f;
  const int look = std::max(1, int(std::lround(0.0015 * sampleRate_)));
  const float release = 1.f - std::exp(-1.f / (0.06f * float(sampleRate_)));
  for (int i = 0; i < frames; ++i) {
    float peak = 0.f;
    const int end = std::min(frames, i + look);
    for (int j = i; j < end; ++j) {
      peak = std::max(peak, std::abs(mixLeft_[j]));
      peak = std::max(peak, std::abs(mixRight_[j]));
    }
    const float target = peak > kCeiling ? kCeiling / peak : 1.f;
    if (target < limitGain_) limitGain_ = target;
    else limitGain_ += (target - limitGain_) * release;
    mixLeft_[i] *= limitGain_;
    mixRight_[i] *= limitGain_;
  }
}

}  // namespace bitchord::loopmix
