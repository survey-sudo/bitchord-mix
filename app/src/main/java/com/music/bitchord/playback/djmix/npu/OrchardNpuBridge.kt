// AUTOMIX-V2(A): Orchard add-on discovery, model staging and the JNI handle.
// Only trusted, known add-on packages are considered (B14.3); the native
// directory always comes from PackageManager, never from stored paths.
package com.music.bitchord.playback.djmix.npu

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.IOException

/** Result of resolving the installed NPU add-on. Immutable snapshot. */
data class NpuAddon(
    val packageName: String,
    val versionCode: Long,
    val nativeLibraryDir: String,
)

object OrchardNpuBridge {
    private const val TAG = "BitChordNPU"

    /** Known add-on packages, in preference order (ours first, then Orchard). */
    val TRUSTED_PACKAGES: List<String> = listOf(
        "com.music.bitchord.loopfix.npu.v73",
        "dev.sfg.orchard.npu.v73",
    )

    /** Asset names inside the add-on that carry the QNN graphs. */
    const val QNN_NIROVI_ASSET = "nirovi_qnn.onnx"
    const val QNN_SYVENU_ASSET = "syvenu_qnn.onnx"

    private const val RUNTIME_SO = "liborchard_qnn.so"
    private const val BACKEND_SO_NAME = RUNTIME_SO
    private const val MODELS_DIR = "npu_models"
    private const val CACHE_DIR = "npu_cache"

    private val loadLock = Any()
    private val nativeOpenLock = Any()
    @Volatile private var libraryLoaded = false
    @Volatile private var libraryError: String? = null

    init {
        loadLibraryOnce()
    }

    private fun loadLibraryOnce() {
        synchronized(loadLock) {
            if (libraryLoaded || libraryError != null) return
            libraryError = runCatching {
                // The JNI symbols live in the existing bitchord_analysis target (CMake).
                System.loadLibrary("bitchord_analysis")
                libraryLoaded = true
                null
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (libraryError != null) {
                Log.w(TAG, "bridge unavailable: $libraryError")
            }
        }
    }

    /** True if the JNI shim loaded. Does not imply an add-on is installed. */
    fun bridgeLoaded(): Boolean = libraryLoaded

    /**
     * Finds the first trusted add-on that is installed, with a native lib dir.
     * Returns null on any PackageManager failure, which is the normal "not installed" path.
     */
    fun resolveAddon(context: Context): NpuAddon? {
        val pm = context.packageManager
        for (pkg in TRUSTED_PACKAGES) {
            val info = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0)
            }.getOrNull() ?: continue
            val appInfo = info.applicationInfo ?: continue
            val libDir = appInfo.nativeLibraryDir?.takeIf { it.isNotBlank() } ?: continue
            if (!File(libDir, RUNTIME_SO).isFile) continue
            val code = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            return NpuAddon(pkg, code, libDir)
        }
        return null
    }

    /**
     * Copies the two QNN graphs out of the add-on's assets into noBackupFilesDir.
     * Returns the staged files, or null if either asset is missing or copy fails.
     */
    fun stageQnnModels(context: Context, addon: NpuAddon): Pair<File, File>? {
        return runCatching {
            val assets = context.createPackageContext(addon.packageName, 0).assets
            val dir = File(context.noBackupFilesDir, MODELS_DIR).apply { mkdirs() }
            val nirovi = copyAsset(assets, QNN_NIROVI_ASSET, File(dir, QNN_NIROVI_ASSET))
            val syvenu = copyAsset(assets, QNN_SYVENU_ASSET, File(dir, QNN_SYVENU_ASSET))
            nirovi to syvenu
        }.onFailure { Log.w(TAG, "stage QNN models failed: ${it.message}") }.getOrNull()
    }

    private fun copyAsset(assets: android.content.res.AssetManager, name: String, target: File): File {
        // Copy atomically: write to a temp file, then rename.
        val tmp = File(target.parentFile, "${target.name}.part")
        assets.open(name).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("rename failed for $name")
        }
        return target
    }

    /** Per-device context cache directory, created on demand. */
    fun cacheDir(context: Context): File =
        File(context.noBackupFilesDir, CACHE_DIR).apply { mkdirs() }

    /**
     * Opens a QNN session for one staged graph. Returns 0 on any failure (CPU fallback).
     * The native side validates the library path and never throws.
     */
    fun open(addon: NpuAddon, modelFile: File, cache: File): Long {
        if (!libraryLoaded) return 0L
        return synchronized(nativeOpenLock) {
            runCatching {
                nativeOpen(addon.nativeLibraryDir, modelFile.absolutePath, cache.absolutePath, BACKEND_SO_NAME)
            }.getOrElse {
                Log.w(TAG, "nativeOpen threw: ${it.message}")
                0L
            }
        }
    }

    /** Runs one QNN inference. Null on failure. Output length is [outSize] floats. */
    fun run(handle: Long, inputName: String, data: FloatArray, shape: IntArray, outSize: Int): FloatArray? {
        if (handle == 0L || !libraryLoaded) return null
        return runCatching { nativeRun(handle, inputName, data, shape, outSize) }.getOrElse {
            Log.w(TAG, "nativeRun threw: ${it.message}")
            null
        }
    }

    fun close(handle: Long) {
        if (handle == 0L || !libraryLoaded) return
        runCatching { nativeClose(handle) }
    }

    @JvmStatic private external fun nativeOpen(
        nativeLibDir: String,
        modelPath: String,
        cacheDir: String,
        backendSoName: String,
    ): Long

    @JvmStatic private external fun nativeRun(
        handle: Long,
        inputName: String,
        inputData: FloatArray,
        shape: IntArray,
        outSize: Int,
    ): FloatArray?

    @JvmStatic private external fun nativeClose(handle: Long)
}
