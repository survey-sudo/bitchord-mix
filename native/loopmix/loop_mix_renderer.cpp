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
constexpr double kLoopSeamSeconds = 0.008;  // djmix24 clickless 8 ms Hann / equal-power (sin/cos)
constexpr double kPi = 3.14159265358979323846;
/** Bounded fallback wet-retirement window when no send/gain geometry exists. Ends at stopAt, so it adds no handoff delay. */
constexpr double kWetRetireFallbackSeconds = 1.0;
constexpr double kNoRetire = 1e18;

/** Start (mix seconds) for Echo's final positive-to-zero send release. */
double echoWetRetireStart(const Envelope& sendEnv, const Envelope& gainEnv, double stopAt, bool runOn) {
  if (runOn) return kNoRetire;
  if (!(stopAt < 1e15) || !std::isfinite(stopAt)) return kNoRetire;
  if (!sendEnv.empty()) {
    const size_t n = std::min(sendEnv.times.size(), sendEnv.values.size());
    size_t s = n;
    for (size_t i = n; i-- > 0;) if (sendEnv.values[i] > 1e-6f) { s = i; break; }
    if (s == n) return kNoRetire;
    if (s + 1 < n && sendEnv.values[n - 1] < sendEnv.values[s]) {
      bool valid = true;
      for (size_t i = s; i + 1 < n; ++i) {
        if (!std::isfinite(sendEnv.times[i]) || !std::isfinite(sendEnv.times[i + 1]) ||
            !(sendEnv.times[i + 1] > sendEnv.times[i])) { valid = false; break; }
      }
      size_t p = s;
      while (p + 1 < n && sendEnv.values[p + 1] == sendEnv.values[s]) ++p;
      if (valid && std::isfinite(sendEnv.times[p]) && sendEnv.times[p] < stopAt) return sendEnv.times[p];
    }
  }
  if (!gainEnv.empty()) {
    const size_t n = std::min(gainEnv.times.size(), gainEnv.values.size());
    for (size_t i = 0; i < n; ++i) {
      if (gainEnv.values[i] > 1e-6f) continue;
      bool sustained = true;
      for (size_t j = i; j < n; ++j) if (gainEnv.values[j] > 1e-6f) { sustained = false; break; }
      if (sustained && std::isfinite(gainEnv.times[i]) && gainEnv.times[i] < stopAt) return gainEnv.times[i];
    }
  }
  const double start = stopAt - kWetRetireFallbackSeconds;
  return start < 0.0 ? 0.0 : start;
}

/**
 * Start (mix seconds) of the smooth reverb-output retirement. This is the
 * baseline 60b9734 scan and intentionally remains independent of Echo.
 * Signal: outReverb(t) = rev(t) * w(t) * cancel(t), with
 *   w(t) = 1                          t <= start
 *        = 0.5*(1+cos(pi*(t-start)/(stopAt-start)))  start < t < stopAt
 *        = 0                          t >= stopAt
 * so w is continuous with zero slope at both ends: no sudden onset at
 * `start`, and wet reaches exactly 0 at stopAt regardless of comb
 * ringing. The window is the reverb send-envelope release geometry,
 * used only when an actual positive-to-lower/zero final release exists:
 * `start` is then the last peak of the final non-increasing suffix (the
 * release point, e.g. releaseAt for the production
 * [open,releaseAt,outStop]->[0,0.65,0] send), not the dry-gain zero time
 * — the wash therefore rings through [releaseAt, stopAt] instead of
 * being muted with the dry at releaseAt. A constant or increasing
 * positive suffix has no such release, so it falls through below instead
 * of fading from the first send point (e.g. a constant {0,0.65} send with
 * stopAt=10 must not fade over [0,10]). Empty/non-releasing sends fall
 * back to the dry gain's first sustained zero, then to a bounded 1 s
 * window ending at stopAt; this fallback retires only reverb.
 * runOn (incoming) decks and decks with no finite stopAt get kNoRetire
 * (w==1): no premature fading.
 * Stack-only scan of a handful of envelope points; no allocation/lock.
 */
double wetRetireStart(const Envelope& sendEnv, const Envelope& gainEnv, double stopAt, bool runOn) {
  if (runOn) return kNoRetire;
  if (!(stopAt < 1e15) || !std::isfinite(stopAt)) return kNoRetire;
  if (!sendEnv.empty()) {
    const size_t n = std::min(sendEnv.times.size(), sendEnv.values.size());
    if (n > 0) {
      size_t s = n - 1;
      while (s > 0 && sendEnv.values[s - 1] >= sendEnv.values[s]) --s;
      if (sendEnv.values[s] > 1e-6f) {
        // Send-derived start only on an actual positive-to-lower final
        // release with valid (finite, strictly increasing) suffix times.
        // Constant/increasing positive suffixes (no strict fall) fall
        // through to gain/bounded fallback below.
        bool hasRelease = (s + 1 < n && sendEnv.values[n - 1] < sendEnv.values[s]);
        if (hasRelease) {
          for (size_t i = s; i + 1 < n; ++i) {
            const double ta = sendEnv.times[i], tb = sendEnv.times[i + 1];
            if (!std::isfinite(ta) || !std::isfinite(tb) || !(tb > ta)) {
              hasRelease = false;
              break;
            }
          }
        }
        if (hasRelease) {
          size_t p = s;
          while (p + 1 < n && sendEnv.values[p + 1] == sendEnv.values[s]) ++p;
          const double start = sendEnv.times[p];
          if (std::isfinite(start) && start < stopAt) return start;
        }
      } else {
        return kNoRetire;  // send never meaningful: no stored wet to retire
      }
    }
  }
  if (!gainEnv.empty()) {
    const size_t n = std::min(gainEnv.times.size(), gainEnv.values.size());
    for (size_t i = 0; i < n; ++i) {
      if (gainEnv.values[i] > 1e-6f) continue;
      bool sustained = true;
      for (size_t j = i; j < n; ++j) {
        if (gainEnv.values[j] > 1e-6f) { sustained = false; break; }
      }
      if (!sustained) continue;
      const double gz = gainEnv.times[i];
      if (std::isfinite(gz) && gz < stopAt) return gz;
      break;
    }
  }
  const double start = stopAt - kWetRetireFallbackSeconds;
  return start < 0.0 ? 0.0 : start;
}

inline float wetRetireMul(double t, double fadeStart, double stopAt) {
  if (fadeStart >= 1e17) return 1.f;
  if (t >= stopAt) return 0.f;
  if (t <= fadeStart) return 1.f;
  const double denom = stopAt - fadeStart;
  if (!(denom > 0.0)) return 0.f;
  const double u = std::clamp((t - fadeStart) / denom, 0.0, 1.0);
  return float(std::clamp(0.5 * (1.0 + std::cos(kPi * u)), 0.0, 1.0));
}

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

void TptHighpass::set(double sampleRate, double frequency, double q) {
  if (!(sampleRate > 0) || !(frequency > 20) || frequency >= sampleRate * 0.45) {
    bypass();
    return;
  }
  const double safeQ = std::max(0.5, q);
  const double T = 1.0 / sampleRate;
  const double wa = (2.0 / T) * std::tan(kPi * frequency * T);
  g = float(wa * T / 2.0);
  k = float(1.0 / safeQ);
  a1 = 1.f / (1.f + g * (g + k));
  a2 = g * a1;
  a3 = g * a2;
}

void ReverbWash::setup(double sampleRate, float roomSize, float dampingIn, float wetIn) {
  enabled = false;
  combL0.clear(); combL1.clear(); combL2.clear(); combL3.clear();
  combR0.clear(); combR1.clear(); combR2.clear(); combR3.clear();
  apL0.clear(); apL1.clear(); apR0.clear(); apR1.clear();
  hpL = hpR = 0.f;
  if (!(sampleRate > 0) || !(wetIn > 0.f)) return;
  if (!(roomSize >= 0.5f && roomSize <= 6.f)) return;
  const float sz = roomSize / 2.2f;
  auto szFor = [&](float base) { return std::clamp(int(std::lround(base * sz * sampleRate)), 64, int(sampleRate * 1.2)); };
  combL0.assign(szFor(0.0297f), 0.f); combL1.assign(szFor(0.0371f), 0.f);
  combL2.assign(szFor(0.0411f), 0.f); combL3.assign(szFor(0.0439f), 0.f);
  combR0.assign(szFor(0.0302f), 0.f); combR1.assign(szFor(0.0376f), 0.f);
  combR2.assign(szFor(0.0416f), 0.f); combR3.assign(szFor(0.0444f), 0.f);
  apL0.assign(szFor(0.0044f), 0.f); apL1.assign(szFor(0.0016f), 0.f);
  apR0.assign(szFor(0.0049f), 0.f); apR1.assign(szFor(0.0019f), 0.f);
  for (int& i : combIdxL) i = 0; for (int& i : combIdxR) i = 0;
  for (int& i : apIdxL) i = 0; for (int& i : apIdxR) i = 0;
  damping = dampingIn;
  wet = wetIn;
  enabled = true;
}

void ReverbWash::apply(float inL, float inR, float send, float& wetL, float& wetR, double sampleRate) {
  wetL = 0; wetR = 0;
  // Send gates only the comb *input* (clamped g below), not the output:
  // at send==0 the combs keep ticking on zero input so the stored tail
  // rings and is retired smoothly by renderDeck's wet multiplier at
  // stopAt, rather than being hard-zeroed on the send==0 sample.
  if (!enabled || combL0.empty() || !(sampleRate > 0)) {
    return;
  }
  const float g = std::clamp(send, 0.f, 1.f);
  // 300 Hz input HPF (1-pole) so the wash stays out of the bass mud.
  const float hpA = float(1.0 - std::exp(-2.0 * kPi * 300.0 / sampleRate));
  hpL += hpA * (inL - hpL);
  hpR += hpA * (inR - hpR);
  const float hpInL = inL - hpL;
  const float hpInR = inR - hpR;
  // Comb filters with damping (low-pass in feedback). Per-buffer cursors
  // (pre-allocated, zero allocations, zero wrap clicks).
  auto combTick = [](std::vector<float>& buf, int& idx, float x, float damp) -> float {
    const int size = static_cast<int>(buf.size());
    const int pp = idx;
    const float y = buf[pp];
    const int nxt = (pp + 1 >= size) ? 0 : (pp + 1);
    float lp = buf[nxt];
    lp = damp * lp + (1.f - damp) * y;
    buf[nxt] = lp;
    // Feedback gain 0.84 (Freeverb-class) with tanh soft clip.
    buf[pp] = x + std::tanh(lp * 0.84f);
    idx = nxt;
    return y;
  };
  const float cL = (combTick(combL0, combIdxL[0], hpInL * g, damping) +
              combTick(combL1, combIdxL[1], hpInL * g, damping) +
              combTick(combL2, combIdxL[2], hpInL * g, damping) +
              combTick(combL3, combIdxL[3], hpInL * g, damping)) * 0.25f;
  const float cR = (combTick(combR0, combIdxR[0], hpInR * g, damping) +
              combTick(combR1, combIdxR[1], hpInR * g, damping) +
              combTick(combR2, combIdxR[2], hpInR * g, damping) +
              combTick(combR3, combIdxR[3], hpInR * g, damping)) * 0.25f;
  // Allpass cascade (0.5 feedback).
  auto apTick = [](std::vector<float>& buf, int& idx, float x) -> float {
    const int size = static_cast<int>(buf.size());
    const int pp = idx;
    const float y = buf[pp];
    buf[pp] = x - y * 0.5f;
    idx = (pp + 1 >= size) ? 0 : (pp + 1);
    return y * 0.5f + x;
  };
  const float aL = apTick(apL1, apIdxL[1], apTick(apL0, apIdxL[0], cL));
  const float aR = apTick(apR1, apIdxR[1], apTick(apR0, apIdxR[0], cR));
  wetL = aL * wet;
  wetR = aR * wet;
  // Denormal guard (flush tiny to zero).
  if (std::abs(wetL) < 1e-20f) wetL = 0;
  if (std::abs(wetR) < 1e-20f) wetR = 0;
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

void Flanger::setup(double sampleRate, double lfoHzIn, double depthSecondsIn,
                    float feedbackGain, float wetGain) {
  enabled = false;
  left.clear();
  right.clear();
  pos = 0;
  dampL = dampR = 0.f;
  if (!(sampleRate > 0) || !(lfoHzIn > 0) || !(depthSecondsIn > 0.0005) || !(wetGain > 0.f)) return;
  depthSeconds = float(depthSecondsIn);
  // djmix24: clamp up to 12 ms (pre-allocated in prepare). Depth 0.0045
  // (buildup) + 0.65 ms base = ~5.2 ms sweep.
  const int size = std::clamp(int(std::lround(depthSecondsIn * sampleRate)) + 2, 4, int(sampleRate * 0.012));
  left.assign(size, 0.f);
  right.assign(size, 0.f);
  feedback = feedbackGain;
  wet = wetGain;
  lfoHz = lfoHzIn;
  enabled = true;
}

void Flanger::apply(float inL, float inR, float& wetL, float& wetR, double frameTimeSec, double sampleRate) {
  applyEnv(inL, inR, 1.f, wetL, wetR, frameTimeSec, sampleRate);
}

void Flanger::applyEnv(float inL, float inR, float wetMul, float& wetL, float& wetR, double frameTimeSec, double sampleRate) {
  wetL = 0;
  wetR = 0;
  if (!enabled || left.empty() || !(sampleRate > 0)) return;
  const int size = int(left.size());
  // djmix24 Jet: 0.65 ms .. 5.2 ms stereo quadrature (90 deg offset).
  // LFO is one cycle per 8 beats (1/(8*beatSec) ~ 0.258 Hz at 124 BPM).
  const double phaseL = 2 * kPi * lfoHz * frameTimeSec;
  const double phaseR = phaseL + kPi / 2.0;
  const double lfoL = 0.5 * std::sin(phaseL) + 0.5;
  const double lfoR = 0.5 * std::sin(phaseR) + 0.5;
  const double delayLSmp = (0.00065 + double(depthSeconds) * lfoL) * sampleRate;
  const double delayRSmp = (0.00065 + double(depthSeconds) * lfoR) * sampleRate;
  double fposL = std::fmod(double(pos) - delayLSmp, double(size));
  if (fposL < 0) fposL += size;
  double fposR = std::fmod(double(pos) - delayRSmp, double(size));
  if (fposR < 0) fposR += size;
  const int i0L = int(fposL);
  const int i1L = (i0L + 1) % size;
  const float fracL = float(fposL - i0L);
  const int i0R = int(fposR);
  const int i1R = (i0R + 1) % size;
  const float fracR = float(fposR - i0R);
  const float rawL = left[i0L] * (1.f - fracL) + left[i1L] * fracL;
  const float rawR = right[i0R] * (1.f - fracR) + right[i1R] * fracR;
  // 1-pole ~10 kHz HF damping inside the feedback loop + denormal guard.
  const float dampA = float(1.0 - std::exp(-2.0 * kPi * 10000.0 / sampleRate));
  dampL += dampA * (rawL - dampL);
  dampR += dampA * (rawR - dampR);
  float fbL = dampL * feedback;
  float fbR = dampR * feedback;
  if (std::abs(fbL) < 1e-20f) fbL = 0;
  if (std::abs(fbR) < 1e-20f) fbR = 0;
  fbL = std::clamp(fbL, -0.78f, 0.78f);
  fbR = std::clamp(fbR, -0.78f, 0.78f);
  left[pos] = inL + fbL;
  right[pos] = inR + fbR;
  pos++;
  if (pos >= size) pos = 0;
  const float mul = std::clamp(wetMul, 0.f, 1.f) * wet;
  wetL = rawL * mul;
  wetR = rawR * mul;
}

// djmix24 tightening schedule: loop length in beats for the roll.
// 4 -> 2 -> 1 -> 0.5 in the final 2 beats; Rung 4 slip is 1 -> 0.5 -> 0.25
// but native reuses the same table anchored at loopStart.
static double rollLoopBeatsNative(double beatsIntoRoll, double totalRollBeats) {
  if (!(totalRollBeats > 0)) return 4.0;
  const double b = std::clamp(beatsIntoRoll, 0.0, totalRollBeats);
  if (b >= totalRollBeats - 1.0) return 0.5;
  if (b >= totalRollBeats - 2.0) return 1.0;
  if (b >= totalRollBeats - 4.0) return 2.0;
  return 4.0;
}

int64_t Deck::sampleAtEffective(int64_t unwrapped) const {
  int64_t effEnd = effectiveLoopEnd;
  if (effEnd <= loopStart) effEnd = loopEnd;
  if (effEnd <= loopStart) {
    return (unwrapped >= 0 && unwrapped < int64_t(left.size())) ? unwrapped : -1;
  }
  int64_t index = unwrapped;
  const int64_t length = effEnd - loopStart;
  if (length > 0 && unwrapped >= effEnd) {
    if (unwrapped < int64_t(releaseAt)) {
      index = loopStart + (unwrapped - loopStart) % length;
    } else {
      index = effEnd + (unwrapped - int64_t(releaseAt));
    }
  }
  return (index >= 0 && index < int64_t(std::min(left.size(), right.size()))) ? index : -1;
}

void Deck::readEffective(int64_t unwrapped, int seamSamples, double mixSec, float& leftOut, float& rightOut) const {
  // Tightening: when loopRoll is on and mixSec is inside the roll window,
  // effective loop length is beat-locked (anchored at loopStart). Compute
  // it from the mix clock; the seam crossfade below is always 8 ms
  // Hann / equal-power (sin/cos), zero allocation.
  int64_t effEnd = loopEnd;
  if (loopRoll && rollBeatSec > 1e-6) {
    if (mixSec >= rollStartMixSec && mixSec < rollReleaseMixSec) {
      const double beatsInto = (mixSec - rollStartMixSec) / rollBeatSec;
      const double loopBeats = rollLoopBeatsNative(beatsInto, rollTotalBeats);
      const double effLen = loopBeats * rollBeatSec * 48000.0;  // placeholder, fixed below by caller
      (void)effLen;
      // The caller (renderDeck) sets effectiveLoopEnd per-block from the
      // exact sample rate, so use it when present; otherwise fall back
      // to the full loop.
      if (effectiveLoopEnd > loopStart) effEnd = effectiveLoopEnd;
    }
  }
  const int64_t index = sampleAtEffective(unwrapped);
  if (index < 0) { leftOut = 0; rightOut = 0; return; }
  // If effective loop is active, sampleAtEffective already wraps; for the
  // seam blend use the effective length.
  leftOut = left[index];
  rightOut = right[index];
  const int64_t length = effEnd - loopStart;
  if (length <= seamSamples || seamSamples <= 1) return;
  if (unwrapped < effEnd || unwrapped >= static_cast<int64_t>(releaseAt)) {
    // Also handle the full-loop case when effective is not tightened.
    if (!(loopRoll && effectiveLoopEnd > loopStart)) {
      // Fall through to full-loop check below by using loopEnd.
    } else {
      return;
    }
  }
  // Seam position within the effective (or full) loop.
  const int64_t eff = (effEnd > loopStart) ? effEnd : loopEnd;
  const int64_t fullLen = loopEnd - loopStart;
  const int64_t useLen = (effectiveLoopEnd > loopStart) ? (effectiveLoopEnd - loopStart) : fullLen;
  if (useLen <= seamSamples) return;
  // Only blend inside the loop region.
  if (unwrapped < loopStart || unwrapped >= static_cast<int64_t>(releaseAt)) return;
  const int64_t pos = (unwrapped - loopStart) % useLen;
  if (pos < 0 || pos >= seamSamples) return;
  const int64_t tail = loopStart + useLen + pos;
  if (tail < 0 || tail >= int64_t(left.size())) return;
  const float tt = float(pos) / float(seamSamples);
  const float intoLoop = std::sin(tt * float(kPi / 2.0));
  const float fromTail = std::cos(tt * float(kPi / 2.0));
  leftOut = left[index] * intoLoop + left[tail] * fromTail;
  rightOut = right[index] * intoLoop + right[tail] * fromTail;
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
  return (index >= 0 && index < int64_t(std::min(left.size(), right.size()))) ? index : -1;
}

double Deck::inputEnd() const {
  const double size = double(std::min(left.size(), right.size()));
  // Only a fully decoded loop body can repeat. Truncated/malformed loops
  // retain their real physical EOF instead of manufacturing available music.
  if (loopStart >= 0 && loopEnd > loopStart && loopEnd <= size && releaseAt >= loopEnd) {
    if (releaseAt > 1e15) return 1e18;
    return releaseAt + (size - loopEnd);
  }
  return size;
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

void Renderer::setFlanger(int index, double lfoHz, double depthSeconds, float feedback, float wet) {
  if (index < 0 || index > 1) return;
  decks_[index].flanger.setup(sampleRate_, lfoHz, depthSeconds, feedback, wet);
}

void Renderer::setLoopRoll(int index, bool enabled, double rollStartSec, double beatSec, double releaseSec, double totalRollBeats) {
  if (index < 0 || index > 1) return;
  Deck& d = decks_[index];
  d.loopRoll = enabled;
  d.rollStartMixSec = rollStartSec;
  d.rollBeatSec = beatSec;
  d.rollReleaseMixSec = releaseSec;
  d.rollTotalBeats = totalRollBeats;
}

void Renderer::setReverb(int index, float roomSize, float damping, float wet) {
  if (index < 0 || index > 1) return;
  decks_[index].reverb.setup(sampleRate_, roomSize, damping, wet);
}

void Renderer::setSvfQ(int index, float q) {
  if (index < 0 || index > 1) return;
  decks_[index].svfQ = q;
}

void Renderer::prepare() {
  limitGain_ = 1.f;
  // Preallocated sample-peak history ring; this is not a true-peak detector.
  {
    const int look = std::max(1, int(std::lround(0.0015 * sampleRate_)));
    const int need = look + 64;
    if (int(limitRingL_.size()) < need) {
      limitRingL_.assign(need, 0.f);
      limitRingR_.assign(need, 0.f);
    }
    std::fill(limitRingL_.begin(), limitRingL_.end(), 0.f);
    std::fill(limitRingR_.begin(), limitRingR_.end(), 0.f);
    limitRingPos_ = 0;
  }
  // djmix14: pre-allocate every render buffer here (worker thread). The audio
  // thread (render/renderDeck) must never allocate — see hard rule. PhraseIsolator
  // is member/stack-allocated and already alloc-free. Sizes cover the largest
  // ExoPlayer callback (frames) and its 8x stretch input plus seek window.
  // If a callback exceeds the cap, render processes it in bounded slices
  // rather than resizing (no heap, no lock, no block on audio).
  {
    constexpr int kMaxFrames = 32768;
    constexpr int kMaxInput = 262144;
    if (int(mixLeft_.size()) < kMaxFrames) {
      mixLeft_.assign(kMaxFrames, 0.f);
      mixRight_.assign(kMaxFrames, 0.f);
    }
    if (int(outLeft_.size()) < kMaxFrames) {
      outLeft_.assign(kMaxFrames, 0.f);
      outRight_.assign(kMaxFrames, 0.f);
    }
    if (int(inLeft_.size()) < kMaxInput) {
      inLeft_.assign(kMaxInput, 0.f);
      inRight_.assign(kMaxInput, 0.f);
    }
  }
  for (Deck& deck : decks_) {
    // Default quality outside efficient mode: the mix is written ahead of the
    // speaker, so the longer window costs nothing on the playback thread.
    // Efficient mode keeps the cheaper preset. Either way the work is spread
    // across the block so one callback doesn't take the whole window.
    if (highQuality_) deck.stretch.presetDefault(2, float(sampleRate_), true);
    else deck.stretch.presetCheaper(2, float(sampleRate_), true);
    deck.started = false;
    deck.finished = deck.left.empty() || deck.left.size() != deck.right.size() || deck.knotTimes.size() < 2;
    deck.activeStartFrame = 0.0;
    if (!deck.knotTimes.empty() && deck.knotPositions.size() == deck.knotTimes.size()) {
      const double p0 = deck.knotPositions.front();
      size_t firstAdv = 0;
      for (size_t i = 1; i < deck.knotPositions.size(); ++i) {
        if (std::abs(deck.knotPositions[i] - p0) > 0.5) {
          firstAdv = i;
          break;
        }
      }
      if (firstAdv > 1) {
        deck.activeStartFrame = deck.knotTimes[firstAdv - 1];
      } else if (firstAdv == 1) {
        deck.activeStartFrame = deck.knotTimes.front() -
            deck.preroll * double(sampleRate_) - deck.stretch.outputLatency();
      } else {
        deck.activeStartFrame = 1e18;
      }
    }
    // Flanger history never survives a session boundary.
    std::fill(deck.flanger.left.begin(), deck.flanger.left.end(), 0.f);
    std::fill(deck.flanger.right.begin(), deck.flanger.right.end(), 0.f);
    deck.flanger.pos = 0;
    deck.flanger.dampL = deck.flanger.dampR = 0.f;
    // Reverb tails die at the session boundary (buffers keep their size).
    if (!deck.reverb.combL0.empty()) {
      std::fill(deck.reverb.combL0.begin(), deck.reverb.combL0.end(), 0.f);
      std::fill(deck.reverb.combL1.begin(), deck.reverb.combL1.end(), 0.f);
      std::fill(deck.reverb.combL2.begin(), deck.reverb.combL2.end(), 0.f);
      std::fill(deck.reverb.combL3.begin(), deck.reverb.combL3.end(), 0.f);
      std::fill(deck.reverb.combR0.begin(), deck.reverb.combR0.end(), 0.f);
      std::fill(deck.reverb.combR1.begin(), deck.reverb.combR1.end(), 0.f);
      std::fill(deck.reverb.combR2.begin(), deck.reverb.combR2.end(), 0.f);
      std::fill(deck.reverb.combR3.begin(), deck.reverb.combR3.end(), 0.f);
      std::fill(deck.reverb.apL0.begin(), deck.reverb.apL0.end(), 0.f);
      std::fill(deck.reverb.apL1.begin(), deck.reverb.apL1.end(), 0.f);
      std::fill(deck.reverb.apR0.begin(), deck.reverb.apR0.end(), 0.f);
      std::fill(deck.reverb.apR1.begin(), deck.reverb.apR1.end(), 0.f);
      for (int& i : deck.reverb.combIdxL) i = 0; for (int& i : deck.reverb.combIdxR) i = 0;
      for (int& i : deck.reverb.apIdxL) i = 0; for (int& i : deck.reverb.apIdxR) i = 0;
      deck.reverb.hpL = deck.reverb.hpR = 0.f;
    }
    deck.shelf.bypass();
    deck.tptHighPass.bypass();
    deck.lowPass.bypass();
    deck.effectiveLoopEnd = -1;
    deck.effectiveLoopLenSamples = -1.0;
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
  if (frames <= 0 || interleaved == nullptr) return;
  if (mixLeft_.empty() || mixRight_.empty()) {
    std::fill(interleaved, interleaved + 2 * frames, 0.f);
    return;
  }
  // Work in preallocated slices; DSP state advances continuously without
  // allocating even when a host callback exceeds the buffer capacity.
  int offset = 0;
  while (offset < frames) {
    const int chunk = std::min(frames - offset, int(mixLeft_.size()));
    std::fill(mixLeft_.begin(), mixLeft_.begin() + chunk, 0.f);
    std::fill(mixRight_.begin(), mixRight_.begin() + chunk, 0.f);
    for (Deck& deck : decks_) renderDeck(deck, frame + offset, chunk);
    limit(chunk);
    for (int i = 0; i < chunk; ++i) {
      interleaved[2 * (offset + i)] = mixLeft_[i];
      interleaved[2 * (offset + i) + 1] = mixRight_[i];
    }
    offset += chunk;
  }
}

void Renderer::renderDeck(Deck& deck, int64_t frame, int frames) {
  if (deck.finished || frames <= 0) return;
  const double sr = sampleRate_;
  const double blockStart = frame / sr;
  if (blockStart >= deck.stopAt) {
    // Graceful-exit gate (djmix11): the outgoing may only go silent at
    // stopAt once its gain is already below -60 dB. The plan's lane and
    // region are built so this can never trip; a violation is recorded to
    // telemetry as proof the guarantee held or broke.
    if (!deck.gain.empty() && deck.gain.at(blockStart) > 0.001f) {
      exitGateViolations_.fetch_add(1, std::memory_order_relaxed);
    }
    deck.finished = true;
    return;
  }
  const double inLatency = deck.stretch.inputLatency();
  const double outLatency = deck.stretch.outputLatency();
  const double frameStart = double(frame);
  const double frameEnd = double(frame + frames);
  // djmix18: hold silent with readPos synchronized until the first advancing
  // knot (e.g., incomingHoldSec > 0), never calling stretch.process(0, frames).
  if (!deck.started && frameEnd <= deck.activeStartFrame) {
    const double initPos = deck.knotPositions.empty() ? 0.0 : deck.knotPositions.front();
    deck.heardPos = initPos;
    deck.readPos = initPos;
    deck.heard.store(deck.trackSecondsAt(initPos, sr), std::memory_order_relaxed);
    return;
  }
  const int silentFrames = (!deck.started && frameStart < deck.activeStartFrame && frames > 1)
      ? std::clamp(int(std::floor(deck.activeStartFrame - frameStart)), 0, std::max(0, frames - 1))
      : 0;
  const int activeFrames = frames - silentFrames;
  const double activeFrameStart = frameStart + double(silentFrames);
  if (!deck.started) {
    // Seeded at activeFrameStart, its read pointer the stretch's own latency
    // ahead of what will be heard.
    const double rateSampleAt = std::max(activeFrameStart, deck.activeStartFrame + 1.0);
    const double rate = std::clamp(deck.knotRate(rateSampleAt), 0.75, 1.35);
    deck.heardPos = deck.knotHeard(activeFrameStart);
    deck.readPos = deck.heardPos + inLatency + rate * outLatency;
    const int seekLength = deck.stretch.seekLength();
    if (int(inLeft_.size()) < seekLength || int(inRight_.size()) < seekLength) {
      deck.started = true; // never retry a resize on the audio thread
      return;
    }
    const int seam = std::max(1, int(std::lround(kLoopSeamSeconds * sr)));
    const int64_t first = int64_t(std::llround(deck.readPos)) - seekLength;
    for (int i = 0; i < seekLength; ++i) {
      const double mixSec = double(frame + silentFrames + i) / sr;
      if (deck.loopRoll) deck.readEffective(first + i, seam, mixSec, inLeft_[i], inRight_[i]);
      else deck.read(first + i, seam, inLeft_[i], inRight_[i]);
    }
    float* seekInput[2] = {inLeft_.data(), inRight_.data()};
    deck.stretch.reset();
    deck.stretch.seek(seekInput, seekLength, rate);
    deck.readPos = double(first + seekLength);
    deck.started = true;
  }

  if (int(outLeft_.size()) < frames || int(outRight_.size()) < frames) return;
  for (int i = 0; i < silentFrames; ++i) {
    outLeft_[i] = 0.f;
    outRight_[i] = 0.f;
  }

  // Where the block must end, heard: on the knots, or (past the last one) at
  // rate 1 (incoming run-on) or extrapolated steady tail rate [0.85, 1.15]
  // during exitTailSeconds / echoHold.
  double heardEnd;
  double rateEnd;
  const double lastKnot = deck.knotTimes.back();
  if (frameEnd <= lastKnot) {
    heardEnd = deck.knotHeard(frameEnd);
    rateEnd = deck.knotRate(frameEnd - 1);
  } else if (deck.runOn) {
    const double from = std::max(activeFrameStart, lastKnot);
    const double base = activeFrameStart < lastKnot ? deck.knotPositions.back() : deck.heardPos;
    heardEnd = base + (frameEnd - from);
    rateEnd = 1.0;
  } else {
    const double tailRate = std::clamp(deck.knotRate(lastKnot - 1), 0.85, 1.15);
    const double from = std::max(activeFrameStart, lastKnot);
    const double base = activeFrameStart < lastKnot ? deck.knotPositions.back() : deck.heardPos;
    heardEnd = base + (frameEnd - from) * tailRate;
    rateEnd = tailRate;
  }
  {
    const double advance = heardEnd - deck.heardPos;
    const double maxAdvance = double(activeFrames) * 1.35;
    if (advance > maxAdvance) heardEnd = deck.heardPos + maxAdvance;
    if (advance < 0.0) heardEnd = deck.heardPos;
  }

  // djmix24 tightening: effective loop length from the mix clock.
  // Anchored at loopStart, beat-locked via knotTimes/beatSec. 8 ms
  // clickless wraps are handled in readEffective (sin/cos equal-power).
  deck.effectiveLoopEnd = -1;
  if (deck.loopRoll && deck.loopEnd > deck.loopStart && deck.rollBeatSec > 1e-6) {
    const double blockMixSec = double(frame) / sr;
    if (blockMixSec >= deck.rollStartMixSec && blockMixSec < deck.rollReleaseMixSec) {
      const double beatsInto = (blockMixSec - deck.rollStartMixSec) / deck.rollBeatSec;
      const double loopBeats = rollLoopBeatsNative(beatsInto, deck.rollTotalBeats);
      const double effSamples = loopBeats * deck.rollBeatSec * sr;
      deck.effectiveLoopEnd = deck.loopStart + int64_t(std::llround(effSamples));
      // Clamp to the decoded region when the full loop allows it.
      if (deck.effectiveLoopEnd > int64_t(deck.left.size()) && deck.effectiveLoopEnd > deck.loopEnd) {
        deck.effectiveLoopEnd = deck.loopEnd;
      }
      if (deck.effectiveLoopEnd <= deck.loopStart) deck.effectiveLoopEnd = -1;
    }
  }
  const double inputEnd = deck.inputEnd();
  const bool loopsForever = inputEnd > 1e15;
  const int64_t readFrom = int64_t(std::floor(deck.readPos));
  const int minInput = std::max(1, int(std::floor(double(activeFrames) * 0.75)));
  const int maxInput = std::max(minInput, int(std::ceil(double(activeFrames) * 1.35)));
  const int64_t remaining = loopsForever ? int64_t(maxInput) : std::max<int64_t>(0, int64_t(std::ceil(inputEnd)) - readFrom);

  {
    const double readEnd = heardEnd + inLatency + rateEnd * outLatency;
    int inputSamples = int(std::floor(readEnd - deck.readPos));
    inputSamples = std::clamp(inputSamples, minInput, maxInput);
    // Always feed a positive bounded block. At true EOF pad input with zero
    // through the stretcher so its buffered latency drains on the same path;
    // never replace stretched output with raw input or park an unwrapped
    // pointer at a physical array size.
    const int available = int(std::min<int64_t>(inputSamples, remaining));
    // No resize on the audio thread (pre-allocated in prepare()).
    if (int(inLeft_.size()) < inputSamples || int(inRight_.size()) < inputSamples) return;
    const int seam = std::max(1, int(std::lround(kLoopSeamSeconds * sr)));
    for (int i = 0; i < inputSamples; ++i) {
      if (i >= available) { inLeft_[i] = 0.f; inRight_[i] = 0.f; continue; }
      const double mixSec = double(frame + silentFrames + i) / sr;
      if (deck.loopRoll) deck.readEffective(readFrom + i, seam, mixSec, inLeft_[i], inRight_[i]);
      else deck.read(readFrom + i, seam, inLeft_[i], inRight_[i]);
    }
    deck.readPos += double(inputSamples);

    float* input[2] = {inLeft_.data(), inRight_.data()};
    float* output[2] = {outLeft_.data() + silentFrames, outRight_.data() + silentFrames};
    deck.stretch.process(input, inputSamples, output, activeFrames);
  }

  const double heardStart = deck.heardPos;
  deck.heardPos = heardEnd;
  deck.heard.store(deck.trackSecondsAt(heardEnd, sr), std::memory_order_relaxed);

  // Gain, bass shelf and high-pass, in control blocks; the gain ramps per sample.
  const double fadeAt = deck.fadeOutAt.load(std::memory_order_relaxed);
  const double fadeSamples = kFadeSeconds * sr;
  // Scheduled wet-output retirement (stopAt), from each effect's send release.
  // runOn decks get kNoRetire (see helper).
  const double echoFadeStart = echoWetRetireStart(deck.echoSend, deck.gain, deck.stopAt, deck.runOn);
  const double revFadeStart = wetRetireStart(deck.reverbSend, deck.gain, deck.stopAt, deck.runOn);
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
          deck.shelf.lowShelf(sr, 200, db);
        }
      }
    }
    if (!deck.highPassHz.empty()) {
      const float hz = deck.highPassHz.at((t0 + t1) / 2);
      if (std::abs(hz - deck.highPassFreq) > 0.5f) {
        deck.highPassFreq = hz;
        if (hz <= 20.5f) {
          deck.tptHighPass.bypass();
        } else {
          // Cytomic TPT SVF: 0.707 default, 0.95 musical during buildup.
          deck.tptHighPass.set(sr, hz, deck.svfQ);
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
      // Emergency cancellation fade (fadeOutAt): linear 1->0 over
      // kFadeSeconds of heard position. It scales the dry (via g) AND the
      // whole wet mix below, so an emergency exit cannot leak echo/reverb.
      float cancel = 1.f;
      if (fadeAt >= 0) {
        const double heard = silentFrames > 0
            ? (n < silentFrames
                ? heardStart
                : heardStart + (heardEnd - heardStart) * (double(n - silentFrames) / double(std::max(1, activeFrames))))
            : (heardStart + (heardEnd - heardStart) * (double(n) / double(frames)));
        const double fade = std::clamp((fadeAt + fadeSamples - heard) / fadeSamples, 0.0, 1.0);
        cancel = float(fade);
      }
      float g = (g0 + (g1 - g0) * (float(i) / count)) * deck.level * cancel;
      float l = deck.shelf.tick(0, outLeft_[n]);
      float r = deck.shelf.tick(1, outRight_[n]);
      l = deck.tptHighPass.tick(0, l);
      r = deck.tptHighPass.tick(1, r);
      l = deck.lowPass.tick(0, l);
      r = deck.lowPass.tick(1, r);
      if (deck.isolate && deck.isolator.ready) {
        float loL, midL, hiL, loR, midR, hiR;
        deck.isolator.process(0, l, loL, midL, hiL);
        deck.isolator.process(1, r, loR, midR, hiR);
        // djmix24 LR4 band gains: mid/high dB envelopes (vocal handoff
        // carve -6..-9 dB over 4 beats; incoming mid/high stay at 0 dB).
        float midDb = 0.f, highDb = 0.f;
        const double bandT = (frame + n) / sr;
        if (!deck.midDb.empty()) midDb = deck.midDb.at(bandT);
        if (!deck.highDb.empty()) highDb = deck.highDb.at(bandT);
        const float midLin = std::pow(10.f, midDb / 20.f);
        const float highLin = std::pow(10.f, highDb / 20.f);
        l = loL + midL * midLin + hiL * highLin;
        r = loR + midR * midLin + hiR * highLin;
      }
      float flangeL = 0.f, flangeR = 0.f;
      if (deck.flanger.enabled) {
        float wetMul = 1.f;
        if (!deck.flangerWet.empty()) wetMul = deck.flangerWet.at((frame + n) / sr);
        deck.flanger.applyEnv(l, r, wetMul, flangeL, flangeR, double(frame + n) / sr, sr);
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
      // djmix24 reverb wash: send envelope (0..1), tail rings past the dry.
      float revL = 0, revR = 0;
      float revSend = 0.f;
      if (deck.reverb.enabled) {
        if (!deck.reverbSend.empty()) revSend = deck.reverbSend.at((frame + n) / sr);
        else if (!deck.knotTimes.empty() && (frame + n) >= deck.knotTimes.front()) revSend = 0.5f;
        deck.reverb.apply(l, r, revSend, revL, revR, sr);
      }
      // Retire stored effect output independently from dry gain. Emergency
      // cancellation remains multiplicative and can never be undone by this.
      // Flanger already rides g (hence cancel) and its own envelope.
      const double tMix = double(frame + n) / sr;
      const float echoMul = wetRetireMul(tMix, echoFadeStart, deck.stopAt) * cancel;
      const float revMul = wetRetireMul(tMix, revFadeStart, deck.stopAt) * cancel;
      mixLeft_[n] += l * g + wetL * echoMul + flangeL * g + revL * revMul;
      mixRight_[n] += r * g + wetR * echoMul + flangeR * g + revR * revMul;
    }
  }
  if (fadeAt >= 0 && heardStart > fadeAt + fadeSamples) deck.finished = true;
}

void Renderer::limit(int frames) {
  // Sample-peak limiter: scan up to 1.5 ms within this callback. It does not
  // oversample or inspect future callbacks. Gain state carries across
  // callbacks with an ~80 ms release.
  constexpr float kCeiling = 0.89125094f;
  constexpr float kKneeDb = 2.0f;  // soft-knee width (dB below ceiling)
  constexpr float kKnee = 0.794f;  // approximately -2 dBFS sample-peak knee start
  const int look = std::max(1, int(std::lround(0.0015 * sampleRate_)));
  const float release = 1.f - std::exp(-1.f / (0.08f * float(sampleRate_)));
  const int ringSize = std::max(1, int(limitRingL_.size()));
  // Legacy ring records input but does not participate in peak detection.
  // The scan is bounded by this callback; no transport delay is introduced.
  for (int i = 0; i < frames; ++i) {
    limitRingL_[(limitRingPos_) % ringSize] = mixLeft_[i];
    limitRingR_[(limitRingPos_) % ringSize] = mixRight_[i];
    limitRingPos_++;
    if (limitRingPos_ >= ringSize) limitRingPos_ = 0;
    float peak = 0.f;
    const int end = std::min(frames, i + look);
    for (int j = i; j < end; ++j) {
      peak = std::max(peak, std::abs(mixLeft_[j]));
      peak = std::max(peak, std::abs(mixRight_[j]));
    }
    // Soft-knee: linear above ceiling, smooth knee below.
    float target = 1.f;
    if (peak > kCeiling) {
      target = kCeiling / peak;
    } else if (peak > kKnee) {
      // Knee region: partial gain reduction (smooth entry).
      const float over = (peak - kKnee) / (kCeiling - kKnee);
      const float kneeGain = 1.f - over * over * 0.08f;
      target = kneeGain;
    }
    if (target < limitGain_) limitGain_ = target;
    else limitGain_ += (target - limitGain_) * release;
    const float gl = mixLeft_[i] * limitGain_;
    const float gr = mixRight_[i] * limitGain_;
    // Denormal guard on the output.
    mixLeft_[i] = std::abs(gl) < 1e-20f ? 0.f : gl;
    mixRight_[i] = std::abs(gr) < 1e-20f ? 0.f : gr;
  }
}

}  // namespace bitchord::loopmix
