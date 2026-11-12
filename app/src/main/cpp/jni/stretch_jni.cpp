#include <jni.h>

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
  if (s == nullptr || samples == nullptr || frames <= 0) return;
  jfloat* data = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(samples, nullptr));
  if (data == nullptr) return;
  s->process(data + offset, frames);
  env->ReleasePrimitiveArrayCritical(samples, data, 0);
  (void)channels;
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_audio_PitchStretch_nativeRealtimeFactor(
    JNIEnv*, jclass, jint sampleRate, jint seconds, jfloat tempo) {
  return bitchord::stretchRealtimeFactor(sampleRate, seconds, tempo);
}

}  // extern "C"
