// Host regression harness for the stretch JNI adapter's process() range and
// channel contract (app/src/main/cpp/jni/stretch_jni.cpp).
//
// It links the real exported Java_..._nativeProcess symbol against the real
// bitchord::PitchStretch and drives it through a small, faithful JNIEnv table
// (real <jni.h> types from the host JDK; only the four entry points the bridge
// calls are implemented). A fake array owns a heap buffer and a declared jsize,
// and the pin/unpin calls are counted, so the test can assert that a rejected
// call neither pins the array nor reaches DSP processing.
//
// Build (normal):
//   g++ -std=c++17 -O2 -fno-fast-math -ffp-contract=off -Wall -Wextra
//       -I native -I native/third_party -isystem "$JDK/include"
//       -isystem "$JDK/include/linux" native/stretch/pitch_stretch.cpp
//       app/src/main/cpp/jni/stretch_jni.cpp
//       native/stretch/pitch_stretch_jni_range_test.cpp
//       -o /tmp/pitch_stretch_jni_range_test
// Build (ASan/UBSan): same with
//   -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer
//
// Modes:
//   (default)               full post-fix suite
//   --emit-accepted=FILE    run the accepted mono/stereo paths and write their
//                           processed PCM to FILE (pre/post bit-identity)
//   --repro-mismatch        the reviewer's stereo-configured / mono-call case
//   --repro-guard           safe pre-fix acceptance probe: the array declares
//                           8 samples but owns more storage, so a build without
//                           the channel check writes past the declared range
//                           without an out-of-bounds allocation.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.

#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

#include "stretch/pitch_stretch.h"

// The JNI entry points under test, as exported by stretch_jni.cpp.
extern "C" {
JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeCreate(JNIEnv*, jclass);
JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeDestroy(JNIEnv*, jclass, jlong);
JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeConfigure(
    JNIEnv*, jclass, jlong, jint, jint);
JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeSetTempo(
    JNIEnv*, jclass, jlong, jfloat);
JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeProcess(
    JNIEnv*, jclass, jlong, jfloatArray, jint, jint, jint);
}

namespace {

constexpr int kRate = 48000;
constexpr float kTempo = 0.95f;

int g_failures = 0;

#define CHECK(cond)                                                       \
  do {                                                                    \
    if (!(cond)) {                                                        \
      std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); \
      ++g_failures;                                                       \
    }                                                                     \
  } while (0)

void note(const char* what) { std::printf("ok   %s\n", what); }

// ---------------------------------------------------------------------------
// Minimal JNIEnv table and fake float arrays.
// ---------------------------------------------------------------------------

struct FakeArray {
  std::vector<float> storage;
  jsize declared = 0;
};

struct FakeJni {
  int exceptionPending = 0;
  int lengthCalls = 0;
  int pinCalls = 0;
  int releaseCalls = 0;
  int lastReleaseMode = -1;
  bool pinReturnsNull = false;
  bool raiseOnPin = false;
};

FakeJni g_jni;

void resetBookkeeping() { g_jni = FakeJni{}; }

extern "C" {

static jboolean JNICALL FakeExceptionCheck(JNIEnv*) {
  return g_jni.exceptionPending != 0 ? JNI_TRUE : JNI_FALSE;
}

static jsize JNICALL FakeGetArrayLength(JNIEnv*, jarray array) {
  ++g_jni.lengthCalls;
  return reinterpret_cast<FakeArray*>(array)->declared;
}

static void* JNICALL FakeGetPrimitiveArrayCritical(
    JNIEnv*, jarray array, jboolean* isCopy) {
  ++g_jni.pinCalls;
  if (isCopy != nullptr) *isCopy = JNI_FALSE;
  if (g_jni.pinReturnsNull) return nullptr;
  void* data = reinterpret_cast<FakeArray*>(array)->storage.data();
  if (g_jni.raiseOnPin) g_jni.exceptionPending = 1;
  return data;
}

static void JNICALL FakeReleasePrimitiveArrayCritical(
    JNIEnv*, jarray, void*, jint mode) {
  ++g_jni.releaseCalls;
  g_jni.lastReleaseMode = static_cast<int>(mode);
}

}  // extern "C"

JNIEnv* fakeEnv() {
  static JNINativeInterface_ table = [] {
    JNINativeInterface_ t{};
    t.ExceptionCheck = FakeExceptionCheck;
    t.GetArrayLength = FakeGetArrayLength;
    t.GetPrimitiveArrayCritical = FakeGetPrimitiveArrayCritical;
    t.ReleasePrimitiveArrayCritical = FakeReleasePrimitiveArrayCritical;
    return t;
  }();
  static JNIEnv_ env;
  env.functions = &table;
  return &env;
}

jfloatArray asArray(FakeArray* array) {
  return reinterpret_cast<jfloatArray>(array);
}

FakeArray sentinelArray(int storageSamples, jsize declaredOverride = -1) {
  FakeArray a;
  a.storage.resize(static_cast<size_t>(storageSamples));
  for (int i = 0; i < storageSamples; ++i) {
    a.storage[static_cast<size_t>(i)] = 0.5f + 0.125f * static_cast<float>(i);
  }
  a.declared = declaredOverride >= 0 ? declaredOverride : storageSamples;
  return a;
}

float signalAt(int frame, int channel) {
  const double t = static_cast<double>(frame) / kRate;
  const double tone = 0.2 * std::sin(2.0 * 3.141592653589793 * 220.0 * t + 0.35 * channel);
  return static_cast<float>(tone) + 0.03f * static_cast<float>(channel + 1);
}

void fillRegion(FakeArray& array, int offset, int frames, int channels) {
  for (int i = 0; i < frames; ++i) {
    for (int c = 0; c < channels; ++c) {
      array.storage[static_cast<size_t>(offset) + static_cast<size_t>(i) * channels +
                    static_cast<size_t>(c)] = signalAt(i, c);
    }
  }
}

bool sameBytes(const float* a, const float* b, int count) {
  return std::memcmp(a, b, static_cast<size_t>(count) * sizeof(float)) == 0;
}

bool allFinite(const float* a, int count) {
  for (int i = 0; i < count; ++i) {
    if (!std::isfinite(a[i])) return false;
  }
  return true;
}

jlong makeHandle(JNIEnv* env, int channels) {
  const jlong handle = Java_com_music_bitchord_playback_audio_PitchStretch_nativeCreate(env, nullptr);
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeConfigure(env, nullptr, handle, channels, kRate);
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeSetTempo(env, nullptr, handle, kTempo);
  return handle;
}

void destroyHandle(JNIEnv* env, jlong handle) {
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeDestroy(env, nullptr, handle);
}

void callProcess(JNIEnv* env, jlong handle, FakeArray& array, jint offset, jint frames, jint channels) {
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeProcess(
      env, nullptr, handle, asArray(&array), offset, frames, channels);
}

// ---------------------------------------------------------------------------
// Channel contract: configured vs supplied must agree, checked before pinning.
// ---------------------------------------------------------------------------

// Reviewer scenario: configure(2, 48000), tempo 0.95, then process an 8-float
// array as offset 0, 8 frames, channels 1. process() walks frames *
// configuredChannels() = 16 samples, twice the declared range.
void testReviewerMismatchRejected() {
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, 2);
  FakeArray array = sentinelArray(8);
  const FakeArray before = array;
  resetBookkeeping();
  callProcess(env, handle, array, 0, 8, 1);
  CHECK(g_jni.lengthCalls == 0);
  CHECK(g_jni.pinCalls == 0);
  CHECK(g_jni.releaseCalls == 0);
  CHECK(sameBytes(array.storage.data(), before.storage.data(), 8));
  destroyHandle(env, handle);
  note("stereo-configured / channels=1 rejected before any array access");
}

// The opposite direction: mono configured, stereo supplied. Accepting it would
// process only half of each declared stereo frame's intended stride and leave
// the DSP interpreting the interleave wrongly.
void testOppositeMismatchRejected() {
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, 1);
  FakeArray array = sentinelArray(16);
  const FakeArray before = array;
  resetBookkeeping();
  callProcess(env, handle, array, 0, 8, 2);
  CHECK(g_jni.lengthCalls == 0);
  CHECK(g_jni.pinCalls == 0);
  CHECK(g_jni.releaseCalls == 0);
  CHECK(sameBytes(array.storage.data(), before.storage.data(), 16));
  destroyHandle(env, handle);
  note("mono-configured / channels=2 rejected before any array access");
}

void testUnconfiguredRejected() {
  JNIEnv* env = fakeEnv();
  const jlong handle = Java_com_music_bitchord_playback_audio_PitchStretch_nativeCreate(env, nullptr);
  FakeArray array = sentinelArray(8);
  const FakeArray before = array;
  resetBookkeeping();
  callProcess(env, handle, array, 0, 8, 1);
  CHECK(g_jni.lengthCalls == 0);
  CHECK(g_jni.pinCalls == 0);
  CHECK(sameBytes(array.storage.data(), before.storage.data(), 8));
  destroyHandle(env, handle);

  // configure() rejects out-of-contract formats and must not enable process().
  const jlong rejected = Java_com_music_bitchord_playback_audio_PitchStretch_nativeCreate(env, nullptr);
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeConfigure(env, nullptr, rejected, 3, kRate);
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeSetTempo(env, nullptr, rejected, kTempo);
  FakeArray second = sentinelArray(8);
  const FakeArray secondBefore = second;
  resetBookkeeping();
  callProcess(env, rejected, second, 0, 8, 3);
  CHECK(g_jni.lengthCalls == 0);
  CHECK(g_jni.pinCalls == 0);
  CHECK(sameBytes(second.storage.data(), secondBefore.storage.data(), 8));
  destroyHandle(env, rejected);

  const jlong badRate = Java_com_music_bitchord_playback_audio_PitchStretch_nativeCreate(env, nullptr);
  Java_com_music_bitchord_playback_audio_PitchStretch_nativeConfigure(env, nullptr, badRate, 2, 4000);
  FakeArray third = sentinelArray(8);
  resetBookkeeping();
  callProcess(env, badRate, third, 0, 8, 2);
  CHECK(g_jni.pinCalls == 0);
  destroyHandle(env, badRate);
  note("unconfigured / rejected-configure handles refuse process()");
}

// ---------------------------------------------------------------------------
// Declared argument guards stay in front of pinning.
// ---------------------------------------------------------------------------

void testGuardsRetained() {
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, 1);
  FakeArray array = sentinelArray(64, 64);
  fillRegion(array, 0, 32, 1);
  const FakeArray before = array;

  auto expectNoPin = [&](const char* label, jlong h, FakeArray* a, jint offset, jint frames,
                         jint channels) {
    resetBookkeeping();
    if (a != nullptr) {
      callProcess(env, h, *a, offset, frames, channels);
    } else {
      Java_com_music_bitchord_playback_audio_PitchStretch_nativeProcess(
          env, nullptr, h, nullptr, offset, frames, channels);
    }
    if (g_jni.pinCalls != 0 || g_jni.releaseCalls != 0) {
      std::fprintf(stderr, "FAIL %s: pinCalls=%d releaseCalls=%d\n", label,
                   g_jni.pinCalls, g_jni.releaseCalls);
      ++g_failures;
    }
  };

  expectNoPin("null handle", 0, &array, 0, 8, 1);
  expectNoPin("null array", handle, nullptr, 0, 8, 1);
  expectNoPin("offset -1", handle, &array, -1, 8, 1);
  expectNoPin("frames 0", handle, &array, 0, 0, 1);
  expectNoPin("frames -5", handle, &array, 0, -5, 1);
  expectNoPin("channels 0", handle, &array, 0, 8, 0);
  expectNoPin("channels -2", handle, &array, 0, 8, -2);
  expectNoPin("offset past length", handle, &array, 60, 8, 1);
  expectNoPin("frames past length", handle, &array, 0, 65, 1);
  expectNoPin("offset INT_MAX", handle, &array, std::numeric_limits<jint>::max(), 1, 1);
  expectNoPin("frames INT_MAX", handle, &array, 0, std::numeric_limits<jint>::max(), 1);
  expectNoPin("offset+frames INT_MAX stereo", handle, &array,
              std::numeric_limits<jint>::max(), std::numeric_limits<jint>::max(), 2);
  CHECK(sameBytes(array.storage.data(), before.storage.data(), 64));

  // Pending exception at entry: no array access at all.
  resetBookkeeping();
  g_jni.exceptionPending = 1;
  callProcess(env, handle, array, 0, 8, 1);
  CHECK(g_jni.lengthCalls == 0);
  CHECK(g_jni.pinCalls == 0);
  g_jni.exceptionPending = 0;

  // Pin failure returns without processing or releasing.
  resetBookkeeping();
  g_jni.pinReturnsNull = true;
  callProcess(env, handle, array, 0, 8, 1);
  CHECK(g_jni.pinCalls == 1);
  CHECK(g_jni.releaseCalls == 0);
  CHECK(sameBytes(array.storage.data(), before.storage.data(), 64));
  g_jni.pinReturnsNull = false;

  // Exception observed after the pin: unpin with JNI_ABORT, no processing.
  resetBookkeeping();
  g_jni.raiseOnPin = true;
  callProcess(env, handle, array, 0, 8, 1);
  CHECK(g_jni.pinCalls == 1);
  CHECK(g_jni.releaseCalls == 1);
  CHECK(g_jni.lastReleaseMode == JNI_ABORT);
  CHECK(sameBytes(array.storage.data(), before.storage.data(), 64));
  g_jni.raiseOnPin = false;
  g_jni.exceptionPending = 0;

  // Non-finite sample: unpin with JNI_ABORT and leave every sample untouched.
  FakeArray poisoned = sentinelArray(64, 64);
  fillRegion(poisoned, 0, 32, 1);
  poisoned.storage[10] = std::numeric_limits<float>::quiet_NaN();
  const FakeArray poisonedBefore = poisoned;
  resetBookkeeping();
  callProcess(env, handle, poisoned, 0, 32, 1);
  CHECK(g_jni.pinCalls == 1);
  CHECK(g_jni.releaseCalls == 1);
  CHECK(g_jni.lastReleaseMode == JNI_ABORT);
  CHECK(std::isnan(poisoned.storage[10]));
  CHECK(sameBytes(poisoned.storage.data(), poisonedBefore.storage.data(), 10));
  CHECK(sameBytes(poisoned.storage.data() + 11, poisonedBefore.storage.data() + 11, 53));

  destroyHandle(env, handle);
  note("null/negative/zero/overflow/nonfinite guards reject before processing");
}

// ---------------------------------------------------------------------------
// Accepted paths: correct channels, bounded nonzero offsets, real DSP, and
// byte identity with a direct PitchStretch driven with the same calls.
// ---------------------------------------------------------------------------

// Runs one accepted case and, when emit is non-null, appends the processed
// region so a pre-fix build and the fixed build can be byte-compared.
void runAcceptedCase(int channels, int frames, int offset, int prefix, int suffix,
                     bool subjectToMismatchFirst, std::vector<float>* emit) {
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, channels);

  FakeArray array = sentinelArray(prefix + frames * channels + suffix);
  fillRegion(array, offset, frames, channels);
  const FakeArray before = array;

  if (subjectToMismatchFirst) {
    // A rejected mismatch must leave the DSP state untouched: the accepted
    // output below has to match a handle that never saw the bad call.
    FakeArray wrong = sentinelArray(8);
    resetBookkeeping();
    callProcess(env, handle, wrong, 0, 8, channels == 1 ? 2 : 1);
    CHECK(g_jni.pinCalls == 0);
  }

  resetBookkeeping();
  callProcess(env, handle, array, offset, frames, channels);
  CHECK(g_jni.lengthCalls == 1);
  CHECK(g_jni.pinCalls == 1);
  CHECK(g_jni.releaseCalls == 1);
  CHECK(g_jni.lastReleaseMode == 0);

  // Prefix and suffix sentinels outside [offset, offset + frames*channels)
  // must be untouched byte for byte.
  CHECK(sameBytes(array.storage.data(), before.storage.data(), prefix));
  CHECK(sameBytes(array.storage.data() + offset + frames * channels,
                  before.storage.data() + offset + frames * channels, suffix));
  CHECK(allFinite(array.storage.data() + offset, frames * channels));

  // The JNI path must equal the direct production API on identical input.
  bitchord::PitchStretch direct;
  direct.configure(channels, kRate);
  direct.setTempo(kTempo);
  std::vector<float> expected(before.storage.begin() + offset,
                              before.storage.begin() + offset + frames * channels);
  direct.process(expected.data(), frames);
  CHECK(sameBytes(array.storage.data() + offset, expected.data(), frames * channels));

  // Processing is observable (tempo 0.95 is far enough from 1).
  CHECK(!sameBytes(expected.data(), before.storage.data() + offset, frames * channels));

  if (emit != nullptr) {
    const float* region = array.storage.data() + offset;
    emit->insert(emit->end(), region, region + frames * channels);
  }
  destroyHandle(env, handle);
}

void testAcceptedMono(std::vector<float>* emit) {
  runAcceptedCase(1, 8192, 5, 5, 7, false, emit);
  note("mono accepted, nonzero offset, bounded writes, PCM matches direct API");
}

void testAcceptedStereo(std::vector<float>* emit) {
  // The mismatch prelude is skipped in emit mode so the pre-fix and fixed
  // builds can be byte-compared on correct-channel input alone.
  const bool mismatchFirst = emit == nullptr;
  runAcceptedCase(2, 4096, 3, 3, 9, mismatchFirst, emit);
  note(mismatchFirst
           ? "stereo accepted after a rejected mismatch; PCM matches direct API"
           : "stereo accepted; PCM matches direct API");
}

// Streaming: two sequential JNI blocks on one handle must match two direct
// blocks, so the adapter neither resets nor mis-strides between calls.
void testAcceptedStreaming() {
  constexpr int kFrames = 4096;
  constexpr int kOffset = 4;
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, 1);

  FakeArray array = sentinelArray(kOffset + 2 * kFrames + 3);
  fillRegion(array, kOffset, 2 * kFrames, 1);
  const FakeArray before = array;

  resetBookkeeping();
  callProcess(env, handle, array, kOffset, kFrames, 1);
  callProcess(env, handle, array, kOffset + kFrames, kFrames, 1);
  CHECK(g_jni.pinCalls == 2);
  CHECK(g_jni.releaseCalls == 2);
  CHECK(g_jni.lastReleaseMode == 0);

  bitchord::PitchStretch direct;
  direct.configure(1, kRate);
  direct.setTempo(kTempo);
  std::vector<float> block1(before.storage.begin() + kOffset,
                            before.storage.begin() + kOffset + kFrames);
  std::vector<float> block2(before.storage.begin() + kOffset + kFrames,
                            before.storage.begin() + kOffset + 2 * kFrames);
  direct.process(block1.data(), kFrames);
  direct.process(block2.data(), kFrames);
  CHECK(sameBytes(array.storage.data() + kOffset, block1.data(), kFrames));
  CHECK(sameBytes(array.storage.data() + kOffset + kFrames, block2.data(), kFrames));
  CHECK(sameBytes(array.storage.data(), before.storage.data(), kOffset));
  CHECK(sameBytes(array.storage.data() + kOffset + 2 * kFrames,
                  before.storage.data() + kOffset + 2 * kFrames, 3));
  destroyHandle(env, handle);
  note("two streaming blocks keep state and offsets consistent with direct API");
}

// ---------------------------------------------------------------------------
// Reproduction / diagnostic modes (not part of the fixed suite's pass gate).
// ---------------------------------------------------------------------------

// Exact reviewer call. Under the fixed adapter this returns before pinning.
// Under the pre-fix adapter it pins and lets PitchStretch::process read/write
// frames * 2 samples; built with ASan and an exactly-sized 8-float array, that
// overrun is reported by the sanitizer (the isolated pre-fix reproduction).
int runReviewerScenario() {
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, 2);
  FakeArray array = sentinelArray(8);
  const FakeArray before = array;
  resetBookkeeping();
  callProcess(env, handle, array, 0, 8, 1);
  const bool rejected = g_jni.pinCalls == 0;
  std::printf("reviewer mismatch: pinCalls=%d lengthCalls=%d rejectedBeforePin=%d\n",
              g_jni.pinCalls, g_jni.lengthCalls, rejected ? 1 : 0);
  std::printf("array byte-identical=%d\n",
              sameBytes(array.storage.data(), before.storage.data(), 8) ? 1 : 0);
  if (!rejected) {
    std::printf("channel mismatch ACCEPTED; DSP ran with configured channels\n");
  }
  destroyHandle(env, handle);
  return rejected ? 0 : 2;
}

// Safe pre-fix acceptance probe: declared length stays 8 while the allocation
// holds 64 floats, so a build without the channel guard writes past the
// declared JNI range without touching unallocated memory.
int runGuardProbe() {
  JNIEnv* env = fakeEnv();
  const jlong handle = makeHandle(env, 2);
  FakeArray array = sentinelArray(64, 8);
  const FakeArray before = array;
  resetBookkeeping();
  callProcess(env, handle, array, 0, 8, 1);
  int changedBeyondDeclared = 0;
  for (int i = 8; i < 64; ++i) {
    if (std::memcmp(&array.storage[static_cast<size_t>(i)],
                    &before.storage[static_cast<size_t>(i)], sizeof(float)) != 0) {
      ++changedBeyondDeclared;
    }
  }
  std::printf("guard probe: pinCalls=%d lengthCalls=%d changedSamplesBeyondDeclared=%d\n",
              g_jni.pinCalls, g_jni.lengthCalls, changedBeyondDeclared);
  std::printf("%s\n", g_jni.pinCalls == 0
                          ? "fixed: rejected before pin"
                          : "pre-fix: accepted mismatch and processed outside declared range");
  destroyHandle(env, handle);
  return 0;
}

bool writeFloats(const std::string& path, const std::vector<float>& values) {
  std::FILE* file = std::fopen(path.c_str(), "wb");
  if (file == nullptr) return false;
  const size_t written =
      std::fwrite(values.data(), sizeof(float), values.size(), file);
  const int closed = std::fclose(file);
  return written == values.size() && closed == 0;
}

}  // namespace

int main(int argc, char** argv) {
  std::string emitPath;
  bool reviewer = false;
  bool guardProbe = false;
  for (int i = 1; i < argc; ++i) {
    const std::string arg = argv[i];
    const std::string emitPrefix = "--emit-accepted=";
    if (arg == "--repro-mismatch") {
      reviewer = true;
    } else if (arg == "--repro-guard") {
      guardProbe = true;
    } else if (arg.compare(0, emitPrefix.size(), emitPrefix) == 0) {
      emitPath = arg.substr(emitPrefix.size());
    } else {
      std::fprintf(stderr, "unknown argument: %s\n", arg.c_str());
      return 2;
    }
  }

  if (reviewer) return runReviewerScenario();
  if (guardProbe) return runGuardProbe();

  if (!emitPath.empty()) {
    std::vector<float> emitted;
    testAcceptedMono(&emitted);
    testAcceptedStereo(&emitted);
    if (g_failures != 0) {
      std::fprintf(stderr, "stretch_jni_range_test: %d failures before emit\n", g_failures);
      return 1;
    }
    if (!writeFloats(emitPath, emitted)) {
      std::fprintf(stderr, "cannot write %s\n", emitPath.c_str());
      return 1;
    }
    std::printf("emitted %zu accepted-path floats to %s\n", emitted.size(), emitPath.c_str());
    return 0;
  }

  testReviewerMismatchRejected();
  testOppositeMismatchRejected();
  testUnconfiguredRejected();
  testGuardsRetained();
  testAcceptedMono(nullptr);
  testAcceptedStereo(nullptr);
  testAcceptedStreaming();

  if (g_failures == 0) {
    std::printf("ok stretch_jni_range_test (0 failures)\n");
    return 0;
  }
  std::fprintf(stderr, "stretch_jni_range_test: %d failures\n", g_failures);
  return 1;
}
