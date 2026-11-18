// Bundled QNN pre-flight for BitChord (vc70 NPU).
//
// Mirrors the Orchard npu-addon runner init() where the bundled DSP
// library path matters: the HTP stub loads its Skel through
// ADSP_LIBRARY_PATH, and vendor directories come after the bundled
// ones. BitChord has no second runtime, so there is no runner to
// dlopen here: the bundled libonnxruntime.so (npu-addon source-built
// QNN) loads the bundled libQnnHtp.so through the Java addQnn
// backend_path. This only sets the search path and proves the
// backend opens once, before any session does.
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
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
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
    // API 31+ requires the optional uses-native-library manifest declaration.
    // Probe by soname in the app namespace, NOT by /vendor path existence:
    // an inaccessible FastRPC transport must skip HTP before QAIRT opens.
    void* rpcLib = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (!rpcLib) {
        const char* error = dlerror();
        LogWarn(std::string("bundled QNN init: libcdsprpc unavailable; HTP skipped: ") +
                (error ? error : "unknown"));
        return JNI_FALSE;
    }
    LogInfo("bundled QNN init: libcdsprpc linker gate OK");
    // Bundled libraries first so the DSP finds them; Orchard qnn_runner
    // sets the same value before opening its add-on runtime.
    const std::string adsp =
        libraryDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/vendor/dsp";
    if (setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1) != 0) {
        LogWarn("bundled QNN init: setenv ADSP_LIBRARY_PATH failed");
        dlclose(rpcLib);
        return JNI_FALSE;
    }
    void* systemLib = dlopen((libraryDir + "/libQnnSystem.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!systemLib) {
        const char* error = dlerror();
        LogWarn(std::string("bundled QNN init: libQnnSystem: ") + (error ? error : "unknown"));
        dlclose(rpcLib);
        return JNI_FALSE;
    }
    void* htpLib = dlopen((libraryDir + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!htpLib) {
        const char* error = dlerror();
        LogWarn(std::string("bundled QNN init: libQnnHtp: ") + (error ? error : "unknown"));
        dlclose(systemLib);
        dlclose(rpcLib);
        return JNI_FALSE;
    }
    LogInfo("bundled QNN init: ADSP_LIBRARY_PATH=" + adsp + " backend open");
    return JNI_TRUE;
}

}  // extern C
