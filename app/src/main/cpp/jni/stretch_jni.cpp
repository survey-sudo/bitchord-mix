#include <jni.h>

#include <cmath>

#include "stretch/pitch_stretch.h"

namespace {

bitchord::PitchStretch* From(jlong handle) {
  return reinterpret_cast<bitchord::PitchStretch*>(handle);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeCreate(JNIEnv*, jclass) {
  return reinterpret_cast<jlong>(new bitchord::PitchStretch());
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete From(handle);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeConfigure(
    JNIEnv*, jclass, jlong handle, jint channels, jint sampleRate) {
  if (auto* s = From(handle)) s->configure(channels, sampleRate);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeSetTempo(
    JNIEnv*, jclass, jlong handle, jfloat tempo) {
  if (auto* s = From(handle)) s->setTempo(tempo);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeProcess(
    JNIEnv* env, jclass, jlong handle, jfloatArray samples, jint offset, jint frames, jint channels) {
  auto* s = From(handle);
  if (env->ExceptionCheck() || s == nullptr || samples == nullptr || offset < 0 || frames <= 0 || channels <= 0) return;
  // process() walks frames * configuredChannels() samples of the pinned
  // buffer, so a caller channel count that disagrees with the last
  // configure() would read past the range checked below (or scramble the
  // interleave). Reject before pinning; an unconfigured handle has none.
  const int configured = s->configuredChannels();
  if (configured < 1 || channels != configured) return;
  const jsize length = env->GetArrayLength(samples);
  if (env->ExceptionCheck() || length < 0) return;
  const jlong sampleCount = static_cast<jlong>(frames) * static_cast<jlong>(channels);
  const jlong end = static_cast<jlong>(offset) + sampleCount;
  if (sampleCount <= 0 || end < offset || end > length) return;
  jfloat* data = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(samples, nullptr));
  if (data == nullptr) return;
  if (env->ExceptionCheck()) {
    env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
    return;
  }
  for (jlong i = 0; i < sampleCount; ++i) {
    if (!std::isfinite(data[static_cast<jlong>(offset) + i])) {
      env->ReleasePrimitiveArrayCritical(samples, data, JNI_ABORT);
      return;
    }
  }
  s->process(data + offset, frames);
  env->ReleasePrimitiveArrayCritical(samples, data, 0);
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeRealtimeFactor(
    JNIEnv*, jclass, jint sampleRate, jint seconds, jfloat tempo) {
  return bitchord::stretchRealtimeFactor(sampleRate, seconds, tempo);
}

}  // extern "C"
