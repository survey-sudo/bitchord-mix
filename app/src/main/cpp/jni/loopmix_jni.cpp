// JNI bridge for the Automix loop mix renderer (native/loopmix/). Every call
// but render() is made while the mix is being set up; render() runs on the
// player's playback thread, inside its audio sink.
//
// Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
#include <jni.h>

#include <algorithm>
#include <new>
#include <vector>

#include "loopmix/loop_mix_renderer.h"

using bitchord::loopmix::Deck;
using bitchord::loopmix::Envelope;
using bitchord::loopmix::Renderer;

namespace {

Renderer* From(jlong handle) { return reinterpret_cast<Renderer*>(handle); }

Deck* DeckOf(jlong handle, jint index) {
  if (handle == 0 || index < 0 || index > 1) return nullptr;
  return &From(handle)->deck(index);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeCreate(JNIEnv*, jclass, jint sampleRate) {
  return reinterpret_cast<jlong>(new (std::nothrow) Renderer(sampleRate));
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeDestroy(JNIEnv*, jclass, jlong handle) {
  delete From(handle);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetDeck(
    JNIEnv* env, jclass, jlong handle, jint index, jfloatArray left, jfloatArray right,
    jdouble regionStart, jlong loopStart, jlong loopEnd, jdouble releaseAt,
    jdoubleArray knotFrames, jdoubleArray knotPositions, jboolean runOn, jdouble stopAt,
    jdouble preroll, jfloat level) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  const jsize frames = std::min(env->GetArrayLength(left), env->GetArrayLength(right));
  deck->left.resize(frames);
  deck->right.resize(frames);
  env->GetFloatArrayRegion(left, 0, frames, deck->left.data());
  env->GetFloatArrayRegion(right, 0, frames, deck->right.data());
  deck->regionStart = regionStart;
  deck->loopStart = loopStart;
  deck->loopEnd = loopEnd;
  deck->releaseAt = releaseAt;
  const jsize knots = std::min(env->GetArrayLength(knotFrames), env->GetArrayLength(knotPositions));
  deck->knotTimes.resize(knots);
  deck->knotPositions.resize(knots);
  env->GetDoubleArrayRegion(knotFrames, 0, knots, deck->knotTimes.data());
  env->GetDoubleArrayRegion(knotPositions, 0, knots, deck->knotPositions.data());
  deck->runOn = runOn;
  deck->stopAt = stopAt;
  deck->preroll = preroll;
  deck->level = level;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetEnvelope(
    JNIEnv* env, jclass, jlong handle, jint index, jint kind, jdoubleArray times, jfloatArray values,
    jboolean exponential) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  Envelope* envelope = kind == 0 ? &deck->gain
      : kind == 1                    ? &deck->lowDb
      : kind == 2                    ? &deck->highPassHz
      : kind == 3                    ? &deck->lowPassHz
      : kind == 4                    ? &deck->echoSend
      : kind == 5                    ? &deck->midDb
      : kind == 6                    ? &deck->highDb
      : kind == 7                    ? &deck->flangerWet
      : kind == 8                    ? &deck->reverbSend
                                     // AUTOMIX-V2(C): kinds 9..19 (stems, FX, brake, knob).
                                     : bitchord::loopmix::v2Envelope(*deck, kind);
  if (envelope == nullptr) return;
  const jsize points = std::min(env->GetArrayLength(times), env->GetArrayLength(values));
  envelope->times.resize(points);
  envelope->values.resize(points);
  env->GetDoubleArrayRegion(times, 0, points, envelope->times.data());
  env->GetFloatArrayRegion(values, 0, points, envelope->values.data());
  envelope->exponential = exponential;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetHighQuality(JNIEnv*, jclass, jlong handle,
                                                                              jboolean high) {
  if (handle != 0) From(handle)->setHighQuality(high == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetIsolate(
    JNIEnv*, jclass, jlong handle, jint index, jboolean on) {
  if (handle != 0) From(handle)->setIsolate(index, on == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetEcho(
    JNIEnv*, jclass, jlong handle, jint index, jdouble delaySeconds, jfloat feedback, jfloat wet) {
  if (handle != 0) From(handle)->setEcho(index, delaySeconds, feedback, wet);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetFlanger(
    JNIEnv*, jclass, jlong handle, jint index, jdouble lfoHz, jdouble depthSeconds, jfloat feedback, jfloat wet) {
  if (handle != 0) From(handle)->setFlanger(index, lfoHz, depthSeconds, feedback, wet);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetLoopRoll(
    JNIEnv*, jclass, jlong handle, jint index, jboolean enabled, jdouble rollStartSec, jdouble beatSec, jdouble releaseSec, jdouble totalRollBeats) {
  if (handle != 0) From(handle)->setLoopRoll(index, enabled == JNI_TRUE, rollStartSec, beatSec, releaseSec, totalRollBeats);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetReverb(
    JNIEnv*, jclass, jlong handle, jint index, jfloat roomSize, jfloat damping, jfloat wet) {
  if (handle != 0) From(handle)->setReverb(index, roomSize, damping, wet);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetSvfQ(
    JNIEnv*, jclass, jlong handle, jint index, jfloat q) {
  if (handle != 0) From(handle)->setSvfQ(index, q);
}

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeExitGateViolations(
    JNIEnv*, jclass, jlong handle) {
  return handle != 0 ? From(handle)->exitGateViolations() : 0;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativePrepare(JNIEnv*, jclass, jlong handle) {
  if (handle != 0) From(handle)->prepare();
}

JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeFirstFrame(JNIEnv*, jclass, jlong handle) {
  return handle != 0 ? From(handle)->firstFrame() : 0;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeRender(
    JNIEnv* env, jclass, jlong handle, jlong frame, jfloatArray interleaved, jint offsetFrames, jint frames) {
  if (handle == 0 || frames <= 0) return;
  // Critical access: no copy of the block, and render() itself never calls back into Java.
  auto* samples = static_cast<jfloat*>(env->GetPrimitiveArrayCritical(interleaved, nullptr));
  if (samples == nullptr) return;
  From(handle)->render(frame, samples + 2 * offsetFrames, frames);
  env->ReleasePrimitiveArrayCritical(interleaved, samples, 0);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetFadeOut(JNIEnv*, jclass, jlong handle, jint index,
                                                                        jdouble trackSeconds) {
  Deck* deck = DeckOf(handle, index);
  if (deck == nullptr) return;
  deck->fadeOutAt.store(trackSeconds < 0 ? -1.0
                                         : deck->releasedPositionOf(trackSeconds, From(handle)->sampleRate()));
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeHeard(JNIEnv*, jclass, jlong handle, jint index) {
  Deck* deck = DeckOf(handle, index);
  return deck != nullptr ? deck->heard.load() : 0.0;
}

// ---- AUTOMIX-V2(C) -------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeV2Capabilities(JNIEnv*, jclass) {
  return Renderer::capabilities();
}

JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetDeckStem(
    JNIEnv* env, jclass, jlong handle, jint index, jint kind, jfloatArray left, jfloatArray right) {
  if (handle == 0 || left == nullptr || right == nullptr) return JNI_FALSE;
  const jsize frames = std::min(env->GetArrayLength(left), env->GetArrayLength(right));
  if (frames <= 0) return JNI_FALSE;
  // Worker thread: copy out, quantize into the deck (no Java callbacks held open).
  std::vector<float> l(static_cast<size_t>(frames)), r(static_cast<size_t>(frames));
  env->GetFloatArrayRegion(left, 0, frames, l.data());
  env->GetFloatArrayRegion(right, 0, frames, r.data());
  if (env->ExceptionCheck()) return JNI_FALSE;
  return From(handle)->setDeckStem(index, kind, l.data(), r.data(), static_cast<int>(frames)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeClearDeckStems(JNIEnv*, jclass, jlong handle,
                                                                            jint index) {
  if (handle != 0) From(handle)->clearDeckStems(index);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetPhaser(
    JNIEnv*, jclass, jlong handle, jint index, jdouble rateBeats, jfloat depth, jfloat feedback) {
  if (handle != 0) From(handle)->setPhaser(index, rateBeats, depth, feedback);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetGate(
    JNIEnv*, jclass, jlong handle, jint index, jdouble beatSec, jfloat division, jfloat swing) {
  if (handle != 0) From(handle)->setGate(index, beatSec, division, swing);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetRiser(
    JNIEnv*, jclass, jlong handle, jint index, jdouble beatSec, jdouble lengthBeats) {
  if (handle != 0) From(handle)->setRiser(index, beatSec, lengthBeats);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetPump(
    JNIEnv*, jclass, jlong handle, jint index, jdouble beatSec, jboolean fullBand) {
  if (handle != 0) From(handle)->setPump(index, beatSec, fullBand == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetBrake(
    JNIEnv*, jclass, jlong handle, jint index, jdouble startSec, jdouble lengthSec, jboolean spinback) {
  if (handle != 0) From(handle)->setBrake(index, startSec, lengthSec, spinback == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetLoudnessMatch(
    JNIEnv*, jclass, jlong handle, jboolean enabled, jfloat targetLufs) {
  if (handle != 0) From(handle)->setLoudnessMatch(enabled == JNI_TRUE, targetLufs);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetLoudnessDropWindow(
    JNIEnv*, jclass, jlong handle, jdouble exemptStartSec, jdouble exemptEndSec, jfloat dropBumpLu) {
  if (handle != 0) From(handle)->setLoudnessDropWindow(exemptStartSec, exemptEndSec, dropBumpLu);
}

JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeSetTruePeak(JNIEnv*, jclass, jlong handle,
                                                                         jboolean on) {
  if (handle != 0) From(handle)->setTruePeak(on == JNI_TRUE);
}

JNIEXPORT jdouble JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeShortTermLufs(JNIEnv*, jclass, jlong handle,
                                                                           jint which) {
  return handle != 0 ? From(handle)->shortTermLufs(which) : -120.0;
}

JNIEXPORT jfloat JNICALL
Java_com_music_bitchord_playback_loopmix_LoopMixRenderer_nativeLoudnessGuardDb(JNIEnv*, jclass, jlong handle) {
  return handle != 0 ? From(handle)->loudnessGuardDb() : 0.f;
}

}  // extern "C"
