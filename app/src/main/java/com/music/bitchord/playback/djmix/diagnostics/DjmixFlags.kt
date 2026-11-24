package com.music.bitchord.playback.djmix.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Effective, merged Automix feature flags (Part B11.5 / M8).
 *
 * Layers (lowest precedence first):
 * 1. Code defaults ([FlagsSnapshot.defaults]).
 * 2. Optional Wi-Fi-only `flags.json` override shipped with a models release —
 *    accepted only when a `flags.json.sha256` sibling holds the correct SHA-256
 *    of the file bytes; any missing/mismatched digest or parse failure ignores
 *    the whole override. [DjmixFlags.reload] additionally re-checks Wi-Fi at
 *    load time (defense-in-depth: the models downloader must fetch over Wi-Fi).
 * 3. Dev Settings overrides (persisted locally in SharedPreferences, sparse —
 *    only fields the developer changed).
 *
 * In safe mode ([FlagsSnapshot.safeMode]) only straight-blend recipes `S1`/`S5`
 * are allowed and every geometry collapses to [TransitionGeometry.STRAIGHT];
 * see [FlagsSnapshot.isRecipeAllowed] / [FlagsSnapshot.effectiveGeometry].
 * Every v2 kill switch moves here as an entry in [FlagsSnapshot.killSwitches]
 * (e.g. `"stems"`, `"analysis"`, `"recipe_lab"`, `"npu_qnn"`).
 */
@Serializable
data class FlagsSnapshot(
    val safeMode: Boolean = false,
    val forceLevel: DjLevel? = null,
    val forceRecipeId: String? = null,
    val showGridOnSeekbar: Boolean = false,
    val showInspectorDetails: Boolean = false,
    val learningEnabled: Boolean = true,
    val killSwitches: Map<String, Boolean> = emptyMap(),
) {
    /** True when the named feature kill switch is on. */
    fun isKilled(name: String): Boolean = killSwitches[name] == true

    /** True when stems are killed via the `"stems"` switch. */
    fun isStemsKilled(): Boolean = isKilled(KILL_SWITCH_STEMS)

    /** True when analysis is killed via the `"analysis"` switch. */
    fun isAnalysisKilled(): Boolean = isKilled(KILL_SWITCH_ANALYSIS)

    /** True when the Recipe Lab preview is killed via the `"recipe_lab"` switch. */
    fun isRecipeLabKilled(): Boolean = isKilled(KILL_SWITCH_RECIPE_LAB)

    /** True when the QNN/NPU backend is killed via the `"npu_qnn"` switch. */
    fun isNpuKilled(): Boolean = isKilled(KILL_SWITCH_NPU_QNN)

    /**
     * Effective recipe allow-list check: safe mode allows only [SAFE_MODE_RECIPE_IDS]
     * (`S1`, `S5`) straight blends; otherwise [forceRecipeId] pins the recipe when
     * set (only that id is allowed); otherwise every recipe is allowed.
     */
    fun isRecipeAllowed(recipeId: String): Boolean {
        if (safeMode) return recipeId in SAFE_MODE_RECIPE_IDS
        val forced = forceRecipeId?.trim()
        if (!forced.isNullOrEmpty()) return recipeId == forced
        return true
    }

    /** Effective DJ level after the [forceLevel] pin. */
    fun effectiveLevel(current: DjLevel): DjLevel = forceLevel ?: current

    /** Collapses every geometry to [TransitionGeometry.STRAIGHT] in safe mode. */
    fun effectiveGeometry(requested: TransitionGeometry): TransitionGeometry =
        if (safeMode) TransitionGeometry.STRAIGHT else requested

    companion object {
        /** Fresh code defaults (all overrides off). */
        fun defaults(): FlagsSnapshot = FlagsSnapshot()

        /** Recipe ids allowed in safe mode (straight blends only). */
        val SAFE_MODE_RECIPE_IDS: Set<String> = setOf("S1", "S5")

        const val KILL_SWITCH_STEMS: String = "stems"
        const val KILL_SWITCH_ANALYSIS: String = "analysis"
        const val KILL_SWITCH_RECIPE_LAB: String = "recipe_lab"
        const val KILL_SWITCH_NPU_QNN: String = "npu_qnn"
    }
}

/**
 * Sparse override layer shared by `flags.json` and the Dev Settings store:
 * non-null fields win over the layer below; [killSwitches] entries merge
 * (overlay entries override same-name entries).
 */
@Serializable
data class DevFlagOverrides(
    val safeMode: Boolean? = null,
    val forceLevel: DjLevel? = null,
    val forceRecipeId: String? = null,
    val showGridOnSeekbar: Boolean? = null,
    val showInspectorDetails: Boolean? = null,
    val learningEnabled: Boolean? = null,
    val killSwitches: Map<String, Boolean> = emptyMap(),
)

/**
 * Single source of truth for Automix flags (Part B11.5). Merges code defaults,
 * the optional Wi-Fi SHA-256-verified `flags.json` override, and Dev Settings
 * overrides into one [StateFlow] ([flags]) so every consumer (planner,
 * preparer, UI) reads the same effective snapshot.
 *
 * M7/M8 glue:
 * - Observe `flags.collectAsStateWithLifecycle()` in Compose screens / view models.
 * - Dev Settings sheet calls the `set*`/`clear*` mutators (persisted locally).
 * - The models-release downloader drops `flags.json` + `flags.json.sha256` over
 *   Wi-Fi, then calls [reload].
 */
class DjmixFlags(
    context: Context,
    val flagsFile: File = defaultFlagsFile(context),
    private val isWifiConnected: () -> Boolean = { isOnWifi(context) },
    private val prefsName: String = PREFS_NAME,
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _flags = MutableStateFlow(FlagsSnapshot.defaults())
    val flags: StateFlow<FlagsSnapshot> = _flags.asStateFlow()

    init {
        _flags.value = loadMerged()
    }

    /** Re-reads `flags.json` (Wi-Fi + SHA-256 verified) and Dev overrides, then republishes. */
    fun reload() {
        _flags.value = loadMerged()
    }

    // ---- Dev Settings mutators (persisted locally) ---------------------------

    fun setSafeMode(enabled: Boolean) {
        saveDevOverrides(readDevOverrides().copy(safeMode = enabled))
    }

    fun setForceLevel(level: DjLevel?) {
        saveDevOverrides(readDevOverrides().copy(forceLevel = level))
    }

    fun setForceRecipe(recipeId: String?) {
        saveDevOverrides(readDevOverrides().copy(forceRecipeId = recipeId?.trim()?.takeIf { it.isNotEmpty() }))
    }

    fun setShowGridOnSeekbar(enabled: Boolean) {
        saveDevOverrides(readDevOverrides().copy(showGridOnSeekbar = enabled))
    }

    fun setShowInspectorDetails(enabled: Boolean) {
        saveDevOverrides(readDevOverrides().copy(showInspectorDetails = enabled))
    }

    fun setLearningEnabled(enabled: Boolean) {
        saveDevOverrides(readDevOverrides().copy(learningEnabled = enabled))
    }

    fun setKillSwitch(name: String, killed: Boolean) {
        val current = readDevOverrides()
        saveDevOverrides(current.copy(killSwitches = current.killSwitches + (name to killed)))
    }

    /** Removes every Dev Settings override (code defaults + `flags.json` only). */
    fun clearDevOverrides() {
        prefs().edit().remove(KEY_DEV_OVERRIDES).apply()
        reload()
    }

    /** Current Dev Settings overlay (before merging). */
    fun devOverrides(): DevFlagOverrides = readDevOverrides()

    // ---- Merge pipeline -------------------------------------------------------

    private fun loadMerged(): FlagsSnapshot {
        val base = FlagsSnapshot.defaults()
        val release = loadReleaseOverrides()
        val dev = readDevOverrides()
        return merge(merge(base, release), dev)
    }

    private fun loadReleaseOverrides(): DevFlagOverrides {
        if (!flagsFile.isFile) return DevFlagOverrides()
        if (!isWifiConnected()) {
            AnalysisLog.warn("djmix flags: flags.json ignored (not on Wi-Fi)")
            return DevFlagOverrides()
        }
        val shaFile = File(flagsFile.parentFile, SHA_SUFFIX)
        if (!shaFile.isFile) {
            AnalysisLog.warn("djmix flags: flags.json ignored (missing ${shaFile.name})")
            return DevFlagOverrides()
        }
        val bytes = runCatching { flagsFile.readBytes() }
            .onFailure { AnalysisLog.warn("djmix flags: flags.json unreadable", it) }
            .getOrNull() ?: return DevFlagOverrides()
        val expectedHex = runCatching { shaFile.readText(Charsets.UTF_8).trim().split(Regex("\\s+")).firstOrNull() }
            .onFailure { AnalysisLog.warn("djmix flags: sha file unreadable", it) }
            .getOrNull()?.lowercase()
        val actualHex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }
        if (expectedHex.isNullOrEmpty() || !expectedHex.equals(actualHex, ignoreCase = true)) {
            AnalysisLog.warn("djmix flags: flags.json ignored (SHA-256 mismatch)")
            return DevFlagOverrides()
        }
        return runCatching { json.decodeFromString(DevFlagOverrides.serializer(), bytes.toString(Charsets.UTF_8)) }
            .onFailure { AnalysisLog.warn("djmix flags: flags.json unparsable, ignored", it) }
            .getOrDefault(DevFlagOverrides())
    }

    private fun readDevOverrides(): DevFlagOverrides {
        val raw = prefs().getString(KEY_DEV_OVERRIDES, null) ?: return DevFlagOverrides()
        return runCatching { json.decodeFromString(DevFlagOverrides.serializer(), raw) }
            .onFailure { AnalysisLog.warn("djmix flags: dev overrides unparsable, ignored", it) }
            .getOrDefault(DevFlagOverrides())
    }

    private fun saveDevOverrides(overrides: DevFlagOverrides) {
        prefs().edit()
            .putString(KEY_DEV_OVERRIDES, json.encodeToString(DevFlagOverrides.serializer(), overrides))
            .apply()
        reload()
    }

    private fun prefs() = appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME: String = "djmix_dev_flags"
        const val KEY_DEV_OVERRIDES: String = "dev_overrides_json"
        const val SHA_SUFFIX: String = "flags.json.sha256"

        /** Default `flags.json` location (models-release drop point, internal storage). */
        fun defaultFlagsFile(context: Context): File =
            File(context.applicationContext.noBackupFilesDir, "djmix/flags.json")

        /** True when the active network is Wi-Fi (models-release flag fetch gate). */
        fun isOnWifi(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }.onFailure { AnalysisLog.warn("djmix flags: wifi check failed", it) }
            .getOrDefault(false)

        /** Merges [overlay] over [base]: non-null fields and kill-switch entries win. Pure. */
        fun merge(base: FlagsSnapshot, overlay: DevFlagOverrides): FlagsSnapshot = FlagsSnapshot(
            safeMode = overlay.safeMode ?: base.safeMode,
            forceLevel = overlay.forceLevel ?: base.forceLevel,
            forceRecipeId = overlay.forceRecipeId ?: base.forceRecipeId,
            showGridOnSeekbar = overlay.showGridOnSeekbar ?: base.showGridOnSeekbar,
            showInspectorDetails = overlay.showInspectorDetails ?: base.showInspectorDetails,
            learningEnabled = overlay.learningEnabled ?: base.learningEnabled,
            killSwitches = base.killSwitches + overlay.killSwitches,
        )

        /** [merge] chained over an ordered overlay list (later overlays win). Pure. */
        fun mergeAll(base: FlagsSnapshot, overlays: Iterable<DevFlagOverrides>): FlagsSnapshot =
            overlays.fold(base) { acc, overlay -> merge(acc, overlay) }
    }
}
