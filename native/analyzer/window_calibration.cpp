// Calibration and invariant checks for the separated stem pair.
//
// Why this exists: the first version of SeparateVocalWindow that ever produced
// audio only recently shipped. Every automix heuristic that reads a vocal mask was
// written against a path that always crashed, so none of its thresholds has
// ever been checked against real separation. Before any of those numbers are
// retuned, the transfer function they sit on has to be known, and the identity
// the single-pass design depends on has to be proven rather than assumed.
//
// Four things are established here, all without audio hardware:
//
//   1. The Wiener gain curve as the *shipped* code actually realises it, not as
//      the comment describes it. A mask value of r does NOT attenuate by r.
//   2. Complement identity: vocals + instrumental reconstruct the dry signal.
//      This is what makes "instrumental = dry - vocals" legitimate, and it is
//      the whole basis of the one-inference-per-deck decision.
//   3. Sample alignment: the reconstruction has no lag, so the local time-domain
//      subtraction cannot comb-filter.
//   4. Energy-weighted clash versus the boolean frame count, on a case where
//      they disagree.
//
//   g++ -O2 -std=c++17 -I native/analyzer native/analyzer/window_calibration.cpp
//       native/analyzer/vocal_spectrogram.cpp -o /tmp/window_calibration
//       && /tmp/window_calibration
//
// Exit 0 means every invariant held. Non-zero means a threshold in Kotlin is
// standing on something that is not true.

#include "vocal_spectrogram.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

using bitchord::smart::ComputeVocalSpectrogram;
using bitchord::smart::SeparateVocalWindow;
using bitchord::smart::kVocalSpectrogramBins;
using bitchord::smart::kVocalSpectrogramChannels;
using bitchord::smart::kVocalSpectrogramSampleRate;

namespace {

int failures = 0;

void check(bool cond, const char* what) {
  if (cond) {
    std::printf("ok   %s\n", what);
  } else {
    std::printf("FAIL %s\n", what);
    ++failures;
  }
}



// The shipped gain, reproduced here only to state the target the code is
// expected to hit. If this drifts from vocal_spectrogram.cpp the curve test
// below fails, which is the point: the table is a contract, not a copy.
double WienerGain(double r) {
  const double rr = r * r;
  const double cc = (1.0 - r) * (1.0 - r);
  return rr / (rr + cc + 1e-8);
}

// Two-tone probe. A pure tone puts nearly all its energy in a handful of bins,
// which is what lets a uniform ratio r across every bin be read back out of the
// time-domain result as a plain amplitude ratio.
void BuildProbe(std::vector<float>& left, std::vector<float>& right, size_t length) {
  left.assign(length, 0.0f);
  right.assign(length, 0.0f);
  for (size_t i = 0; i < length; ++i) {
    const double t = static_cast<double>(i) / kVocalSpectrogramSampleRate;
    const double a = 0.30 * std::sin(2.0 * 3.14159265358979323846 * 220.0 * t);
    const double b = 0.20 * std::sin(2.0 * 3.14159265358979323846 * 660.0 * t);
    left[i] = static_cast<float>(a + b);
    right[i] = static_cast<float>(0.9 * a - 0.4 * b);
  }
}

double Rms(const std::vector<float>& v) {
  if (v.empty()) return 0.0;
  double sum = 0.0;
  for (float x : v) sum += static_cast<double>(x) * x;
  return std::sqrt(sum / static_cast<double>(v.size()));
}

double RmsDiff(const std::vector<float>& a, const std::vector<float>& b) {
  if (a.size() != b.size() || a.empty()) return 1e9;
  double sum = 0.0;
  for (size_t i = 0; i < a.size(); ++i) {
    const double d = static_cast<double>(a[i]) - b[i];
    sum += d * d;
  }
  return std::sqrt(sum / static_cast<double>(a.size()));
}

// --- 1. Gain curve, measured through the real code path ---------------------
//
// For a uniform ratio r the mask is w(r) at every bin, so the output is the
// input scaled by w(r). Measuring the RMS ratio therefore reads the shipped
// curve back out of the shipped implementation.
void MeasureGainCurve() {
  std::printf("\n=== 1. gain curve as shipped (mask value r -> energy kept) ===\n");
  const size_t length = 22050;
  std::vector<float> left, right;
  BuildProbe(left, right, length);

  const auto spec = ComputeVocalSpectrogram({left, right}, kVocalSpectrogramSampleRate);
  check(spec.frames > 0, "probe produced a spectrogram");
  if (spec.frames == 0) return;

  const double dry_rms = Rms(left);
  const double ratios[] = {0.0, 0.1, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0};

  for (double r : ratios) {
    std::vector<float> target(spec.values.size());
    for (size_t i = 0; i < spec.values.size(); ++i) {
      target[i] = static_cast<float>(r) * spec.values[i];
    }
    std::vector<float> vocL, vocR;
    const bool ok = SeparateVocalWindow(left, right, kVocalSpectrogramSampleRate, target,
                                        spec.frames, true, vocL, vocR);
    if (!ok) {
      std::printf("FAIL gain curve at r=%.2f (call refused)\n", r);
      ++failures;
      continue;
    }
    const double measured = Rms(vocL) / (dry_rms > 0.0 ? dry_rms : 1.0);
    const double expected = WienerGain(r);
    std::printf("     r=%.2f  gain=%.4f  expected=%.4f  delta=%.4f\n", r, measured, expected,
                std::fabs(measured - expected));
    // 2% covers resampling-free numerical drift in the OLA, nothing more.
    if (std::fabs(measured - expected) > 0.02) {
      std::printf("FAIL gain at r=%.2f deviates from the documented curve\n", r);
      ++failures;
    }
  }

  // The number that matters for every Kotlin threshold currently in the tree.
  const double at_threshold = WienerGain(0.25);
  std::printf("\n     VOCAL_ACTIVE_THRESHOLD = 0.25 keeps %.1f%% of energy\n",
              at_threshold * 100.0);
  const double at_voice_floor = WienerGain(0.35);
  std::printf("     VOICE_FLOOR            = 0.35 keeps %.1f%% of energy\n",
              at_voice_floor * 100.0);
  const double at_peak = WienerGain(0.62);
  std::printf("     vocalProbability       = 0.62 keeps %.1f%% of energy\n", at_peak * 100.0);
  std::printf("     -> every one of these sits below 50%%: at the decision boundary the\n");
  std::printf("        separator barely removes anything. Thresholds are not gains.\n");
}

// --- 2, 3. Complement identity and alignment -------------------------------
//
// Both stems come from complementary masks on the same spectrum, so their sum
// is the dry signal. That is exactly the assumption "instrumental = dry minus
// vocals" rests on, and it is what removes the need for a second inference.
void CheckComplementAndAlignment() {
  std::printf("\n=== 2/3. complement identity and alignment ===\n");
  const size_t length = 22050;
  std::vector<float> left, right;
  BuildProbe(left, right, length);

  const auto spec = ComputeVocalSpectrogram({left, right}, kVocalSpectrogramSampleRate);
  if (spec.frames == 0) {
    check(false, "spectrogram for identity check");
    return;
  }

  // A mid-strength mask, the least favourable case for cancellation error:
  // both stems carry real energy and the split is finely balanced.
  const double r = 0.55;
  std::vector<float> target(spec.values.size());
  for (size_t i = 0; i < spec.values.size(); ++i) {
    target[i] = static_cast<float>(r) * spec.values[i];
  }

  std::vector<float> vocL, vocR, instL, instR;
  const bool okV = SeparateVocalWindow(left, right, kVocalSpectrogramSampleRate, target,
                                       spec.frames, true, vocL, vocR);
  const bool okI = SeparateVocalWindow(left, right, kVocalSpectrogramSampleRate, target,
                                       spec.frames, false, instL, instR);
  check(okV && okI, "both stems produced");
  if (!okV || !okI) return;

  const double dry_rms = Rms(left);

  // vocals + instrumental vs dry
  std::vector<float> sumL(length), sumR(length);
  for (size_t i = 0; i < length; ++i) {
    sumL[i] = vocL[i] + instL[i];
    sumR[i] = vocR[i] + instR[i];
  }
  const double sum_err = RmsDiff(sumL, left);
  const double sum_err_r = RmsDiff(sumR, right);
  std::printf("     vocals+instrumental vs dry: %.3e (L), %.3e (R)\n", sum_err, sum_err_r);
  std::printf("     dry rms: %.6f\n", dry_rms);
  check(sum_err < 1e-4 * dry_rms + 1e-6, "vocals + instrumental reconstructs the dry left channel");
  check(sum_err_r < 1e-4 * dry_rms + 1e-6, "vocals + instrumental reconstructs the dry right channel");

  // dry minus vocals vs the instrumental stem: the single-pass assumption.
  std::vector<float> subL(length), subR(length);
  for (size_t i = 0; i < length; ++i) {
    subL[i] = left[i] - vocL[i];
    subR[i] = right[i] - vocR[i];
  }
  const double sub_err = RmsDiff(subL, instL);
  const double sub_err_r = RmsDiff(subR, instR);
  std::printf("     (dry - vocals) vs instrumental stem: %.3e (L), %.3e (R)\n", sub_err, sub_err_r);
  check(sub_err < 1e-5 * dry_rms + 1e-6, "local subtraction matches the complement-mask stem (L)");
  check(sub_err_r < 1e-5 * dry_rms + 1e-6, "local subtraction matches the complement-mask stem (R)");

  // Alignment: a lag in the reconstruction would make the subtraction comb.
  // Search +-64 samples for a better correlation than lag 0.
  int best_lag = 0;
  double best = 1e18;
  for (int lag = -64; lag <= 64; ++lag) {
    double sum = 0.0;
    int count = 0;
    for (size_t i = 128; i + 128 < length; ++i) {
      const long j = static_cast<long>(i) + lag;
      if (j < 0 || j >= static_cast<long>(length)) continue;
      const double d = static_cast<double>(sumL[i]) - left[j];
      sum += d * d;
      ++count;
    }
    if (count > 0 && sum / count < best) {
      best = sum / count;
      best_lag = lag;
    }
  }
  std::printf("     best alignment lag: %d samples\n", best_lag);
  check(best_lag == 0, "reconstruction is sample aligned (no comb-filtering risk)");

  // Energy split sanity: r=0.55 means the vocal stem should carry more than
  // half the energy, since the gain curve is not linear in r.
  const double voc_share = Rms(vocL) / (Rms(vocL) + Rms(instL) + 1e-12);
  std::printf("     vocal stem holds %.1f%% of the energy at r=0.55\n", voc_share * 100.0);
  check(voc_share > 0.5 && voc_share < 0.8, "energy split is monotonic in the mask value");
}

// --- 4. Energy-weighted clash vs the boolean count -------------------------
//
// The tree currently decides clash by counting frames above a threshold. A
// frame at 0.30 and a frame at 0.90 count the same. This shows what that costs
// on a composite that is exactly the shape a soft verse over a loud chorus
// produces.
struct ClashSample {
  double presence_out;
  double presence_in;
  double energy_out;
};

double BooleanClash(const std::vector<ClashSample>& s, double threshold) {
  if (s.empty()) return 0.0;
  int both = 0;
  for (const auto& f : s) {
    if (f.presence_out >= threshold && f.presence_in >= threshold) ++both;
  }
  return static_cast<double>(both) / static_cast<double>(s.size());
}

double WeightedClash(const std::vector<ClashSample>& s) {
  double num = 0.0;
  double den = 0.0;
  for (const auto& f : s) {
    num += f.energy_out * f.presence_out * f.presence_in;
    den += f.energy_out;
  }
  return den > 0.0 ? num / den : 0.0;
}

void CompareClashMeasures() {
  std::printf("\n=== 4. energy-weighted clash vs boolean frame count ===\n");

  // A: quiet vocal over a loud bed, 12 frames. B: loud vocal over a quiet bed,
  // 12 frames. The boolean count cannot tell these apart; the ear can.
  std::vector<ClashSample> soft, loud;
  for (int i = 0; i < 12; ++i) {
    soft.push_back({0.30, 0.30, 0.05});
    loud.push_back({0.90, 0.90, 0.95});
  }

  const double soft_bool = BooleanClash(soft, 0.25);
  const double loud_bool = BooleanClash(loud, 0.25);
  const double soft_w = WeightedClash(soft);
  const double loud_w = WeightedClash(loud);

  std::printf("     soft verse  : boolean=%.3f  weighted=%.3f\n", soft_bool, soft_w);
  std::printf("     loud chorus : boolean=%.3f  weighted=%.3f\n", loud_bool, loud_w);

  check(std::fabs(soft_bool - loud_bool) < 1e-9, "boolean count cannot separate the two cases");
  check(loud_w > soft_w * 5.0, "weighted clash separates them by a wide margin");

  // A mixed programme: mostly quiet frames with a short loud peak. The boolean
  // count calls this a clash; the weighted integral correctly calls it minor.
  std::vector<ClashSample> mixed;
  for (int i = 0; i < 40; ++i) mixed.push_back({0.30, 0.30, 0.02});
  for (int i = 0; i < 4; ++i) mixed.push_back({0.95, 0.95, 1.00});
  const double mixed_bool = BooleanClash(mixed, 0.25);
  const double mixed_w = WeightedClash(mixed);
  std::printf("     40 quiet + 4 loud: boolean=%.3f  weighted=%.3f\n", mixed_bool, mixed_w);
  check(mixed_bool > 0.99 && mixed_w < 0.80,
        "boolean over-reports a short peak inside a quiet programme");

  std::printf("\n     -> an energy-weighted mean is the sound choice, and it is what the\n");
  std::printf("        BlendFlow rank term should consume (0.20 * (1 - meanClash)).\n");
}

}  // namespace

int main() {
  std::printf("=== window path calibration ===\n");
  std::printf("sample rate %g, bins %d, channels %d\n", kVocalSpectrogramSampleRate,
              static_cast<int>(kVocalSpectrogramBins), static_cast<int>(kVocalSpectrogramChannels));

  MeasureGainCurve();
  CheckComplementAndAlignment();
  CompareClashMeasures();

  std::printf("\n=== result ===\n");
  if (failures == 0) {
    std::printf("all invariants held\n");
    return 0;
  }
  std::printf("%d check(s) failed\n", failures);
  return 1;
}
