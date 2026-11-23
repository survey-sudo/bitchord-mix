/*
 * BitChord 4-stem separation DSP (AUTOMIX-V2 Stream B).
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

// Everything around a 4-stem separation network except the network itself:
// the STFT front end the network was trained on, chunking with crossfaded
// overlap, an optional multichannel Wiener post-filter (one EM iteration, as
// norbert / open-unmix do it), the inverse STFT with overlap-add, and the
// mixture-consistency step that makes VOCALS + DRUMS + BASS + OTHER equal the
// dry input exactly (OTHER is defined as dry minus the other three, so every
// model/numerical residual is folded into it).
//
// Two network layouts are supported, both with n_fft 4096 / hop 1024 at
// 44.1 kHz, centred with reflect padding (torch.stft center=True):
//
//  * kScnetCac  - SCNet (starrytong/SCNet, MIT weights). Rectangular window,
//                 normalized (x 1/sqrt(n_fft)). Input  [1, 4, 2049, T] planes
//                 L.re, L.im, R.re, R.im. Output [1, 16, 2049, T] planes
//                 ((source * 2 + channel) * 2 + part), sources in the model's
//                 order drums, bass, other, vocals. One output buffer.
//  * kUmxMagnitude - Open-Unmix UMX-HQ (MIT weights), one graph per target.
//                 Periodic Hann window, unnormalized. Input [1, 2, 2049, T]
//                 magnitudes; four output buffers (vocals, drums, bass,
//                 other), each [1, 2, 2049, T] magnitudes. Mixture phase is
//                 used through a ratio mask, as open-unmix does.
//
// Plane layout everywhere is bin-major: plane p, bin b, frame t lives at
// p * bins * T + b * T + t, which is exactly the row-major ONNX tensor.
//
// Allocation: the large buffers use calloc (never throws) so this compiles and
// fails soft with or without C++ exceptions. Work is O(n log n) per chunk and
// allocates, so it belongs on a worker thread, never the audio callback.

#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>

namespace bitchord::stems {

inline constexpr double kStemSampleRate = 44100.0;
inline constexpr size_t kStemChannels = 2;
inline constexpr size_t kStemFft = 4096;
inline constexpr size_t kStemHop = 1024;
inline constexpr size_t kStemBins = kStemFft / 2 + 1;
/** Canonical stem order everywhere outside the model == Kotlin StemKind.ordinal. */
inline constexpr size_t kStemCount = 4;
inline constexpr size_t kStemVocals = 0;
inline constexpr size_t kStemDrums = 1;
inline constexpr size_t kStemBass = 2;
inline constexpr size_t kStemOther = 3;

enum class Layout : int { kScnetCac = 0, kUmxMagnitude = 1 };
enum class PostFilter : int {
  /** SCNet: the network's complex estimates as-is. UMX: ratio mask over mixture phase (niter=0). */
  kDirect = 0,
  /** One EM iteration of the multichannel Wiener filter (norbert / open-unmix niter=1). */
  kWienerEm1 = 1,
};
enum class Window : int { kRect = 0, kHann = 1 };

struct StftSpec {
  Window window = Window::kRect;
  bool normalized = true;
};

/** The STFT convention a network layout was trained on. */
StftSpec SpecFor(Layout layout);

/**
 * Chunk starts covering [0, total) with chunks of `useful` samples whose
 * neighbours overlap by at least `min_overlap * useful`, spread evenly so the
 * first starts at 0 and the last ends exactly at `total`. One chunk at 0 when
 * total <= useful. Empty when total or useful is 0.
 */
std::vector<size_t> PlanChunks(size_t total, size_t useful, double min_overlap);

/**
 * Crossfade weight of chunk `index` at local sample q (0 <= q < useful),
 * normalized so the weights of every chunk covering a sample sum to 1.
 * Raised-cosine (sin^2 / cos^2) ramps over each actual overlap.
 */
float ChunkWeight(const std::vector<size_t>& starts, size_t useful, size_t index, size_t q);

/**
 * One STFT convention, n_fft kStemFft, hop kStemHop, centred with reflect
 * padding. Not thread-safe (owns FFT scratch); cheap enough to make per job.
 */
class Stft {
 public:
  explicit Stft(StftSpec spec);
  ~Stft();
  Stft(const Stft&) = delete;
  Stft& operator=(const Stft&) = delete;

  /** False when the FFT failed its construction-time self-check; never use it then. */
  bool ok() const;
  StftSpec spec() const;

  /** torch.stft(center=True) frame count for `samples` samples. */
  static size_t FramesFor(size_t samples) { return samples / kStemHop + 1; }

  /**
   * Spectrum (kStemBins re/im) of frame `frame` of a segment of `samples`
   * samples, of which the first `valid` come from `segment` and the rest are
   * zeros, centred and reflect-padded by kStemFft/2. Requires
   * samples > kStemFft/2.
   */
  void ForwardFrame(const float* segment, size_t valid, size_t samples, size_t frame,
                    float* re, float* im);

  /**
   * Inverse of one frame: kStemFft time samples already scaled and multiplied
   * by the synthesis window. Overlap-add them at frame * hop (padded
   * coordinates) and divide by Envelope() to reproduce torch.istft.
   */
  void InverseFrame(const float* re, const float* im, float* out);

  /** Sum of squared windows at each padded position, length (frames-1)*hop + fft. */
  std::vector<float> Envelope(size_t frames) const;

  /** Whole-segment helpers (tests, tools): planes re/im are [bin * frames + t]. */
  void Forward(const float* segment, size_t valid, size_t samples, size_t frames,
               float* re, float* im);
  /** Writes `samples` <= (frames-1)*hop samples. */
  void Inverse(const float* re, const float* im, size_t frames, size_t samples, float* out);

 private:
  struct Impl;
  std::unique_ptr<Impl> impl_;
};

struct JobConfig {
  Layout layout = Layout::kScnetCac;
  /** The network's fixed frame count T (SCNet export: 476; bundled UMX vocals: 960). */
  size_t frames = 476;
  /**
   * Real audio per chunk; the rest of the (frames-1)*hop STFT segment is zeros.
   * SCNet: 485100 (11 s, the training chunk) inside 486400. 0 = the full segment.
   */
  size_t segment_samples = 485100;
  double min_overlap = 0.12;
  PostFilter post = PostFilter::kDirect;
  /** Music-Source-Separation-Training's inference.normalize: mono mean/std over the region. */
  bool normalize = true;
};

/**
 * One region's separation. Lifecycle (single thread):
 *   Create -> for i in chunks: PrepareChunk(i, in) ; <run network> ; ConsumeChunk(i, in, outs)
 *          -> CopyStem(...) for each stem/channel.
 * The region is planar stereo at kStemSampleRate. Results are available
 * after every chunk has been consumed; a silent region has zero chunks and
 * yields VOCALS = DRUMS = BASS = 0, OTHER = dry.
 */
class StemJob {
 public:
  static std::unique_ptr<StemJob> Create(const float* left, const float* right, size_t length,
                                         const JobConfig& config);
  ~StemJob();
  StemJob(const StemJob&) = delete;
  StemJob& operator=(const StemJob&) = delete;

  size_t length() const { return length_; }
  size_t chunk_count() const { return starts_.size(); }
  size_t chunk_start(size_t index) const { return starts_[index]; }
  /** Floats in the network input tensor. */
  size_t input_floats() const;
  /** Floats in each network output tensor. */
  size_t output_floats() const;
  /** 1 for kScnetCac, 4 for kUmxMagnitude (vocals, drums, bass, other). */
  size_t output_buffers() const;

  bool PrepareChunk(size_t index, float* input);
  /** `outputs` has output_buffers() entries; may be modified in place. */
  bool ConsumeChunk(size_t index, const float* input, float* const* outputs);
  /** True once every chunk was consumed (or the region is silent). */
  bool complete() const;

  /**
   * Copies `count` samples of stem (canonical order) / channel starting at
   * `offset` into `out`. OTHER is dry - VOCALS - DRUMS - BASS, so the four
   * always sum to the dry input (up to float rounding).
   */
  bool CopyStem(size_t stem, size_t channel, size_t offset, size_t count, float* out) const;

  /** Spectral energy of (mixture - sum of raw network estimates) over mixture energy. */
  double residual_ratio() const;
  bool silent() const { return silent_; }

 private:
  StemJob() = default;
  bool BuildSegment(size_t index);
  void MixAt(size_t frame, const float* input);
  void EstimateAt(size_t stem, size_t channel, size_t bin, size_t frame,
                  float* const* outputs, double* re, double* im) const;

  JobConfig config_;
  size_t length_ = 0;
  size_t samples_ = 0;  // STFT segment length (frames-1)*hop
  size_t useful_ = 0;   // real audio per chunk
  std::vector<size_t> starts_;
  std::vector<bool> consumed_;
  float* dry_[kStemChannels] = {nullptr, nullptr};
  float* acc_[3][kStemChannels] = {{nullptr, nullptr}, {nullptr, nullptr}, {nullptr, nullptr}};
  double mean_ = 0.0;
  double std_ = 1.0;
  bool silent_ = false;
  std::unique_ptr<Stft> stft_;
  std::vector<float> envelope_;
  std::vector<float> segment_[kStemChannels];
  size_t segment_valid_ = 0;
  long segment_index_ = -1;
  std::vector<float> mix_re_[kStemChannels];
  std::vector<float> mix_im_[kStemChannels];
  std::vector<float> weights_;
  double residual_num_ = 0.0;
  double residual_den_ = 0.0;
};

}  // namespace bitchord::stems
