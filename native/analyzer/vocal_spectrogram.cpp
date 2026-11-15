/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard), whose native STFT
 * front end this file is adapted from almost unchanged.
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

#include "vocal_spectrogram.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

namespace bitchord::smart {
namespace {

constexpr double kPi = 3.14159265358979323846;

// Unnormalized in-place radix-2 FFT. kVocalSpectrogramFft is a power of two,
// so no generic padding is performed. Private to this file, matching the
// existing convention (tempo_analysis.cpp and mel_spectrogram.cpp each carry
// their own copy rather than sharing one across translation units).
void Fft(std::vector<std::complex<double>>& values) {
  const size_t size = values.size();
  for (size_t index = 1, swapped = 0; index < size; ++index) {
    size_t bit = size >> 1;
    for (; swapped & bit; bit >>= 1) swapped ^= bit;
    swapped ^= bit;
    if (index < swapped) std::swap(values[index], values[swapped]);
  }
  for (size_t length = 2; length <= size; length <<= 1) {
    const std::complex<double> root = std::polar(1.0, -2.0 * kPi / length);
    for (size_t start = 0; start < size; start += length) {
      std::complex<double> weight(1, 0);
      for (size_t offset = 0; offset < length / 2; ++offset) {
        const auto even = values[start + offset];
        const auto odd = values[start + offset + length / 2] * weight;
        values[start + offset] = even + odd;
        values[start + offset + length / 2] = even - odd;
        weight *= root;
      }
    }
  }
}

// Periodic Hann, matching torch.hann_window(periodic=True) -- the divisor is
// the window length, not length - 1. transforms.py's TorchSTFT uses this via
// `nn.Parameter(torch.hann_window(n_fft))`.
std::vector<double> HannWindow(size_t size) {
  std::vector<double> window(size);
  for (size_t index = 0; index < size; ++index) {
    window[index] = 0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(index) / size);
  }
  return window;
}

}  // namespace

VocalSpectrogram ComputeVocalSpectrogram(
  const std::vector<std::vector<float>>& channels,
  double sample_rate
) {
  VocalSpectrogram result;
  if (std::abs(sample_rate - kVocalSpectrogramSampleRate) > 1.0) return result;
  if (channels.size() != kVocalSpectrogramChannels) return result;
  const size_t input_length = channels.front().size();
  if (channels[1].size() != input_length) return result;

  // torch.stft(center=True, pad_mode="reflect") centres frame f on sample
  // f * hop, matching the mel front end's identical padding.
  const size_t pad = kVocalSpectrogramFft / 2;
  if (input_length <= pad + 1) return result;

  const auto window = HannWindow(kVocalSpectrogramFft);
  std::vector<std::vector<float>> padded(kVocalSpectrogramChannels);
  for (size_t channel = 0; channel < kVocalSpectrogramChannels; ++channel) {
    auto& out = padded[channel];
    const auto& in = channels[channel];
    out.reserve(input_length + 2 * pad);
    for (size_t index = pad; index >= 1; --index) out.push_back(in[index]);
    out.insert(out.end(), in.begin(), in.end());
    for (size_t index = 1; index <= pad; ++index) out.push_back(in[input_length - 1 - index]);
  }

  const size_t padded_length = padded.front().size();
  if (padded_length < kVocalSpectrogramFft) return result;
  const size_t frames = (padded_length - kVocalSpectrogramFft) / kVocalSpectrogramHop + 1;

  result.frames = frames;
  result.values.assign(kVocalSpectrogramChannels * frames * kVocalSpectrogramBins, 0.0f);

  std::vector<std::complex<double>> spectrum(kVocalSpectrogramFft);
  for (size_t channel = 0; channel < kVocalSpectrogramChannels; ++channel) {
    const auto& source = padded[channel];
    for (size_t frame = 0; frame < frames; ++frame) {
      const size_t start = frame * kVocalSpectrogramHop;
      for (size_t index = 0; index < kVocalSpectrogramFft; ++index) {
        spectrum[index] = std::complex<double>(source[start + index] * window[index], 0.0);
      }
      Fft(spectrum);

      // torch.stft is called with normalized=False: the raw DFT magnitude,
      // with no additional 1/sqrt(win_length) scaling. That is the one
      // difference from the Beat This mel front end's `normalized=True`
      // convention, and getting it backwards would feed the model a
      // spectrogram scaled by sqrt(4096) ~= 64x too loud.
      //
      // Written bin-major (stride `frames` apart) rather than contiguously,
      // to land directly in the [channel][bin][frame] layout the ONNX tensor
      // needs; the FFT still produces one whole frame's bins at a time, only
      // where each one is stored differs.
      const size_t channel_base = channel * kVocalSpectrogramBins * frames;
      for (size_t bin = 0; bin < kVocalSpectrogramBins; ++bin) {
        result.values[channel_base + bin * frames + frame] =
          static_cast<float>(std::abs(spectrum[bin]));
      }
    }
  }
  return result;
}

bool SeparateVocalWindow(
  const std::vector<float>& left,
  const std::vector<float>& right,
  double sample_rate,
  const std::vector<float>& target_mag_bin_major,
  size_t target_frames,
  bool keep_vocals,
  std::vector<float>& out_left,
  std::vector<float>& out_right
) {
  out_left.clear();
  out_right.clear();
  if (std::abs(sample_rate - kVocalSpectrogramSampleRate) > 1.0) return false;
  const size_t input_length = left.size();
  if (right.size() != input_length) return false;
  if (target_frames == 0) return false;
  if (target_mag_bin_major.size() != kVocalSpectrogramChannels * kVocalSpectrogramBins * target_frames) return false;
  const size_t pad = kVocalSpectrogramFft / 2;
  if (input_length <= pad + 1) return false;
  const auto window = HannWindow(kVocalSpectrogramFft);
  const std::vector<float> inputs[2] = {left, right};
  std::vector<float> outputs[2];
  outputs[0].assign(input_length, 0.0f);
  outputs[1].assign(input_length, 0.0f);
  std::vector<std::vector<double>> ola(2);
  std::vector<std::vector<double>> win_sq(2);
  for (size_t c = 0; c < 2; ++c) {
    ola[c].assign(input_length + 2 * pad, 0.0);
    win_sq[c].assign(input_length + 2 * pad, 0.0);
  }
  std::vector<std::vector<float>> padded(kVocalSpectrogramChannels);
  for (size_t channel = 0; channel < kVocalSpectrogramChannels; ++channel) {
    auto& out = padded[channel];
    const auto& in = inputs[channel];
    out.reserve(input_length + 2 * pad);
    for (size_t index = pad; index >= 1; --index) out.push_back(in[index]);
    out.insert(out.end(), in.begin(), in.end());
    for (size_t index = 1; index <= pad; ++index) out.push_back(in[input_length - 1 - index]);
  }
  const size_t padded_length = padded.front().size();
  if (padded_length < kVocalSpectrogramFft) return false;
  const size_t frames = (padded_length - kVocalSpectrogramFft) / kVocalSpectrogramHop + 1;
  const size_t capped = std::min(frames, target_frames);
  const size_t n = kVocalSpectrogramFft;
  const size_t half = n / 2;
  std::vector<std::complex<double>> spectrum(n);
  for (size_t channel = 0; channel < kVocalSpectrogramChannels; ++channel) {
    const auto& source = padded[channel];
    for (size_t frame = 0; frame < capped; ++frame) {
      const size_t start = frame * kVocalSpectrogramHop;
      for (size_t index = 0; index < n; ++index) {
        spectrum[index] = std::complex<double>(source[start + index] * window[index], 0.0);
      }
      Fft(spectrum);
      for (size_t bin = 0; bin < kVocalSpectrogramBins; ++bin) {
        const double mix_mag = std::abs(spectrum[bin]);
        const double voc_mag = static_cast<double>(
          target_mag_bin_major[(channel * kVocalSpectrogramBins + bin) * target_frames + frame]);
        double r = 0.0;
        if (mix_mag > 1e-6) {
          r = voc_mag / mix_mag;
          r = std::clamp(r, 0.0, 1.0);
        }
        const double w = (r * r) / (r * r + (1.0 - r) * (1.0 - r) + 1e-8);
        const double mask = keep_vocals ? w : (1.0 - w);
        spectrum[bin] *= mask;
      }
      for (size_t bin = 1; bin < half; ++bin) {
        spectrum[n - bin] = std::conj(spectrum[bin]);
      }
      spectrum[0] = std::complex<double>(spectrum[0].real(), 0.0);
      spectrum[half] = std::complex<double>(spectrum[half].real(), 0.0);
      for (auto& v : spectrum) v = std::conj(v);
      Fft(spectrum);
      for (auto& v : spectrum) v = std::conj(v) / static_cast<double>(n);
      for (size_t index = 0; index < n; ++index) {
        const size_t pos = start + index;
        ola[channel][pos] += spectrum[index].real() * window[index];
        win_sq[channel][pos] += window[index] * window[index];
      }
    }
  }
  for (size_t channel = 0; channel < 2; ++channel) {
    std::vector<float>& dst = (channel == 0) ? out_left : out_right;
    for (size_t index = 0; index < input_length; ++index) {
      const double denom = std::max(1e-6, win_sq[channel][pad + index]);
      dst[index] = static_cast<float>(ola[channel][pad + index] / denom);
    }
  }
  return true;
}

}  // namespace bitchord::smart
