// CUE-only diagnostics: use BitChord's actual resampler and mel front end,
// rather than a librosa/torchaudio approximation. Not part of the APK.
#include "mel_spectrogram.h"
#include "resampler.h"

#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

int main(int argc, char** argv) {
  try {
    if (argc != 4) throw std::runtime_error("usage: mel-probe mono.f32 input-rate mel.f32");
    std::ifstream input(argv[1], std::ios::binary | std::ios::ate);
    if (!input) throw std::runtime_error("cannot open PCM");
    const auto bytes = input.tellg();
    if (bytes <= 0 || bytes % sizeof(float) != 0) throw std::runtime_error("malformed PCM");
    std::vector<float> pcm(static_cast<size_t>(bytes) / sizeof(float));
    input.seekg(0);
    input.read(reinterpret_cast<char*>(pcm.data()), bytes);
    if (!input) throw std::runtime_error("short PCM read");
    const double rate = std::stod(argv[2]);
    auto resampled = bitchord::smart::Resample(pcm, rate, bitchord::smart::kBeatSpectrogramSampleRate);
    auto mel = bitchord::smart::ComputeBeatSpectrogram(resampled, bitchord::smart::kBeatSpectrogramSampleRate);
    if (mel.frames == 0) throw std::runtime_error("empty mel");
    std::ofstream output(argv[3], std::ios::binary);
    output.write(reinterpret_cast<const char*>(mel.values.data()), mel.values.size() * sizeof(float));
    if (!output) throw std::runtime_error("mel write failed");
    std::cout << "{\"pcm_samples\":" << pcm.size()
              << ",\"resampled_samples\":" << resampled.size()
              << ",\"frames\":" << mel.frames << ",\"mels\":" << bitchord::smart::kBeatSpectrogramMels
              << ",\"sample_rate\":22050,\"hop\":441,\"fps\":50}\n";
    return 0;
  } catch (const std::exception& error) {
    std::cerr << error.what() << '\n';
    return 1;
  }
}
