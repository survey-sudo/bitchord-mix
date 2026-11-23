/*
 * BitChord 4-stem separation DSP (AUTOMIX-V2 Stream B). See stem_dsp.h.
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 */

#include "stem_dsp.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstdlib>
#include <cstring>

// Relative include on purpose: the analysis library's CMake target only puts
// native/ on the include path, and the CI host test adds nothing either.
#include "../third_party/signalsmith-linear/fft.h"

namespace bitchord::stems {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr size_t kHalf = kStemFft / 2;
constexpr size_t kPlane = kStemBins;  // floats per frame column in one plane

// SCNet's output source order is drums, bass, other, vocals; this maps the
// canonical stem (vocals, drums, bass, other) to the model's source index.
constexpr size_t kScnetSourceOf[kStemCount] = {3, 0, 1, 2};

float* CallocFloats(size_t count) {
  if (count == 0) return nullptr;
  return static_cast<float*>(std::calloc(count, sizeof(float)));
}

inline float SampleAt(const float* segment, size_t valid, size_t samples, long long index) {
  // torch.stft(center=True, pad_mode="reflect"): reflect without repeating the
  // edge sample, at both ends of the `samples`-long segment.
  const long long last = static_cast<long long>(samples) - 1;
  if (index < 0) index = -index;
  if (index > last) index = 2 * last - index;
  if (index < 0 || index >= static_cast<long long>(valid)) return 0.0f;
  return segment[index];
}

double RawWeight(const std::vector<size_t>& starts, size_t useful, size_t index, size_t q) {
  double weight = 1.0;
  if (index > 0) {
    const size_t prev_end = starts[index - 1] + useful;
    if (prev_end > starts[index]) {
      const size_t overlap = prev_end - starts[index];
      if (q < overlap) {
        const double s = std::sin(0.5 * kPi * (static_cast<double>(q) + 0.5) / overlap);
        weight *= s * s;
      }
    }
  }
  if (index + 1 < starts.size()) {
    const size_t end = starts[index] + useful;
    if (end > starts[index + 1]) {
      const size_t overlap = end - starts[index + 1];
      const size_t fade_from = useful - overlap;
      if (q >= fade_from) {
        const double c = std::cos(0.5 * kPi * (static_cast<double>(q - fade_from) + 0.5) / overlap);
        weight *= c * c;
      }
    }
  }
  return std::max(weight, 1e-12);
}

}  // namespace

StftSpec SpecFor(Layout layout) {
  StftSpec spec;
  if (layout == Layout::kScnetCac) {
    spec.window = Window::kRect;  // SCNet passes no window to torch.stft: ones
    spec.normalized = true;       // config normalized: True -> x 1/sqrt(n_fft)
  } else {
    spec.window = Window::kHann;  // open-unmix TorchSTFT: periodic Hann
    spec.normalized = false;
  }
  return spec;
}

std::vector<size_t> PlanChunks(size_t total, size_t useful, double min_overlap) {
  std::vector<size_t> starts;
  if (total == 0 || useful == 0) return starts;
  if (total <= useful) {
    starts.push_back(0);
    return starts;
  }
  const double overlap = std::min(0.9, std::max(0.0, min_overlap));
  const size_t step = std::max<size_t>(
      1, static_cast<size_t>(std::floor(static_cast<double>(useful) * (1.0 - overlap))));
  const size_t span = total - useful;
  const size_t count = (span + step - 1) / step + 1;
  starts.reserve(count);
  for (size_t i = 0; i < count; ++i) {
    const unsigned long long numerator = static_cast<unsigned long long>(i) * span;
    const unsigned long long denominator = count - 1;
    starts.push_back(static_cast<size_t>((numerator + denominator / 2) / denominator));
  }
  return starts;
}

float ChunkWeight(const std::vector<size_t>& starts, size_t useful, size_t index, size_t q) {
  if (index >= starts.size() || q >= useful) return 0.0f;
  const size_t global = starts[index] + q;
  double total = 0.0;
  for (size_t j = 0; j < starts.size(); ++j) {
    if (global < starts[j] || global >= starts[j] + useful) continue;
    total += RawWeight(starts, useful, j, global - starts[j]);
  }
  const double mine = RawWeight(starts, useful, index, q);
  return total > 0.0 ? static_cast<float>(mine / total) : 1.0f;
}

// ---------------------------------------------------------------------------
// Stft

struct Stft::Impl {
  StftSpec spec;
  std::vector<float> window;
  // Constructed at its size: a default (size 0) RealFFT builds NaN twiddles.
  signalsmith::linear::RealFFT<float> fft{kStemFft};
  std::vector<float> time;
  std::vector<std::complex<float>> freq;
  float forward_scale = 1.0f;
  float inverse_scale = 1.0f;
  bool ok = false;
};

Stft::Stft(StftSpec spec) : impl_(new Impl()) {
  Impl& s = *impl_;
  s.spec = spec;
  s.window.resize(kStemFft);
  for (size_t i = 0; i < kStemFft; ++i) {
    s.window[i] = spec.window == Window::kRect
                      ? 1.0f
                      : static_cast<float>(0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) / kStemFft));
  }
  s.time.assign(kStemFft, 0.0f);
  s.freq.assign(kHalf, std::complex<float>(0.0f, 0.0f));
  s.forward_scale = spec.normalized ? static_cast<float>(1.0 / std::sqrt(static_cast<double>(kStemFft))) : 1.0f;

  // Self-check the vendored real FFT once: the packing of DC / Nyquist into
  // bin 0 and the inverse's gain are conventions, not guarantees, and a wrong
  // guess here would silently corrupt every stem. A failure disables the
  // whole 4-stem path (callers fall back), it never produces wrong audio.
  std::vector<float> probe(kStemFft);
  for (size_t n = 0; n < kStemFft; ++n) {
    probe[n] = static_cast<float>(0.5 + std::cos(2.0 * kPi * 3.0 * n / kStemFft) + ((n & 1) ? -0.25 : 0.25));
  }
  s.fft.fft(probe.data(), s.freq.data());
  const double n = static_cast<double>(kStemFft);
  const double tol = 1e-3 * n;
  bool good = std::fabs(s.freq[0].real() - 0.5 * n) < tol &&
              std::fabs(s.freq[0].imag() - 0.25 * n) < tol &&
              std::fabs(s.freq[3].real() - 0.5 * n) < tol &&
              std::fabs(s.freq[3].imag()) < tol &&
              std::abs(s.freq[5]) < tol;
  if (good) {
    s.fft.ifft(s.freq.data(), s.time.data());
    double dot = 0.0, energy = 0.0;
    for (size_t i = 0; i < kStemFft; ++i) {
      dot += static_cast<double>(s.time[i]) * probe[i];
      energy += static_cast<double>(probe[i]) * probe[i];
    }
    const double gain = energy > 0.0 ? dot / energy : 0.0;
    double worst = 0.0;
    for (size_t i = 0; i < kStemFft; ++i) {
      worst = std::max(worst, std::fabs(s.time[i] - gain * probe[i]));
    }
    good = gain > 0.0 && std::isfinite(gain) && worst < 1e-3 * gain;
    if (good) {
      const double synthesis = spec.normalized ? std::sqrt(n) : 1.0;
      s.inverse_scale = static_cast<float>(synthesis / gain);
    }
  }
  s.ok = good;
}

Stft::~Stft() = default;

bool Stft::ok() const { return impl_->ok; }
StftSpec Stft::spec() const { return impl_->spec; }

void Stft::ForwardFrame(const float* segment, size_t valid, size_t samples, size_t frame,
                        float* re, float* im) {
  Impl& s = *impl_;
  const long long origin = static_cast<long long>(frame * kStemHop) - static_cast<long long>(kHalf);
  for (size_t i = 0; i < kStemFft; ++i) {
    s.time[i] = SampleAt(segment, valid, samples, origin + static_cast<long long>(i)) *
                s.window[i] * s.forward_scale;
  }
  s.fft.fft(s.time.data(), s.freq.data());
  re[0] = s.freq[0].real();
  im[0] = 0.0f;
  re[kHalf] = s.freq[0].imag();
  im[kHalf] = 0.0f;
  for (size_t b = 1; b < kHalf; ++b) {
    re[b] = s.freq[b].real();
    im[b] = s.freq[b].imag();
  }
}

void Stft::InverseFrame(const float* re, const float* im, float* out) {
  Impl& s = *impl_;
  // irfft ignores the imaginary parts of DC and Nyquist, as torch.istft does.
  s.freq[0] = std::complex<float>(re[0], re[kHalf]);
  for (size_t b = 1; b < kHalf; ++b) s.freq[b] = std::complex<float>(re[b], im[b]);
  s.fft.ifft(s.freq.data(), s.time.data());
  for (size_t i = 0; i < kStemFft; ++i) out[i] = s.time[i] * s.inverse_scale * s.window[i];
}

std::vector<float> Stft::Envelope(size_t frames) const {
  const Impl& s = *impl_;
  if (frames == 0) return {};
  std::vector<float> envelope((frames - 1) * kStemHop + kStemFft, 0.0f);
  for (size_t t = 0; t < frames; ++t) {
    float* base = envelope.data() + t * kStemHop;
    for (size_t i = 0; i < kStemFft; ++i) base[i] += s.window[i] * s.window[i];
  }
  return envelope;
}

void Stft::Forward(const float* segment, size_t valid, size_t samples, size_t frames,
                   float* re, float* im) {
  std::vector<float> fr(kStemBins), fi(kStemBins);
  for (size_t t = 0; t < frames; ++t) {
    ForwardFrame(segment, valid, samples, t, fr.data(), fi.data());
    for (size_t b = 0; b < kStemBins; ++b) {
      re[b * frames + t] = fr[b];
      im[b * frames + t] = fi[b];
    }
  }
}

void Stft::Inverse(const float* re, const float* im, size_t frames, size_t samples, float* out) {
  if (frames == 0) return;
  const std::vector<float> envelope = Envelope(frames);
  std::vector<float> ola(envelope.size(), 0.0f);
  std::vector<float> fr(kStemBins), fi(kStemBins), frame(kStemFft);
  for (size_t t = 0; t < frames; ++t) {
    for (size_t b = 0; b < kStemBins; ++b) {
      fr[b] = re[b * frames + t];
      fi[b] = im[b * frames + t];
    }
    InverseFrame(fr.data(), fi.data(), frame.data());
    float* base = ola.data() + t * kStemHop;
    for (size_t i = 0; i < kStemFft; ++i) base[i] += frame[i];
  }
  const size_t limit = std::min(samples, (frames - 1) * kStemHop);
  for (size_t q = 0; q < limit; ++q) {
    out[q] = ola[q + kHalf] / std::max(envelope[q + kHalf], 1e-11f);
  }
  for (size_t q = limit; q < samples; ++q) out[q] = 0.0f;
}

// ---------------------------------------------------------------------------
// StemJob

std::unique_ptr<StemJob> StemJob::Create(const float* left, const float* right, size_t length,
                                         const JobConfig& config) {
  if (left == nullptr || right == nullptr || length == 0) return nullptr;
  if (config.frames < 4) return nullptr;
  if (config.layout != Layout::kScnetCac && config.layout != Layout::kUmxMagnitude) return nullptr;
  if (config.post != PostFilter::kDirect && config.post != PostFilter::kWienerEm1) return nullptr;

  std::unique_ptr<StemJob> job(new StemJob());
  job->config_ = config;
  job->length_ = length;
  job->samples_ = (config.frames - 1) * kStemHop;
  if (job->samples_ <= kHalf + 1) return nullptr;
  job->useful_ = config.segment_samples == 0 ? job->samples_
                                             : std::min(config.segment_samples, job->samples_);
  if (job->useful_ == 0) return nullptr;

  job->stft_.reset(new Stft(SpecFor(config.layout)));
  if (!job->stft_->ok()) return nullptr;

  for (size_t c = 0; c < kStemChannels; ++c) {
    job->dry_[c] = CallocFloats(length);
    if (job->dry_[c] == nullptr) return nullptr;
    for (size_t k = 0; k < 3; ++k) {
      job->acc_[k][c] = CallocFloats(length);
      if (job->acc_[k][c] == nullptr) return nullptr;
    }
  }
  std::memcpy(job->dry_[0], left, length * sizeof(float));
  std::memcpy(job->dry_[1], right, length * sizeof(float));

  double peak = 0.0, sum = 0.0;
  bool finite = true;
  for (size_t i = 0; i < length; ++i) {
    const double l = left[i], r = right[i];
    if (!std::isfinite(l) || !std::isfinite(r)) {
      finite = false;
      break;
    }
    peak = std::max(peak, std::max(std::fabs(l), std::fabs(r)));
    sum += 0.5 * (l + r);
  }
  if (!finite) return nullptr;  // a NaN in the mixture would poison every stem
  if (config.normalize && length > 1) {
    const double mean = sum / static_cast<double>(length);
    double var = 0.0;
    for (size_t i = 0; i < length; ++i) {
      const double d = 0.5 * (static_cast<double>(left[i]) + right[i]) - mean;
      var += d * d;
    }
    var /= static_cast<double>(length - 1);  // torch.std is unbiased
    job->mean_ = mean;
    job->std_ = std::sqrt(var);
  }
  job->silent_ = peak < 1e-7 || (config.normalize && job->std_ < 1e-6);
  if (job->silent_) {
    job->mean_ = 0.0;
    job->std_ = 1.0;
  }

  job->envelope_ = job->stft_->Envelope(config.frames);
  for (size_t c = 0; c < kStemChannels; ++c) {
    job->segment_[c].assign(job->useful_, 0.0f);
    job->mix_re_[c].assign(kStemBins, 0.0f);
    job->mix_im_[c].assign(kStemBins, 0.0f);
  }
  job->weights_.assign(job->useful_, 0.0f);
  if (!job->silent_) {
    job->starts_ = PlanChunks(length, job->useful_, config.min_overlap);
  }
  job->consumed_.assign(job->starts_.size(), false);
  return job;
}

StemJob::~StemJob() {
  for (size_t c = 0; c < kStemChannels; ++c) {
    std::free(dry_[c]);
    for (size_t k = 0; k < 3; ++k) std::free(acc_[k][c]);
  }
}

size_t StemJob::input_floats() const {
  const size_t planes = config_.layout == Layout::kScnetCac ? kStemChannels * 2 : kStemChannels;
  return planes * kPlane * config_.frames;
}

size_t StemJob::output_floats() const {
  const size_t planes = config_.layout == Layout::kScnetCac ? kStemCount * kStemChannels * 2 : kStemChannels;
  return planes * kPlane * config_.frames;
}

size_t StemJob::output_buffers() const {
  return config_.layout == Layout::kScnetCac ? 1 : kStemCount;
}

bool StemJob::complete() const {
  if (silent_) return true;
  for (bool done : consumed_) {
    if (!done) return false;
  }
  return !consumed_.empty();
}

double StemJob::residual_ratio() const {
  return residual_den_ > 0.0 ? residual_num_ / residual_den_ : 0.0;
}

bool StemJob::BuildSegment(size_t index) {
  if (index >= starts_.size()) return false;
  if (segment_index_ == static_cast<long>(index)) return true;
  const size_t start = starts_[index];
  const size_t valid = std::min(useful_, length_ - start);
  const double inv_std = 1.0 / std_;
  for (size_t c = 0; c < kStemChannels; ++c) {
    float* seg = segment_[c].data();
    const float* dry = dry_[c] + start;
    for (size_t q = 0; q < valid; ++q) seg[q] = static_cast<float>((dry[q] - mean_) * inv_std);
    for (size_t q = valid; q < useful_; ++q) seg[q] = 0.0f;
  }
  segment_valid_ = valid;
  segment_index_ = static_cast<long>(index);
  return true;
}

bool StemJob::PrepareChunk(size_t index, float* input) {
  if (input == nullptr || !BuildSegment(index)) return false;
  const size_t frames = config_.frames;
  const size_t plane = kPlane * frames;
  for (size_t c = 0; c < kStemChannels; ++c) {
    float* re = mix_re_[c].data();
    float* im = mix_im_[c].data();
    for (size_t t = 0; t < frames; ++t) {
      stft_->ForwardFrame(segment_[c].data(), segment_valid_, samples_, t, re, im);
      if (config_.layout == Layout::kScnetCac) {
        float* pr = input + (c * 2 + 0) * plane + t;
        float* pi = input + (c * 2 + 1) * plane + t;
        for (size_t b = 0; b < kStemBins; ++b) {
          pr[b * frames] = re[b];
          pi[b * frames] = im[b];
        }
      } else {
        float* pm = input + c * plane + t;
        for (size_t b = 0; b < kStemBins; ++b) {
          pm[b * frames] = std::sqrt(re[b] * re[b] + im[b] * im[b]);
        }
      }
    }
  }
  return true;
}

void StemJob::MixAt(size_t frame, const float* input) {
  const size_t frames = config_.frames;
  if (config_.layout == Layout::kScnetCac) {
    // The network's input already is the mixture's (normalized) CaC STFT.
    const size_t plane = kPlane * frames;
    for (size_t c = 0; c < kStemChannels; ++c) {
      const float* pr = input + (c * 2 + 0) * plane + frame;
      const float* pi = input + (c * 2 + 1) * plane + frame;
      float* re = mix_re_[c].data();
      float* im = mix_im_[c].data();
      for (size_t b = 0; b < kStemBins; ++b) {
        re[b] = pr[b * frames];
        im[b] = pi[b * frames];
      }
    }
  } else {
    // Magnitude input carries no phase: recompute the frame (two FFTs).
    for (size_t c = 0; c < kStemChannels; ++c) {
      stft_->ForwardFrame(segment_[c].data(), segment_valid_, samples_, frame,
                          mix_re_[c].data(), mix_im_[c].data());
    }
  }
}

void StemJob::EstimateAt(size_t stem, size_t channel, size_t bin, size_t frame,
                         float* const* outputs, double* re, double* im) const {
  const size_t frames = config_.frames;
  const size_t plane = kPlane * frames;
  const size_t cell = bin * frames + frame;
  if (config_.layout == Layout::kScnetCac) {
    const size_t source = kScnetSourceOf[stem];
    const size_t base = (source * kStemChannels + channel) * 2;
    *re = outputs[0][(base + 0) * plane + cell];
    *im = outputs[0][(base + 1) * plane + cell];
    return;
  }
  // open-unmix's wiener(softmask=False) initialisation: the mixture times
  // each target's share of the summed target magnitudes.
  double total = 0.0;
  for (size_t k = 0; k < kStemCount; ++k) {
    total += std::max(0.0f, outputs[k][channel * plane + cell]);
  }
  const double mine = std::max(0.0f, outputs[stem][channel * plane + cell]);
  const double mask = mine / (total + 1e-10);
  *re = mix_re_[channel][bin] * mask;
  *im = mix_im_[channel][bin] * mask;
}

bool StemJob::ConsumeChunk(size_t index, const float* input, float* const* outputs) {
  if (input == nullptr || outputs == nullptr || index >= starts_.size()) return false;
  for (size_t b = 0; b < output_buffers(); ++b) {
    if (outputs[b] == nullptr) return false;
  }
  if (!BuildSegment(index)) return false;

  const size_t frames = config_.frames;
  const size_t start = starts_[index];
  const size_t valid = segment_valid_;
  for (size_t q = 0; q < valid; ++q) weights_[q] = ChunkWeight(starts_, useful_, index, q);

  const bool wiener = config_.post == PostFilter::kWienerEm1;
  // Per-bin local Gaussian model: R_k(f) = sum_t y y^H / sum_t v_k, the
  // spatial covariance norbert's EM estimates, over this chunk.
  std::vector<double> r00, r11, r01re, r01im;
  double scale = 1.0;
  if (wiener) {
    std::vector<double> vsum(kStemCount * kStemBins, 0.0);
    r00.assign(kStemCount * kStemBins, 0.0);
    r11.assign(kStemCount * kStemBins, 0.0);
    r01re.assign(kStemCount * kStemBins, 0.0);
    r01im.assign(kStemCount * kStemBins, 0.0);
    double max_abs = 0.0;
    for (size_t t = 0; t < frames; ++t) {
      MixAt(t, input);
      for (size_t f = 0; f < kStemBins; ++f) {
        for (size_t c = 0; c < kStemChannels; ++c) {
          max_abs = std::max(max_abs, std::hypot(static_cast<double>(mix_re_[c][f]),
                                                 static_cast<double>(mix_im_[c][f])));
        }
        for (size_t k = 0; k < kStemCount; ++k) {
          double y0r, y0i, y1r, y1i;
          EstimateAt(k, 0, f, t, outputs, &y0r, &y0i);
          EstimateAt(k, 1, f, t, outputs, &y1r, &y1i);
          const double p0 = y0r * y0r + y0i * y0i;
          const double p1 = y1r * y1r + y1i * y1i;
          const size_t cell = k * kStemBins + f;
          r00[cell] += p0;
          r11[cell] += p1;
          // y0 * conj(y1)
          r01re[cell] += y0r * y1r + y0i * y1i;
          r01im[cell] += y0i * y1r - y0r * y1i;
          vsum[cell] += 0.5 * (p0 + p1);
        }
      }
    }
    // open-unmix scales mixture and estimates by max(1, max|x| / 10) for EM;
    // the equivalent in unscaled units scales eps and the regularisation.
    scale = std::max(1.0, max_abs / 10.0);
    const double eps = 1e-10 * scale * scale;
    for (size_t cell = 0; cell < kStemCount * kStemBins; ++cell) {
      const double norm = 1.0 / (eps + vsum[cell]);
      r00[cell] *= norm;
      r11[cell] *= norm;
      r01re[cell] *= norm;
      r01im[cell] *= norm;
    }
  }
  const double regularisation = 1e-5 * scale * scale;  // sqrt(eps=1e-10) * I

  std::vector<float> spec_re[3][kStemChannels];
  std::vector<float> spec_im[3][kStemChannels];
  for (size_t k = 0; k < 3; ++k) {
    for (size_t c = 0; c < kStemChannels; ++c) {
      spec_re[k][c].assign(kStemBins, 0.0f);
      spec_im[k][c].assign(kStemBins, 0.0f);
    }
  }
  std::vector<float> frame_out(kStemFft, 0.0f);
  double residual_num = 0.0, residual_den = 0.0;

  for (size_t t = 0; t < frames; ++t) {
    MixAt(t, input);
    for (size_t f = 0; f < kStemBins; ++f) {
      const std::complex<double> x0(mix_re_[0][f], mix_im_[0][f]);
      const std::complex<double> x1(mix_re_[1][f], mix_im_[1][f]);
      std::complex<double> y[kStemCount][kStemChannels];
      std::complex<double> sum0(0.0, 0.0), sum1(0.0, 0.0);
      for (size_t k = 0; k < kStemCount; ++k) {
        double ar, ai, br, bi;
        EstimateAt(k, 0, f, t, outputs, &ar, &ai);
        EstimateAt(k, 1, f, t, outputs, &br, &bi);
        y[k][0] = std::complex<double>(ar, ai);
        y[k][1] = std::complex<double>(br, bi);
        sum0 += y[k][0];
        sum1 += y[k][1];
      }
      residual_num += std::norm(x0 - sum0) + std::norm(x1 - sum1);
      residual_den += std::norm(x0) + std::norm(x1);

      if (wiener) {
        double v[kStemCount];
        // Cxx = sum_k v_k R_k + reg I  (2x2 Hermitian: a, b; conj(b), d)
        double a = regularisation, d = regularisation;
        std::complex<double> b(0.0, 0.0);
        for (size_t k = 0; k < kStemCount; ++k) {
          v[k] = 0.5 * (std::norm(y[k][0]) + std::norm(y[k][1]));
          const size_t cell = k * kStemBins + f;
          a += v[k] * r00[cell];
          d += v[k] * r11[cell];
          b += v[k] * std::complex<double>(r01re[cell], r01im[cell]);
        }
        const double det = std::max(a * d - std::norm(b), 1e-30);
        // inv = 1/det [[d, -b], [-conj(b), a]]
        const std::complex<double> i00(d / det, 0.0), i01 = -b / det;
        const std::complex<double> i10 = -std::conj(b) / det, i11(a / det, 0.0);
        for (size_t k = 0; k < 3; ++k) {
          const size_t cell = k * kStemBins + f;
          const std::complex<double> q00(v[k] * r00[cell], 0.0);
          const std::complex<double> q01 = v[k] * std::complex<double>(r01re[cell], r01im[cell]);
          const std::complex<double> q10 = std::conj(q01);
          const std::complex<double> q11(v[k] * r11[cell], 0.0);
          // G = v R inv ; y = G x
          const std::complex<double> g00 = q00 * i00 + q01 * i10;
          const std::complex<double> g01 = q00 * i01 + q01 * i11;
          const std::complex<double> g10 = q10 * i00 + q11 * i10;
          const std::complex<double> g11 = q10 * i01 + q11 * i11;
          y[k][0] = g00 * x0 + g01 * x1;
          y[k][1] = g10 * x0 + g11 * x1;
        }
      }
      for (size_t k = 0; k < 3; ++k) {
        for (size_t c = 0; c < kStemChannels; ++c) {
          spec_re[k][c][f] = static_cast<float>(y[k][c].real());
          spec_im[k][c][f] = static_cast<float>(y[k][c].imag());
        }
      }
    }

    // Overlap-add straight into the region accumulators: dividing each
    // frame's contribution by the window envelope at its position is the
    // same linear operation torch.istft performs on the summed frames.
    const long long origin = static_cast<long long>(t * kStemHop) - static_cast<long long>(kHalf);
    const long long lo = std::max<long long>(0, -origin);
    const long long hi = std::min<long long>(static_cast<long long>(kStemFft),
                                             static_cast<long long>(valid) - origin);
    if (hi <= lo) continue;
    for (size_t k = 0; k < 3; ++k) {
      for (size_t c = 0; c < kStemChannels; ++c) {
        stft_->InverseFrame(spec_re[k][c].data(), spec_im[k][c].data(), frame_out.data());
        float* acc = acc_[k][c] + start;
        for (long long i = lo; i < hi; ++i) {
          const size_t q = static_cast<size_t>(origin + i);
          const size_t p = t * kStemHop + static_cast<size_t>(i);
          const float env = std::max(envelope_[p], 1e-11f);
          acc[q] += static_cast<float>(frame_out[static_cast<size_t>(i)] / env * weights_[q] * std_);
        }
      }
    }
  }
  residual_num_ += residual_num;
  residual_den_ += residual_den;
  consumed_[index] = true;
  return true;
}

bool StemJob::CopyStem(size_t stem, size_t channel, size_t offset, size_t count, float* out) const {
  if (out == nullptr || stem >= kStemCount || channel >= kStemChannels) return false;
  if (offset > length_ || count > length_ - offset) return false;
  if (!complete()) return false;
  if (stem < 3) {
    std::memcpy(out, acc_[stem][channel] + offset, count * sizeof(float));
    return true;
  }
  const float* dry = dry_[channel] + offset;
  const float* v = acc_[kStemVocals][channel] + offset;
  const float* d = acc_[kStemDrums][channel] + offset;
  const float* b = acc_[kStemBass][channel] + offset;
  for (size_t i = 0; i < count; ++i) out[i] = dry[i] - (v[i] + d[i] + b[i]);
  return true;
}

}  // namespace bitchord::stems
