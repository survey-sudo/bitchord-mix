// Bundled QNN pre-flight for BitChord (AUTOMIX-V2(A), onnxruntime-android-qnn + qnn-runtime).
//
// The HTP stub (libQnnHtpV73Stub.so) asks the DSP to load its Skel through
// ADSP_LIBRARY_PATH, so the APK's extracted nativeLibraryDir must come first, followed by
// the standard vendor DSP directories. This must be set before the first QNN session
// (FastRPC reads it when the DSP session is opened). Then the bundled backend is dlopened
// once by path to prove the stack is present; ONNX Runtime later opens the same file via
// the backend_path provider option and gets the same handle.
#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <cstdlib>
#include <string>

namespace {

constexpr const char* kTag = "BitChordNPU";

void LogInfo(const std::string& message) {
    __android_log_print(ANDROID_LOG_INFO, kTag, "%s", message.c_str());
}

void LogWarn(const std::string& message) {
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", message.c_str());
}

std::string ToStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string LastDlError() {
    const char* error = dlerror();
    return error ? std::string(error) : std::string("unknown");
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_music_bitchord_playback_smart_NpuNative_nativeInitAdspPath(
    JNIEnv* env, jclass, jstring jlibraryDir) {
    const std::string libraryDir = ToStdString(env, jlibraryDir);
    if (libraryDir.empty()) {
        LogWarn("bundled QNN init: empty libraryDir");
        return JNI_FALSE;
    }
    // Bundled libraries first so the DSP finds the V73 Skel shipped in the APK; then the
    // standard vendor locations (Qualcomm QNN Android guidance).
    const std::string adsp = libraryDir +
        ";/odm/lib/rfsa/adsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp";
    if (setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1) != 0) {
        LogWarn("bundled QNN init: setenv ADSP_LIBRARY_PATH failed");
        return JNI_FALSE;
    }
    // FastRPC client: only reachable when the manifest declares
    // <uses-native-library android:name="libcdsprpc.so" android:required="false"/>.
    // Diagnostic only: QNN loads it itself; a failure here predicts a QNN open failure.
    void* rpcLib = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!rpcLib) {
        LogWarn("bundled QNN init: libcdsprpc.so not loadable (" + LastDlError() + ")");
    }
    void* systemLib = dlopen((libraryDir + "/libQnnSystem.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!systemLib) {
        LogWarn("bundled QNN init: libQnnSystem: " + LastDlError());
        return JNI_FALSE;
    }
    void* htpLib = dlopen((libraryDir + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!htpLib) {
        LogWarn("bundled QNN init: libQnnHtp: " + LastDlError());
        return JNI_FALSE;
    }
    // Handles are intentionally kept open for the process lifetime.
    LogInfo("bundled QNN init: ADSP_LIBRARY_PATH=" + adsp + " cdsprpc=" + (rpcLib ? "yes" : "no") +
            " backend open");
    return JNI_TRUE;
}

}  // extern "C"
