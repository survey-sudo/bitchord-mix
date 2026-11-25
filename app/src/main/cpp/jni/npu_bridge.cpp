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
#include <cmath>
#include <cstring>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {

constexpr const char* kTag = "BitChordNPU";
constexpr const char* kRuntimeSoName = "liborchard_qnn.so";
constexpr size_t kErrCap = 512;
constexpr int kMaxRank = 4;
constexpr size_t kMaxOutputElements = 1U << 20;

using OpenFn = void* (*)(const char*, const char*, char*, size_t);
using RunFn = int (*)(void*, const char*, const float*, const int64_t*, int,
                      float*, size_t, size_t*);
using CloseFn = void (*)(void*);

struct Runtime {
    void* lib = nullptr;
    std::string nativeLibDir;
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
std::mutex g_runtime_mutex;

struct DlCloser {
    void operator()(void* handle) const noexcept {
        if (handle != nullptr) dlclose(handle);
    }
};

struct ContextCloser {
    Runtime* runtime = nullptr;
    void operator()(void* ctx) const noexcept {
        if (ctx != nullptr && runtime != nullptr && runtime->close != nullptr) runtime->close(ctx);
    }
};

bool isSafeAbsoluteDir(const std::string& dir) {
    // Refuse relative paths and any traversal segments: the directory must come
    // from PackageManager, never from user-controlled text.
    if (dir.empty() || dir[0] != '/') return false;
    if (dir.find("/../") != std::string::npos) return false;
    if (dir.size() >= 3 && dir.compare(dir.size() - 3, 3, "/..") == 0) return false;
    return true;
}

// Returns the runtime, or nullptr with `err` set. Runtime initialization is
// serialized here as well as in Kotlin because JNI may have other callers.
Runtime* loadRuntime(const std::string& nativeLibDir, std::string& err) {
    std::lock_guard<std::mutex> guard(g_runtime_mutex);
    if (g_runtime != nullptr) {
        if (g_runtime->nativeLibDir != nativeLibDir) {
            err = "runtime already loaded from a different add-on directory";
            return nullptr;
        }
        return g_runtime;
    }
    if (!isSafeAbsoluteDir(nativeLibDir)) {
        err = "nativeLibDir is empty or not an absolute path";
        return nullptr;
    }
    const std::string full = nativeLibDir + "/" + kRuntimeSoName;
    std::unique_ptr<void, DlCloser> lib(dlopen(full.c_str(), RTLD_NOW | RTLD_LOCAL));
    if (lib == nullptr) {
        const char* why = dlerror();
        err = std::string("dlopen failed: ") + (why ? why : "unknown");
        return nullptr;
    }
    std::unique_ptr<Runtime> rt(new Runtime());
    rt->lib = lib.get();
    rt->nativeLibDir = nativeLibDir;
    rt->open = reinterpret_cast<OpenFn>(dlsym(lib.get(), "orchard_qnn_open"));
    rt->run = reinterpret_cast<RunFn>(dlsym(lib.get(), "orchard_qnn_run"));
    rt->close = reinterpret_cast<CloseFn>(dlsym(lib.get(), "orchard_qnn_close"));
    if (rt->open == nullptr || rt->run == nullptr || rt->close == nullptr) {
        err = "missing orchard_qnn_* symbol";
        return nullptr;
    }
    lib.release();
    g_runtime = rt.release();
    return g_runtime;
}

std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    try {
        std::string out(chars);
        env->ReleaseStringUTFChars(s, chars);
        return out;
    } catch (...) {
        env->ReleaseStringUTFChars(s, chars);
        throw;
    }
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
    try {
    if (env->ExceptionCheck() || nativeLibDir == nullptr || modelPath == nullptr || cacheDir == nullptr || backendSoName == nullptr) return 0;
    const std::string libDir = toStdString(env, nativeLibDir);
    if (env->ExceptionCheck()) return 0;
    const std::string model = toStdString(env, modelPath);
    if (env->ExceptionCheck()) return 0;
    const std::string cache = toStdString(env, cacheDir);
    if (env->ExceptionCheck()) return 0;
    const std::string soName = toStdString(env, backendSoName);
    if (env->ExceptionCheck()) return 0;
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
    std::unique_ptr<void, ContextCloser> ownedContext(ctx, ContextCloser{rt});
    std::unique_ptr<Session> session(new Session());
    session->runtime = rt;
    session->ctx = ownedContext.get();
    ownedContext.release();
    return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeOpen failed: %s", error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeOpen failed: unknown native exception");
    }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_music_bitchord_playback_djmix_npu_OrchardNpuBridge_nativeRun(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring inputName,
    jfloatArray inputData, jintArray shape, jint outSize) {
    try {
    auto* session = reinterpret_cast<Session*>(handle);
    if (env->ExceptionCheck() || session == nullptr || session->ctx == nullptr || outSize <= 0 ||
        static_cast<size_t>(outSize) > kMaxOutputElements || inputName == nullptr ||
        inputData == nullptr || shape == nullptr) return nullptr;

    const jsize inLen = env->GetArrayLength(inputData);
    if (env->ExceptionCheck()) return nullptr;
    const jsize rank = env->GetArrayLength(shape);
    if (env->ExceptionCheck() || rank <= 0 || rank > kMaxRank || inLen <= 0) return nullptr;

    jint* dims = env->GetIntArrayElements(shape, nullptr);
    if (env->ExceptionCheck()) return nullptr;
    jfloat* in = env->GetFloatArrayElements(inputData, nullptr);
    if (env->ExceptionCheck()) {
        if (dims != nullptr) env->ReleaseIntArrayElements(shape, dims, JNI_ABORT);
        return nullptr;
    }
    if (dims == nullptr || in == nullptr) {
        if (dims) env->ReleaseIntArrayElements(shape, dims, JNI_ABORT);
        if (in) env->ReleaseFloatArrayElements(inputData, in, JNI_ABORT);
        return nullptr;
    }

    struct ArrayReleaser {
        JNIEnv* env;
        jintArray shape;
        jfloatArray input;
        jint* dims;
        jfloat* values;
        ~ArrayReleaser() {
            if (dims != nullptr) env->ReleaseIntArrayElements(shape, dims, JNI_ABORT);
            if (values != nullptr) env->ReleaseFloatArrayElements(input, values, JNI_ABORT);
        }
    } release{env, shape, inputData, dims, in};

    int64_t dims64[kMaxRank] = {0, 0, 0, 0};
    int64_t product = 1;
    bool ok = true;
    for (jsize i = 0; i < rank; ++i) {
        dims64[i] = dims[i];
        if (dims[i] <= 0 || product > static_cast<int64_t>(inLen) / dims[i]) { ok = false; break; }
        product *= dims[i];
    }
    if (ok && product != inLen) ok = false;
    if (ok) {
        for (jsize i = 0; i < inLen; ++i) {
            if (!std::isfinite(in[i])) { ok = false; break; }
        }
    }

    const std::string name = toStdString(env, inputName);
    if (env->ExceptionCheck() || name.empty()) return nullptr;
    std::vector<float> out(static_cast<size_t>(outSize), 0.0f);
    size_t written = 0;
    if (ok) {
        const int rc = session->runtime->run(
            session->ctx, name.c_str(), in, dims64, static_cast<int>(rank),
            out.data(), out.size(), &written);
        if (rc != 0) ok = false;
    }

    if (env->ExceptionCheck()) return nullptr;
    if (!ok || written == 0 || written > out.size()) return nullptr;
    for (size_t i = 0; i < written; ++i) {
        if (!std::isfinite(out[i])) return nullptr;
    }

    jfloatArray result = env->NewFloatArray(static_cast<jsize>(written));
    if (result == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(written), out.data());
    if (env->ExceptionCheck()) return nullptr;
    return result;
    } catch (const std::exception& error) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeRun failed: %s", error.what());
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeRun failed: unknown native exception");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_djmix_npu_OrchardNpuBridge_nativeClose(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* session = reinterpret_cast<Session*>(handle);
    if (session == nullptr) return;
    try {
        if (session->ctx != nullptr && session->runtime != nullptr) {
            session->runtime->close(session->ctx);
        }
    } catch (...) {
        __android_log_print(ANDROID_LOG_WARN, kTag, "nativeClose: vendor close threw");
    }
    delete session;
}
