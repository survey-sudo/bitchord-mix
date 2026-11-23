/*
 * JNI bridge for the 4-stem separation DSP (AUTOMIX-V2 Stream B).
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

// Kotlin side: app/src/main/java/com/music/bitchord/playback/smart/StemNative.kt.
//
// The network tensors (15.6 MB in, 62.4 MB out for SCNet) never touch the Java
// heap: they live in calloc'd blocks handed to Kotlin as direct ByteBuffers
// (nativeAllocate / nativeFree), ONNX Runtime reads and writes them in place
// (pinned outputs), and this file only passes their addresses to the DSP.
// Nothing here may run on the audio thread; every entry point allocates.

#include <jni.h>

#include <algorithm>
#include <cstdlib>
#include <memory>
#include <vector>

#include "analyzer/stem_dsp.h"

namespace {

using bitchord::stems::JobConfig;
using bitchord::stems::Layout;
using bitchord::stems::PostFilter;
using bitchord::stems::StemJob;

StemJob* FromHandle(jlong handle) { return reinterpret_cast<StemJob*>(handle); }

// The direct buffer's floats, or null when it is missing, not direct, or too small.
float* Floats(JNIEnv* env, jobject buffer, size_t needed) {
  if (buffer == nullptr) return nullptr;
  void* address = env->GetDirectBufferAddress(buffer);
  const jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (address == nullptr || capacity < 0) return nullptr;
  if (static_cast<unsigned long long>(capacity) < static_cast<unsigned long long>(needed) * sizeof(float)) {
    return nullptr;
  }
  return static_cast<float*>(address);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeSelfCheck(JNIEnv* /* env */, jclass /* clazz */) {
  bitchord::stems::Stft rect(bitchord::stems::SpecFor(Layout::kScnetCac));
  bitchord::stems::Stft hann(bitchord::stems::SpecFor(Layout::kUmxMagnitude));
  return (rect.ok() && hann.ok()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeCreateJob(
    JNIEnv* env, jclass /* clazz */, jfloatArray left, jfloatArray right, jint layout, jint frames,
    jint segmentSamples, jdouble minOverlap, jint postFilter, jboolean normalize) {
  if (left == nullptr || right == nullptr) return 0;
  const jsize count = env->GetArrayLength(left);
  if (count <= 0 || env->GetArrayLength(right) != count) return 0;
  if (frames < 4 || segmentSamples < 0) return 0;
  if (layout != static_cast<jint>(Layout::kScnetCac) && layout != static_cast<jint>(Layout::kUmxMagnitude)) return 0;
  if (postFilter != static_cast<jint>(PostFilter::kDirect) &&
      postFilter != static_cast<jint>(PostFilter::kWienerEm1)) {
    return 0;
  }

  // Plain region copies, not critical access: Create runs statistics over the
  // whole region and must not hold the GC off while it does.
  std::vector<float> l(static_cast<size_t>(count));
  std::vector<float> r(static_cast<size_t>(count));
  env->GetFloatArrayRegion(left, 0, count, l.data());
  env->GetFloatArrayRegion(right, 0, count, r.data());
  JobConfig config;
  config.layout = static_cast<Layout>(layout);
  config.frames = static_cast<size_t>(frames);
  config.segment_samples = static_cast<size_t>(segmentSamples);
  config.min_overlap = minOverlap;
  config.post = static_cast<PostFilter>(postFilter);
  config.normalize = normalize != JNI_FALSE;
  std::unique_ptr<StemJob> job = StemJob::Create(l.data(), r.data(), static_cast<size_t>(count), config);
  return reinterpret_cast<jlong>(job.release());
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeDestroyJob(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
  delete FromHandle(handle);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeChunkCount(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
  StemJob* job = FromHandle(handle);
  return job == nullptr ? -1 : static_cast<jint>(job->chunk_count());
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeInputFloats(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
  StemJob* job = FromHandle(handle);
  return job == nullptr ? -1 : static_cast<jint>(job->input_floats());
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeOutputFloats(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
  StemJob* job = FromHandle(handle);
  return job == nullptr ? -1 : static_cast<jint>(job->output_floats());
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeOutputBuffers(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
  StemJob* job = FromHandle(handle);
  return job == nullptr ? -1 : static_cast<jint>(job->output_buffers());
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativePrepareChunk(
    JNIEnv* env, jclass /* clazz */, jlong handle, jint index, jobject input) {
  StemJob* job = FromHandle(handle);
  if (job == nullptr || index < 0) return JNI_FALSE;
  float* in = Floats(env, input, job->input_floats());
  if (in == nullptr) return JNI_FALSE;
  return job->PrepareChunk(static_cast<size_t>(index), in) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeConsumeChunk(
    JNIEnv* env, jclass /* clazz */, jlong handle, jint index, jobject input,
    jobject out0, jobject out1, jobject out2, jobject out3) {
  StemJob* job = FromHandle(handle);
  if (job == nullptr || index < 0) return JNI_FALSE;
  const float* in = Floats(env, input, job->input_floats());
  if (in == nullptr) return JNI_FALSE;
  jobject buffers[4] = {out0, out1, out2, out3};
  float* outputs[4] = {nullptr, nullptr, nullptr, nullptr};
  const size_t needed = job->output_buffers();
  for (size_t i = 0; i < needed; ++i) {
    outputs[i] = Floats(env, buffers[i], job->output_floats());
    if (outputs[i] == nullptr) return JNI_FALSE;
  }
  return job->ConsumeChunk(static_cast<size_t>(index), in, outputs) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeResidualRatio(JNIEnv* /* env */, jclass /* clazz */, jlong handle) {
  StemJob* job = FromHandle(handle);
  return job == nullptr ? -1.0 : job->residual_ratio();
}

// Fills `out` (length == region length) with one stem channel, canonical order
// vocals, drums, bass, other. Copied in slices so OTHER (computed on the fly as
// dry minus the rest) never needs a region-sized temporary.
JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeCopyStem(
    JNIEnv* env, jclass /* clazz */, jlong handle, jint stem, jint channel, jfloatArray out) {
  StemJob* job = FromHandle(handle);
  if (job == nullptr || out == nullptr || stem < 0 || channel < 0) return JNI_FALSE;
  const jsize count = env->GetArrayLength(out);
  if (count < 0 || static_cast<size_t>(count) != job->length()) return JNI_FALSE;
  constexpr size_t kSlice = 65536;
  std::vector<float> slice(std::min<size_t>(kSlice, static_cast<size_t>(count)));
  for (size_t offset = 0; offset < static_cast<size_t>(count); offset += kSlice) {
    const size_t n = std::min(kSlice, static_cast<size_t>(count) - offset);
    if (!job->CopyStem(static_cast<size_t>(stem), static_cast<size_t>(channel), offset, n, slice.data())) {
      return JNI_FALSE;
    }
    env->SetFloatArrayRegion(out, static_cast<jsize>(offset), static_cast<jsize>(n), slice.data());
  }
  return JNI_TRUE;
}

// A zeroed native block as a direct ByteBuffer (big-endian view by default:
// the Kotlin side sets native order before viewing it as floats).
JNIEXPORT jobject JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeAllocate(JNIEnv* env, jclass /* clazz */, jint floats) {
  if (floats <= 0) return nullptr;
  const size_t bytes = static_cast<size_t>(floats) * sizeof(float);
  void* block = std::calloc(static_cast<size_t>(floats), sizeof(float));
  if (block == nullptr) return nullptr;
  jobject buffer = env->NewDirectByteBuffer(block, static_cast<jlong>(bytes));
  if (buffer == nullptr) std::free(block);
  return buffer;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_smart_StemNative_nativeFree(JNIEnv* env, jclass /* clazz */, jobject buffer) {
  if (buffer == nullptr) return;
  std::free(env->GetDirectBufferAddress(buffer));
}

}  // extern "C"
