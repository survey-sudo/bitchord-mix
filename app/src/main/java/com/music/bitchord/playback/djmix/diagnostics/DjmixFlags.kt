package com.music.bitchord.playback.djmix.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Effective, merged Automix feature flags (Part B11.5 / M8).
 *
 * Layers (lowest precedence first):
 * 1. Code defaults ([FlagsSnapshot.defaults]).
 * 2. Optional Wi-Fi-only `flags.json` override shipped with a models release —
 *    accepted only when a `flags.json.sha256` sibling holds the correct SHA-256
 *    of the file bytes; any missing/mismatched digest or parse failure ignores
 *    the whole override. [ReleaseFlagsUpdater] fetches only over validated Wi-Fi;
 *    the last verified generation remains available offline.
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
    val disabledRecipeIds: Set<String> = emptySet(),
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
        if (recipeId.isBlank() || recipeId in disabledRecipeIds || isKilled("$KILL_SWITCH_RECIPE_PREFIX$recipeId")) return false
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

    /** A dispatch owns its maps even when a caller supplied mutable override collections. */
    fun detached(): FlagsSnapshot = copy(killSwitches = killSwitches.toMap(), disabledRecipeIds = disabledRecipeIds.toSet())

    /** Refuse a frozen plan; execution must never silently rewrite its recipe or geometry. */
    fun executionRefusal(plan: TransitionPlanV1, recipe: RecipeV2? = null): String? = when {
        !isRecipeAllowed(plan.recipeId) -> "recipe_disabled:${plan.recipeId}"
        recipe != null && recipe.id != plan.recipeId -> "recipe_identity_mismatch"
        recipe?.enabled == false -> "recipe_disabled:${plan.recipeId}"
        safeMode && plan.geometry != TransitionGeometry.STRAIGHT -> "safe_mode_requires_straight"
        isStemsKilled() && plan.requiredStems.isNotEmpty() -> "stems_disabled"
        else -> null
    }

    companion object {
        /** Fresh code defaults (all overrides off). */
        fun defaults(): FlagsSnapshot = FlagsSnapshot()

        /** Recipe ids allowed in safe mode (straight blends only). */
        val SAFE_MODE_RECIPE_IDS: Set<String> = setOf("S1", "S5")

        const val KILL_SWITCH_STEMS: String = "stems"
        const val KILL_SWITCH_ANALYSIS: String = "analysis"
        const val KILL_SWITCH_RECIPE_LAB: String = "recipe_lab"
        const val KILL_SWITCH_NPU_QNN: String = "npu_qnn"
        const val KILL_SWITCH_RECIPE_PREFIX: String = "recipe:"
    }
}

/**
 * Sparse override layer shared by `flags.json` and the Dev Settings store:
 * non-null fields win over the layer below; [killSwitches] entries merge
 * (overlay entries override same-name entries). The two force pins also support
 * explicit clear markers; legacy records without those markers still inherit on null.
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
    val disabledRecipeIds: Set<String>? = null,
    val clearForceLevel: Boolean = false,
    val clearForceRecipeId: Boolean = false,
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
 * - [ReleaseFlagsUpdater] verifies and atomically publishes the optional release
 *   pair, then reloads this process owner's flow. Legacy loose pairs remain readable.
 */
class DjmixFlags(
    context: Context,
    val flagsFile: File = defaultFlagsFile(context),
    @Suppress("UNUSED_PARAMETER") isWifiConnected: () -> Boolean = { isOnWifi(context) }, // Caller compatibility; cache reads work offline.
    private val prefsName: String = PREFS_NAME,
) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val releaseFile = File(flagsFile.absoluteFile.parentFile, flagsFile.name + ".verified")
    private var lastVerifiedRelease: DevFlagOverrides? = null

    private val _flags = MutableStateFlow(FlagsSnapshot.defaults())
    val flags: StateFlow<FlagsSnapshot> = _flags.asStateFlow()

    init {
        _flags.value = loadMerged()
    }

    /** Re-verifies the cached release, including offline, then applies Dev overrides. */
    @Synchronized
    fun reload() {
        _flags.value = loadMerged()
    }

    /** One atomic record keeps the exact downloaded bytes and sidecar in the same generation. */
    @Synchronized
    internal fun publishRelease(bytes: ByteArray, checksum: ByteArray, beforeCommit: () -> Unit) {
        verifyRelease(bytes, checksum)
        val parent = releaseFile.parentFile ?: throw IOException("Flags directory unavailable")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create flags directory")
        val temporary = File(parent, releaseFile.name + ".tmp")
        try {
            beforeCommit()
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file)
                output.writeInt(RELEASE_MAGIC)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.writeInt(checksum.size)
                output.write(checksum)
                output.flush()
                file.fd.sync()
            }
            beforeCommit()
            // No non-atomic fallback: any failed move leaves the preceding generation intact.
            Files.move(temporary.toPath(), releaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            reload()
        } finally {
            temporary.delete()
        }
    }

    // ---- Dev Settings mutators (persisted locally) ---------------------------

    fun setSafeMode(enabled: Boolean) {
        saveDevOverrides(readDevOverrides().copy(safeMode = enabled))
    }

    fun setForceLevel(level: DjLevel?) {
        saveDevOverrides(readDevOverrides().copy(forceLevel = level, clearForceLevel = level == null))
    }

    fun setForceRecipe(recipeId: String?) {
        val forced = recipeId?.trim()?.takeIf { it.isNotEmpty() }
        saveDevOverrides(readDevOverrides().copy(forceRecipeId = forced, clearForceRecipeId = forced == null))
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

    fun setDisabledRecipes(recipeIds: Set<String>) {
        saveDevOverrides(readDevOverrides().copy(disabledRecipeIds = recipeIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()))
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
        if (!releaseFile.exists() && !flagsFile.exists()) return lastVerifiedRelease ?: DevFlagOverrides()
        return runCatching {
            val (bytes, checksum) = if (releaseFile.exists()) {
                DataInputStream(releaseFile.inputStream()).use { input ->
                    if (input.readInt() != RELEASE_MAGIC) throw IOException("Invalid flags generation")
                    fun readPart(limit: Int): ByteArray {
                        val size = input.readInt()
                        if (size !in 1..limit) throw IOException("Invalid flags generation size")
                        return ByteArray(size).also { input.readFully(it) }
                    }
                    val pair = readPart(MAX_RELEASE_BYTES) to readPart(MAX_CHECKSUM_BYTES)
                    if (input.read() != -1) throw IOException("Trailing flags generation bytes")
                    pair
                }
            } else {
                flagsFile.inputStream().use { readBounded(it, MAX_RELEASE_BYTES) } to
                    File(flagsFile.parentFile, SHA_SUFFIX).inputStream().use { readBounded(it, MAX_CHECKSUM_BYTES) }
            }
            verifyRelease(bytes, checksum)
        }.onSuccess { lastVerifiedRelease = it }
            .onFailure { AnalysisLog.warn("djmix flags: release unavailable; retaining last verified layer", it) }
            .getOrElse { lastVerifiedRelease ?: DevFlagOverrides() }
    }

    private fun verifyRelease(bytes: ByteArray, checksum: ByteArray): DevFlagOverrides {
        if (bytes.size !in 1..MAX_RELEASE_BYTES || checksum.size !in 1..MAX_CHECKSUM_BYTES) {
            throw IOException("Flags release size outside limits")
        }
        val expectedHex = Regex("([0-9a-fA-F]{64})(?:[ \\t]+\\*?flags\\.json)?")
            .matchEntire(decodeUtf8(checksum).trim())?.groupValues?.get(1)
            ?: throw IOException("Malformed flags checksum")
        val actualHex = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (!expectedHex.equals(actualHex, ignoreCase = true)) {
            throw IOException("Flags SHA-256 mismatch")
        }
        return json.decodeFromString(DevFlagOverrides.serializer(), decodeUtf8(bytes))
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
        internal const val MAX_RELEASE_BYTES = 64 * 1024
        internal const val MAX_CHECKSUM_BYTES = 256
        private const val RELEASE_MAGIC = 0x44464c31 // DFL1, length-prefixed JSON + checksum.

        private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()

        internal fun readBounded(input: InputStream, limit: Int, beforeRead: () -> Unit = {}): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                beforeRead()
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (count < 0) return output.toByteArray()
                if (output.size() + count > limit) throw IOException("Flags response exceeds $limit bytes")
                output.write(buffer, 0, count)
            }
        }

        /** Default `flags.json` location (models-release drop point, internal storage). */
        fun defaultFlagsFile(context: Context): File =
            File(context.applicationContext.noBackupFilesDir, "djmix/flags.json")

        /** True only for validated active Wi-Fi; cached release reads never need this gate. */
        fun isOnWifi(context: Context): Boolean = runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.onFailure { AnalysisLog.warn("djmix flags: wifi check failed", it) }
            .getOrDefault(false)

        /** Merges [overlay] over [base]: clear markers, non-null fields and kill-switch entries win. Pure. */
        fun merge(base: FlagsSnapshot, overlay: DevFlagOverrides): FlagsSnapshot = FlagsSnapshot(
            safeMode = overlay.safeMode ?: base.safeMode,
            forceLevel = if (overlay.clearForceLevel) null else overlay.forceLevel ?: base.forceLevel,
            forceRecipeId = if (overlay.clearForceRecipeId) null else overlay.forceRecipeId ?: base.forceRecipeId,
            showGridOnSeekbar = overlay.showGridOnSeekbar ?: base.showGridOnSeekbar,
            showInspectorDetails = overlay.showInspectorDetails ?: base.showInspectorDetails,
            learningEnabled = overlay.learningEnabled ?: base.learningEnabled,
            killSwitches = base.killSwitches + overlay.killSwitches,
            disabledRecipeIds = overlay.disabledRecipeIds?.toSet() ?: base.disabledRecipeIds.toSet(),
        )

        /** [merge] chained over an ordered overlay list (later overlays win). Pure. */
        fun mergeAll(base: FlagsSnapshot, overlays: Iterable<DevFlagOverrides>): FlagsSnapshot =
            overlays.fold(base) { acc, overlay -> merge(acc, overlay) }
    }
}
