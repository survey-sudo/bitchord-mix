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

bool readWav48k(const std::string& path, std::vector<float>& outL,
                std::vector<float>& outR, std::string& err) {
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

  if (srcRate == static_cast<uint32_t>(kSampleRate)) {
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
                                       static_cast<double>(kSampleRate) /
                                       static_cast<double>(srcRate)));
  outL.assign(outFrames, 0.0f);
  outR.assign(outFrames, 0.0f);
  const double step = static_cast<double>(srcRate) / static_cast<double>(kSampleRate);
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

bool writeWav48kStereo16(const std::string& path, const std::vector<float>& inter,
                         size_t frames, std::string& err) {
  if (inter.size() < frames * 2) {
    err = "short interleaved buffer for " + path;
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
  w32(static_cast<uint32_t>(kSampleRate));
  w32(static_cast<uint32_t>(kSampleRate) * 4u);  // byte rate
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

  if (ok) {
    std::printf("ok selftest passed (5 renders, firstFrame=%lld)\n",
                static_cast<long long>(firstFrames[0]));
    return 0;
  }
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
     << "  " << argv0
     << " --out-bed <wav> --out-voc <wav> --in-bed <wav> --in-voc <wav>"
        " --out-prefix <path> [--clashE <val>] [--csv <gain.csv>]\n"
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

}  // namespace

int main(int argc, char** argv) {
  if (argc == 1) return runSelfTest();
  bool wantSelftest = false;
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
  if (wantSelftest || argc == 1) return runSelfTest();
  printUsage(std::cerr, argv[0]);
  return 2;
}
