package com.music.bitchord.playback.smart

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.music.bitchord.BuildConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AUTOMIX-V2(A): Android wiring for the NPU runtime switch.
 *
 * [install] (called once from TrackAnalyzer, see Stream A NOTES "Orchestrator hooks")
 * installs:
 * - the [NpuPolicy] over a SharedPreferences store, bound to the user's switch
 *   (AppSettings.automixNpuEnabled) and reset whenever BuildConfig.VERSION_CODE changes;
 * - the SoC model (Build.SOC_MODEL, API 31+) used for the htp_arch / soc_model options and
 *   the EP-context cache key;
 * - the bundled libQnnHtp.so resolver, which runs the ADSP_LIBRARY_PATH + dlopen preflight
 *   (npu_jni.cpp) once, before the first QNN session;
 * - an [NpuQnnSession.QdqResolver] that unpacks `*_qdq.onnx` / `*_qdq_ctx.onnx` assets on demand.
 *
 * Safe to call while the Service is still unattached: nothing touches the Context until a
 * method needs it, and every Context access is guarded (null state = defaults, retried later).
 */
object NpuAndroid {
    private const val TAG = "BitChordNPU"
    private const val PREFS = "bitchord_npu_policy"
    private const val KEY_VERSION = "version_code"
    private const val KEY_FAILURES = "consecutive_failures"
    private const val KEY_DISABLED = "auto_disabled"
    private const val KEY_REASON = "reason"
    private const val KEY_VERDICT = "selftest_verdict"

    private val installed = AtomicBoolean(false)
    private val assetLock = Any()
    private val preflightLock = Any()
    @Volatile private var preflightDone = false
    @Volatile private var preflightOk = false

    /**
     * Bundled backend path after a one-time preflight, or null (NPU off, Service not yet
     * attached, libs missing or preflight failed). Re-evaluated on every call until the
     * Context is attached, never cached as null before that.
     */
    private fun preflightBackend(context: Context): String? {
        if (!NpuRuntime.runtimeEnabled()) return null
        val dir = runCatching { context.applicationContext?.applicationInfo?.nativeLibraryDir }.getOrNull()
            ?: return null
        val backend = NpuSupport.bundledBackendPath(dir)
        if (!File(backend).exists()) return null
        synchronized(preflightLock) {
            if (!preflightDone) {
                preflightOk = runCatching {
                    System.loadLibrary("bitchord_analysis")
                    NpuNative.nativeInitAdspPath(dir)
                }.getOrDefault(false)
                preflightDone = true
                Log.i(TAG, "BitChordNPU v2 preflight libDir=" + dir + " ok=" + preflightOk +
                    " soc=" + (NpuRuntime.socModel() ?: "unknown") + " " + (NpuRuntime.policy()?.summary() ?: ""))
            }
        }
        return if (preflightOk) backend else null
    }

    fun install(context: Context, userEnabled: () -> Boolean) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            NpuRuntime.installSocModel(socModel())
            NpuRuntime.install(NpuPolicy(PrefsStore(context, userEnabled), BuildConfig.VERSION_CODE.toLong()))
            NpuRuntime.installBackendProvider { preflightBackend(context) }
            NpuQnnSession.qdqResolver = NpuQnnSession.QdqResolver { model, name ->
                model.parentFile?.let { File(it, name) }?.takeIf { it.isFile && it.length() > 0 }
                    ?: unpackAsset(context, name)
            }
        }.onFailure { Log.w(TAG, "BitChordNPU install failed; NPU stays off", it) }
    }

    /**
     * Build.SOC_MODEL on API 31+ (a system property read; guarded because some OEM SELinux
     * policies make SoC queries throw or return "unknown"). Null when unknown.
     */
    fun socModel(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null
    }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }

    /** Unpacks a bundled asset named [name] into noBackupFilesDir/npu/models/<versionCode>/, or null. */
    fun unpackAsset(context: Context, name: String): File? = runCatching {
        val app = context.applicationContext ?: return@runCatching null
        val names = app.assets.list("") ?: return@runCatching null
        if (name !in names) return@runCatching null
        val root = File(app.noBackupFilesDir, "npu/models")
        val dir = File(root, BuildConfig.VERSION_CODE.toString())
        val target = File(dir, name)
        synchronized(assetLock) {
            if (!(target.isFile && target.length() > 0)) {
                dir.mkdirs()
                root.listFiles()?.forEach { old -> if (old.isDirectory && old.name != dir.name) old.deleteRecursively() }
                val tmp = File(dir, name + ".tmp")
                app.assets.open(name).use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
        target.takeIf { it.isFile && it.length() > 0 }
    }.getOrNull()

    private class PrefsStore(
        private val context: Context,
        private val userSwitch: () -> Boolean,
    ) : NpuPolicyStore {
        private fun prefs(): SharedPreferences? = runCatching {
            context.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()

        override fun userEnabled(): Boolean = runCatching { userSwitch() }.getOrDefault(false)

        override fun load(): NpuPolicyState? {
            val p = prefs() ?: return null
            return NpuPolicyState(
                versionCode = p.getLong(KEY_VERSION, -1L),
                consecutiveFailures = p.getInt(KEY_FAILURES, 0),
                autoDisabled = p.getBoolean(KEY_DISABLED, false),
                reason = p.getString(KEY_REASON, "") ?: "",
                selfTestVerdict = p.getString(KEY_VERDICT, "") ?: "",
            )
        }

        override fun save(state: NpuPolicyState) {
            val p = prefs() ?: return
            p.edit()
                .putLong(KEY_VERSION, state.versionCode)
                .putInt(KEY_FAILURES, state.consecutiveFailures)
                .putBoolean(KEY_DISABLED, state.autoDisabled)
                .putString(KEY_REASON, state.reason)
                .putString(KEY_VERDICT, state.selfTestVerdict)
                .apply()
        }
    }
}
