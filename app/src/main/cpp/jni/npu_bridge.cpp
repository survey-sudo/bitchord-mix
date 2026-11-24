// AUTOMIX-V2(A): dlopen bridge to the Orchard NPU add-on runtime.
//
// The add-on ships liborchard_qnn.so with its own ONNX Runtime + QNN stack.
// This file never links against it: the library is opened by absolute path
// from the PackageManager-resolved nativeLibraryDir, and only the three
// documented entry points are resolved. Every failure returns 0 / null, so the
// Kotlin side falls back to CPU. Nothing here throws into the JVM.
//
// C ABI expected from liborchard_qnn.so (see docs/NPU.md):
//   void* orchard_qnn_open (const char* modelPath, const char* cacheDir,
//                           char* errBuf, size_t errCap);
//   int   orchard_qnn_run  (void* ctx, const char* inputName,
//                           const float* data, const int64_t* shape, int rank,
//                           float* out, size_t outCap, size_t* outLen);
//   void  orchard_qnn_close(void* ctx);

#include <jni.h>
#include <dlfcn.h>

#include <android/log.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "BitChordNPU";
constexpr const char* kJavaBridge =
    "com/music/bitchord/playback/djmix/npu/OrchardNpuBridge";
constexpr const char* kRuntimeSoName = "liborchard_qnn.so";
constexpr size_t kErrCap = 512;
constexpr int kMaxRank = 4;

using OpenFn = void* (*)(const char*, const char*, char*, size_t);
using RunFn = int (*)(void*, const char*, const float*, const int64_t*, int,
                      float*, size_t, size_t*);
using CloseFn = void (*)(void*);

struct Runtime {
    void* lib = nullptr;
    OpenFn open = nullptr;
    RunFn run = nullptr;
    CloseFn close = nullptr;
};

struct Session {
    Runtime* runtime = nullptr;
    void* ctx = nullptr;
    std::string lastError;
};

// Opened at most once per process; dlopen refcounts, so keep the handle alive.
Runtime* g_runtime = nullptr;

bool isSafeAbsoluteDir(const std::string& dir) {
    // Refuse relative paths and any traversal segments: the directory must come
    // from PackageManager, never from user-controlled text.
    if (dir.empty() || dir[0] != '/') return false;
    if (dir.find("/../") != std::string::npos) return false;
    if (dir.size() >= 3 && dir.compare(dir.size() - 3, 3, "/..") == 0) return false;
    return true;
}

// Returns the runtime, or nullptr with `err` set. Not thread-safe by itself;
// the Kotlin caller serialises opens behind one lock.
Runtime* loadRuntime(const std::string& nativeLibDir, std::string& err) {
    if (g_runtime != nullptr) return g_runtime;
    if (!isSafeAbsoluteDir(nativeLibDir)) {
        err = "nativeLibDir is empty or not an absolute path";
        return nullptr;
    }
    const std::string full = nativeLibDir + "/" + kRuntimeSoName;
    void* lib = dlopen(full.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (lib == nullptr) {
        const char* why = dlerror();
        err = std::string("dlopen failed: ") + (why ? why : "unknown");
        return nullptr;
    }
    auto* rt = new Runtime();
    rt->lib = lib;
    rt->open = reinterpret_cast<OpenFn>(dlsym(lib, "orchard_qnn_open"));
    rt->run = reinterpret_cast<RunFn>(dlsym(lib, "orchard_qnn_run"));
    rt->close = reinterpret_cast<CloseFn>(dlsym(lib, "orchard_qnn_close"));
    if (rt->open == nullptr || rt->run == nullptr || rt->close == nullptr) {
        err = "missing orchard_qnn_* symbol";
        dlclose(lib);
        delete rt;
        return nullptr;
    }
    g_runtime = rt;
    return rt;
}

std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

void throwOnBridgeError(JNIEnv* env, const char* message) {
    // Only used for programmer errors from Kotlin; runtime failures return 0/null.
    __android_log_print(ANDROID_LOG_WARN, kTag, "%s", message);
    (void)env;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_djmix_npu_OrchardNpuBridge_nativeOpen(
    JNIEnv* env, jclass /*clazz*/, jstring nativeLibDir, jstring modelPath,
    jstring cacheDir, jstring backendSoName) {
    const std::string libDir = toStdString(env, nativeLibDir);
    const std::string model = toStdString(env, modelPath);
    const std::string cache = toStdString(env, cacheDir);
    const std::string soName = toStdString(env, backendSoName);
    if (libDir.empty() || model.empty()) {
        throwOnBridgeError(env, "nativeOpen: empty libDir or model path");
        return 0;
    }
    // The backend soname is informational: the add-on's runtime file is fixed.
    if (!soName.empty() && soName != kRuntimeSoName) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "unexpected backend %s", soName.c_str());
        return 0;
    }

    std::string err;
    Runtime* rt = loadRuntime(libDir, err);
    if (rt == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "open rejected: %s", err.c_str());
        return 0;
    }

    char errBuf[kErrCap];
    std::memset(errBuf, 0, sizeof(errBuf));
    void* ctx = rt->open(model.c_str(), cache.c_str(), errBuf, sizeof(errBuf));
    if (ctx == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "orchard_qnn_open failed: %s", errBuf);
        return 0;
    }
    auto* session = new Session();
    session->runtime = rt;
    session->ctx = ctx;
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_djmix_npu_OrchardNpuBridge_nativeRun(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring inputName,
    jfloatArray inputData, jintArray shape, jint outSize) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session == nullptr || session->ctx == nullptr || outSize <= 0) return nullptr;
    if (inputData == nullptr || shape == nullptr) return nullptr;

    const jsize inLen = env->GetArrayLength(inputData);
    const jsize rank = env->GetArrayLength(shape);
    if (rank <= 0 || rank > kMaxRank || inLen <= 0) return nullptr;

    jint* dims = env->GetIntArrayElements(shape, nullptr);
    jfloat* in = env->GetFloatArrayElements(inputData, nullptr);
    if (dims == nullptr || in == nullptr) {
        if (dims) env->ReleaseIntArrayElements(shape, dims, JNI_ABORT);
        if (in) env->ReleaseFloatArrayElements(inputData, in, JNI_ABORT);
        return nullptr;
    }

    int64_t dims64[kMaxRank] = {0, 0, 0, 0};
    int64_t product = 1;
    bool ok = true;
    for (jsize i = 0; i < rank; ++i) {
        dims64[i] = dims[i];
        if (dims[i] <= 0) { ok = false; break; }
        product *= dims[i];
    }
    if (ok && product != inLen) ok = false;

    const std::string name = toStdString(env, inputName);
    std::vector<float> out(static_cast<size_t>(outSize), 0.0f);
    size_t written = 0;
    if (ok) {
        const int rc = session->runtime->run(
            session->ctx, name.c_str(), in, dims64, static_cast<int>(rank),
            out.data(), out.size(), &written);
        if (rc != 0) ok = false;
    }

    env->ReleaseIntArrayElements(shape, dims, JNI_ABORT);
    env->ReleaseFloatArrayElements(inputData, in, JNI_ABORT);
    if (!ok || written == 0 || written > out.size()) return nullptr;

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(written));
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(written), out.data());
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_djmix_npu_OrchardNpuBridge_nativeClose(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session == nullptr) return;
    if (session->ctx != nullptr && session->runtime != nullptr) {
        session->runtime->close(session->ctx);
    }
    delete session;
}
