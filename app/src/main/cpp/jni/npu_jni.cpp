// CPU-only djmix52: retain the old JNI ABI without a vendor-loader implementation.
//
// The official ORT interface creates sessions through OrtGetApiBase()->GetApi(...)
// and OrtApi::CreateSession / CreateSessionOptions. It does not require this app
// to reproduce another player's runner or manipulate a DSP search path.
// Reference: https://onnxruntime.ai/docs/api/c/struct_ort_api.html
// ORT 1.31.0's AAR header declares ORT_API_VERSION 31; no QNN API/runtime is linked.
// A future separately licensed addon must supply a new official-C-API runner.
#include <jni.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_com_music_bitchord_playback_smart_NpuNative_nativeInitAdspPath(
    JNIEnv*, jclass, jstring) {
    // Deliberately no dlopen, setenv, filesystem access or model loading, even if
    // an independent caller reaches this native entry point directly.
    return JNI_FALSE;
}
