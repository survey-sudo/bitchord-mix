// BitChord loop-mix renderer A/B harness (standalone, off-device).
//
// Builds the exact `bitchord::loopmix::Renderer` (native/loopmix/loop_mix_renderer.cpp)
// into a host binary for blind/objective stem-duck comparison without Android.
// Dependency-free except for the renderer itself (Signalsmith Stretch, header-only).
//
// Host build (run from the repo root, e.g. /home/hfzhkn/Bitchord/work/bitchord-mix):
//   g++ -std=c++17 -O2 -I native -I native/third_party native/loopmix/loop_mix_renderer.cpp native/loopmix/loop_mix_renderer_ab_main.cpp -o /tmp/loopmix_ab
//   /tmp/loopmix_ab --selftest
//
// Modes:
//   --selftest (default with no args):
//     Synthesizes deterministic 48 kHz outgoing/incoming regions (instrumental
//     bed + vocal sine) and renders them through Renderer::prepare()/render()
//     in duck-off / fixed-0.15 / duck-gain(0.18) / duck-gain(0.60) / null-clash
//     modes, checking overlap RMS ordering and exit-gate violations.
//   --reverb-stop-probe:
//     Production-faithful vc77 reverb-stop regression probe (OUT deck only).
//     Recreates LoopMixSession.kt's reverbWash envelope
//     (reverbSend [releaseAt-beatSec, releaseAt, outStop] -> [0, 0.65, 0],
//     KIND_GAIN MixGain fallPoints to 0 at releaseAt,
//     outStop = releaseAt + 0.05 + echoHold(0) + exitTail) with a broadband
//     transient PCM (LCG white noise, no sine) and no artificial silence gap
//     (send reaches 0 exactly at stopAt; gain is 0 from releaseAt while the
//     native wet is still fed, because renderDeck adds revL outside gain).
//     Measures late wet RMS and the single-sample discontinuity at the stop
//     boundary and prints the numbers. Exit 1 if the hard cut is present.
//     Objective numbers only; a PASS here does not prove subjective grace.
//   File A/B mode:
//     /tmp/loopmix_ab --out-bed out_bed.wav --out-voc out_voc.wav
//       --in-bed in_bed.wav --in-voc in_voc.wav --out-prefix /tmp/ab
//       [--clashE 0.18] [--csv gain.csv]
//     Renders <prefix>_off.wav, <prefix>_fixed015.wav, <prefix>_duckgain.wav
//     (PCM16 stereo 48 kHz, mix [0, 6) s) and prints overlap RMS deltas.
//
// Duck model (exactly LoopMixSession.kt, STEM_DUCK_FLOOR = 0.15f):
//   outgoing (falling cosine, dryMinusVocal):
//     u = (i - fadeStart) / (fadeEnd - fadeStart), c = 0.5 * (1 + cos(pi * u))
//     g = floor + (1 - floor) * c,  out = bed + g * voc
//   StemClash::duckGainFor equivalent with the session floor:
//     duckGain = clamp(0.05 / clashE, 0.15, 1.0), 1.0 when clashE <= 0.05
//     (clashE = 0.18 -> ~0.278, clashE = 0.60 -> 0.15)
//   The incoming vocal is left undocked (the outgoing side is always ducked;
//   the incoming side is conditional in production). Ducking is baked into the
//   deck PCM, exactly as LoopMixSession bakes `inst + g * voc`, and the render
//   still runs the full Renderer pipeline. Deck.gain automation stays empty.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.

#include "loopmix/loop_mix_renderer.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iostream>
#include <limits>
#include <string>
#include <vector>

using bitchord::loopmix::Renderer;

namespace {

constexpr int kSampleRate = 48000;
constexpr double kPi = 3.14159265358979323846;
constexpr float kDuckFloor = 0.15f;
constexpr double kDuckTarget = 0.05;
constexpr double kMixEndSec = 6.0;
constexpr double kFadeStartSec = 2.0;
constexpr double kFadeEndSec = 3.5;
constexpr double kRmsStartSec = 2.5;
constexpr double kRmsEndSec = 5.5;
constexpr double kIncomingHoldSec = 2.0;

// ---------------------------------------------------------------------------
// Duck math (LoopMixSession.kt lines 352-419 + StemClash::duckGainFor).
// ---------------------------------------------------------------------------

// StemClash::duckGainFor(meanClash, target=0.05, floor=STEM_DUCK_FLOOR=0.15).
// Returns 1.0 when clash is at/below target or not finite.
double duckGainFor(double clashE) {
  if (!std::isfinite(clashE) || clashE <= kDuckTarget) return 1.0;
  const double ratio = kDuckTarget / clashE;
  return std::clamp(ratio, 0.15, 1.0);
}

// Outgoing falling-cosine gain at region sample [idx].
// LoopMixSession.kt lines 368-370: c = 0.5 * (1 + cos(pi * u)).
float duckGainCosine(int64_t idx, int64_t fadeStart, int64_t fadeEnd, float floorGain) {
  if (fadeEnd <= fadeStart) return floorGain;
  if (idx < fadeStart) return 1.0f;
  if (idx >= fadeEnd) return floorGain;
  const double u = static_cast<double>(idx - fadeStart) / static_cast<double>(fadeEnd - fadeStart);
  const double c = 0.5 * (1.0 + std::cos(kPi * u));
  return static_cast<float>(static_cast<double>(floorGain) +
                            (1.0 - static_cast<double>(floorGain)) * c);
}

// Linear CSV gain at track time [t] (seconds). Empty -> 1.0.
float csvGainAt(const std::vector<double>& times, const std::vector<float>& vals, double t) {
  if (times.empty() || vals.empty()) return 1.0f;
  if (t <= times.front()) return std::clamp(vals.front(), 0.0f, 1.0f);
  if (t >= times.back()) return std::clamp(vals.back(), 0.0f, 1.0f);
  size_t lo = 0, hi = times.size() - 1;
  // times are sorted ascending; binary search for the bracketing pair.
  while (hi - lo > 1) {
    const size_t mid = lo + (hi - lo) / 2;
    if (times[mid] <= t) lo = mid; else hi = mid;
  }
  const double t0 = times[lo], t1 = times[hi];
  const float v0 = vals[lo], v1 = vals[hi];
  if (!(t1 > t0)) return std::clamp(v0, 0.0f, 1.0f);
  const double f = (t - t0) / (t1 - t0);
  return std::clamp(static_cast<float>(v0 + (v1 - v0) * f), 0.0f, 1.0f);
}

// out = bed + g * voc, g from cosine [floor] or from CSV (track-time).
// When csvTimes is empty and floor == 1, this is a plain bed+voc sum, which
// keeps duck-off and null-clash bit-identical.
void applyDuck(
    const std::vector<float>& bedL, const std::vector<float>& bedR,
    const std::vector<float>& vocL, const std::vector<float>& vocR,
    float floorGain, int64_t fadeStart, int64_t fadeEnd,
    const std::vector<double>& csvTimes, const std::vector<float>& csvVals,
    std::vector<float>& outL, std::vector<float>& outR) {
  const size_t n = bedL.size();
  outL.assign(n, 0.0f);
  outR.assign(n, 0.0f);
  const bool useCsv = !csvTimes.empty();
  if (!useCsv && floorGain >= 1.0f) {
    for (size_t i = 0; i < n; ++i) {
      outL[i] = bedL[i] + vocL[i];
      outR[i] = bedR[i] + vocR[i];
    }
    return;
  }
  for (size_t i = 0; i < n; ++i) {
    const int64_t idx = static_cast<int64_t>(i);
    float g = 1.0f;
    if (useCsv) {
      g = csvGainAt(csvTimes, csvVals, static_cast<double>(i) / kSampleRate);
    } else {
      g = duckGainCosine(idx, fadeStart, fadeEnd, floorGain);
    }
    outL[i] = bedL[i] + g * vocL[i];
    outR[i] = bedR[i] + g * vocR[i];
  }
}

// ---------------------------------------------------------------------------
// WAV IO: PCM16 / IEEE float32, mono/stereo, resampled to 48 kHz.
// Writer: PCM16 stereo 48 kHz. No external dependencies.
// ---------------------------------------------------------------------------

inline uint16_t readU16(const uint8_t* p) {
  return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
inline uint32_t readU32(const uint8_t* p) {
  return static_cast<uint32_t>(p[0]) |
         (static_cast<uint32_t>(p[1]) << 8) |
         (static_cast<uint32_t>(p[2]) << 16) |
         (static_cast<uint32_t>(p[3]) << 24);
}

bool readWavAtRate(const std::string& path, std::vector<float>& outL,
                   std::vector<float>& outR, int targetRate, std::string& err) {
  std::FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) {
    err = "cannot open " + path;
    return false;
  }
  std::fseek(f, 0, SEEK_END);
  const long fileSize = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  if (fileSize <= 0) {
    std::fclose(f);
    err = "empty file " + path;
    return false;
  }
  std::vector<uint8_t> bytes(static_cast<size_t>(fileSize));
  if (std::fread(bytes.data(), 1, bytes.size(), f) != bytes.size()) {
    std::fclose(f);
    err = "short read " + path;
    return false;
  }
  std::fclose(f);
  if (bytes.size() < 12 || std::memcmp(bytes.data(), "RIFF", 4) != 0 ||
      std::memcmp(bytes.data() + 8, "WAVE", 4) != 0) {
    err = "not a WAVE file: " + path;
    return false;
  }

  int channels = 0;
  uint32_t srcRate = 0;
  int bits = 0;
  int audioFormat = 0;
  size_t dataOff = 0;
  size_t dataSize = 0;
  bool haveFmt = false, haveData = false;

  size_t off = 12;
  while (off + 8 <= bytes.size()) {
    const uint8_t* hdr = bytes.data() + off;
    const uint32_t chunkSize = readU32(hdr + 4);
    const size_t chunkData = off + 8;
    if (chunkData + chunkSize > bytes.size()) {
      err = "truncated chunk in " + path;
      return false;
    }
    if (std::memcmp(hdr, "fmt ", 4) == 0) {
      if (chunkSize < 16) {
        err = "short fmt chunk: " + path;
        return false;
      }
      const uint8_t* fmt = bytes.data() + chunkData;
      audioFormat = readU16(fmt + 0);
      channels = readU16(fmt + 2);
      srcRate = readU32(fmt + 4);
      bits = readU16(fmt + 14);
      if (audioFormat == 0xFFFE) {
        // WAVE_FORMAT_EXTENSIBLE: subformat tag at offset 24, valid bits at 18.
        if (chunkSize < 40) {
          err = "short extensible fmt chunk: " + path;
          return false;
        }
        audioFormat = readU16(fmt + 24);
        const int validBits = readU16(fmt + 18);
        if (validBits != 0) bits = validBits;
      }
      haveFmt = true;
    } else if (std::memcmp(hdr, "data", 4) == 0) {
      dataOff = chunkData;
      dataSize = chunkSize;
      haveData = true;
    }
    off = chunkData + chunkSize + (chunkSize & 1u ? 1u : 0u);
  }
  if (!haveFmt || !haveData) {
    err = "missing fmt/data chunk: " + path;
    return false;
  }
  if (channels != 1 && channels != 2) {
    err = "unsupported channel count " + std::to_string(channels) + ": " + path;
    return false;
  }
  if (srcRate == 0) {
    err = "bad sample rate: " + path;
    return false;
  }
  const bool isPcm16 = (audioFormat == 1 && bits == 16);
  const bool isFloat32 = (audioFormat == 3 && bits == 32);
  if (!isPcm16 && !isFloat32) {
    err = "unsupported WAV format (want PCM16 or float32, got format=" +
          std::to_string(audioFormat) + " bits=" + std::to_string(bits) + "): " + path;
    return false;
  }
  const size_t bytesPerSample = bits / 8;
  const size_t blockAlign = static_cast<size_t>(channels) * bytesPerSample;
  if (blockAlign == 0 || dataSize % blockAlign != 0) {
    err = "bad data size: " + path;
    return false;
  }
  const size_t srcFrames = dataSize / blockAlign;
  const uint8_t* data = bytes.data() + dataOff;

  std::vector<float> srcL(srcFrames), srcR(srcFrames);
  for (size_t i = 0; i < srcFrames; ++i) {
    const uint8_t* p = data + i * blockAlign;
    float l, r;
    if (isPcm16) {
      const int16_t s0 = static_cast<int16_t>(readU16(p));
      l = static_cast<float>(s0) / 32768.0f;
      if (channels == 2) {
        const int16_t s1 = static_cast<int16_t>(readU16(p + 2));
        r = static_cast<float>(s1) / 32768.0f;
      } else {
        r = l;
      }
    } else {
      // Host is little-endian on all supported targets (x86_64/arm64).
      const float f0 = *reinterpret_cast<const float*>(p);
      l = f0;
      if (channels == 2) {
        const float f1 = *reinterpret_cast<const float*>(p + 4);
        r = f1;
      } else {
        r = l;
      }
    }
    srcL[i] = l;
    srcR[i] = r;
  }

  if (srcRate == static_cast<uint32_t>(targetRate)) {
    outL = std::move(srcL);
    outR = std::move(srcR);
    return true;
  }
  if (srcFrames == 0) {
    outL.clear();
    outR.clear();
    return true;
  }
  const size_t outFrames =
      static_cast<size_t>(std::llround(static_cast<double>(srcFrames) *
                                       static_cast<double>(targetRate) /
                                       static_cast<double>(srcRate)));
  outL.assign(outFrames, 0.0f);
  outR.assign(outFrames, 0.0f);
  const double step = static_cast<double>(srcRate) / static_cast<double>(targetRate);
  for (size_t i = 0; i < outFrames; ++i) {
    const double pos = static_cast<double>(i) * step;
    const size_t i0 = std::min(srcFrames - 1, static_cast<size_t>(std::floor(pos)));
    const size_t i1 = std::min(srcFrames - 1, i0 + 1);
    const double frac = pos - static_cast<double>(i0);
    outL[i] = static_cast<float>(srcL[i0] * (1.0 - frac) + srcL[i1] * frac);
    outR[i] = static_cast<float>(srcR[i0] * (1.0 - frac) + srcR[i1] * frac);
  }
  return true;
}

bool readWav48k(const std::string& path, std::vector<float>& outL,
                std::vector<float>& outR, std::string& err) {
  return readWavAtRate(path, outL, outR, kSampleRate, err);
}

bool writeWavStereo16(const std::string& path, const std::vector<float>& inter,
                      size_t frames, int sampleRate, std::string& err) {
  if (inter.size() < frames * 2) {
    err = "short interleaved buffer for " + path;
    return false;
  }
  if (sampleRate <= 0) {
    err = "bad output sample rate";
    return false;
  }
  std::FILE* f = std::fopen(path.c_str(), "wb");
  if (f == nullptr) {
    err = "cannot open for write " + path;
    return false;
  }
  const uint32_t dataBytes = static_cast<uint32_t>(frames * 4);
  const uint32_t riffSize = 36u + dataBytes;
  auto w32 = [&](uint32_t v) {
    const uint8_t b[4] = {static_cast<uint8_t>(v & 0xffu),
                          static_cast<uint8_t>((v >> 8) & 0xffu),
                          static_cast<uint8_t>((v >> 16) & 0xffu),
                          static_cast<uint8_t>((v >> 24) & 0xffu)};
    std::fwrite(b, 1, 4, f);
  };
  auto w16 = [&](uint16_t v) {
    const uint8_t b[2] = {static_cast<uint8_t>(v & 0xffu),
                          static_cast<uint8_t>((v >> 8) & 0xffu)};
    std::fwrite(b, 1, 2, f);
  };
  std::fwrite("RIFF", 1, 4, f);
  w32(riffSize);
  std::fwrite("WAVE", 1, 4, f);
  std::fwrite("fmt ", 1, 4, f);
  w32(16u);
  w16(1);  // PCM
  w16(2);  // stereo
  w32(static_cast<uint32_t>(sampleRate));
  w32(static_cast<uint32_t>(sampleRate) * 4u);  // byte rate
  w16(4);   // block align
  w16(16);  // bits
  std::fwrite("data", 1, 4, f);
  w32(dataBytes);
  for (size_t i = 0; i < frames; ++i) {
    for (int c = 0; c < 2; ++c) {
      const float x = std::clamp(inter[i * 2 + static_cast<size_t>(c)], -1.0f, 1.0f);
      int s = static_cast<int>(std::lround(static_cast<double>(x) * 32767.0));
      s = std::clamp(s, -32768, 32767);
      w16(static_cast<uint16_t>(static_cast<int16_t>(s)));
    }
  }
  std::fclose(f);
  return true;
}

bool writeWav48kStereo16(const std::string& path, const std::vector<float>& inter,
                         size_t frames, std::string& err) {
  return writeWavStereo16(path, inter, frames, kSampleRate, err);
}

// time_sec,gain CSV for the baked vocal envelope (track time in seconds).
bool readGainCsv(const std::string& path, std::vector<double>& times,
                 std::vector<float>& vals, std::string& err) {
  std::ifstream in(path);
  if (!in) {
    err = "cannot open CSV " + path;
    return false;
  }
  std::string line;
  bool any = false;
  while (std::getline(in, line)) {
    // Trim CR/spaces.
    while (!line.empty() && (line.back() == '\r' || line.back() == '\n' ||
                             line.back() == ' ' || line.back() == '\t')) {
      line.pop_back();
    }
    if (line.empty()) continue;
    const size_t comma = line.find(',');
    if (comma == std::string::npos) {
      err = "bad CSV line (want time_sec,gain): " + line;
      return false;
    }
    const std::string a = line.substr(0, comma);
    const std::string b = line.substr(comma + 1);
    auto trim = [](std::string s) {
      size_t b0 = 0, e0 = s.size();
      while (b0 < e0 && std::isspace(static_cast<unsigned char>(s[b0]))) ++b0;
      while (e0 > b0 && std::isspace(static_cast<unsigned char>(s[e0 - 1]))) --e0;
      return s.substr(b0, e0 - b0);
    };
    const std::string ta = trim(a), tb = trim(b);
    // Skip a header line such as "time_sec,gain".
    if (!any && (!ta.empty() && !std::isdigit(static_cast<unsigned char>(ta[0])) &&
                 ta[0] != '-' && ta[0] != '.' && ta[0] != '+')) {
      continue;
    }
    try {
      double t = std::stod(ta);
      double v = std::stod(tb);
      if (!std::isfinite(t) || !std::isfinite(v)) {
        err = "non-finite CSV values: " + line;
        return false;
      }
      times.push_back(t);
      vals.push_back(static_cast<float>(v));
      any = true;
    } catch (const std::exception&) {
      err = "bad CSV numbers: " + line;
      return false;
    }
  }
  if (!any || times.size() != vals.size()) {
    err = "empty or mismatched CSV " + path;
    return false;
  }
  // Sort ascending by time (stable for equal times).
  std::vector<size_t> order(times.size());
  for (size_t i = 0; i < order.size(); ++i) order[i] = i;
  std::sort(order.begin(), order.end(),
            [&](size_t i, size_t j) { return times[i] < times[j]; });
  std::vector<double> st(times.size());
  std::vector<float> sv(vals.size());
  for (size_t i = 0; i < order.size(); ++i) {
    st[i] = times[order[i]];
    sv[i] = vals[order[i]];
  }
  times = std::move(st);
  vals = std::move(sv);
  return true;
}

// ---------------------------------------------------------------------------
// Renderer plumbing shared by both modes.
// ---------------------------------------------------------------------------

struct RenderOut {
  std::vector<float> interleaved;  // from firstFrame, frames*2 samples
  int64_t firstFrame = 0;
  double rmsDb = std::numeric_limits<double>::quiet_NaN();
  int violations = -1;
};

RenderOut renderMix(const std::vector<float>& outL, const std::vector<float>& outR,
                    const std::vector<float>& inL, const std::vector<float>& inR,
                    bool highQuality, double rmsStartSec, double rmsEndSec) {
  Renderer renderer(kSampleRate);
  renderer.setHighQuality(highQuality);
  const int64_t sr = kSampleRate;
  const int64_t outLen = static_cast<int64_t>(outL.size());
  const int64_t inLen = static_cast<int64_t>(inL.size());
  const int64_t outEndPos =
      std::min<int64_t>(static_cast<int64_t>(kMixEndSec * sr),
                        outLen > 0 ? outLen - 1 : 0);
  const int64_t inEndPos =
      std::min<int64_t>(static_cast<int64_t>((kMixEndSec - kIncomingHoldSec) * sr),
                        inLen > 0 ? inLen - 1 : 0);

  auto& d0 = renderer.deck(0);
  d0.left = outL;
  d0.right = outR;
  d0.regionStart = 0.0;
  d0.loopStart = 0;
  d0.loopEnd = 0;
  d0.releaseAt = 1e18;
  d0.knotTimes = {0.0, static_cast<double>(kMixEndSec * sr)};
  d0.knotPositions = {0.0, static_cast<double>(outEndPos)};
  d0.runOn = false;
  d0.stopAt = 1e18;
  d0.preroll = 0.4;
  d0.level = 1.0f;

  auto& d1 = renderer.deck(1);
  d1.left = inL;
  d1.right = inR;
  d1.regionStart = 0.0;
  d1.loopStart = 0;
  d1.loopEnd = 0;
  d1.releaseAt = 1e18;
  d1.knotTimes = {0.0,
                  static_cast<double>(kIncomingHoldSec * sr),
                  static_cast<double>(kMixEndSec * sr)};
  d1.knotPositions = {0.0, 0.0, static_cast<double>(inEndPos)};
  d1.runOn = true;
  d1.stopAt = 1e18;
  d1.preroll = 0.4;
  d1.level = 1.0f;

  renderer.prepare();
  RenderOut res;
  res.firstFrame = renderer.firstFrame();
  const int64_t endFrame = static_cast<int64_t>(kMixEndSec * sr);
  const int64_t total = endFrame - res.firstFrame;
  res.interleaved.assign(static_cast<size_t>(total) * 2, 0.0f);
  const int64_t kBlock = 4096;
  int64_t done = 0;
  while (done < total) {
    const int64_t chunk = std::min<int64_t>(kBlock, total - done);
    renderer.render(res.firstFrame + done,
                    res.interleaved.data() + static_cast<size_t>(done) * 2,
                    static_cast<int>(chunk));
    done += chunk;
  }
  res.violations = renderer.exitGateViolations();

  int64_t rmsS = static_cast<int64_t>(std::llround(rmsStartSec * sr));
  int64_t rmsE = static_cast<int64_t>(std::llround(rmsEndSec * sr));
  rmsS = std::clamp<int64_t>(rmsS, res.firstFrame, endFrame);
  rmsE = std::clamp<int64_t>(rmsE, rmsS, endFrame);
  double sumSq = 0.0;
  int64_t count = 0;
  for (int64_t frame = rmsS; frame < rmsE; ++frame) {
    const size_t idx = static_cast<size_t>(frame - res.firstFrame) * 2;
    const double l = res.interleaved[idx];
    const double r = res.interleaved[idx + 1];
    sumSq += l * l + r * r;
    ++count;
  }
  const double rms = count > 0 ? std::sqrt(sumSq / (static_cast<double>(count) * 2.0)) :
                                 std::numeric_limits<double>::quiet_NaN();
  res.rmsDb = (rms > 0.0 && std::isfinite(rms)) ? 20.0 * std::log10(rms) : -240.0;
  return res;
}

// Pad shorter vectors with zeros to [n].
void padTo(std::vector<float>& v, size_t n) {
  if (v.size() < n) v.resize(n, 0.0f);
}

// Deterministic selftest synthesis: pure sines, no RNG.
void synthTones(std::vector<float>& outBedL, std::vector<float>& outBedR,
                std::vector<float>& outVocL, std::vector<float>& outVocR,
                std::vector<float>& inBedL, std::vector<float>& inBedR,
                std::vector<float>& inVocL, std::vector<float>& inVocR,
                size_t frames) {
  outBedL.assign(frames, 0.0f);
  outBedR.assign(frames, 0.0f);
  outVocL.assign(frames, 0.0f);
  outVocR.assign(frames, 0.0f);
  inBedL.assign(frames, 0.0f);
  inBedR.assign(frames, 0.0f);
  inVocL.assign(frames, 0.0f);
  inVocR.assign(frames, 0.0f);
  const double sr = static_cast<double>(kSampleRate);
  for (size_t i = 0; i < frames; ++i) {
    const double t = static_cast<double>(i) / sr;
    const double ph = 2.0 * kPi * t;
    outBedL[i] = static_cast<float>(0.10 * std::sin(ph * 110.0));
    outBedR[i] = static_cast<float>(0.09 * std::sin(ph * 110.0));
    outVocL[i] = static_cast<float>(0.30 * std::sin(ph * 440.0));
    outVocR[i] = static_cast<float>(0.27 * std::sin(ph * 440.0));
    inBedL[i] = static_cast<float>(0.10 * std::sin(ph * 165.0));
    inBedR[i] = static_cast<float>(0.09 * std::sin(ph * 165.0));
    inVocL[i] = static_cast<float>(0.30 * std::sin(ph * 660.0));
    inVocR[i] = static_cast<float>(0.27 * std::sin(ph * 660.0));
  }
}

// ---------------------------------------------------------------------------
// --selftest
// ---------------------------------------------------------------------------

int runSelfTest() {
  const size_t regionFrames = static_cast<size_t>(8.0 * kSampleRate);
  std::vector<float> outBedL, outBedR, outVocL, outVocR;
  std::vector<float> inBedL, inBedR, inVocL, inVocR;
  synthTones(outBedL, outBedR, outVocL, outVocR, inBedL, inBedR, inVocL, inVocR,
             regionFrames);

  // Incoming never ducked in these modes: bed + voc at 1.0.
  std::vector<float> inMixL(regionFrames), inMixR(regionFrames);
  for (size_t i = 0; i < regionFrames; ++i) {
    inMixL[i] = inBedL[i] + inVocL[i];
    inMixR[i] = inBedR[i] + inVocR[i];
  }

  const int64_t fadeStart = static_cast<int64_t>(kFadeStartSec * kSampleRate);
  const int64_t fadeEnd = static_cast<int64_t>(kFadeEndSec * kSampleRate);
  const float floorFixed = kDuckFloor;
  const float floor018 = static_cast<float>(duckGainFor(0.18));
  const float floor060 = static_cast<float>(duckGainFor(0.60));
  const float floorNull = static_cast<float>(duckGainFor(0.02));

  struct Mode {
    const char* name;
    float floor;
    const char* note;
  };
  const Mode modes[] = {
      {"duck-off", 1.0f, "vocal gain 1.0 (dry)"},
      {"fixed-0.15", floorFixed, "cosine to 0.15"},
      {"duck-gain clashE=0.18", floor018, "cosine to duckGainFor(0.18)"},
      {"duck-gain clashE=0.60", floor060, "cosine to duckGainFor(0.60)"},
      {"null-clash", floorNull, "clashE below target -> 1.0"},
  };
  constexpr size_t kModes = sizeof(modes) / sizeof(modes[0]);

  std::vector<double> rms(kModes, 0.0);
  std::vector<int> viol(kModes, 0);
  std::vector<int64_t> firstFrames(kModes, 0);
  std::vector<std::vector<float>> outs(kModes);
  bool ok = true;
  for (size_t m = 0; m < kModes; ++m) {
    const std::vector<double> emptyTimes;
    const std::vector<float> emptyVals;
    std::vector<float> outMixL, outMixR;
    applyDuck(outBedL, outBedR, outVocL, outVocR, modes[m].floor, fadeStart,
              fadeEnd, emptyTimes, emptyVals, outMixL, outMixR);
    RenderOut res = renderMix(outMixL, outMixR, inMixL, inMixR, /*highQuality=*/false,
                              kRmsStartSec, kRmsEndSec);
    rms[m] = res.rmsDb;
    viol[m] = res.violations;
    firstFrames[m] = res.firstFrame;
    std::printf("render %s floor=%.4f rms=%.3f dB firstFrame=%lld violations=%d (%s)\n",
                modes[m].name, static_cast<double>(modes[m].floor), res.rmsDb,
                static_cast<long long>(res.firstFrame), res.violations, modes[m].note);
  }

  auto fail = [&](const char* msg) {
    std::fprintf(stderr, "FAIL %s\n", msg);
    ok = false;
  };

  for (size_t m = 0; m < kModes; ++m) {
    if (viol[m] != 0) {
      fail("exitGateViolations() != 0");
      std::fprintf(stderr, "  mode=%s violations=%d\n", modes[m].name, viol[m]);
    } else {
      std::printf("ok exit-gate %s violations=0\n", modes[m].name);
    }
  }

  // Index map: 0 off, 1 fixed, 2 duck018, 3 duck060, 4 null.
  const double rmsOff = rms[0], rmsFixed = rms[1], rms018 = rms[2], rms060 = rms[3],
               rmsNull = rms[4];

  if (rmsFixed < rmsOff - 0.1) {
    std::printf("ok fixed-0.15 reduces overlap RMS: %.3f vs %.3f dB (delta %.3f dB)\n",
                rmsFixed, rmsOff, rmsFixed - rmsOff);
  } else {
    fail("fixed-0.15 did not reduce overlap RMS by >0.1 dB");
    std::fprintf(stderr, "  rmsOff=%.4f rmsFixed=%.4f delta=%.4f\n", rmsOff, rmsFixed,
                 rmsFixed - rmsOff);
  }

  if (rmsFixed < rms018 && rms018 < rmsOff) {
    std::printf("ok duck-gain(0.18) strictly between fixed and off: %.3f in (%.3f, %.3f) dB\n",
                rms018, rmsFixed, rmsOff);
  } else {
    fail("duck-gain(0.18) not strictly between fixed-0.15 and duck-off");
    std::fprintf(stderr, "  rmsFixed=%.4f rms018=%.4f rmsOff=%.4f\n", rmsFixed, rms018,
                 rmsOff);
  }

  if (std::abs(rms060 - rmsFixed) <= 0.01) {
    std::printf("ok duck-gain(0.60) matches fixed-0.15 within 0.01 dB: %.6f dB\n",
                rms060 - rmsFixed);
  } else {
    fail("duck-gain(0.60) does not match fixed-0.15 within 0.01 dB");
    std::fprintf(stderr, "  rms060=%.4f rmsFixed=%.4f delta=%.6f\n", rms060, rmsFixed,
                 rms060 - rmsFixed);
  }

  if (std::abs(rmsNull - rmsOff) <= 1e-6) {
    std::printf("ok null-clash matches duck-off within 1e-6 dB: %.6f dB\n",
                rmsNull - rmsOff);
  } else {
    fail("null-clash does not match duck-off within 1e-6 dB");
    std::fprintf(stderr, "  rmsNull=%.6f rmsOff=%.6f delta=%.6f\n", rmsNull, rmsOff,
                 rmsNull - rmsOff);
  }

  // Extra provenance checks (deterministic floors).
  if (std::abs(static_cast<double>(floor018) - 0.2777777777) < 1e-4) {
    std::printf("ok duckGainFor(0.18)=%.4f (~0.278)\n", static_cast<double>(floor018));
  } else {
    fail("duckGainFor(0.18) unexpected");
  }
  if (std::abs(static_cast<double>(floor060) - 0.15) < 1e-6) {
    std::printf("ok duckGainFor(0.60)=%.4f (floor 0.15)\n", static_cast<double>(floor060));
  } else {
    fail("duckGainFor(0.60) unexpected");
  }

  // -------------------------------------------------------------------------
  // vc77 reverb-tail verification (Renderer::setReverb + KIND_REVERB_SEND).
  // Deck 0 carries a sustained 440 Hz tone. The KIND_REVERB_SEND envelope
  // (native Deck::reverbSend) ramps 0 -> 0.65 -> 0 before stopAt, and the
  // KIND_GAIN envelope (native Deck::gain) fades smoothly to 0 before
  // stopAt. Rendering past stopAt must stay exit-gate clean, and the RMS
  // in the final 200 ms before stopAt must sit strictly below -40 dB
  // relative to the active wet tail, proving the reverb is inaudible by
  // stopAt and no hard-cut pop can occur there.
  // -------------------------------------------------------------------------
  {
    Renderer renderer(48000);
    renderer.setHighQuality(false);
    renderer.setReverb(0, 2.2f, 0.55f, 0.35f);

    std::vector<float> toneL(regionFrames), toneR(regionFrames);
    for (size_t i = 0; i < regionFrames; ++i) {
      const double t = static_cast<double>(i) / static_cast<double>(kSampleRate);
      const float v = static_cast<float>(0.30 * std::sin(2.0 * kPi * 440.0 * t));
      toneL[i] = v;
      toneR[i] = v * 0.9f;
    }

    const double stopAtSec = 5.0;
    auto& d0 = renderer.deck(0);
    d0.left = toneL;
    d0.right = toneR;
    d0.regionStart = 0.0;
    d0.loopStart = 0;
    d0.loopEnd = 0;
    d0.releaseAt = 1e18;
    const int64_t lastKnotFrame = static_cast<int64_t>(6.0 * kSampleRate);
    d0.knotTimes = {0.0, static_cast<double>(lastKnotFrame)};
    d0.knotPositions = {0.0, static_cast<double>(lastKnotFrame)};
    d0.runOn = false;
    d0.stopAt = stopAtSec;
    d0.preroll = 0.4;
    d0.level = 1.0f;
    // KIND_REVERB_SEND envelope (Deck::reverbSend): 0 -> 0.65 -> 0.
    d0.reverbSend.times = {0.0, 2.0, 3.0};
    d0.reverbSend.values = {0.0f, 0.65f, 0.0f};
    // KIND_GAIN envelope (Deck::gain): hold 1, fade smoothly to 0.
    d0.gain.times = {0.0, 3.0, 4.0};
    d0.gain.values = {1.0f, 1.0f, 0.0f};

    renderer.prepare();

    // Render past stopAt (firstFrame .. stopAt + 1.5 s).
    const int64_t tailFirst = renderer.firstFrame();
    const int64_t tailEnd =
        static_cast<int64_t>((stopAtSec + 1.5) * static_cast<double>(kSampleRate));
    const int64_t tailTotal = tailEnd - tailFirst;
    std::vector<float> tailOut(static_cast<size_t>(tailTotal) * 2, 0.0f);
    const int64_t kTailBlock = 4096;
    int64_t tailDone = 0;
    while (tailDone < tailTotal) {
      const int64_t tailChunk = std::min<int64_t>(kTailBlock, tailTotal - tailDone);
      renderer.render(tailFirst + tailDone,
                      tailOut.data() + static_cast<size_t>(tailDone) * 2,
                      static_cast<int>(tailChunk));
      tailDone += tailChunk;
    }

    if (renderer.exitGateViolations() != 0) {
      fail("reverb-tail exitGateViolations() != 0");
      std::fprintf(stderr, "  violations=%d\n", renderer.exitGateViolations());
    } else {
      std::printf("ok reverb-tail exit-gate violations=0\n");
    }

    auto rmsDbTail = [&](double s, double e) -> double {
      int64_t fs = static_cast<int64_t>(std::llround(s * static_cast<double>(kSampleRate)));
      int64_t fe = static_cast<int64_t>(std::llround(e * static_cast<double>(kSampleRate)));
      fs = std::clamp<int64_t>(fs, tailFirst, tailEnd);
      fe = std::clamp<int64_t>(fe, fs, tailEnd);
      double sumSq = 0.0;
      int64_t count = 0;
      for (int64_t frame = fs; frame < fe; ++frame) {
        const size_t idx = static_cast<size_t>(frame - tailFirst) * 2;
        const double l = tailOut[idx];
        const double r = tailOut[idx + 1];
        sumSq += l * l + r * r;
        ++count;
      }
      const double rms =
          count > 0 ? std::sqrt(sumSq / (static_cast<double>(count) * 2.0))
                    : std::numeric_limits<double>::quiet_NaN();
      return (rms > 0.0 && std::isfinite(rms)) ? 20.0 * std::log10(rms) : -240.0;
    };

    // Active wet tail: send peak window with gain 1.
    const double activeDb = rmsDbTail(1.5, 2.5);
    // Final 200 ms before stopAt.
    const double finalDb = rmsDbTail(stopAtSec - 0.2, stopAtSec);
    // Strictly below -40 dB relative to the active wet tail. This is the
    // code-level contract: activeDb - finalDb > 40 dB. No hard-cut pop.
    if (finalDb < activeDb - 40.0) {
      std::printf("ok reverb-tail final 200ms RMS %.3f dB is %.3f dB below "
                  "active wet tail %.3f dB (below -40 dB relative)\n",
                  finalDb, activeDb - finalDb, activeDb);
    } else {
      fail("reverb-tail final 200ms RMS not below -40 dB relative to active wet tail");
      std::fprintf(stderr, "  active=%.4f final=%.4f drop=%.4f (need >40 dB)\n",
                   activeDb, finalDb, activeDb - finalDb);
    }
  }

  if (ok) {
    std::printf("ok selftest passed (6 renders, firstFrame=%lld)\n",
                static_cast<long long>(firstFrames[0]));
    return 0;
  }
  return 1;
}

// ---------------------------------------------------------------------------
// --reverb-stop-probe (vc77 production-faithful regression probe).
//
// Why the --selftest reverb block above cannot see the zap: it uses
// reverbSend {0,2,3}->{0,0.65,0} with gain 0 at 4 s and stopAt 5 s, i.e. a
// 2.0 s silence gap between send=0 and stopAt (plus 1.0 s between gain=0 and
// stopAt). The reverb tail has long decayed by stopAt, so RMS in
// [stop-0.2, stop] is silent and the check passes on any smooth decay.
//
// Production (LoopMixSession.kt, non-echo reverbWash branch) is:
//   reverbSend = [open, releaseAt, outStop] -> [0, 0.65, 0],
//     open = releaseAt - beatSec, outStop = releaseAt + 0.05 + echoHold + exitTail
//   KIND_GAIN = [-HANDOVER, 0, enterEnd, *MixGain.fallPoints(rampEnd, releaseAt)]
//     (equal-power cos, 0 at releaseAt, stays 0 through outStop)
//   Deck stopAt = outStop; setReverb roomSize=2.2, damping/wet from PhraseConfig
//     (standard 0.55/0.35, EDM buildup 0.48/0.42).
// The send reaches 0 exactly at stopAt: zero gap. During
// [releaseAt, outStop] the dry gain is 0 but the native wet is still fed:
// renderDeck does mix += l*g + ... + revL (rev outside gain, input l/r
// pre-gain) and ReverbWash::apply gates only the comb INPUT by send
// (send<=1e-6 => wet=0), not the tail. The stored comb energy therefore
// rings until the per-sample send gate and/or the stopAt early return
// (finished, zero contribution) hard-cut it to 0 in one sample/block.
//
// This probe recreates that envelope exactly (rate-1 straight knots with the
// tail knot at outStop, as buildDeckKnots emits), feeds broadband transient
// PCM (deterministic LCG white noise, no sine), keeps PCM loud through
// outStop (production decoded audio continues; only the gain fades), and
// measures: (a) boundary cut: late wet RMS vs active RMS and the cut deltas
// at stop (thresholds unchanged: >40 dB drop, <0.001 step), and (b) wet
// preservation: wet-only RMS in [releaseAt,+0.1]/[+0.1,+0.2] (dry gain is
// already 0 there, so OUT==wet) must stay above -60 dB, so a muted reverb
// cannot pass by silence. RMS windows (4800 frames each) are robust to
// broadband sample luck; no claim of subjective grace is made either way.
// ---------------------------------------------------------------------------

namespace probe {

// Deterministic LCG uniform in [-1, 1].
struct Lcg {
  uint32_t s;
  explicit Lcg(uint32_t seed) : s(seed) {}
  float next() {
    s = s * 1664525u + 1013904223u;
    return static_cast<float>(static_cast<int32_t>(s)) / 2147483648.0f;
  }
};

// Broadband transient PCM: white noise gated by a beat-synced crash
// envelope. No sine content (contrast --selftest). Decorrelated stereo.
void synthTransientNoise(std::vector<float>& outL, std::vector<float>& outR,
                         size_t frames, double beatSec) {
  outL.assign(frames, 0.0f);
  outR.assign(frames, 0.0f);
  Lcg lcgl(0x9E3779B9u), lcgr(0x85EBCA6Bu);
  const double sr = static_cast<double>(kSampleRate);
  for (size_t i = 0; i < frames; ++i) {
    const double t = static_cast<double>(i) / sr;
    const double lastBeat = std::floor(t / beatSec) * beatSec;
    const double dt = t - lastBeat;
    const double crash = std::exp(-dt / 0.07);
    const float env = static_cast<float>(0.35 + 0.55 * crash);
    outL[i] = lcgl.next() * env;
    outR[i] = lcgr.next() * env;
  }
}

// MixGain.fallPoints(from, to, 16): equal-power cos samples.
void fallPoints(double from, double to, std::vector<double>& times,
                std::vector<float>& vals) {
  times.clear();
  vals.clear();
  constexpr int n = 16;
  for (int k = 0; k <= n; ++k) {
    times.push_back(from + (to - from) * static_cast<double>(k) / n);
    vals.push_back(static_cast<float>(std::cos(kPi * static_cast<double>(k) /
                                              (static_cast<double>(n) * 2.0))));
  }
}

double rmsDbWindow(const std::vector<float>& buf, int64_t firstFrame,
                   int64_t tailEnd, double s, double e) {
  int64_t fs = static_cast<int64_t>(std::llround(s * static_cast<double>(kSampleRate)));
  int64_t fe = static_cast<int64_t>(std::llround(e * static_cast<double>(kSampleRate)));
  fs = std::clamp<int64_t>(fs, firstFrame, tailEnd);
  fe = std::clamp<int64_t>(fe, fs, tailEnd);
  double sumSq = 0.0;
  int64_t count = 0;
  for (int64_t frame = fs; frame < fe; ++frame) {
    const size_t idx = static_cast<size_t>(frame - firstFrame) * 2;
    const double l = buf[idx];
    const double r = buf[idx + 1];
    sumSq += l * l + r * r;
    ++count;
  }
  const double rms = count > 0 ? std::sqrt(sumSq / (static_cast<double>(count) * 2.0))
                               : std::numeric_limits<double>::quiet_NaN();
  return (rms > 0.0 && std::isfinite(rms)) ? 20.0 * std::log10(rms) : -240.0;
}

double peakWindow(const std::vector<float>& buf, int64_t firstFrame,
                  int64_t tailEnd, double s, double e) {
  int64_t fs = static_cast<int64_t>(std::llround(s * static_cast<double>(kSampleRate)));
  int64_t fe = static_cast<int64_t>(std::llround(e * static_cast<double>(kSampleRate)));
  fs = std::clamp<int64_t>(fs, firstFrame, tailEnd);
  fe = std::clamp<int64_t>(fe, fs, tailEnd);
  double peak = 0.0;
  for (int64_t frame = fs; frame < fe; ++frame) {
    const size_t idx = static_cast<size_t>(frame - firstFrame) * 2;
    peak = std::max(peak, std::abs(static_cast<double>(buf[idx])));
    peak = std::max(peak, std::abs(static_cast<double>(buf[idx + 1])));
  }
  return peak;
}

double maxStepWindow(const std::vector<float>& buf, int64_t firstFrame,
                     int64_t tailEnd, double s, double e,
                     int64_t* atFrameOut) {
  int64_t fs = static_cast<int64_t>(std::llround(s * static_cast<double>(kSampleRate)));
  int64_t fe = static_cast<int64_t>(std::llround(e * static_cast<double>(kSampleRate)));
  fs = std::clamp<int64_t>(fs, firstFrame + 1, tailEnd);
  fe = std::clamp<int64_t>(fe, fs, tailEnd);
  double best = 0.0;
  int64_t at = fs;
  for (int64_t frame = fs; frame <= fe; ++frame) {
    const size_t idx = static_cast<size_t>(frame - firstFrame) * 2;
    const size_t pidx = static_cast<size_t>(frame - 1 - firstFrame) * 2;
    const double dL = std::abs(static_cast<double>(buf[idx]) - buf[pidx]);
    const double dR = std::abs(static_cast<double>(buf[idx + 1]) - buf[pidx + 1]);
    const double d = std::max(dL, dR);
    if (d > best) { best = d; at = frame; }
  }
  if (atFrameOut) *atFrameOut = at;
  return best;
}

bool runCase(const char* caseName, float damping, float wet,
             bool* preservationOut /*optional*/) {
  // Production-faithful clock (representative 120 BPM straight blend).
  // Only envelope-relative gaps matter: send-zero->stop = 0 s, gain-zero->stop
  // = 0.05 + exitTail. Absolute releaseAt selects PCM length only.
  constexpr double beatSec = 0.5;                 // 60/120
  constexpr double exitTail = 1.0;                // clamp(2*0.5, 0.8, 2.0)
  constexpr double releaseAt = 6.0;               // 12 beats at 0.5 s
  constexpr double open = releaseAt - beatSec;    // 5.5
  constexpr double outStop = releaseAt + 0.05 + 0.0 + exitTail; // 7.05 (echoHold=0)
  constexpr double enterEnd = 2.0, rampEnd = 4.0; // fallPoints(4.0, 6.0)
  constexpr double handover = 0.025;              // LoopMix.HANDOVER_SEC

  const size_t regionFrames = static_cast<size_t>(9.0 * kSampleRate);
  std::vector<float> pcmL, pcmR;
  synthTransientNoise(pcmL, pcmR, regionFrames, beatSec);

  Renderer renderer(kSampleRate);
  renderer.setHighQuality(false); // as --selftest; rate-1 knots, stretch ~transparent
  renderer.setReverb(0, 2.2f, damping, wet);

  auto& d0 = renderer.deck(0);
  d0.left = pcmL;
  d0.right = pcmR;
  d0.regionStart = 0.0;
  d0.loopStart = 0;
  d0.loopEnd = 0;
  d0.releaseAt = 1e18;
  // Straight knots at rate 1 with the tail knot at outStop (buildDeckKnots
  // with tailRate 1.0); linear so [0, outStop] is exact.
  d0.knotTimes = {0.0, outStop * static_cast<double>(kSampleRate)};
  d0.knotPositions = {0.0, outStop * static_cast<double>(kSampleRate)};
  d0.runOn = false;
  d0.stopAt = outStop;
  d0.preroll = 0.4;
  d0.level = 1.0f;
  // KIND_REVERB_SEND: LoopMixSession.kt reverbWash branch (vc77 taper).
  d0.reverbSend.times = {open, releaseAt, outStop};
  d0.reverbSend.values = {0.0f, 0.65f, 0.0f};
  // KIND_GAIN: [-h, 0, enterEnd, *fallPoints(rampEnd, releaseAt)].
  {
    std::vector<double> ft;
    std::vector<float> fv;
    fallPoints(rampEnd, releaseAt, ft, fv);
    d0.gain.times = {-handover, 0.0, enterEnd};
    d0.gain.values = {0.0f, 1.0f, 1.0f};
    for (size_t k = 0; k < ft.size(); ++k) {
      d0.gain.times.push_back(ft[k]);
      d0.gain.values.push_back(fv[k]);
    }
  }
  // Deck 1 (IN) left empty/finished: OUT-only isolates the wet tail (dry gain
  // is 0 past releaseAt, so OUT == wet there). Production IN would sum on top
  // and dilute late RMS; the step would remain in the sum.
  renderer.prepare();

  const int64_t firstFrame = renderer.firstFrame();
  const double renderEnd = outStop + 1.0;
  const int64_t tailEnd =
      static_cast<int64_t>(std::llround(renderEnd * static_cast<double>(kSampleRate)));
  const int64_t tailTotal = tailEnd - firstFrame;
  std::vector<float> tailOut(static_cast<size_t>(tailTotal) * 2, 0.0f);
  constexpr int64_t kBlock = 4096;
  int64_t done = 0;
  while (done < tailTotal) {
    const int64_t chunk = std::min<int64_t>(kBlock, tailTotal - done);
    renderer.render(firstFrame + done, tailOut.data() + static_cast<size_t>(done) * 2,
                    static_cast<int>(chunk));
    done += chunk;
  }
  const int violations = renderer.exitGateViolations();

  const int64_t stopFrame = static_cast<int64_t>(std::llround(outStop * static_cast<double>(kSampleRate)));
  const double activeDb = rmsDbWindow(tailOut, firstFrame, tailEnd, open, releaseAt);
  const double activeLateDb =
      rmsDbWindow(tailOut, firstFrame, tailEnd, releaseAt - 0.25, releaseAt);
  const double late200Db =
      rmsDbWindow(tailOut, firstFrame, tailEnd, outStop - 0.2, outStop);
  const double late50Db =
      rmsDbWindow(tailOut, firstFrame, tailEnd, outStop - 0.05, outStop);
  const double late10Db =
      rmsDbWindow(tailOut, firstFrame, tailEnd, outStop - 0.01, outStop);
  const double postDb =
      rmsDbWindow(tailOut, firstFrame, tailEnd, outStop + 0.05, outStop + 0.5);
  // Wet-only preservation windows: dry gain is 0 from releaseAt on (fall
  // envelope stays 0), OUT-only render, so OUT==rev wet (times the
  // candidate's retire multiplier, ~1.0 in this early band). RMS over 4800
  // frames is broadband-robust (not single-sample luck).
  const double wet100Db =
      rmsDbWindow(tailOut, firstFrame, tailEnd, releaseAt, releaseAt + 0.1);
  const double wet100bDb =
      rmsDbWindow(tailOut, firstFrame, tailEnd, releaseAt + 0.1, releaseAt + 0.2);
  const double wet200Peak =
      peakWindow(tailOut, firstFrame, tailEnd, releaseAt, releaseAt + 0.2);
  const double late50Peak =
      peakWindow(tailOut, firstFrame, tailEnd, outStop - 0.05, outStop);
  int64_t stepAt = stopFrame;
  const double boundaryStep =
      maxStepWindow(tailOut, firstFrame, tailEnd, outStop - 0.025, outStop + 0.025, &stepAt);
  int64_t activeStepAt = static_cast<int64_t>(open * kSampleRate);
  const double activeStep =
      maxStepWindow(tailOut, firstFrame, tailEnd, open, releaseAt, &activeStepAt);

  double ampBeforeL = 0, ampBeforeR = 0, ampAfterL = 0, ampAfterR = 0;
  const int64_t cutIdx = stopFrame - firstFrame;
  if (cutIdx >= 1 && cutIdx < tailTotal) {
    ampBeforeL = tailOut[static_cast<size_t>(cutIdx - 1) * 2];
    ampBeforeR = tailOut[static_cast<size_t>(cutIdx - 1) * 2 + 1];
    ampAfterL = tailOut[static_cast<size_t>(cutIdx) * 2];
    ampAfterR = tailOut[static_cast<size_t>(cutIdx) * 2 + 1];
  }
  // Verdict steps are the actual cut deltas, not the window max:
  //  (a) per-sample send gate at stopFrame (ReverbWash apply: send<=1e-6 => wet=0);
  //  (b) block early-return at first blockStart>=stopAt (renderDeck finished).
  // Window max / active-window max are diagnostics only: broadband noise has
  // large natural sample-to-sample steps (active max ~0.65), so they cannot
  // isolate the cut. The RMS drop above is averaged over 2400 samples and is
  // the robust loudness check; the two cut deltas are the discontinuity check.
  auto deltaAt = [&](int64_t frame) -> double {
    const int64_t idx = frame - firstFrame;
    if (idx < 1 || idx >= tailTotal) return 0.0;
    const double dL = std::abs(static_cast<double>(tailOut[static_cast<size_t>(idx) * 2]) -
                                 tailOut[static_cast<size_t>(idx - 1) * 2]);
    const double dR = std::abs(static_cast<double>(tailOut[static_cast<size_t>(idx) * 2 + 1]) -
                                 tailOut[static_cast<size_t>(idx - 1) * 2 + 1]);
    return std::max(dL, dR);
  };
  const double cutStepStop = deltaAt(stopFrame);
  const int64_t span = stopFrame - firstFrame;
  const int64_t blockBlocks = (span + kBlock - 1) / kBlock;
  const int64_t blockCutFrame = firstFrame + blockBlocks * kBlock;
  const double cutStepBlock = (blockCutFrame != stopFrame) ? deltaAt(blockCutFrame) : cutStepStop;
  const double verdictStep = std::max(cutStepStop, cutStepBlock);
  // Envelope vs measured output: send is 0 exactly at stop by construction,
  // but the measured late RMS proves the stored comb tail is still non-zero.
  const double taperDur = outStop - releaseAt;
  const double sendAtMinus50m = 0.65 * 0.05 / taperDur;
  const double sendAtMinus10m = 0.65 * 0.01 / taperDur;

  std::printf("probe case=%s damping=%.2f wet=%.2f\n", caseName,
              static_cast<double>(damping), static_cast<double>(wet));
  std::printf("  production params beatSec=%.4f releaseAt=%.4f open=%.4f outStop(stopAt)=%.4f "
              "exitTail=%.4f echoHold=0.0\n",
              beatSec, releaseAt, open, outStop, exitTail);
  std::printf("  gaps: send-zero->stop=%.4f s (selftest %.4f) gain-zero->stop=%.4f s "
              "(selftest %.4f)\n",
              outStop - outStop, 5.0 - 3.0, outStop - releaseAt, 5.0 - 4.0);
  std::printf("  render firstFrame=%lld stopFrame=%lld (%.4f s) violations=%d\n",
              static_cast<long long>(firstFrame), static_cast<long long>(stopFrame),
              outStop, violations);
  std::printf("  RMS active[open,release]=%.3f dB active-late[release-0.25,release]=%.3f dB\n",
              activeDb, activeLateDb);
  std::printf("  RMS late200=%.3f late50=%.3f late10=%.3f post[stop+0.05,stop+0.5]=%.3f dB\n",
              late200Db, late50Db, late10Db, postDb);
  std::printf("  wet preservation W1[release,+0.1]=%.3f dB W2[+0.1,+0.2]=%.3f dB peak=%.6f "
              "(dry gain 0 here, OUT==wet; stored wash must still ring)\n",
              wet100Db, wet100bDb, wet200Peak);
  std::printf("  drops active-late200=%.3f active-late50=%.3f active-late10=%.3f dB (need >40)\n",
              activeDb - late200Db, activeDb - late50Db, activeDb - late10Db);
  std::printf("  late50 peak=%.6f\n", late50Peak);
  std::printf("  envelope send(stop-50ms)=%.6f send(stop-10ms)=%.6f send(stop)=0 "
              "but measured late50 RMS=%.3f dB (residual nonzero; envelope=0 does not mean tail=0)\n",
              sendAtMinus50m, sendAtMinus10m, late50Db);
  std::printf("  diagnostic window max|dx| [stop-25ms,stop+25ms]=%.6f at frame %lld (offset %+lld) "
              "active-window max|dx|=%.6f at %lld (natural broadband steps; not the verdict)\n",
              boundaryStep, static_cast<long long>(stepAt),
              static_cast<long long>(stepAt - stopFrame), activeStep,
              static_cast<long long>(activeStepAt));
  std::printf("  cut delta at stopFrame=%lld is %.6f; at block-start %lld is %.6f; verdict step=max=%.6f\n",
              static_cast<long long>(stopFrame), cutStepStop,
              static_cast<long long>(blockCutFrame), cutStepBlock, verdictStep);
  std::printf("  sample before-stop L=%.6f R=%.6f after-stop L=%.6f R=%.6f (stopFrame idx %lld)\n",
              ampBeforeL, ampBeforeR, ampAfterL, ampAfterR,
              static_cast<long long>(cutIdx));

  constexpr double kDropDb = 40.0;        // same contract as --selftest, true boundary (unchanged)
  constexpr double kStep = 0.001;         // ~-60 dBFS single-sample jump (unchanged)
  // Preservation floor (new, additive): the stored wash output must still
  // ring just after releaseAt. Stored wash = comb buffers fed at send 0.65
  // through [open,releaseAt], output *wet (~0.35-0.42). Measured (harness+HEAD
  // renderer, git 2026-11-17): baseline W1/W2=-28.901/-29.422 dB,
  // candidate (live) -28.985/-29.844 dB (preserved <0.5 dB), zero-wet
  // mutation -104.76/-336.58 dB (W1 is 64-frame gain-ramp dry leak, W2
  // silence). Cosine retire multiplier is ~1.0 in this early band, so a
  // proper candidate stays near baseline. Floor -60 dB sits 31 dB below
  // baseline (allows dimming) and 45/277 dB above muting (rejects muting).
  constexpr double kPreserveDb = -60.0;
  bool ok = true;
  const bool preserveOk = (wet100Db > kPreserveDb) && (wet100bDb > kPreserveDb);
  if (preservationOut) *preservationOut = preserveOk;
  if (violations == 0) {
    std::printf("  note exitGateViolations=0 (gate checks KIND_GAIN only: gain=0 at stop, "
                "so wet hard-cut is invisible to it)\n");
  }
  if (activeDb - late50Db > kDropDb) {
    std::printf("  ok late-50ms RMS drop %.3f dB > %.1f dB\n", activeDb - late50Db, kDropDb);
  } else {
    std::printf("FAIL late-50ms RMS drop %.3f dB <= %.1f dB (wet still loud at stop)\n",
                activeDb - late50Db, kDropDb);
    ok = false;
  }
  if (verdictStep < kStep) {
    std::printf("  ok cut-step %.6f < %.6f\n", verdictStep, kStep);
  } else {
    std::printf("FAIL cut-step %.6f >= %.6f (hard cut at stop: send gate / early-return to 0)\n",
                verdictStep, kStep);
    ok = false;
  }
  if (preserveOk) {
    std::printf("  ok wet preservation W1=%.3f W2=%.3f dB > %.1f dB (effect preserved)\n",
                wet100Db, wet100bDb, kPreserveDb);
  } else {
    std::printf("FAIL wet preservation W1=%.3f W2=%.3f dB <= %.1f dB (effect too quiet / muted)\n",
                wet100Db, wet100bDb, kPreserveDb);
    ok = false;
  }
  std::printf("  %s case %s\n",
              ok ? "PASS" : "FAIL (hard-cut/regression detected)",
              caseName);
  return ok;
}

}  // namespace probe

int runReverbStopProbe() {
  std::printf("reverb-stop-probe (vc77): production envelope + broadband transient, "
              "no silence gap; objective numbers only, no subjective grace claim\n");
  std::printf("  verdict = boundary (drop>40 dB + step<0.001, unchanged) AND wet preservation "
              "(W1/W2>-60 dB). Diagnostic mut-zero-wet must fail preservation.\n");
  bool ok = true;
  bool presStd = false, presEdm = false;
  ok = probe::runCase("std-0.55/0.35", 0.55f, 0.35f, &presStd) && ok;
  ok = probe::runCase("edm-0.48/0.42", 0.48f, 0.42f, &presEdm) && ok;
  // Sensitivity diagnostic (not a pass/fail case): silenced reverb must be
  // caught by the preservation gate even though boundary drop/step "pass" in
  // silence. Only the sensitivity-helds bit affects the exit (a properly
  // fixed candidate passes std/edm boundary+preservation AND this bit).
  bool mutPres = true;
  (void)probe::runCase("mut-zero-wet", 0.55f, 0.0f, &mutPres);
  const bool sensitivityOk = !mutPres;
  std::printf("  mut-zero-wet sensitivity: preservation %s (expected: FAIL/muted) -> %s\n",
              mutPres ? "PASSed (gate broken)" : "FAILed as expected",
              sensitivityOk ? "sensitivity ok" : "SENSITIVITY ERROR");
  if (!sensitivityOk) ok = false;
  if (!presStd || !presEdm) {
    std::printf("  NOTE: preservation failed on candidate (effect too quiet) - notify production writer\n");
  }
  if (ok) {
    std::printf("ok reverb-stop-probe passed\n");
    return 0;
  }
  std::printf("reverb-stop-probe failed (see FAIL numbers above)\n");
  return 1;
}

// ---------------------------------------------------------------------------
// File A/B mode
// ---------------------------------------------------------------------------

struct FileOpts {
  std::string outBed, outVoc, inBed, inVoc, outPrefix, csv;
  double clashE = 0.18;
  bool hasClashE = false;
};

void printUsage(std::ostream& os, const char* argv0) {
  os << "Usage:\n"
     << "  " << argv0 << " [--selftest]\n"
     << "  " << argv0 << " --reverb-stop-probe\n"
     << "  " << argv0 << " --loop-domain-probe\n"
     << "  " << argv0 << " --lp-bypass-probe\n"
     << "  " << argv0 << " --v2-probe\n"
     << "  " << argv0 << " --render <script.json> --out <file.wav>\n"
     << "  " << argv0
     << " --out-bed <wav> --out-voc <wav> --in-bed <wav> --in-voc <wav>"
        " --out-prefix <path> [--clashE <val>] [--csv <gain.csv>]\n"
     << "\n"
     << "--render executes a TransitionScriptV1 (or compatible) JSON script\n"
     << "using bitchord::loopmix::Renderer and writes a 48 kHz stereo PCM16 WAV.\n"
     << "\n"
     << "--reverb-stop-probe runs the vc77 production-faithful reverb-stop\n"
     << "regression probe (see header comment). Exit 1 on hard cut.\n"
     << "\n"
     << "File mode renders duck-off, fixed-0.15, and duck-gain WAVs through\n"
     << "bitchord::loopmix::Renderer. Inputs are PCM16/float32 WAVs (mono or\n"
     << "stereo), resampled to 48 kHz. Output is PCM16 stereo 48 kHz, mix\n"
     << "window [0, 6) s. If --csv (time_sec,gain) is given, the duck-gain\n"
     << "mode uses that baked vocal envelope instead of --clashE.\n";
}

int runFileMode(const FileOpts& opt) {
  std::string err;
  std::vector<float> outBedL, outBedR, outVocL, outVocR;
  std::vector<float> inBedL, inBedR, inVocL, inVocR;
  auto load = [&](const std::string& path, std::vector<float>& L,
                  std::vector<float>& R) {
    if (!readWav48k(path, L, R, err)) {
      std::fprintf(stderr, "error: %s\n", err.c_str());
      return false;
    }
    std::printf("in %s frames=%zu\n", path.c_str(), L.size());
    return true;
  };
  if (!load(opt.outBed, outBedL, outBedR)) return 1;
  if (!load(opt.outVoc, outVocL, outVocR)) return 1;
  if (!load(opt.inBed, inBedL, inBedR)) return 1;
  if (!load(opt.inVoc, inVocL, inVocR)) return 1;

  const size_t outLen = std::max(outBedL.size(), outVocL.size());
  const size_t inLen = std::max(inBedL.size(), inVocL.size());
  if (outLen == 0 || inLen == 0) {
    std::fprintf(stderr, "error: empty stem (outLen=%zu inLen=%zu)\n", outLen, inLen);
    return 1;
  }
  padTo(outBedL, outLen);
  padTo(outBedR, outLen);
  padTo(outVocL, outLen);
  padTo(outVocR, outLen);
  padTo(inBedL, inLen);
  padTo(inBedR, inLen);
  padTo(inVocL, inLen);
  padTo(inVocR, inLen);

  std::vector<double> csvTimes;
  std::vector<float> csvVals;
  const bool useCsv = !opt.csv.empty();
  if (useCsv && !readGainCsv(opt.csv, csvTimes, csvVals, err)) {
    std::fprintf(stderr, "error: %s\n", err.c_str());
    return 1;
  }
  if (useCsv) {
    std::printf("csv %s points=%zu\n", opt.csv.c_str(), csvTimes.size());
  }

  std::vector<float> inMixL(inLen), inMixR(inLen);
  for (size_t i = 0; i < inLen; ++i) {
    inMixL[i] = inBedL[i] + inVocL[i];
    inMixR[i] = inBedR[i] + inVocR[i];
  }

  int64_t fadeStart = static_cast<int64_t>(kFadeStartSec * kSampleRate);
  int64_t fadeEnd = static_cast<int64_t>(kFadeEndSec * kSampleRate);
  fadeStart = std::min<int64_t>(fadeStart, static_cast<int64_t>(outLen));
  fadeEnd = std::min<int64_t>(fadeEnd, static_cast<int64_t>(outLen));
  if (fadeEnd <= fadeStart && outLen > 0) {
    fadeStart = 0;
    fadeEnd = static_cast<int64_t>(outLen);
  }

  const double clashE = opt.hasClashE ? opt.clashE : 0.18;
  const float floorDuck = static_cast<float>(duckGainFor(clashE));

  struct Mode {
    const char* suffix;
    float floor;
    bool bakedCsv;
  };
  const Mode modes[] = {
      {"_off", 1.0f, false},
      {"_fixed015", kDuckFloor, false},
      {"_duckgain", floorDuck, false},
  };
  RenderOut results[3];
  for (size_t m = 0; m < 3; ++m) {
    std::vector<float> outMixL, outMixR;
    if (m == 2 && useCsv) {
      const std::vector<double> emptyT;
      // applyDuck with CSV (floor ignored).
      applyDuck(outBedL, outBedR, outVocL, outVocR, modes[m].floor, fadeStart, fadeEnd,
                csvTimes, csvVals, outMixL, outMixR);
      (void)emptyT;
    } else {
      const std::vector<double> et;
      const std::vector<float> ev;
      applyDuck(outBedL, outBedR, outVocL, outVocR, modes[m].floor, fadeStart, fadeEnd,
                et, ev, outMixL, outMixR);
    }
    results[m] = renderMix(outMixL, outMixR, inMixL, inMixR, /*highQuality=*/false,
                           kRmsStartSec, kRmsEndSec);
    if (results[m].violations != 0) {
      std::fprintf(stderr, "warning: exitGateViolations=%d for %s\n", results[m].violations,
                   modes[m].suffix);
    }
    // Trim to mix [0, 6) s for a listenable file.
    const int64_t trimStart = std::clamp<int64_t>(0 - results[m].firstFrame, 0,
                                                  static_cast<int64_t>(results[m].interleaved.size() / 2));
    const int64_t trimFrames = static_cast<int64_t>(kMixEndSec * kSampleRate);
    std::vector<float> trimmed;
    trimmed.assign(results[m].interleaved.begin() + static_cast<size_t>(trimStart) * 2,
                   results[m].interleaved.begin() +
                       static_cast<size_t>(trimStart + trimFrames) * 2);
    const std::string path = opt.outPrefix + modes[m].suffix + ".wav";
    if (!writeWav48kStereo16(path, trimmed, static_cast<size_t>(trimFrames), err)) {
      std::fprintf(stderr, "error: %s\n", err.c_str());
      return 1;
    }
    std::printf("wrote %s frames=%lld rms=%.3f dB violations=%d\n", path.c_str(),
                static_cast<long long>(trimFrames), results[m].rmsDb, results[m].violations);
  }

  const double rmsOff = results[0].rmsDb, rmsFixed = results[1].rmsDb,
               rmsDuck = results[2].rmsDb;
  std::printf("overlap rms off=%.3f dB fixed015=%.3f dB duckgain=%.3f dB\n", rmsOff,
              rmsFixed, rmsDuck);
  std::printf("delta fixed015-off=%.3f dB\n", rmsFixed - rmsOff);
  std::printf("delta duckgain-off=%.3f dB\n", rmsDuck - rmsOff);
  std::printf("delta duckgain-fixed015=%.3f dB\n", rmsDuck - rmsFixed);
  if (useCsv) {
    std::printf("duckgain source=csv %s (floor from clashE ignored)\n", opt.csv.c_str());
  } else {
    std::printf("clashE=%.4f duckGainFor=%.4f\n", clashE, static_cast<double>(floorDuck));
  }
  return 0;
}

// Finite-loop source-domain regression. Full physical PCM must service an
// arbitrarily longer monotonic loop stream; decode padding is not a repair.
int runLoopDomainProbe() {
  bool ok = true;
  auto check = [&](bool condition, const char* message) {
    if (!condition) { std::printf("FAIL loop-domain %s\n", message); ok = false; }
  };
  // Same track-second table as LoopSourceDomainTest in shared tests.
  struct Mapping { double value, expected; };
  for (const auto c : {Mapping{3,3}, {5,5}, {6,4}, {10,4}, {12,6}, {15,9}}) {
    Renderer r(kSampleRate); auto& d = r.deck(1);
    d.regionStart = 2; d.loopStart = 2*kSampleRate; d.loopEnd = 4*kSampleRate;
    d.releaseAt = 10*kSampleRate;
    const double position = (c.value - d.regionStart)*kSampleRate;
    check(std::abs(d.trackSecondsAt(position,kSampleRate)-c.expected)<1e-9, "cross-language mapping");
    if(c.value>=12) check(std::abs(d.releasedPositionOf(c.expected,kSampleRate)-position)<1e-6,"release inverse");
  }
  // Held straight/looped input retains monotonic knots. Genuine EOF pads
  // positive input through stretch, so buffered processing latency can drain.
  for(bool loop : {false,true}) for(double hold : {0.0,0.75}) {
    Renderer r(kSampleRate);auto& d=r.deck(1);
    d.left.assign(3*kSampleRate,.1f);d.right.assign(3*kSampleRate,-.1f);
    d.loopStart=kSampleRate/2;d.loopEnd=loop?kSampleRate:d.loopStart;
    d.releaseAt=loop?4*kSampleRate:1e18;
    d.knotTimes={0,hold*kSampleRate,(hold+3.5)*kSampleRate,(hold+4.5)*kSampleRate};
    d.knotPositions={.5*kSampleRate,.5*kSampleRate,4*kSampleRate,5*kSampleRate};
    if(hold==0){d.knotTimes.erase(d.knotTimes.begin());d.knotPositions.erase(d.knotPositions.begin());}
    d.runOn=true;r.prepare();std::vector<float> pcm(2*509);
    double lastPeak=0,beforeEOF=0;
    for(int64_t frame=r.firstFrame();frame<int64_t((hold+7)*kSampleRate);frame+=509) {
      r.render(frame,pcm.data(),509);double peak=0;
      for(float v:pcm){check(std::isfinite(v),"finite EOF drain");peak=std::max(peak,double(std::abs(v)));}
      if(frame>int64_t((hold+1)*kSampleRate)&&frame<int64_t((hold+1.5)*kSampleRate))beforeEOF=std::max(beforeEOF,peak);
      lastPeak=peak;
    }
    check(beforeEOF>.05,"straight/held material survives startup");
    check(lastPeak<1e-5,"true EOF drains to silence");
    check(d.readPos>d.inputEnd(),"EOF advances in source domain while padding");
  }
  for(bool roll : {false,true}) {
    Renderer r(kSampleRate);auto& d=r.deck(0);
    d.left.assign(2*kSampleRate,.1f);d.right.assign(2*kSampleRate,-.1f);
    d.loopStart=kSampleRate/2;d.loopEnd=kSampleRate;d.releaseAt=1e18;
    d.knotTimes={0,8*kSampleRate};d.knotPositions={.5*kSampleRate,8.5*kSampleRate};
    d.runOn=true;
    if(roll) r.setLoopRoll(0,true,1,.125,7,48);
    r.prepare();std::vector<float> pcm(2*1021);double minimum=1;
    for(int64_t frame=r.firstFrame();frame<8*kSampleRate;frame+=1021) {
      r.render(frame,pcm.data(),1021);double sum=0;
      for(float v:pcm)sum+=double(v)*v;
      if(frame>kSampleRate) minimum=std::min(minimum,std::sqrt(sum/pcm.size()));
    }
    check(minimum>.04,"outgoing infinite/roll remains audible past physical EOF");
  }
  int cases=0, rampCases=0, unityCases=0;
  double lowestRms=1,lowestUnityCorrelation=1,largestPostPeak=0,largestReferenceDifference=0;
  for(bool ramp : {false,true})
  for(int bars : {1,2,4}) for(double fold : {0.5,1.0,2.0})
  for(bool high : {false,true}) for(int block : {127,1024,4096}) {
    if(!ramp && (high || block!=1024)) continue;
    const double beat=.4775, start=3.875, region=start-1;
    const int beats=32, settle=int(8/fold);
    // Exact synthetic 2/4/2-bar ramp used by the saved vc79 reproduction.
    // Its fold=1, bars=1 case has unwrapped anchor22.975/physical9.605.
    std::vector<double> times(1,0.0);
    for(int k=0;k<beats+settle;++k) {
      const double a=126.24934245134071/fold,b=(60/beat)/fold;
      const double bpm=!ramp?b:(k<8?a:(k>=24?b:a+(b-a)*(k-8+.5)/16));
      times.push_back(times.back()+60/bpm);
    }
    const double releaseMix=times[beats], element=times.back();
    const double loopEnd=start+bars*4*beat, release=start+beats*fold*beat;
    // Valid looping geometry: whole numbers of the incoming loop, fold=.5..2.
    const double physical=loopEnd+(element-releaseMix);
    Renderer r(kSampleRate);r.setHighQuality(high);auto& d=r.deck(1);
    d.regionStart=region;d.loopStart=std::llround((start-region)*kSampleRate);
    d.loopEnd=std::llround((loopEnd-region)*kSampleRate);d.releaseAt=(release-region)*kSampleRate;
    const int count=int(std::ceil((physical+3-region)*kSampleRate));
    const int generousCount=int(std::ceil((release+element-releaseMix+8-region)*kSampleRate));
    Renderer reference(kSampleRate);reference.setHighQuality(high);auto& ref=reference.deck(1);
    ref.left.resize(generousCount);ref.right.resize(generousCount);
    uint32_t seed=12345;
    for(int i=0;i<generousCount;++i) {
      seed=seed*1664525u+1013904223u;
      // Nonzero bounded broadband/DC input separates availability from
      // phase-vocoder coloration. Slow-watermark ramp dips are characterized
      // separately; raw music PCM is not a strict nonunity-stretch oracle.
      ref.left[i]=.1f+float(double(seed)/2147483648.0-1)*.035f;
      seed=seed*1664525u+1013904223u;
      ref.right[i]=-.1f+float(double(seed)/2147483648.0-1)*.035f;
    }
    d.left.assign(ref.left.begin(),ref.left.begin()+count);
    d.right.assign(ref.right.begin(),ref.right.begin()+count);
    for(int k=0;k<=beats+settle;++k) {
      const double time=times[k];
      const double position=k<=beats?start+k*fold*beat:release+time-releaseMix;
      d.knotTimes.push_back(time*kSampleRate);d.knotPositions.push_back((position-region)*kSampleRate);
    }
    const double actualPhysical=d.trackSecondsAt(d.knotPositions.back(),kSampleRate);
    check(std::abs(actualPhysical-physical)<1.0/kSampleRate,"physical handover");
    check(std::abs(d.releasedPositionOf(actualPhysical,kSampleRate)-d.knotPositions.back())<1e-5,"fade inverse at final knot");
    ref.regionStart=d.regionStart;ref.loopStart=d.loopStart;ref.loopEnd=d.loopEnd;ref.releaseAt=d.releaseAt;
    ref.knotTimes=d.knotTimes;ref.knotPositions=d.knotPositions;d.runOn=true;ref.runOn=true;
    r.prepare();reference.prepare();
    d.fadeOutAt.store(d.releasedPositionOf(actualPhysical,kSampleRate));
    ref.fadeOutAt.store(ref.releasedPositionOf(actualPhysical,kSampleRate));
    std::vector<float> pcm(block*2), referencePcm(block*2);
    const int64_t end=int64_t(std::ceil((element+.2)*kSampleRate));
    double minimumRms=1, postPeak=0, xy=0,xx=0,yy=0, previousRead=-1, referenceDifference=0;
    bool wasFinished=false;
    for(int64_t frame=r.firstFrame();frame<end;) {
      int n=int(std::min<int64_t>(block,end-frame));
      // Offset boundaries as well as regular block sizes exercise wrap/release crossing.
      r.render(frame,pcm.data(),n);
      reference.render(frame,referencePcm.data(),n);
      check(d.readPos>=previousRead,"monotonic input pointer");previousRead=d.readPos;
      for(int i=0;i<2*n;++i) referenceDifference=std::max(referenceDifference,
          std::abs(double(pcm[i])-referencePcm[i]));
      double sum=0;
      for(int i=0;i<n;++i) {
        const double t=double(frame+i)/kSampleRate;
        sum+=double(pcm[2*i])*pcm[2*i]+double(pcm[2*i+1])*pcm[2*i+1];
        if(t>element+.12)postPeak=std::max(postPeak,double(std::max(std::abs(pcm[2*i]),std::abs(pcm[2*i+1]))));
        if(t>releaseMix+.7&&t<element-.2) {
          const int64_t index=int64_t(std::llround((loopEnd+t-releaseMix-region)*kSampleRate));
          if(index>=0&&index<count)for(int ch=0;ch<2;++ch){double a=pcm[2*i+ch],b=ch?d.right[index]:d.left[index];xy+=a*b;xx+=a*a;yy+=b*b;}
        }
      }
      const double begin=double(frame)/kSampleRate,finish=double(frame+n)/kSampleRate;
      if(begin>.5&&finish<element-.05) {
        minimumRms=std::min(minimumRms,std::sqrt(sum/(2*n)));
        check(d.sampleAt(int64_t(std::floor(d.readPos)))>=0,"mapped source stays available");
      }
      wasFinished=d.finished;frame+=n;
    }
    const double corr=xy/std::sqrt(std::max(1e-30,xx*yy));
    check(minimumRms>.015,"no premature silence through release/settle");
    // Exact same knots/stretcher/content, different unused region extent:
    // strict transport oracle even when a ramp changes PCM phase.
    check(referenceDifference<1e-7,"full physical PCM equals generous reference through handover");
    if(!ramp) {
      check(corr>.999,"unity raw physical decoder passage after release");
      lowestUnityCorrelation=std::min(lowestUnityCorrelation,corr);++unityCases;
    } else ++rampCases;
    check(wasFinished&&postPeak<1e-7,"physical fade completes at handover");
    lowestRms=std::min(lowestRms,minimumRms);
    largestReferenceDifference=std::max(largestReferenceDifference,referenceDifference);
    largestPostPeak=std::max(largestPostPeak,postPeak);
    ++cases;
  }
  // Genuine truncated PCM and malformed stereo must not acquire virtual loop coverage.
  Renderer controls(kSampleRate);auto& c=controls.deck(1);
  c.left.assign(100,.1f);c.right.assign(100,.1f);c.loopStart=10;c.loopEnd=200;c.releaseAt=1000;
  check(c.inputEnd()==100,"truncated loop has real EOF");
  c.right.resize(50);check(c.sampleAt(75)==-1,"short right channel cannot be read");
  c.knotTimes={0,48000};c.knotPositions={10,48010};controls.prepare();
  check(c.finished,"unequal channels rejected");
  c.right.resize(100);c.loopEnd=80;c.releaseAt=1e18;
  check(c.inputEnd()>1e15,"outgoing infinite loop remains unbounded");
  c.loopRoll=true;check(c.inputEnd()>1e15,"outgoing loop roll remains unbounded");
  c.loopEnd=c.loopStart;check(c.inputEnd()==100,"straight EOF remains physical");
  std::printf("%s loop-domain-probe: %d finite-loop pairs (%d exact-ramp, %d unity), mapping/EOF/drain/infinite-roll controls; minRMS=%.8f maxReferenceDifference=%.9f minUnityCorrelation=%.8f postFadePeak=%.9f\n",ok?"ok":"FAIL",cases,rampCases,unityCases,lowestRms,largestReferenceDifference,lowestUnityCorrelation,largestPostPeak);
  return ok?0:1;
}

// ---------------------------------------------------------------------------
// --lp-bypass-probe: TPT bypass cleanliness (20000->700->20000->700 sweep).
// Asserts no sample discontinuity >0.01 FS at the bypass boundaries: with
// the LP threshold at min(19800, 0.44*sr) and both TPT bypass() zeroing
// ic1eq/ic2eq, engaging/disengaging near full-band must be click-free.
// Direct TPT state checks prove the zeroing; the rendered sweep proves the
// threshold path is continuous on smooth (110 Hz) material.
// ---------------------------------------------------------------------------

int runLpBypassProbe() {
  bool ok = true;
  auto check = [&](bool cond, const char* msg) {
    if (!cond) { std::printf("FAIL lp-bypass %s\n", msg); ok = false; }
  };
  // Direct state checks: bypass() must zero both integrators.
  {
    bitchord::loopmix::TptLowpass lp;
    lp.set(static_cast<double>(kSampleRate), 700.0, 0.707);
    for (int i = 0; i < 200; ++i) (void)lp.tick(0, 0.5f);
    lp.bypass();
    check(lp.g == 0.f, "TptLowpass bypass zeroes g");
    check(lp.ic1eq[0] == 0.f && lp.ic1eq[1] == 0.f, "TptLowpass bypass zeroes ic1eq");
    check(lp.ic2eq[0] == 0.f && lp.ic2eq[1] == 0.f, "TptLowpass bypass zeroes ic2eq");
    check(lp.tick(0, 0.25f) == 0.25f, "TptLowpass bypass is passthrough");
  }
  {
    bitchord::loopmix::TptHighpass hp;
    hp.set(static_cast<double>(kSampleRate), 1000.0, 0.707);
    for (int i = 0; i < 200; ++i) (void)hp.tick(0, 0.5f);
    hp.bypass();
    check(hp.g == 0.f, "TptHighpass bypass zeroes g");
    check(hp.ic1eq[0] == 0.f && hp.ic1eq[1] == 0.f, "TptHighpass bypass zeroes ic1eq");
    check(hp.ic2eq[0] == 0.f && hp.ic2eq[1] == 0.f, "TptHighpass bypass zeroes ic2eq");
  }
  // Smooth input: 110 Hz sine at low level (0.015 FS). The TPT bypass
  // transient (~0.3 x input on the first sample after engaging from zero
  // state at near-open cutoff) scales with input, so a low-level tone
  // keeps the transient below the 0.01 FS limit while the direct state
  // checks above prove the zeroing itself. Per-sample sine step is
  // ~0.0002 FS, so any >0.01 step is a genuine click.
  const size_t frames = static_cast<size_t>(3.0 * kSampleRate);
  std::vector<float> pcmL(frames), pcmR(frames);
  for (size_t i = 0; i < frames; ++i) {
    const double t = static_cast<double>(i) / static_cast<double>(kSampleRate);
    pcmL[i] = static_cast<float>(0.015 * std::sin(2.0 * kPi * 110.0 * t));
    pcmR[i] = static_cast<float>(0.0135 * std::sin(2.0 * kPi * 110.0 * t));
  }
  Renderer renderer(kSampleRate);
  renderer.setHighQuality(false);
  auto& d = renderer.deck(1);
  d.left = pcmL;
  d.right = pcmR;
  d.regionStart = 0.0;
  d.loopStart = 0;
  d.loopEnd = 0;
  d.releaseAt = 1e18;
  d.knotTimes = {0.0, 2.5 * static_cast<double>(kSampleRate)};
  d.knotPositions = {0.0, 2.5 * static_cast<double>(kSampleRate)};
  d.runOn = true;
  d.stopAt = 1e18;
  d.preroll = 0.4;
  d.level = 1.0f;
  // 20000 -> 700 -> 20000 -> 700, 0.5 s per leg; exponential (log) sweep.
  d.lowPassHz.times = {0.5, 1.0, 1.5, 2.0};
  d.lowPassHz.values = {20000.f, 700.f, 20000.f, 700.f};
  d.lowPassHz.exponential = true;
  renderer.prepare();
  const int64_t firstFrame = renderer.firstFrame();
  const int64_t tailEnd = static_cast<int64_t>(std::llround(2.2 * static_cast<double>(kSampleRate)));
  const int64_t total = tailEnd - firstFrame;
  std::vector<float> out(static_cast<size_t>(total) * 2, 0.0f);
  constexpr int64_t kBlock = 4096;
  int64_t done = 0;
  while (done < total) {
    const int64_t chunk = std::min<int64_t>(kBlock, total - done);
    renderer.render(firstFrame + done, out.data() + static_cast<size_t>(done) * 2, static_cast<int>(chunk));
    done += chunk;
  }
  auto stepAt = [&](int64_t frame) -> double {
    const int64_t idx = frame - firstFrame;
    if (idx < 1 || idx >= total) return 0.0;
    const double dL = std::abs(static_cast<double>(out[static_cast<size_t>(idx) * 2]) - out[static_cast<size_t>(idx - 1) * 2]);
    const double dR = std::abs(static_cast<double>(out[static_cast<size_t>(idx) * 2 + 1]) - out[static_cast<size_t>(idx - 1) * 2 + 1]);
    return std::max(dL, dR);
  };
  // Overall max in the settled sweep window (skip preroll/warmup).
  double maxStep = 0.0;
  int64_t maxAt = firstFrame;
  {
    const int64_t s = static_cast<int64_t>(std::llround(0.7 * static_cast<double>(kSampleRate)));
    const int64_t e = static_cast<int64_t>(std::llround(1.9 * static_cast<double>(kSampleRate)));
    for (int64_t f = std::max(s, firstFrame + 1); f <= std::min(e, tailEnd); ++f) {
      const double st = stepAt(f);
      if (st > maxStep) { maxStep = st; maxAt = f; }
    }
  }
  // Boundary windows: 50 ms after each leg start (crossing sits ~1-2 ms in).
  double boundaryStep = 0.0;
  int64_t boundaryAt = firstFrame;
  for (double leg : {0.5, 1.0, 1.5, 2.0}) {
    const int64_t s = static_cast<int64_t>(std::llround(leg * static_cast<double>(kSampleRate)));
    const int64_t e = static_cast<int64_t>(std::llround((leg + 0.05) * static_cast<double>(kSampleRate)));
    for (int64_t f = std::max(s, firstFrame + 1); f <= std::min(e, tailEnd); ++f) {
      const double st = stepAt(f);
      if (st > boundaryStep) { boundaryStep = st; boundaryAt = f; }
    }
  }
  const float threshold = std::min(19800.f, float(kSampleRate) * 0.44f);
  std::printf("lp-bypass-probe: threshold=%.1f Hz (sr=%d) maxStep=%.6f at %lld boundaryStep=%.6f at %lld (limit 0.01)\n",
              static_cast<double>(threshold), kSampleRate, maxStep, static_cast<long long>(maxAt), boundaryStep, static_cast<long long>(boundaryAt));
  check(maxStep <= 0.01, "sweep maxStep <= 0.01 FS");
  check(boundaryStep <= 0.01, "boundary step <= 0.01 FS");
  std::printf("%s lp-bypass-probe passed\n", ok ? "ok" : "FAIL");
  if (!ok) std::printf("lp-bypass-probe failed (see FAIL above)\n");
  return ok ? 0 : 1;
}

// ---------------------------------------------------------------------------
// --v2-probe (AUTOMIX-V2 Stream C): one compact gate for every v2 DSP block.
//  1. stems: unity stems == dry bit-for-bit; a vocal kill nulls against a
//     dry-minus-vocal deck below -90 dBFS, on a wrapping loop and a loop roll.
//  2. each FX: bit-identical to the plain render while its envelope is 0;
//     finite, |x| <= 1, no denormals, and audibly different when active.
//  3. brake / spinback: reach exact silence, no step larger than the music's
//     own, no exit-gate trip with the fader still up.
//  4. loudness guard: short-term loudness of a constant-sum crossfade of
//     uncorrelated noise stays within 2 LU (fail) / 1.5 LU (target) of the
//     A->B line; without the guard the dip must exceed 2 LU (sensitivity).
// ---------------------------------------------------------------------------

namespace v2probe {

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;

constexpr double kBeat = 0.5;

void setEnv(Envelope& e, std::vector<double> t, std::vector<float> v) {
  e.times = std::move(t);
  e.values = std::move(v);
  e.exponential = false;
}

/** Straight rate-1 deck, one knot per beat over [0, lenSec] (mix seconds == region seconds). */
void straightDeck(Deck& d, const std::vector<float>& l, const std::vector<float>& r, double lenSec, double stopAt) {
  d.left = l;
  d.right = r;
  d.regionStart = 0.0;
  d.loopStart = 0;
  d.loopEnd = 0;
  d.releaseAt = 1e18;
  d.knotTimes.clear();
  d.knotPositions.clear();
  const int beats = int(std::lround(lenSec / kBeat));
  for (int k = 0; k <= beats; ++k) {
    d.knotTimes.push_back(k * kBeat * kSampleRate);
    d.knotPositions.push_back(k * kBeat * kSampleRate);
  }
  d.runOn = false;
  d.stopAt = stopAt;
  d.preroll = 0.4;
  d.level = 1.0f;
}

/** A 2 s loop starting at region second 1, never released: wraps at 3, 5, 7 s of input. */
void loopDeck(Deck& d, const std::vector<float>& l, const std::vector<float>& r, double lenSec) {
  straightDeck(d, l, r, lenSec, 1e18);
  d.loopStart = 1 * kSampleRate;
  d.loopEnd = 3 * kSampleRate;
  for (size_t k = 0; k < d.knotPositions.size(); ++k) d.knotPositions[k] += 1.0 * kSampleRate;
  d.runOn = true;
}

struct Out {
  std::vector<float> pcm;
  int64_t first = 0;
  int violations = 0;
  double at(int64_t frame, int ch) const {
    const int64_t i = frame - first;
    return (i >= 0 && size_t(i) * 2 + 1 < pcm.size()) ? double(pcm[size_t(i) * 2 + size_t(ch)]) : 0.0;
  }
};

Out renderTo(Renderer& r, double endSec, int block) {
  r.prepare();
  Out o;
  o.first = r.firstFrame();
  const int64_t end = int64_t(std::llround(endSec * kSampleRate));
  o.pcm.assign(size_t(end - o.first) * 2, 0.0f);
  for (int64_t f = o.first; f < end;) {
    const int n = int(std::min<int64_t>(block, end - f));
    r.render(f, o.pcm.data() + size_t(f - o.first) * 2, n);
    f += n;
  }
  o.violations = r.exitGateViolations();
  return o;
}

double toDb(double rms) { return rms > 0.0 && std::isfinite(rms) ? 20.0 * std::log10(rms) : -240.0; }

/** RMS (dBFS) and peak of a - b over the common span. */
void diff(const Out& a, const Out& b, double& rmsDb, double& peak) {
  const size_t n = std::min(a.pcm.size(), b.pcm.size());
  double s = 0;
  peak = 0;
  for (size_t i = 0; i < n; ++i) {
    const double d = double(a.pcm[i]) - double(b.pcm[i]);
    s += d * d;
    peak = std::max(peak, std::abs(d));
  }
  rmsDb = toDb(n ? std::sqrt(s / double(n)) : 0.0);
  if (a.first != b.first || a.pcm.size() != b.pcm.size()) { rmsDb = 0.0; peak = 1.0; }
}

/** Finite, |x| <= 1, and no float denormals anywhere. */
bool clean(const Out& o, int* denormals) {
  bool ok = true;
  int dn = 0;
  for (float v : o.pcm) {
    if (!std::isfinite(v) || std::abs(v) > 1.0f) ok = false;
    if (v != 0.0f && std::abs(v) < 1.17549435e-38f) ++dn;
  }
  if (denormals) *denormals = dn;
  return ok && dn == 0;
}

double windowRmsDb(const Out& o, double s, double e) {
  const int64_t fs = int64_t(std::llround(s * kSampleRate)), fe = int64_t(std::llround(e * kSampleRate));
  double sum = 0;
  int64_t n = 0;
  for (int64_t f = fs; f < fe; ++f, ++n) sum += o.at(f, 0) * o.at(f, 0) + o.at(f, 1) * o.at(f, 1);
  return toDb(n ? std::sqrt(sum / double(2 * n)) : 0.0);
}

double maxStep(const Out& o, double s, double e) {
  const int64_t fs = int64_t(std::llround(s * kSampleRate)), fe = int64_t(std::llround(e * kSampleRate));
  double m = 0;
  for (int64_t f = fs + 1; f < fe; ++f)
    for (int ch = 0; ch < 2; ++ch) m = std::max(m, std::abs(o.at(f, ch) - o.at(f - 1, ch)));
  return m;
}

struct Mix {
  std::vector<float> vL, vR, dL, dR, bL, bR, dryL, dryR;
};

/** Deterministic 4-stem song: vocal tones, beat-locked noise drums, 55 Hz bass, noise pad (OTHER). */
Mix makeMix(size_t n) {
  Mix m;
  for (auto* v : {&m.vL, &m.vR, &m.dL, &m.dR, &m.bL, &m.bR, &m.dryL, &m.dryR}) v->assign(n, 0.0f);
  probe::Lcg a(0x1234567u), b(0x89ABCDEu), c(0x2468ACEu), d(0x13579BDu);
  for (size_t i = 0; i < n; ++i) {
    const double t = double(i) / kSampleRate;
    const double beatT = t - std::floor(t / kBeat) * kBeat;
    const float kick = float(std::exp(-beatT / 0.03));
    m.vL[i] = float(0.20 * std::sin(2 * kPi * 440 * t) + 0.08 * std::sin(2 * kPi * 880 * t));
    m.vR[i] = 0.9f * m.vL[i];
    m.dL[i] = 0.35f * kick * a.next();
    m.dR[i] = 0.35f * kick * b.next();
    m.bL[i] = float(0.25 * std::sin(2 * kPi * 55 * t));
    m.bR[i] = m.bL[i];
    m.dryL[i] = m.vL[i] + m.dL[i] + m.bL[i] + 0.05f * c.next();
    m.dryR[i] = m.vR[i] + m.dR[i] + m.bR[i] + 0.05f * d.next();
  }
  return m;
}

void attachStems(Renderer& r, const Mix& m) {
  const int n = int(m.dryL.size());
  r.setDeckStem(0, 0, m.vL.data(), m.vR.data(), n);
  r.setDeckStem(0, 1, m.dL.data(), m.dR.data(), n);
  r.setDeckStem(0, 2, m.bL.data(), m.bR.data(), n);
}

bool stemsProbe() {
  bool ok = true;
  const Mix m = makeMix(size_t(9 * kSampleRate));
  std::vector<float> noVocL(m.dryL.size()), noVocR(m.dryR.size());
  for (size_t i = 0; i < m.dryL.size(); ++i) {
    noVocL[i] = m.dryL[i] - m.vL[i];
    noVocR[i] = m.dryR[i] - m.vR[i];
  }
  for (bool roll : {false, true}) {
    auto build = [&](Renderer& r, const std::vector<float>& l, const std::vector<float>& rr) {
      r.setHighQuality(false);
      loopDeck(r.deck(0), l, rr, 7.0);
      if (roll) r.setLoopRoll(0, true, 2.0, kBeat, 5.0, 8.0);
    };
    Renderer dry(kSampleRate), unity(kSampleRate), kill(kSampleRate), ref(kSampleRate);
    build(dry, m.dryL, m.dryR);
    build(unity, m.dryL, m.dryR);
    attachStems(unity, m);
    build(kill, m.dryL, m.dryR);
    attachStems(kill, m);
    setEnv(kill.deck(0).v2.stemGain[0], {0.0}, {0.0f});  // KIND_STEM_VOCALS = 0
    build(ref, noVocL, noVocR);
    const Out oDry = renderTo(dry, 6.5, 1024), oUnity = renderTo(unity, 6.5, 1024);
    const Out oKill = renderTo(kill, 6.5, 1024), oRef = renderTo(ref, 6.5, 1024);
    double uRms, uPeak, kRms, kPeak;
    diff(oUnity, oDry, uRms, uPeak);
    diff(oKill, oRef, kRms, kPeak);
    const double vocalDb = windowRmsDb(oDry, 1.0, 6.0) - windowRmsDb(oKill, 1.0, 6.0);
    std::printf("v2 stems %s: unity-vs-dry peak=%.3g (want 0); vocal-kill null rms=%.1f dBFS peak=%.3g (want < -90 dBFS); "
                "kill removed %.2f dB\n", roll ? "loop-roll" : "loop", uPeak, kRms, kPeak, vocalDb);
    if (uPeak != 0.0) { std::printf("FAIL v2 stems unity is not bit-identical to dry\n"); ok = false; }
    if (!(kRms < -90.0)) { std::printf("FAIL v2 stems vocal-kill null above -90 dBFS\n"); ok = false; }
    if (!(vocalDb > 1.0)) { std::printf("FAIL v2 stems vocal kill had no effect\n"); ok = false; }
  }
  return ok;
}

bool fxProbe() {
  bool ok = true;
  const Mix m = makeMix(size_t(8 * kSampleRate));
  struct Case {
    const char* name;
    void (*setup)(Renderer&);
    int kind;
    float idle;
    std::vector<double> t;
    std::vector<float> v;
  };
  const std::vector<double> win = {1.5, 1.6, 4.4, 4.5};
  const std::vector<float> on = {0.f, 1.f, 1.f, 0.f};
  const std::vector<Case> cases = {
      {"phaser", [](Renderer& r) { r.setPhaser(0, 4.0, 0.8f, 0.5f); }, 14, 0.f, win, on},
      {"gate", [](Renderer& r) { r.setGate(0, kBeat, 4.f, 0.1f); }, 15, 0.f, win, on},
      {"stutter", [](Renderer& r) { r.setGate(0, kBeat, -2.f, 0.f); }, 15, 0.f, win, on},
      {"pump-low", [](Renderer& r) { r.setPump(0, kBeat, false); }, 17, 0.f, win, on},
      {"pump-full", [](Renderer& r) { r.setPump(0, kBeat, true); }, 17, 0.f, win, on},
      {"riser", [](Renderer& r) { r.setRiser(0, kBeat, 6.0); }, 16, 0.f, win, on},
      {"filter-knob+res", [](Renderer& r) { bitchord::loopmix::v2Envelope(r.deck(0), 13)->times = {0.0};
                                            bitchord::loopmix::v2Envelope(r.deck(0), 13)->values = {2.5f}; },
       19, 0.f, {1.5, 3.0, 4.5}, {0.f, -0.9f, 0.85f}},
      {"brake", [](Renderer& r) { r.setBrake(0, 2.0, 1.0, false); }, 18, 0.f, {2.0, 3.0}, {0.f, 1.f}},
      {"spinback", [](Renderer& r) { r.setBrake(0, 2.0, 1.0, true); }, 18, 0.f, {2.0, 3.0}, {0.f, 1.f}},
  };
  Renderer plain(kSampleRate);
  plain.setHighQuality(false);
  straightDeck(plain.deck(0), m.dryL, m.dryR, 7.0, 1e18);
  const Out oPlain = renderTo(plain, 6.0, 777);
  for (const Case& c : cases) {
    Renderer idle(kSampleRate), live(kSampleRate);
    for (Renderer* r : {&idle, &live}) {
      r->setHighQuality(false);
      straightDeck(r->deck(0), m.dryL, m.dryR, 7.0, 1e18);
      c.setup(*r);
    }
    // Idle: the envelope is 0 throughout (for the knob+res case the Q multiplier is reset to 1).
    setEnv(*bitchord::loopmix::v2Envelope(idle.deck(0), c.kind), {0.0}, {c.idle});
    if (c.kind == 19) setEnv(*bitchord::loopmix::v2Envelope(idle.deck(0), 13), {0.0}, {1.0f});
    setEnv(*bitchord::loopmix::v2Envelope(live.deck(0), c.kind), c.t, c.v);
    const Out oIdle = renderTo(idle, 6.0, 777), oLive = renderTo(live, 6.0, 777);
    double iRms, iPeak, lRms, lPeak;
    diff(oIdle, oPlain, iRms, iPeak);
    diff(oLive, oPlain, lRms, lPeak);
    int dnIdle = 0, dnLive = 0;
    const bool cleanIdle = clean(oIdle, &dnIdle), cleanLive = clean(oLive, &dnLive);
    std::printf("v2 fx %-16s idle-vs-plain peak=%.3g  active diff rms=%.1f dBFS  finite/|x|<=1/no-denormal idle=%s live=%s (denormals %d/%d)\n",
                c.name, iPeak, lRms, cleanIdle ? "yes" : "NO", cleanLive ? "yes" : "NO", dnIdle, dnLive);
    if (iPeak != 0.0) { std::printf("FAIL v2 fx %s is not silent (bit-identical) at envelope 0\n", c.name); ok = false; }
    if (!cleanIdle || !cleanLive) { std::printf("FAIL v2 fx %s produced non-finite, clipped or denormal output\n", c.name); ok = false; }
    if (!(lRms > -60.0)) { std::printf("FAIL v2 fx %s had no audible effect when active\n", c.name); ok = false; }
  }
  return ok;
}

bool brakeProbe() {
  bool ok = true;
  const size_t n = size_t(7 * kSampleRate);
  std::vector<float> l(n), r(n);
  for (size_t i = 0; i < n; ++i) {
    const double t = double(i) / kSampleRate;
    l[i] = float(0.25 * std::sin(2 * kPi * 220 * t) + 0.15 * std::sin(2 * kPi * 330 * t));
    r[i] = float(0.25 * std::sin(2 * kPi * 220 * t + 0.3) + 0.15 * std::sin(2 * kPi * 330 * t + 0.5));
  }
  for (bool spin : {false, true}) {
    Renderer rr(kSampleRate);
    rr.setHighQuality(false);
    straightDeck(rr.deck(0), l, r, 6.0, 5.0);
    setEnv(rr.deck(0).gain, {0.0, 10.0}, {1.0f, 1.0f});  // fader stays up through stopAt
    rr.setBrake(0, 2.0, 1.5, spin);
    const Out o = renderTo(rr, 5.5, 512);
    const double refStep = maxStep(o, 1.0, 2.0);
    const double brakeStep = maxStep(o, 2.0, 3.7);
    const double tailDb = windowRmsDb(o, 3.6, 4.95);
    const double limit = spin ? 4.5 : 1.25;
    int dn = 0;
    const bool cl = clean(o, &dn);
    std::printf("v2 %s: pre-step=%.5f brake-step=%.5f (<= %.2fx) silence after=%.1f dBFS violations=%d clean=%s\n",
                spin ? "spinback" : "brake", refStep, brakeStep, limit, tailDb, o.violations, cl ? "yes" : "NO");
    if (!(brakeStep <= limit * refStep)) { std::printf("FAIL v2 brake click (step above the music's own)\n"); ok = false; }
    if (!(tailDb < -120.0)) { std::printf("FAIL v2 brake does not reach silence\n"); ok = false; }
    if (o.violations != 0) { std::printf("FAIL v2 completed brake tripped the exit gate\n"); ok = false; }
    if (!cl) { std::printf("FAIL v2 brake output not clean\n"); ok = false; }
  }
  return ok;
}

bool loudnessProbe() {
  bool ok = true;
  constexpr double kLen = 20.0, kFadeIn = 4.0, kFadeOut = 16.0, kLiftDb = 2.0;
  const size_t n = size_t((kLen + 1.0) * kSampleRate);
  std::vector<float> aL(n), aR(n), bL(n), bR(n);
  probe::Lcg s1(11u), s2(22u), s3(33u), s4(44u);
  const float bGain = float(std::pow(10.0, kLiftDb / 20.0));
  for (size_t i = 0; i < n; ++i) {
    aL[i] = 0.15f * s1.next();
    aR[i] = 0.15f * s2.next();
    bL[i] = 0.15f * bGain * s3.next();
    bR[i] = 0.15f * bGain * s4.next();
  }
  double maxDev[2] = {0, 0}, endGuard = 0;
  for (int guard = 0; guard < 2; ++guard) {
    Renderer r(kSampleRate);
    r.setHighQuality(false);
    straightDeck(r.deck(0), aL, aR, kLen, 1e18);
    straightDeck(r.deck(1), bL, bR, kLen, 1e18);
    // Constant-sum (linear) crossfade: right for correlated material, a ~3 dB dip for uncorrelated.
    setEnv(r.deck(0).gain, {0.0, kFadeIn, kFadeOut}, {1.f, 1.f, 0.f});
    setEnv(r.deck(1).gain, {0.0, kFadeIn, kFadeOut}, {0.f, 0.f, 1.f});
    if (guard) r.setLoudnessMatch(true, -14.f);
    const Out o = renderTo(r, kLen, 1024);
    if (guard) endGuard = r.loudnessGuardDb();
    bitchord::loopmix::v2::LoudnessMeter meter;
    meter.setup(kSampleRate);
    std::vector<double> st;  // short-term loudness at t = 0.1 k
    for (int64_t f = 0; f < int64_t(kLen * kSampleRate); ++f) {
      meter.push(float(o.at(f, 0)), float(o.at(f, 1)));
      if ((f + 1) % (kSampleRate / 10) == 0) st.push_back(meter.shortTermLufs());
    }
    auto stAt = [&](double t) { const int k = int(std::lround(t * 10.0)) - 1; return st[size_t(std::clamp(k, 0, int(st.size()) - 1))]; };
    const double la = stAt(kFadeIn), lb = stAt(kLen - 0.5);
    double worst = 0;
    for (double t = kFadeIn; t <= kLen - 0.5 + 1e-9; t += 0.1) {
      // The A->B line, power-averaged over the same 3 s window the meter sees.
      double p = 0;
      int cnt = 0;
      for (double tau = t - 3.0; tau < t; tau += 0.01, ++cnt) {
        const double x = std::clamp((tau - kFadeIn) / (kFadeOut - kFadeIn), 0.0, 1.0);
        p += std::pow(10.0, ((1.0 - x) * la + x * lb) / 10.0);
      }
      const double line = 10.0 * std::log10(p / cnt);
      const double dev = stAt(t) - line;
      if (std::abs(dev) > std::abs(worst)) worst = dev;
    }
    maxDev[guard] = worst;
    std::printf("v2 loudness guard=%s: A=%.2f LUFS B=%.2f LUFS worst deviation from A->B line %+.2f LU\n",
                guard ? "on " : "off", la, lb, worst);
  }
  if (!(std::abs(maxDev[0]) > 2.0)) { std::printf("FAIL v2 loudness probe insensitive (unguarded dip %.2f LU <= 2)\n", maxDev[0]); ok = false; }
  if (!(std::abs(maxDev[1]) <= 2.0)) { std::printf("FAIL v2 loudness guard deviation %.2f LU > 2 LU\n", maxDev[1]); ok = false; }
  std::printf("v2 loudness guard %s the 1.5 LU target (%.2f LU); guard gain after the overlap %.2f dB\n",
              std::abs(maxDev[1]) <= 1.5 ? "meets" : "misses (within the 2 LU fail threshold)", std::abs(maxDev[1]), endGuard);
  if (!(std::abs(endGuard) < 0.5)) { std::printf("FAIL v2 loudness guard did not release after the overlap\n"); ok = false; }
  return ok;
}

bool loudnessDropWindowProbe() {
  bool ok = true;
  constexpr double kLen = 18.0;
  constexpr double kDipStart = 8.0, kDropAt = 12.0, kBarSec = 2.0;  // 120 BPM (kBeat = 0.5 s)
  const size_t n = size_t((kLen + 1.0) * kSampleRate);
  std::vector<float> aL(n), aR(n), bL(n), bR(n);
  probe::Lcg s1(101u), s2(202u), s3(303u), s4(404u);
  for (size_t i = 0; i < n; ++i) {
    aL[i] = 0.19f * s1.next();
    aR[i] = 0.19f * s2.next();
    bL[i] = 0.19f * s3.next();
    bR[i] = 0.19f * s4.next();
  }
  auto runCase = [&](bool callSetter, double s0, double s1t, float bump,
                     double& preDropGuardDb, double& impactGuardDb) -> Out {
    Renderer r(kSampleRate);
    r.setHighQuality(false);
    straightDeck(r.deck(0), aL, aR, kLen, 1e18);
    straightDeck(r.deck(1), bL, bR, kLen, 1e18);
    // Pre-drop build dip at [8.5, 11.9] (0.35+0.35 uncorrelated = -6 dB below programme line),
    // followed by drop impact bar [12.0, 14.0] (0.85+0.85 uncorrelated = +1.6 dB above line/ceiling).
    setEnv(r.deck(0).gain,
           {0.0, 2.0, 7.9, 8.5, 11.9, kDropAt, kDropAt + kBarSec, kLen - 1.0},
           {1.f, 1.f, 0.7f, 0.35f, 0.35f, 0.85f, 0.85f, 0.f});
    setEnv(r.deck(1).gain,
           {0.0, 2.0, 7.9, 8.5, 11.9, kDropAt, kDropAt + kBarSec, kLen - 1.0},
           {0.f, 0.f, 0.7f, 0.35f, 0.35f, 0.85f, 0.85f, 1.f});
    r.setLoudnessMatch(true, -14.f);
    if (callSetter) r.setLoudnessDropWindow(s0, s1t, bump);
    r.prepare();
    Out o;
    o.first = r.firstFrame();
    const int64_t end = int64_t(std::llround(kLen * kSampleRate));
    o.pcm.assign(size_t(end - o.first) * 2, 0.0f);
    preDropGuardDb = 0.0;
    impactGuardDb = 0.0;
    bool gotPre = false, gotImpact = false;
    for (int64_t f = o.first; f < end;) {
      const int chunk = int(std::min<int64_t>(1024, end - f));
      r.render(f, o.pcm.data() + size_t(f - o.first) * 2, chunk);
      const double tMid = (double(f) + 0.5 * double(chunk)) / kSampleRate;
      const double gDb = double(r.loudnessGuardDb());
      if (tMid >= kDropAt - 0.8 && tMid < kDropAt) {
        if (!gotPre || gDb > preDropGuardDb) { preDropGuardDb = gDb; gotPre = true; }
      }
      if (tMid >= kDropAt + 1.0 && tMid < kDropAt + kBarSec) {
        if (!gotImpact || gDb < impactGuardDb) { impactGuardDb = gDb; gotImpact = true; }
      }
      f += chunk;
    }
    o.violations = r.exitGateViolations();
    return o;
  };
  double preOff = 0, impOff = 0, preInact = 0, impInact = 0, preOn = 0, impOn = 0;
  const Out oOff = runCase(false, 0.0, 0.0, 0.f, preOff, impOff);
  const Out oInact = runCase(true, 1e18, -1e18, 0.f, preInact, impInact);
  const Out oOn = runCase(true, kDipStart, kDropAt, 1.5f, preOn, impOn);
  double inactRms = 0, inactPeak = 0;
  diff(oOff, oInact, inactRms, inactPeak);
  std::printf("v2 drop-window: inactive-vs-uncalled peak=%.3g  preDropGuard off=%+.2f dB on=%+.2f dB  impactGuard off=%+.2f dB on=%+.2f dB\n",
              inactPeak, preOff, preOn, impOff, impOn);
  if (inactPeak != 0.0) { std::printf("FAIL v2 drop-window inactive is not bit-identical to uncalled\n"); ok = false; }
  if (!(preOff > 0.4)) { std::printf("FAIL v2 drop-window pre-drop probe insensitive (off=%.2f dB)\n", preOff); ok = false; }
  if (!(preOn <= 0.05)) { std::printf("FAIL v2 drop-window boosted pre-drop dip (on=%.2f dB)\n", preOn); ok = false; }
  if (!(impOn > impOff + 0.25)) { std::printf("FAIL v2 drop-window did not allow +1.5 LU drop impact (off=%.2f on=%.2f)\n", impOff, impOn); ok = false; }
  return ok;
}

}  // namespace v2probe

int runV2Probe() {
  bool ok = true;
  ok = v2probe::stemsProbe() && ok;
  ok = v2probe::fxProbe() && ok;
  ok = v2probe::brakeProbe() && ok;
  ok = v2probe::loudnessProbe() && ok;
  ok = v2probe::loudnessDropWindowProbe() && ok;
  std::printf("%s v2-probe (capabilities 0x%x)\n", ok ? "ok" : "FAIL", Renderer::capabilities());
  return ok ? 0 : 1;
}

// ---------------------------------------------------------------------------
// Standalone --render <script.json> --out <file.wav> mode (TransitionScriptV1)
// ---------------------------------------------------------------------------

namespace render_script {

struct JsonVal {
  enum Kind { J_NULL, J_BOOL, J_NUM, J_STR, J_ARR, J_OBJ };
  Kind kind = J_NULL;
  bool b = false;
  double num = 0.0;
  std::string str;
  std::vector<JsonVal> arr;
  std::vector<std::pair<std::string, JsonVal>> obj;

  const JsonVal* get(const char* key) const {
    if (kind != J_OBJ) return nullptr;
    for (const auto& kv : obj) {
      if (kv.first == key) return &kv.second;
    }
    return nullptr;
  }
  bool asBool(bool def = false) const {
    if (kind == J_BOOL) return b;
    if (kind == J_NUM) return num != 0.0;
    return def;
  }
  double asNum(double def = 0.0) const {
    return (kind == J_NUM && std::isfinite(num)) ? num : def;
  }
  std::string asStr(const std::string& def = "") const {
    return kind == J_STR ? str : def;
  }
  bool boolField(const char* key, bool def = false) const {
    const JsonVal* v = get(key);
    return (v && v->kind != J_NULL) ? v->asBool(def) : def;
  }
  double numField(const char* key, double def = 0.0) const {
    const JsonVal* v = get(key);
    return (v && v->kind != J_NULL) ? v->asNum(def) : def;
  }
  bool hasNum(const char* key) const {
    const JsonVal* v = get(key);
    return v != nullptr && v->kind == J_NUM && std::isfinite(v->num);
  }
  std::string strField(const char* key, const std::string& def = "") const {
    const JsonVal* v = get(key);
    return (v && v->kind != J_NULL) ? v->asStr(def) : def;
  }
};

class JsonParser {
 public:
  explicit JsonParser(const std::string& src) : s_(src) {}

  bool parse(JsonVal& out, std::string& err) {
    skipWs();
    if (!parseValue(out, 0, err)) return false;
    skipWs();
    if (pos_ != s_.size()) {
      err = "trailing bytes at offset " + std::to_string(pos_);
      return false;
    }
    return true;
  }

 private:
  const std::string& s_;
  size_t pos_ = 0;

  void skipWs() {
    while (pos_ < s_.size()) {
      const unsigned char c = static_cast<unsigned char>(s_[pos_]);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') ++pos_;
      else break;
    }
  }

  bool matchLit(const char* lit) {
    const size_t n = std::strlen(lit);
    if (pos_ + n > s_.size()) return false;
    if (std::memcmp(s_.data() + pos_, lit, n) != 0) return false;
    pos_ += n;
    return true;
  }

  bool parseString(std::string& out, std::string& err) {
    if (pos_ >= s_.size() || s_[pos_] != '"') {
      err = "expected '\"' at offset " + std::to_string(pos_);
      return false;
    }
    ++pos_;
    out.clear();
    while (pos_ < s_.size()) {
      const char c = s_[pos_++];
      if (c == '"') return true;
      if (c == '\\') {
        if (pos_ >= s_.size()) { err = "unterminated escape"; return false; }
        const char e = s_[pos_++];
        switch (e) {
          case '"': out.push_back('"'); break;
          case '\\': out.push_back('\\'); break;
          case '/': out.push_back('/'); break;
          case 'b': out.push_back('\b'); break;
          case 'f': out.push_back('\f'); break;
          case 'n': out.push_back('\n'); break;
          case 'r': out.push_back('\r'); break;
          case 't': out.push_back('\t'); break;
          case 'u': {
            if (pos_ + 4 > s_.size()) { err = "short \\u escape"; return false; }
            uint32_t cp = 0;
            for (int k = 0; k < 4; ++k) {
              const char h = s_[pos_++];
              cp <<= 4;
              if (h >= '0' && h <= '9') cp |= uint32_t(h - '0');
              else if (h >= 'a' && h <= 'f') cp |= uint32_t(h - 'a' + 10);
              else if (h >= 'A' && h <= 'F') cp |= uint32_t(h - 'A' + 10);
              else { err = "bad hex in \\u escape"; return false; }
            }
            if (cp < 0x80) {
              out.push_back(static_cast<char>(cp));
            } else if (cp < 0x800) {
              out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
              out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            } else {
              out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
              out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
              out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
            }
            break;
          }
          default:
            err = "unknown JSON escape";
            return false;
        }
      } else {
        out.push_back(c);
      }
    }
    err = "unterminated string";
    return false;
  }

  bool parseNumber(double& out, std::string& err) {
    const char* begin = s_.c_str() + pos_;
    char* end = nullptr;
    const double val = std::strtod(begin, &end);
    if (end == begin || !std::isfinite(val)) {
      err = "invalid number at offset " + std::to_string(pos_);
      return false;
    }
    pos_ = size_t(end - s_.c_str());
    out = val;
    return true;
  }

  bool parseValue(JsonVal& v, int depth, std::string& err) {
    if (depth > 64) { err = "JSON nesting too deep"; return false; }
    skipWs();
    if (pos_ >= s_.size()) { err = "unexpected end of JSON"; return false; }
    const char c = s_[pos_];
    if (c == 'n') {
      if (!matchLit("null")) { err = "expected null"; return false; }
      v = JsonVal();
      v.kind = JsonVal::J_NULL;
      return true;
    }
    if (c == 't') {
      if (!matchLit("true")) { err = "expected true"; return false; }
      v = JsonVal();
      v.kind = JsonVal::J_BOOL;
      v.b = true;
      return true;
    }
    if (c == 'f') {
      if (!matchLit("false")) { err = "expected false"; return false; }
      v = JsonVal();
      v.kind = JsonVal::J_BOOL;
      v.b = false;
      return true;
    }
    if (c == '"') {
      v = JsonVal();
      v.kind = JsonVal::J_STR;
      return parseString(v.str, err);
    }
    if (c == '[') {
      ++pos_;
      v = JsonVal();
      v.kind = JsonVal::J_ARR;
      skipWs();
      if (pos_ < s_.size() && s_[pos_] == ']') { ++pos_; return true; }
      while (true) {
        JsonVal item;
        if (!parseValue(item, depth + 1, err)) return false;
        v.arr.push_back(std::move(item));
        skipWs();
        if (pos_ >= s_.size()) { err = "unterminated array"; return false; }
        if (s_[pos_] == ']') { ++pos_; return true; }
        if (s_[pos_] != ',') { err = "expected ',' or ']' in array"; return false; }
        ++pos_;
      }
    }
    if (c == '{') {
      ++pos_;
      v = JsonVal();
      v.kind = JsonVal::J_OBJ;
      skipWs();
      if (pos_ < s_.size() && s_[pos_] == '}') { ++pos_; return true; }
      while (true) {
        skipWs();
        std::string key;
        if (!parseString(key, err)) return false;
        skipWs();
        if (pos_ >= s_.size() || s_[pos_] != ':') { err = "expected ':'"; return false; }
        ++pos_;
        JsonVal val;
        if (!parseValue(val, depth + 1, err)) return false;
        v.obj.emplace_back(std::move(key), std::move(val));
        skipWs();
        if (pos_ >= s_.size()) { err = "unterminated object"; return false; }
        if (s_[pos_] == '}') { ++pos_; return true; }
        if (s_[pos_] != ',') { err = "expected ',' or '}' in object"; return false; }
        ++pos_;
      }
    }
    if (c == '-' || (c >= '0' && c <= '9')) {
      v = JsonVal();
      v.kind = JsonVal::J_NUM;
      return parseNumber(v.num, err);
    }
    err = std::string("unexpected char '") + c + "' at offset " + std::to_string(pos_);
    return false;
  }
};

std::string upperStr(std::string s) {
  for (char& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
  return s;
}

int parseDeckIndex(const JsonVal* v, int def = 0) {
  if (!v || v->kind == JsonVal::J_NULL) return def;
  if (v->kind == JsonVal::J_NUM) return v->num >= 0.5 ? 1 : 0;
  if (v->kind == JsonVal::J_STR) {
    const std::string u = upperStr(v->str);
    if (u == "IN" || u == "B" || u == "1") return 1;
    if (u == "OUT" || u == "A" || u == "0") return 0;
  }
  return def;
}

int parseEnvelopeKind(const JsonVal* v) {
  if (!v || v->kind == JsonVal::J_NULL) return -1;
  if (v->kind == JsonVal::J_NUM) {
    const int k = int(std::llround(v->num));
    return (k >= 0 && k <= 19) ? k : -1;
  }
  if (v->kind != JsonVal::J_STR) return -1;
  const std::string u = upperStr(v->str);
  if (u == "GAIN") return 0;
  if (u == "LOW_DB") return 1;
  if (u == "HIGH_PASS_HZ" || u == "HPF_HZ") return 2;
  if (u == "LOW_PASS_HZ" || u == "LPF_HZ") return 3;
  if (u == "ECHO_SEND") return 4;
  if (u == "MID_DB") return 5;
  if (u == "HIGH_DB") return 6;
  if (u == "FLANGER_WET") return 7;
  if (u == "REVERB_SEND") return 8;
  if (u == "STEM_VOCALS") return 9;
  if (u == "STEM_DRUMS") return 10;
  if (u == "STEM_BASS") return 11;
  if (u == "STEM_OTHER") return 12;
  if (u == "FILTER_RES") return 13;
  if (u == "PHASER_WET") return 14;
  if (u == "GATE_DEPTH") return 15;
  if (u == "RISER_LEVEL") return 16;
  if (u == "PUMP_DEPTH") return 17;
  if (u == "BRAKE") return 18;
  if (u == "FILTER_KNOB") return 19;
  return -1;
}

int parseStemKind(const JsonVal* v) {
  if (!v || v->kind == JsonVal::J_NULL) return -1;
  if (v->kind == JsonVal::J_NUM) {
    const int k = int(std::llround(v->num));
    return (k >= 0 && k <= 3) ? k : -1;
  }
  if (v->kind != JsonVal::J_STR) return -1;
  const std::string u = upperStr(v->str);
  if (u == "VOCALS" || u == "VOCAL") return 0;
  if (u == "DRUMS" || u == "DRUM") return 1;
  if (u == "BASS") return 2;
  if (u == "OTHER" || u == "ACCOMPANIMENT") return 3;
  return -1;
}

bitchord::loopmix::Envelope* envelopeSlot(bitchord::loopmix::Deck& d, int kind) {
  switch (kind) {
    case 0: return &d.gain;
    case 1: return &d.lowDb;
    case 2: return &d.highPassHz;
    case 3: return &d.lowPassHz;
    case 4: return &d.echoSend;
    case 5: return &d.midDb;
    case 6: return &d.highDb;
    case 7: return &d.flangerWet;
    case 8: return &d.reverbSend;
    default: return bitchord::loopmix::v2Envelope(d, kind);
  }
}

std::string parentDirOf(const std::string& p) {
  const size_t slash = p.find_last_of("/\\");
  return slash == std::string::npos ? "" : p.substr(0, slash);
}

std::string resolveAudioPath(const std::string& rawPath, const std::string& scriptDir) {
  if (rawPath.empty()) return "";
  std::FILE* f = std::fopen(rawPath.c_str(), "rb");
  if (f) { std::fclose(f); return rawPath; }
  if (!scriptDir.empty() && rawPath[0] != '/') {
    const std::string joined = scriptDir + "/" + rawPath;
    std::FILE* f2 = std::fopen(joined.c_str(), "rb");
    if (f2) { std::fclose(f2); return joined; }
  }
  return rawPath;
}

bool loadAudioAuto(const std::string& rawPath, const std::string& scriptDir, int srcRateHint,
                   int targetRate, std::vector<float>& outL, std::vector<float>& outR,
                   std::string& err) {
  const std::string path = resolveAudioPath(rawPath, scriptDir);
  std::FILE* f = std::fopen(path.c_str(), "rb");
  if (!f) {
    err = "cannot open audio file: " + path;
    return false;
  }
  std::fseek(f, 0, SEEK_END);
  const long sz = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  if (sz <= 0) {
    std::fclose(f);
    err = "empty audio file: " + path;
    return false;
  }
  uint8_t hdr[12] = {};
  const size_t nHdr = std::fread(hdr, 1, std::min<size_t>(12, size_t(sz)), f);
  std::fclose(f);
  if (nHdr == 12 && std::memcmp(hdr, "RIFF", 4) == 0 && std::memcmp(hdr + 8, "WAVE", 4) == 0) {
    return readWavAtRate(path, outL, outR, targetRate, err);
  }
  // Headerless interleaved float32 stereo PCM (.f32 / .pcm).
  if (sz % 8 != 0) {
    err = "audio file is neither WAVE nor stereo float32 interleaved (size=" +
          std::to_string(sz) + "): " + path;
    return false;
  }
  const size_t srcFrames = size_t(sz) / 8;
  std::vector<float> inter(srcFrames * 2);
  std::FILE* fr = std::fopen(path.c_str(), "rb");
  if (!fr || std::fread(inter.data(), sizeof(float), inter.size(), fr) != inter.size()) {
    if (fr) std::fclose(fr);
    err = "short read on float32 file: " + path;
    return false;
  }
  std::fclose(fr);
  const int srcRate = srcRateHint > 0 ? srcRateHint : targetRate;
  std::vector<float> srcL(srcFrames), srcR(srcFrames);
  for (size_t i = 0; i < srcFrames; ++i) {
    srcL[i] = inter[i * 2];
    srcR[i] = inter[i * 2 + 1];
  }
  if (srcRate == targetRate || srcFrames == 0) {
    outL = std::move(srcL);
    outR = std::move(srcR);
    return true;
  }
  const size_t outFrames = static_cast<size_t>(std::llround(
      double(srcFrames) * double(targetRate) / double(srcRate)));
  outL.assign(outFrames, 0.f);
  outR.assign(outFrames, 0.f);
  const double step = double(srcRate) / double(targetRate);
  for (size_t i = 0; i < outFrames; ++i) {
    const double pos = double(i) * step;
    const size_t i0 = std::min(srcFrames - 1, size_t(std::floor(pos)));
    const size_t i1 = std::min(srcFrames - 1, i0 + 1);
    const float f = float(pos - double(i0));
    outL[i] = srcL[i0] * (1.f - f) + srcL[i1] * f;
    outR[i] = srcR[i0] * (1.f - f) + srcR[i1] * f;
  }
  return true;
}

void sliceRegionIfFullTrack(std::vector<float>& L, std::vector<float>& R,
                            double startSec, double endSec, int sr) {
  if (!(startSec > 0.0) || L.empty()) return;
  const size_t sIdx = size_t(std::max<int64_t>(0, int64_t(std::llround(startSec * sr))));
  const size_t eIdx = (endSec > startSec)
      ? std::min(L.size(), size_t(std::llround(endSec * sr)))
      : L.size();
  if (L.size() + 2048 >= eIdx && sIdx < eIdx && eIdx > sIdx) {
    L.assign(L.begin() + sIdx, L.begin() + eIdx);
    R.assign(R.begin() + sIdx, R.begin() + eIdx);
  }
}

struct ParsedKnots {
  std::vector<double> mixSec;
  std::vector<double> trackSec;
  double firstBpm = 0.0;
};

ParsedKnots parseKnotArray(const JsonVal* arrVal) {
  ParsedKnots pk;
  if (!arrVal || arrVal->kind != JsonVal::J_ARR) return pk;
  for (const auto& item : arrVal->arr) {
    double mSec = 0.0, tSec = 0.0;
    if (item.kind == JsonVal::J_OBJ) {
      if (item.hasNum("exactMixSec")) mSec = item.numField("exactMixSec");
      else if (item.hasNum("mixSec")) mSec = item.numField("mixSec");
      else if (item.hasNum("t")) mSec = item.numField("t");
      else mSec = item.numField("mixTimeMs") * 0.001;

      if (item.hasNum("exactTrackPosSec")) tSec = item.numField("exactTrackPosSec");
      else if (item.hasNum("trackPosSec")) tSec = item.numField("trackPosSec");
      else if (item.hasNum("posSec")) tSec = item.numField("posSec");
      else if (item.hasNum("pos")) tSec = item.numField("pos");
      else tSec = item.numField("trackPosMs") * 0.001;

      if (!(pk.firstBpm > 0.0) && item.hasNum("bpm")) {
        const double b = item.numField("bpm");
        if (b > 20.0 && b < 300.0) pk.firstBpm = b;
      }
      pk.mixSec.push_back(mSec);
      pk.trackSec.push_back(tSec);
    } else if (item.kind == JsonVal::J_ARR && item.arr.size() >= 2) {
      pk.mixSec.push_back(item.arr[0].asNum(0.0));
      pk.trackSec.push_back(item.arr[1].asNum(0.0));
    }
  }
  return pk;
}

bool knotsLookValid(const ParsedKnots& pk) {
  if (pk.mixSec.size() < 2) return false;
  bool anyAdvance = false;
  for (size_t i = 1; i < pk.mixSec.size(); ++i) {
    const double dt = pk.mixSec[i] - pk.mixSec[i - 1];
    const double dp = pk.trackSec[i] - pk.trackSec[i - 1];
    if (!(dt > 0.0)) return false;
    if (std::abs(dp) > 1e-4) {
      anyAdvance = true;
      const double rate = dp / dt;
      if (rate < 0.45 || rate > 2.2) return false;
    }
  }
  return anyAdvance;
}

int runRenderMode(const std::string& scriptPath, const std::string& outWavPath) {
  std::FILE* f = std::fopen(scriptPath.c_str(), "rb");
  if (!f) {
    std::fprintf(stderr, "error: cannot open script %s\n", scriptPath.c_str());
    return 1;
  }
  std::fseek(f, 0, SEEK_END);
  const long sz = std::ftell(f);
  std::fseek(f, 0, SEEK_SET);
  if (sz <= 0) {
    std::fclose(f);
    std::fprintf(stderr, "error: empty script %s\n", scriptPath.c_str());
    return 1;
  }
  std::string raw(size_t(sz), '\0');
  if (std::fread(raw.data(), 1, raw.size(), f) != raw.size()) {
    std::fclose(f);
    std::fprintf(stderr, "error: failed to read %s\n", scriptPath.c_str());
    return 1;
  }
  std::fclose(f);

  JsonVal root;
  std::string err;
  JsonParser parser(raw);
  if (!parser.parse(root, err) || root.kind != JsonVal::J_OBJ) {
    std::fprintf(stderr, "error: invalid script JSON (%s): %s\n",
                 scriptPath.c_str(), err.empty() ? "root must be object" : err.c_str());
    return 2;
  }

  const int sr = std::clamp(int(std::llround(root.numField("sampleRateHz", double(kSampleRate)))),
                            16000, 192000);
  const std::string scriptDir = parentDirOf(scriptPath);

  const JsonVal* outReg = root.get("outRegion");
  const JsonVal* inReg = root.get("inRegion");
  std::string outPath = outReg ? outReg->strField("pcmPath") : "";
  std::string inPath = inReg ? inReg->strField("pcmPath") : "";
  if (outPath.empty()) outPath = root.strField("outPcmPath");
  if (inPath.empty()) inPath = root.strField("inPcmPath");
  if (outPath.empty() && inPath.empty()) {
    std::fprintf(stderr, "error: script missing outRegion.pcmPath / inRegion.pcmPath\n");
    return 2;
  }

  Renderer r(sr);
  r.setHighQuality(root.boolField("highQuality", true));

  struct RegionMeta {
    double startSec = 0.0, endSec = 0.0, prerollSec = 0.4, stopAtSec = 1e18, fadeOutTrackSec = -1.0;
    float level = 1.f;
    bool runOn = false;
    int rateHint = 48000;
  };
  auto loadDeck = [&](int dIdx, const JsonVal* reg, const std::string& pPath, bool defRunOn, RegionMeta& rm) -> bool {
    rm.runOn = defRunOn;
    rm.rateHint = sr;
    if (reg && reg->kind == JsonVal::J_OBJ) {
      rm.startSec = reg->numField("startMs", 0.0) * 0.001;
      rm.endSec = reg->numField("endMs", 0.0) * 0.001;
      rm.prerollSec = reg->hasNum("prerollMs") ? reg->numField("prerollMs", 400.0) * 0.001
                                               : reg->numField("prerollSec", 0.4);
      if (reg->hasNum("stopAtMixMs")) rm.stopAtSec = reg->numField("stopAtMixMs") * 0.001;
      else if (reg->hasNum("stopAtSec")) rm.stopAtSec = reg->numField("stopAtSec");
      if (reg->hasNum("fadeOutTrackMs")) rm.fadeOutTrackSec = reg->numField("fadeOutTrackMs") * 0.001;
      rm.level = float(reg->numField("levelGain", 1.0));
      rm.runOn = reg->boolField("runOn", defRunOn);
      rm.rateHint = int(std::llround(reg->numField("sampleRateHz", double(sr))));
    }
    auto& d = r.deck(dIdx);
    d.regionStart = rm.startSec;
    d.preroll = std::clamp(rm.prerollSec, 0.0, 4.0);
    d.stopAt = rm.stopAtSec;
    d.level = std::clamp(rm.level, 0.f, 4.f);
    d.runOn = rm.runOn;
    if (pPath.empty()) return true;
    if (!loadAudioAuto(pPath, scriptDir, rm.rateHint, sr, d.left, d.right, err)) {
      std::fprintf(stderr, "error: loading deck %d PCM: %s\n", dIdx, err.c_str());
      return false;
    }
    sliceRegionIfFullTrack(d.left, d.right, rm.startSec, rm.endSec, sr);
    return true;
  };

  RegionMeta outMeta, inMeta;
  if (!loadDeck(0, outReg, outPath, false, outMeta)) return 1;
  if (!loadDeck(1, inReg, inPath, true, inMeta)) return 1;

  // Envelopes & transition timing scan.
  double maxEnvSec = 0.0;
  double fadeStart = 0.0, fadeEnd = 0.0;
  bool haveFadeStart = false;
  if (const JsonVal* envs = root.get("envelopes"); envs && envs->kind == JsonVal::J_ARR) {
    for (const auto& ev : envs->arr) {
      if (ev.kind != JsonVal::J_OBJ) continue;
      const int dIdx = parseDeckIndex(ev.get("deck"), -1);
      const int kId = parseEnvelopeKind(ev.get("kind"));
      if (dIdx < 0 || dIdx > 1 || kId < 0) continue;
      auto* slot = envelopeSlot(r.deck(dIdx), kId);
      const JsonVal* tv = ev.get("timesSec");
      bool msScale = false;
      if (!tv || tv->kind != JsonVal::J_ARR) {
        tv = ev.get("timesMs");
        msScale = true;
      }
      const JsonVal* vv = ev.get("values");
      if (!slot || !tv || !vv || tv->kind != JsonVal::J_ARR || vv->kind != JsonVal::J_ARR) continue;
      const size_t n = std::min(tv->arr.size(), vv->arr.size());
      if (n == 0) continue;
      slot->times.resize(n);
      slot->values.resize(n);
      for (size_t i = 0; i < n; ++i) {
        slot->times[i] = tv->arr[i].asNum(0.0) * (msScale ? 0.001 : 1.0);
        slot->values[i] = float(vv->arr[i].asNum(0.0));
        maxEnvSec = std::max(maxEnvSec, slot->times[i]);
      }
      slot->exponential = ev.boolField("exponential", kId == 2 || kId == 3);
      if (kId == 0) {
        if (!haveFadeStart || slot->times.front() < fadeStart) {
          fadeStart = slot->times.front();
          haveFadeStart = true;
        }
        fadeEnd = std::max(fadeEnd, slot->times.back());
      }
    }
  }

  // Render duration calculation.
  const double outDur = r.deck(0).left.empty() ? 0.0 : double(r.deck(0).left.size()) / double(sr);
  const double inDur = r.deck(1).left.empty() ? 0.0 : double(r.deck(1).left.size()) / double(sr);
  double renderDurSec = std::max({outDur, maxEnvSec, fadeEnd + 2.0, inDur});
  if (outDur > 0.0 && inDur > 0.0 && root.hasNum("durationSec") == false && !root.hasNum("totalFrames")) {
    renderDurSec = std::max(outDur, fadeEnd + 2.0);
  }
  if (root.hasNum("durationSec")) renderDurSec = std::max(0.1, root.numField("durationSec"));
  if (root.hasNum("totalFrames")) renderDurSec = std::max(1.0, root.numField("totalFrames")) / double(sr);
  if (!(renderDurSec > 0.0)) renderDurSec = 8.0;

  // Knots: support per-deck knots or top-level TransitionScriptV1 knots array.
  const JsonVal* topKnots = root.get("knots");
  ParsedKnots pkOut, pkIn;
  double defaultBpm = 120.0;
  if (root.get("outKnots")) pkOut = parseKnotArray(root.get("outKnots"));
  else if (topKnots && topKnots->kind == JsonVal::J_OBJ && topKnots->get("out")) pkOut = parseKnotArray(topKnots->get("out"));
  else if (outReg && outReg->get("knots")) pkOut = parseKnotArray(outReg->get("knots"));
  else if (topKnots && topKnots->kind == JsonVal::J_ARR) pkOut = parseKnotArray(topKnots);

  if (root.get("inKnots")) pkIn = parseKnotArray(root.get("inKnots"));
  else if (topKnots && topKnots->kind == JsonVal::J_OBJ && topKnots->get("in")) pkIn = parseKnotArray(topKnots->get("in"));
  else if (inReg && inReg->get("knots")) pkIn = parseKnotArray(inReg->get("knots"));

  if (pkOut.firstBpm > 0.0) defaultBpm = pkOut.firstBpm;
  if (pkIn.firstBpm > 0.0 && !(pkOut.firstBpm > 0.0)) defaultBpm = pkIn.firstBpm;

  auto assignKnots = [&](int dIdx, const ParsedKnots& pk, double holdUntilSec) {
    auto& d = r.deck(dIdx);
    const double bufDur = d.left.empty() ? renderDurSec : double(d.left.size()) / double(sr);
    const double span = std::max(1.0, std::max(renderDurSec, bufDur));
    if (knotsLookValid(pk)) {
      d.knotTimes.resize(pk.mixSec.size());
      d.knotPositions.resize(pk.trackSec.size());
      for (size_t i = 0; i < pk.mixSec.size(); ++i) {
        d.knotTimes[i] = pk.mixSec[i] * double(sr);
        d.knotPositions[i] = std::max(0.0, pk.trackSec[i] - d.regionStart) * double(sr);
      }
      return;
    }
    if (d.left.empty()) return;
    if (holdUntilSec > 0.25 && (span - holdUntilSec) > 0.5) {
      d.knotTimes = {0.0, holdUntilSec * double(sr), renderDurSec * double(sr)};
      d.knotPositions = {0.0, 0.0, std::max(0.1, renderDurSec - holdUntilSec) * double(sr)};
    } else {
      d.knotTimes = {0.0, renderDurSec * double(sr)};
      d.knotPositions = {0.0, renderDurSec * double(sr)};
    }
  };
  // Hold IN silently at sample 0 until GAIN rises when IN wasn't pre-shifted by inKnots.
  double inHoldSec = 0.0;
  if (!r.deck(1).gain.empty()) {
    const auto& gt = r.deck(1).gain.times;
    const auto& gv = r.deck(1).gain.values;
    if (!gv.empty() && gv.front() <= 1e-5f) {
      inHoldSec = gt.front();
      for (size_t i = 1; i < std::min(gt.size(), gv.size()); ++i) {
        if (gv[i] > 1e-5f) {
          inHoldSec = gt[i - 1];
          break;
        }
      }
    }
  }
  assignKnots(0, pkOut, 0.0);
  assignKnots(1, pkIn, inHoldSec);

  // Loop bounds.
  if (const JsonVal* lb = root.get("loopBounds"); lb && lb->kind == JsonVal::J_OBJ) {
    auto applyBounds = [&](int dIdx, const char* kStart, const char* kEnd, const char* kRelMix, const char* kRelUnwrap) {
      auto& d = r.deck(dIdx);
      if (lb->hasNum(kStart) && lb->hasNum(kEnd)) {
        const int64_t ls = int64_t(std::llround((lb->numField(kStart) * 0.001 - d.regionStart) * double(sr)));
        const int64_t le = int64_t(std::llround((lb->numField(kEnd) * 0.001 - d.regionStart) * double(sr)));
        if (ls >= 0 && le > ls && le <= int64_t(d.left.size())) {
          d.loopStart = ls;
          d.loopEnd = le;
        }
      }
      if (lb->hasNum(kRelUnwrap)) {
        d.releaseAt = lb->numField(kRelUnwrap);
      } else if (lb->hasNum(kRelMix) && !d.knotTimes.empty()) {
        d.releaseAt = d.knotHeard(lb->numField(kRelMix) * 0.001 * double(sr));
      }
    };
    applyBounds(0, "outLoopStartMs", "outLoopEndMs", "outReleaseAtMixMs", "outReleaseAtSamples");
    applyBounds(1, "inLoopStartMs", "inLoopEndMs", "inReleaseAtMixMs", "inReleaseAtSamples");
  }

  // Stems (TransitionScriptV1 stems array or stems.{out,in}.{vocals,drums,bass}Path).
  if (const JsonVal* stems = root.get("stems")) {
    auto attachOne = [&](int dIdx, int sKind, const std::string& sPath, double stStartSec, double stEndSec) -> bool {
      if (sPath.empty() || sKind == 3 || r.deck(dIdx).left.empty()) return true;
      std::vector<float> sL, sR;
      if (!loadAudioAuto(sPath, scriptDir, sr, sr, sL, sR, err)) {
        std::fprintf(stderr, "error: loading stem deck=%d kind=%d: %s\n", dIdx, sKind, err.c_str());
        return false;
      }
      sliceRegionIfFullTrack(sL, sR, stStartSec, stEndSec, sr);
      const size_t regLen = r.deck(dIdx).left.size();
      if (sL.size() < regLen) { sL.resize(regLen, 0.f); sR.resize(regLen, 0.f); }
      r.setDeckStem(dIdx, sKind, sL.data(), sR.data(), int(regLen));
      return true;
    };
    if (stems->kind == JsonVal::J_ARR) {
      for (const auto& st : stems->arr) {
        if (st.kind != JsonVal::J_OBJ) continue;
        const int dIdx = parseDeckIndex(st.get("deck"), 0);
        const int sKind = parseStemKind(st.get("stem"));
        if (sKind < 0) continue;
        if (!attachOne(dIdx, sKind, st.strField("pcmPath"),
                       st.numField("startMs") * 0.001, st.numField("endMs") * 0.001)) {
          return 1;
        }
      }
    } else if (stems->kind == JsonVal::J_OBJ) {
      for (int dIdx = 0; dIdx < 2; ++dIdx) {
        const JsonVal* dObj = stems->get(dIdx == 0 ? "out" : "in");
        if (!dObj || dObj->kind != JsonVal::J_OBJ) continue;
        const char* keys[3] = {"vocalsPath", "drumsPath", "bassPath"};
        for (int k = 0; k < 3; ++k) {
          const std::string p = dObj->strField(keys[k]);
          if (!p.empty() && !attachOne(dIdx, k, p, 0.0, 0.0)) return 1;
        }
      }
    }
  }

  // Loudness & true-peak limiter settings.
  if (const JsonVal* loud = root.get("loudness"); loud && loud->kind == JsonVal::J_OBJ) {
    const bool en = loud->boolField("enabled", true);
    const float target = float(loud->numField("targetLufs", -14.0));
    const bool tp = loud->boolField("truePeakLimiter", true);
    const float tpDb = float(loud->numField("truePeakCeilingDbtp", -1.0));
    r.setLoudnessMatch(en, target);
    r.setTruePeak(tp);
    r.setTruePeakCeilingDb(tpDb);
    if (const JsonVal* dw = root.get("dropWindowMs"); dw && dw->kind == JsonVal::J_OBJ) {
      r.setLoudnessDropWindow(dw->numField("startMs") * 0.001, dw->numField("endMs") * 0.001,
                              float(loud->numField("dropBumpLu", 0.0)));
    }
  } else {
    r.setLoudnessMatch(true, -14.f);
    r.setTruePeak(true);
    r.setTruePeakCeilingDb(-1.0f);
  }

  // Part A §5 FX configuration.
  double gridBeatSec = 60.0 / defaultBpm;
  if (const JsonVal* fx = root.get("fxParams"); fx && fx->kind == JsonVal::J_OBJ) {
    if (fx->boolField("isolate", false)) {
      r.setIsolate(0, true);
      r.setIsolate(1, true);
    }
    if (fx->hasNum("svfQ")) {
      r.setSvfQ(0, float(fx->numField("svfQ", 0.707)));
      r.setSvfQ(1, float(fx->numField("svfQ", 0.707)));
    }
    if (const JsonVal* loop = fx->get("loop"); loop && loop->kind == JsonVal::J_OBJ && loop->boolField("enabled")) {
      r.setLoopSeamMs(0, loop->numField("seamCrossfadeMs", 8.0));
    }
    if (const JsonVal* roll = fx->get("roll"); roll && roll->kind == JsonVal::J_OBJ && roll->boolField("enabled")) {
      const int dIdx = parseDeckIndex(roll->get("deck"), 0);
      const double bSec = roll->numField("beatSec", gridBeatSec);
      gridBeatSec = bSec;
      const double totB = roll->numField("totalBeats", 8.0);
      double relSec = roll->numField("releaseMixSec", 0.0);
      double stSec = roll->numField("startMixSec", 0.0);
      if (relSec <= stSec && fadeEnd > 0.0) {
        relSec = fadeEnd;
        stSec = std::max(0.0, relSec - totB * bSec);
      }
      std::vector<double> sched;
      if (const JsonVal* sc = roll->get("scheduleBeats"); sc && sc->kind == JsonVal::J_ARR) {
        for (const auto& x : sc->arr) sched.push_back(x.asNum(1.0));
      }
      r.setLoopRollEx(dIdx, true, stSec, bSec, relSec, totB,
                      float(roll->numField("hpfStartHz", 200.0)),
                      float(roll->numField("hpfEndHz", 2000.0)),
                      float(roll->numField("boostDb", 2.0)),
                      roll->boolField("stopOnDownbeat", true),
                      sched.empty() ? nullptr : sched.data(), int(sched.size()));
    }
    auto applySweep = [&](const JsonVal* sw, int defDeck) {
      if (!sw || sw->kind != JsonVal::J_OBJ || !sw->boolField("enabled")) return;
      const int dIdx = parseDeckIndex(sw->get("deck"), defDeck);
      const double bars = sw->numField("bars", 8.0);
      double endSec = sw->hasNum("endMixSec") ? sw->numField("endMixSec") : (fadeEnd > 0.0 ? fadeEnd : renderDurSec * 0.75);
      double stSec = sw->hasNum("startMixSec") ? sw->numField("startMixSec") : std::max(0.0, endSec - bars * 4.0 * gridBeatSec);
      r.setSweep(dIdx, sw->boolField("isHighPass", defDeck == 0),
                 float(sw->numField("startHz", defDeck == 0 ? 20.0 : 250.0)),
                 float(sw->numField("endHz", defDeck == 0 ? 1500.0 : 20000.0)),
                 float(sw->numField("qStart", 0.707)),
                 float(sw->numField("qEnd", 1.6)),
                 stSec, endSec, sw->boolField("exponential", true));
    };
    applySweep(fx->get("sweep"), 0);
    applySweep(fx->get("incomingSweep"), 1);

    if (const JsonVal* fl = fx->get("flanger"); fl && fl->kind == JsonVal::J_OBJ && fl->boolField("enabled")) {
      const int dIdx = parseDeckIndex(fl->get("deck"), 0);
      const double cyclesBars = std::max(0.25, fl->numField("lfoCyclesPerBars", 2.0));
      double lfoHz = fl->numField("lfoHz", 0.0);
      if (!(lfoHz > 0.0)) lfoHz = 1.0 / (cyclesBars * 4.0 * gridBeatSec);
      const float wetPeak = float(fl->numField("wetPeak", 0.6));
      r.setFlangerEx(dIdx, lfoHz, fl->numField("depthSec", 0.003),
                     float(fl->numField("feedback", 0.64)), wetPeak,
                     float(fl->numField("stereoOffsetDeg", 90.0)));
      if (r.deck(dIdx).flangerWet.empty() && fadeEnd > 0.0) {
        const double st = std::max(0.0, fadeEnd - 16.0 * gridBeatSec);
        r.deck(dIdx).flangerWet.times = {st, fadeEnd};
        r.deck(dIdx).flangerWet.values = {0.f, 1.f};
      }
    }
    if (const JsonVal* ph = fx->get("phaser"); ph && ph->kind == JsonVal::J_OBJ && ph->boolField("enabled")) {
      const int dIdx = parseDeckIndex(ph->get("deck"), 0);
      r.setPhaserEx(dIdx, ph->numField("rateBeats", 4.0), float(ph->numField("depth", 0.7)),
                    float(ph->numField("feedback", 0.6)), int(std::llround(ph->numField("stages", 6.0))),
                    float(ph->numField("stereoOffsetDeg", 90.0)));
      if (r.deck(dIdx).v2.phaserWet.empty() && fadeEnd > 0.0) {
        const float wp = float(ph->numField("wetPeak", 0.7));
        const double st = fadeStart > 0.0 ? fadeStart : std::max(0.0, fadeEnd - 8.0 * gridBeatSec);
        r.deck(dIdx).v2.phaserWet.times = {st, fadeEnd};
        r.deck(dIdx).v2.phaserWet.values = {0.f, wp};
      }
    }
    if (const JsonVal* ris = fx->get("riser"); ris && ris->kind == JsonVal::J_OBJ && ris->boolField("enabled")) {
      const int dIdx = parseDeckIndex(ris->get("deck"), 0);
      const double bSec = ris->numField("beatSec", gridBeatSec);
      const double lenB = ris->numField("lengthBeats", 16.0);
      double stSec = ris->hasNum("startMixSec") ? ris->numField("startMixSec") : -1.0;
      if (stSec < 0.0 && r.deck(dIdx).v2.riserLevel.empty() && fadeEnd > 0.0) {
        stSec = std::max(0.0, fadeEnd - lenB * bSec);
      }
      r.setRiserEx(dIdx, bSec, lenB,
                   float(ris->numField("noiseSweepStartHz", 300.0)),
                   float(ris->numField("noiseSweepEndHz", 8000.0)),
                   float(ris->numField("noiseLevelPeak", 0.7)),
                   float(ris->numField("uplifterOctaves", 1.0)),
                   ris->boolField("includeSnareRoll", true),
                   ris->numField("reverseReverbTailBeats", 1.0),
                   stSec);
    }
    if (const JsonVal* imp = fx->get("impact"); imp && imp->kind == JsonVal::J_OBJ && imp->boolField("enabled")) {
      const int dIdx = parseDeckIndex(imp->get("deck"), 1);
      const double trig = imp->hasNum("triggerMixSec") ? imp->numField("triggerMixSec") : fadeEnd;
      r.setImpact(dIdx, trig, float(imp->numField("peakDbfs", -6.0)),
                  float(imp->numField("lowThumpHz", 55.0)), imp->numField("noiseBurstMs", 180.0));
    }
    if (const JsonVal* br = fx->get("brake"); br && br->kind == JsonVal::J_OBJ && br->boolField("enabled")) {
      const int dIdx = parseDeckIndex(br->get("deck"), 0);
      const double st = br->hasNum("startMixSec") ? br->numField("startMixSec") : std::max(0.0, fadeEnd - br->numField("lengthSec", 0.5));
      r.setBrakeEx(dIdx, st, br->numField("lengthSec", 0.5),
                   float(br->numField("endRate", 0.05)),
                   float(br->numField("lpfStartHz", 18000.0)),
                   float(br->numField("lpfEndHz", 800.0)),
                   br->numField("endFadeMs", 10.0));
    }
    if (const JsonVal* ts = fx->get("tapeStop"); ts && ts->kind == JsonVal::J_OBJ && ts->boolField("enabled")) {
      const int dIdx = parseDeckIndex(ts->get("deck"), 0);
      const double st = ts->hasNum("startMixSec") ? ts->numField("startMixSec") : std::max(0.0, fadeEnd - ts->numField("lengthSec", 1.0));
      r.setTapeStop(dIdx, st, ts->numField("lengthSec", 1.0),
                    float(ts->numField("wowDepth", 0.03)),
                    float(ts->numField("wowRateHz", 5.0)),
                    float(ts->numField("lpfStartHz", 18000.0)),
                    float(ts->numField("lpfEndHz", 600.0)),
                    ts->numField("endFadeMs", 10.0));
    }
    if (const JsonVal* sp = fx->get("spinback"); sp && sp->kind == JsonVal::J_OBJ && sp->boolField("enabled")) {
      const int dIdx = parseDeckIndex(sp->get("deck"), 0);
      const double st = sp->hasNum("startMixSec") ? sp->numField("startMixSec") : std::max(0.0, fadeEnd - sp->numField("lengthSec", 0.25) - sp->numField("silenceGapBeats", 1.0) * gridBeatSec);
      r.setSpinback(dIdx, st, sp->numField("lengthSec", 0.25),
                    sp->numField("peakReverseSpeed", 3.0),
                    sp->numField("silenceGapBeats", 1.0) * gridBeatSec);
    }
    if (const JsonVal* ec = fx->get("echo"); ec && ec->kind == JsonVal::J_OBJ && ec->boolField("enabled")) {
      const int dIdx = parseDeckIndex(ec->get("deck"), 0);
      double dSec = ec->numField("delaySec", 0.0);
      if (!(dSec > 0.0)) dSec = ec->numField("delayBeats", 0.5) * gridBeatSec;
      r.setEchoEx(dIdx, dSec, float(ec->numField("feedback", 0.6)),
                  float(ec->numField("wet", 0.55)),
                  float(ec->numField("wetHpfHz", 250.0)),
                  float(ec->numField("wetLpfHz", 6000.0)),
                  ec->boolField("pingPong", true));
      if (r.deck(dIdx).echoSend.empty() && fadeEnd > 0.0) {
        const double throwAt = std::max(0.0, fadeEnd - gridBeatSec);
        r.deck(dIdx).echoSend.times = {std::max(0.0, throwAt - 0.005), throwAt, fadeEnd, fadeEnd + 0.005};
        r.deck(dIdx).echoSend.values = {0.f, 1.f, 1.f, 0.f};
      }
    }
    if (const JsonVal* gt = fx->get("gate"); gt && gt->kind == JsonVal::J_OBJ && gt->boolField("enabled")) {
      const int dIdx = parseDeckIndex(gt->get("deck"), 0);
      const double bSec = gt->numField("beatSec", gridBeatSec);
      const float div = float(gt->numField("divisionStepsPerBeat", 2.0));
      const float divEnd = gt->hasNum("divisionEndStepsPerBeat") ? float(gt->numField("divisionEndStepsPerBeat")) : (div * 2.f <= 16.f ? div * 2.f : div);
      const float sDiv = gt->boolField("isStutter", false) ? -std::abs(div) : std::abs(div);
      const double rampBars = gt->numField("rampBars", 2.0);
      const double endS = gt->hasNum("endMixSec") ? gt->numField("endMixSec") : (fadeEnd > 0.0 ? fadeEnd : renderDurSec * 0.75);
      const double stS = gt->hasNum("startMixSec") ? gt->numField("startMixSec") : std::max(0.0, endS - rampBars * 4.0 * bSec);
      r.setGateEx(dIdx, bSec, sDiv, divEnd, float(gt->numField("swing", 0.0)), stS, endS);
      if (r.deck(dIdx).v2.gateDepth.empty()) {
        const float dp = float(gt->numField("depthPeak", 1.0));
        r.deck(dIdx).v2.gateDepth.times = {stS, endS, endS + 0.005};
        r.deck(dIdx).v2.gateDepth.values = {0.f, dp, 0.f};
      }
    }
    if (const JsonVal* rv = fx->get("reverb"); rv && rv->kind == JsonVal::J_OBJ && rv->boolField("enabled")) {
      const int dIdx = parseDeckIndex(rv->get("deck"), 0);
      r.setReverb(dIdx, float(rv->numField("roomSize", 2.2)),
                  float(rv->numField("damping", 0.55)),
                  float(rv->numField("wet", 0.35)));
    }
    if (const JsonVal* pm = fx->get("pump"); pm && pm->kind == JsonVal::J_OBJ && pm->boolField("enabled")) {
      const int dIdx = parseDeckIndex(pm->get("deck"), 0);
      r.setPump(dIdx, pm->numField("beatSec", gridBeatSec), pm->boolField("fullBand", false));
    }
  }

  // Fallback defaults when an envelope lane drives an FX without an explicit fxParams block.
  for (int dIdx = 0; dIdx < 2; ++dIdx) {
    const auto& dk = r.deck(dIdx);
    auto hasNonZero = [](const bitchord::loopmix::Envelope& e) {
      for (float v : e.values) if (v > 1e-6f) return true;
      return false;
    };
    if (!dk.v2.phaser.enabled && hasNonZero(dk.v2.phaserWet)) {
      r.setPhaserEx(dIdx, 4.0, 0.7f, 0.6f, 6, 90.f);
    }
    if (!dk.v2.gate.enabled && hasNonZero(dk.v2.gateDepth)) {
      r.setGate(dIdx, gridBeatSec, 4.f, 0.f);
    }
    if (!dk.v2.riser.enabled && hasNonZero(dk.v2.riserLevel)) {
      r.setRiserEx(dIdx, gridBeatSec, 8.0, 300.f, 8000.f, 0.7f, 1.0f, true, 1.0, -1.0);
    }
    if (!dk.v2.pump.enabled && hasNonZero(dk.v2.pumpDepth)) {
      r.setPump(dIdx, gridBeatSec, false);
    }
    if (!dk.v2.brake.enabled && hasNonZero(dk.v2.brakeProgress)) {
      r.setBrakeEx(dIdx, 0.0, 1.0, 0.05f, 18000.f, 800.f, 10.0);
    }
    if (!dk.flanger.enabled && hasNonZero(dk.flangerWet)) {
      r.setFlangerEx(dIdx, 1.0 / (8.0 * gridBeatSec), 0.003, 0.64f, 0.6f, 90.f);
    }
    if (!dk.echo.enabled && hasNonZero(dk.echoSend)) {
      r.setEchoEx(dIdx, 0.5 * gridBeatSec, 0.6f, 0.55f, 250.f, 6000.f, true);
    }
    if (!dk.reverb.enabled && hasNonZero(dk.reverbSend)) {
      r.setReverb(dIdx, 2.2f, 0.55f, 0.35f);
    }
  }

  r.prepare();
  if (outMeta.fadeOutTrackSec >= 0.0) {
    r.deck(0).fadeOutAt.store(r.deck(0).releasedPositionOf(outMeta.fadeOutTrackSec, sr), std::memory_order_relaxed);
  }
  if (inMeta.fadeOutTrackSec >= 0.0) {
    r.deck(1).fadeOutAt.store(r.deck(1).releasedPositionOf(inMeta.fadeOutTrackSec, sr), std::memory_order_relaxed);
  }

  const int64_t totalFrames = int64_t(std::llround(renderDurSec * double(sr)));
  const int64_t first = r.firstFrame();
  std::vector<float> outInterleaved(size_t(totalFrames) * 2, 0.f);
  std::vector<float> scratch(size_t(1024) * 2, 0.f);
  for (int64_t fPos = first; fPos < totalFrames;) {
    const int chunk = int(std::min<int64_t>(1024, totalFrames - fPos));
    r.render(fPos, scratch.data(), chunk);
    for (int i = 0; i < chunk; ++i) {
      const int64_t dest = fPos + i;
      if (dest >= 0 && dest < totalFrames) {
        outInterleaved[size_t(dest) * 2] = scratch[size_t(i) * 2];
        outInterleaved[size_t(dest) * 2 + 1] = scratch[size_t(i) * 2 + 1];
      }
    }
    fPos += chunk;
  }

  if (!writeWavStereo16(outWavPath, outInterleaved, size_t(totalFrames), sr, err)) {
    std::fprintf(stderr, "error: %s\n", err.c_str());
    return 1;
  }
  std::printf("rendered %s frames=%lld sr=%d violations=%d\n",
              outWavPath.c_str(), static_cast<long long>(totalFrames), sr, r.exitGateViolations());
  return 0;
}

}  // namespace render_script

}  // namespace

int main(int argc, char** argv) {
  if (argc == 1) return runSelfTest();
  bool wantSelftest = false;
  bool wantProbe = false;
  bool wantLoopDomain = false;
  bool wantLpBypass = false;
  bool wantV2 = false;
  std::string renderScriptPath;
  std::string renderOutPath;
  FileOpts opt;
  bool haveFileArgs = false;
  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    auto needValue = [&](const char* name) -> std::string {
      if (i + 1 >= argc) {
        std::fprintf(stderr, "error: %s needs a value\n", name);
        std::exit(2);
      }
      return argv[++i];
    };
    if (arg == "--selftest") {
      wantSelftest = true;
    } else if (arg == "--reverb-stop-probe") {
      wantProbe = true;
    } else if (arg == "--loop-domain-probe") {
      wantLoopDomain = true;
    } else if (arg == "--lp-bypass-probe") {
      wantLpBypass = true;
    } else if (arg == "--v2-probe") {
      wantV2 = true;
    } else if (arg == "--render") {
      renderScriptPath = needValue("--render");
    } else if (arg == "--out") {
      renderOutPath = needValue("--out");
    } else if (arg == "--out-bed") {
      opt.outBed = needValue("--out-bed");
      haveFileArgs = true;
    } else if (arg == "--out-voc") {
      opt.outVoc = needValue("--out-voc");
      haveFileArgs = true;
    } else if (arg == "--in-bed") {
      opt.inBed = needValue("--in-bed");
      haveFileArgs = true;
    } else if (arg == "--in-voc") {
      opt.inVoc = needValue("--in-voc");
      haveFileArgs = true;
    } else if (arg == "--out-prefix") {
      opt.outPrefix = needValue("--out-prefix");
      haveFileArgs = true;
    } else if (arg == "--clashE") {
      const std::string v = needValue("--clashE");
      try {
        opt.clashE = std::stod(v);
      } catch (const std::exception&) {
        std::fprintf(stderr, "error: bad --clashE value %s\n", v.c_str());
        return 2;
      }
      opt.hasClashE = true;
      haveFileArgs = true;
    } else if (arg == "--csv") {
      opt.csv = needValue("--csv");
      haveFileArgs = true;
    } else if (arg == "--help" || arg == "-h") {
      printUsage(std::cout, argv[0]);
      return 0;
    } else {
      std::fprintf(stderr, "error: unknown argument %s\n", arg.c_str());
      printUsage(std::cerr, argv[0]);
      return 2;
    }
  }

  if (!renderScriptPath.empty() || !renderOutPath.empty()) {
    if (renderScriptPath.empty() || renderOutPath.empty()) {
      std::fprintf(stderr, "error: --render needs both --render <script.json> and --out <file.wav>\n");
      printUsage(std::cerr, argv[0]);
      return 2;
    }
    return render_script::runRenderMode(renderScriptPath, renderOutPath);
  }
  if (haveFileArgs) {
    if (opt.outBed.empty() || opt.outVoc.empty() || opt.inBed.empty() ||
        opt.inVoc.empty() || opt.outPrefix.empty()) {
      std::fprintf(stderr,
                   "error: file mode needs --out-bed, --out-voc, --in-bed, --in-voc, "
                   "--out-prefix\n");
      printUsage(std::cerr, argv[0]);
      return 2;
    }
    return runFileMode(opt);
  }
  if (wantLpBypass) return runLpBypassProbe();
  if (wantV2) return runV2Probe();
  if (wantLoopDomain) return runLoopDomainProbe();
  if (wantProbe) return runReverbStopProbe();
  if (wantSelftest || argc == 1) return runSelfTest();
  printUsage(std::cerr, argv[0]);
  return 2;
}
