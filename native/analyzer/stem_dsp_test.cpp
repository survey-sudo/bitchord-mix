// Host self-test for the 4-stem DSP around the separation network
// (native/analyzer/stem_dsp.{h,cpp}). No model is needed: a fake "network"
// that returns fixed fractions of the mixture makes every stem's exact value
// known, so the test proves the front end, the chunk crossfades, the Wiener
// post-filter, the inverse STFT and the mixture-consistency step together.
//
//   g++ -std=c++17 -O2 -I native native/analyzer/stem_dsp_test.cpp native/analyzer/stem_dsp.cpp -o /tmp/stem_dsp_test && /tmp/stem_dsp_test
//
// Exit 0 means every check passed.
#include "analyzer/stem_dsp.h"

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

using namespace bitchord::stems;

static int failures = 0;

static void check(bool cond, const char* what) {
  std::printf("%s %s\n", cond ? "ok  " : "FAIL", what);
  if (!cond) ++failures;
}

static std::vector<float> signal(size_t n, uint32_t seed, double freq) {
  std::vector<float> v(n);
  uint32_t state = seed;
  for (size_t i = 0; i < n; ++i) {
    state = state * 1664525u + 1013904223u;
    const double noise = (static_cast<double>(state >> 8) / 16777216.0) - 0.5;
    v[i] = static_cast<float>(0.2 * std::sin(2.0 * 3.14159265358979 * freq * i / 44100.0) + 0.15 * noise);
  }
  return v;
}

static bool all_finite(const std::vector<float>& v) {
  for (float x : v) {
    if (!std::isfinite(x)) return false;
  }
  return true;
}

// Canonical (vocals, drums, bass, other) fractions of the mixture the fake network returns.
static const float kGain[kStemCount] = {0.4f, 0.3f, 0.2f, 0.1f};
// SCNet's source order is drums, bass, other, vocals.
static const size_t kCanonicalOfScnet[kStemCount] = {kStemDrums, kStemBass, kStemOther, kStemVocals};

static void fake_network(const StemJob& job, Layout layout, const std::vector<float>& input,
                         std::vector<std::vector<float>>& outputs, size_t frames) {
  const size_t plane = kStemBins * frames;
  if (layout == Layout::kScnetCac) {
    std::vector<float>& out = outputs[0];
    for (size_t s = 0; s < kStemCount; ++s) {
      const float g = kGain[kCanonicalOfScnet[s]];
      for (size_t c = 0; c < kStemChannels; ++c) {
        for (size_t part = 0; part < 2; ++part) {
          const float* src = input.data() + (c * 2 + part) * plane;
          float* dst = out.data() + ((s * kStemChannels + c) * 2 + part) * plane;
          for (size_t i = 0; i < plane; ++i) dst[i] = g * src[i];
        }
      }
    }
  } else {
    for (size_t k = 0; k < kStemCount; ++k) {
      for (size_t i = 0; i < kStemChannels * plane; ++i) outputs[k][i] = kGain[k] * input[i];
    }
  }
  (void)job;
}

struct Result {
  bool ok = false;
  std::vector<float> stem[kStemCount][kStemChannels];
  size_t chunks = 0;
  double residual = 0.0;
};

static Result run(const std::vector<float>& left, const std::vector<float>& right, const JobConfig& config) {
  Result result;
  auto job = StemJob::Create(left.data(), right.data(), left.size(), config);
  if (!job) return result;
  result.chunks = job->chunk_count();
  std::vector<float> input(job->input_floats(), 0.0f);
  std::vector<std::vector<float>> outputs(job->output_buffers(), std::vector<float>(job->output_floats(), 0.0f));
  std::vector<float*> pointers;
  for (auto& o : outputs) pointers.push_back(o.data());
  for (size_t i = 0; i < job->chunk_count(); ++i) {
    if (!job->PrepareChunk(i, input.data())) return result;
    fake_network(*job, config.layout, input, outputs, config.frames);
    if (!job->ConsumeChunk(i, input.data(), pointers.data())) return result;
  }
  for (size_t k = 0; k < kStemCount; ++k) {
    for (size_t c = 0; c < kStemChannels; ++c) {
      result.stem[k][c].assign(left.size(), 0.0f);
      if (!job->CopyStem(k, c, 0, left.size(), result.stem[k][c].data())) return result;
    }
  }
  result.residual = job->residual_ratio();
  result.ok = true;
  return result;
}

static double mono_mean(const std::vector<float>& l, const std::vector<float>& r) {
  double s = 0.0;
  for (size_t i = 0; i < l.size(); ++i) s += 0.5 * (static_cast<double>(l[i]) + r[i]);
  return s / static_cast<double>(l.size());
}

// max |stem - expected * (dry - mean)| over both channels.
static double stem_error(const Result& r, size_t stem, double expected, const std::vector<float>& l,
                         const std::vector<float>& rr, double mean) {
  if (!r.ok || r.stem[stem][0].size() != l.size() || r.stem[stem][1].size() != l.size()) return 1.0;
  double worst = 0.0;
  const std::vector<float>* dry[2] = {&l, &rr};
  for (size_t c = 0; c < kStemChannels; ++c) {
    for (size_t i = 0; i < l.size(); ++i) {
      worst = std::fmax(worst, std::fabs(r.stem[stem][c][i] - expected * ((*dry[c])[i] - mean)));
    }
  }
  return worst;
}

// max |V + D + B + O - dry|, and finiteness of every stem.
static double consistency_error(const Result& r, const std::vector<float>& l, const std::vector<float>& rr,
                                bool* finite) {
  *finite = false;
  for (size_t k = 0; k < kStemCount; ++k) {
    for (size_t c = 0; c < kStemChannels; ++c) {
      if (r.stem[k][c].size() != l.size()) return 1.0;
    }
  }
  double worst = 0.0;
  *finite = true;
  const std::vector<float>* dry[2] = {&l, &rr};
  for (size_t c = 0; c < kStemChannels; ++c) {
    for (size_t k = 0; k < kStemCount; ++k) *finite = *finite && all_finite(r.stem[k][c]);
    for (size_t i = 0; i < l.size(); ++i) {
      const double sum = static_cast<double>(r.stem[0][c][i]) + r.stem[1][c][i] + r.stem[2][c][i] + r.stem[3][c][i];
      worst = std::fmax(worst, std::fabs(sum - (*dry[c])[i]));
    }
  }
  return worst;
}

int main() {
  // 1. The FFT self-check passes for both conventions.
  {
    Stft rect(SpecFor(Layout::kScnetCac));
    Stft hann(SpecFor(Layout::kUmxMagnitude));
    check(rect.ok(), "rect/normalized STFT self-check");
    check(hann.ok(), "hann STFT self-check");
  }

  // 2. STFT -> iSTFT reproduces the segment (torch.stft/istft convention), with and
  //    without a zero tail, for both windows.
  for (int w = 0; w < 2; ++w) {
    const Layout layout = w == 0 ? Layout::kScnetCac : Layout::kUmxMagnitude;
    Stft stft(SpecFor(layout));
    const size_t frames = 64;
    const size_t samples = (frames - 1) * kStemHop;
    const std::vector<float> x = signal(samples, 7u + w, 523.0);
    for (int tail = 0; tail < 2; ++tail) {
      const size_t valid = tail ? samples - 3000 : samples;
      std::vector<float> re(kStemBins * frames), im(kStemBins * frames), y(samples);
      stft.Forward(x.data(), valid, samples, frames, re.data(), im.data());
      stft.Inverse(re.data(), im.data(), frames, samples, y.data());
      double worst = 0.0;
      for (size_t i = 0; i < samples; ++i) {
        const double expect = i < valid ? x[i] : 0.0;
        worst = std::fmax(worst, std::fabs(y[i] - expect));
      }
      std::printf("     round trip %s tail=%d max err %.3g\n", w == 0 ? "rect" : "hann", tail, worst);
      check(worst < 1e-4 && all_finite(y), "iSTFT(STFT(x)) == x within 1e-4");
    }
  }

  // 3. Chunk plan: a 30 s region in 11 s chunks with >= 12% overlap is three
  //    chunks, first at 0, last ending at the region end, weights a partition of 1.
  {
    const size_t total = 30 * 44100, useful = 485100;
    const auto starts = PlanChunks(total, useful, 0.12);
    check(starts.size() == 3, "30 s region -> 3 chunks");
    check(!starts.empty() && starts.front() == 0, "first chunk at 0");
    check(!starts.empty() && starts.back() + useful == total, "last chunk ends at region end");
    double worst = 0.0;
    for (size_t n = 0; n < total; n += 997) {
      double sum = 0.0;
      for (size_t j = 0; j < starts.size(); ++j) {
        if (n >= starts[j] && n < starts[j] + useful) sum += ChunkWeight(starts, useful, j, n - starts[j]);
      }
      worst = std::fmax(worst, std::fabs(sum - 1.0));
    }
    check(worst < 1e-5, "crossfade weights sum to 1");
    check(PlanChunks(1000, 4000, 0.12).size() == 1, "short region -> one chunk");
    check(PlanChunks(0, 4000, 0.12).empty(), "empty region -> no chunks");
  }

  const size_t n = 200000;  // ~4.5 s: four 64-frame chunks
  const std::vector<float> left = signal(n, 1u, 220.0);
  const std::vector<float> right = signal(n, 2u, 330.0);
  const double mean = mono_mean(left, right);

  // 4. SCNet layout, direct: each stem is exactly its fraction of the (mean-
  //    removed) mixture through every chunk crossfade; the four sum to dry.
  {
    JobConfig config;
    config.layout = Layout::kScnetCac;
    config.frames = 64;
    config.segment_samples = 64000;
    config.post = PostFilter::kDirect;
    config.normalize = true;
    const Result r = run(left, right, config);
    check(r.ok, "scnet direct job runs");
    check(r.chunks == 4, "scnet direct job uses 4 chunks");
    double worst = 0.0;
    for (size_t k = 0; k < 3; ++k) worst = std::fmax(worst, stem_error(r, k, kGain[k], left, right, mean));
    std::printf("     scnet direct stem err %.3g residual %.3g\n", worst, r.residual);
    check(r.ok && worst < 1e-4, "scnet direct stems == gain * mixture within 1e-4");
    bool finite = false;
    const double cons = r.ok ? consistency_error(r, left, right, &finite) : 1.0;
    check(cons < 1e-4 && finite, "scnet direct: V+D+B+O == dry within 1e-4, no NaN");
    check(r.residual < 1e-6, "scnet direct: raw estimates sum to the mixture");
  }

  // 5. SCNet layout, Wiener EM1: proportional estimates give g^2 / sum g^2.
  {
    JobConfig config;
    config.layout = Layout::kScnetCac;
    config.frames = 64;
    config.segment_samples = 64000;
    config.post = PostFilter::kWienerEm1;
    config.normalize = true;
    const Result r = run(left, right, config);
    double sum_sq = 0.0;
    for (float g : kGain) sum_sq += g * g;
    double worst = 0.0;
    for (size_t k = 0; k < 3; ++k) {
      worst = std::fmax(worst, stem_error(r, k, kGain[k] * kGain[k] / sum_sq, left, right, mean));
    }
    std::printf("     scnet wiener stem err %.3g\n", worst);
    check(r.ok && worst < 3e-3, "scnet wiener stems == g^2/sum(g^2) * mixture");
    bool finite = false;
    const double cons = r.ok ? consistency_error(r, left, right, &finite) : 1.0;
    check(cons < 1e-4 && finite, "scnet wiener: V+D+B+O == dry within 1e-4, no NaN");
  }

  // 6. UMX magnitude layout: ratio mask (direct) and Wiener EM1.
  for (int post = 0; post < 2; ++post) {
    JobConfig config;
    config.layout = Layout::kUmxMagnitude;
    config.frames = 64;
    config.segment_samples = 0;  // the whole STFT segment is real audio
    config.post = post == 0 ? PostFilter::kDirect : PostFilter::kWienerEm1;
    config.normalize = false;
    const Result r = run(left, right, config);
    double sum_sq = 0.0;
    for (float g : kGain) sum_sq += g * g;
    double worst = 0.0;
    for (size_t k = 0; k < 3; ++k) {
      const double expected = post == 0 ? kGain[k] : kGain[k] * kGain[k] / sum_sq;
      worst = std::fmax(worst, stem_error(r, k, expected, left, right, 0.0));
    }
    std::printf("     umx %s stem err %.3g\n", post == 0 ? "mask" : "wiener", worst);
    check(r.ok && worst < (post == 0 ? 1e-4 : 3e-3), "umx stems match the expected fraction");
    bool finite = false;
    const double cons = r.ok ? consistency_error(r, left, right, &finite) : 1.0;
    check(cons < 1e-4 && finite, "umx: V+D+B+O == dry within 1e-4, no NaN");
  }

  // 7. Production geometry: 476 frames, 11 s of audio per chunk, 30 s region.
  {
    const size_t big = 30 * 44100;
    const std::vector<float> l = signal(big, 11u, 110.0);
    const std::vector<float> rr = signal(big, 12u, 165.0);
    JobConfig config;  // defaults: SCNet, 476 frames, 485100 useful, 12% overlap, direct
    const Result r = run(l, rr, config);
    check(r.ok && r.chunks == 3, "production scnet geometry: 3 chunks for 30 s");
    const double m = mono_mean(l, rr);
    double worst = 0.0;
    for (size_t k = 0; k < 3; ++k) worst = std::fmax(worst, stem_error(r, k, kGain[k], l, rr, m));
    bool finite = false;
    const double cons = r.ok ? consistency_error(r, l, rr, &finite) : 1.0;
    std::printf("     production stem err %.3g consistency %.3g\n", worst, cons);
    check(r.ok && worst < 1e-4, "production stems exact within 1e-4");
    check(cons < 1e-4 && finite, "production: V+D+B+O == dry within 1e-4, no NaN");
  }

  // 8. Short region (shorter than one chunk) keeps its length and stays exact.
  {
    const std::vector<float> l = signal(5000, 3u, 440.0);
    const std::vector<float> rr = signal(5000, 4u, 660.0);
    JobConfig config;
    config.frames = 64;
    config.segment_samples = 64000;
    const Result r = run(l, rr, config);
    check(r.ok && r.chunks == 1 && r.stem[0][0].size() == 5000, "short region: one chunk, length kept");
    bool finite = false;
    const double cons = r.ok ? consistency_error(r, l, rr, &finite) : 1.0;
    check(cons < 1e-4 && finite, "short region: V+D+B+O == dry");
  }

  // 9. Silence: no chunks, no NaN (normalization would divide by a zero std).
  {
    const std::vector<float> zeros(30000, 0.0f);
    JobConfig config;
    config.frames = 64;
    config.segment_samples = 64000;
    const Result r = run(zeros, zeros, config);
    check(r.ok && r.chunks == 0, "silent region needs no network run");
    bool finite = false;
    const double cons = r.ok ? consistency_error(r, zeros, zeros, &finite) : 1.0;
    check(cons == 0.0 && finite, "silent region: all stems zero, finite");
  }

  // 10. Rejections never write: null input, too few frames, NaN mixture, early copy.
  {
    JobConfig config;
    check(!StemJob::Create(nullptr, right.data(), n, config), "null input rejected");
    JobConfig tiny = config;
    tiny.frames = 2;
    check(!StemJob::Create(left.data(), right.data(), n, tiny), "frames < 4 rejected");
    std::vector<float> bad = left;
    bad[100] = std::nanf("");
    check(!StemJob::Create(bad.data(), right.data(), n, config), "NaN mixture rejected");
    JobConfig small = config;
    small.frames = 64;
    small.segment_samples = 64000;
    auto job = StemJob::Create(left.data(), right.data(), n, small);
    std::vector<float> out(n);
    check(job && !job->CopyStem(0, 0, 0, n, out.data()), "stems unavailable before every chunk ran");
    check(job && !job->ConsumeChunk(99, nullptr, nullptr), "bad chunk index rejected");
  }

  if (failures != 0) {
    std::printf("%d check(s) failed\n", failures);
    return 1;
  }
  std::printf("all checks passed\n");
  return 0;
}
