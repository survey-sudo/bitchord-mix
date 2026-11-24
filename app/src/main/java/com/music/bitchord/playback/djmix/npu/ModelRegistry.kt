// AUTOMIX-V2(A): model catalogue and backend selection. Reads the bundled
// assets/models.json, with an optional downloaded override in filesDir.
package com.music.bitchord.playback.djmix.npu

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One catalogue entry, mirroring models.json. Nullable fields are optional per entry. */
data class ModelEntry(
    val id: String,
    val version: String,
    val fileName: String,
    val bytes: Long?,
    val sha256: String?,
    val checksumStatus: String?,
    val source: String,
    val frontend: String?,
    val inputName: String?,
    val inputShape: IntArray?,
    val outputName: String?,
    val backends: List<String>,
    val role: String,
    val minEngine: String,
)

/** Snapshot of the add-on, used by the selector. */
data class AddonState(
    val installed: Boolean,
    val versionCode: Long,
)

/** The backend choice plus the reason, for Inspector and logs. */
data class Selection(
    val model: ModelEntry,
    val backendId: String,
    val reason: String,
)

object ModelRegistry {
    const val ASSET_NAME = "models.json"
    const val OVERRIDE_NAME = "models.json"
    private const val SCHEMA = 1

    /** Loads the bundled catalogue, then merges a filesDir override when present and valid. */
    fun load(context: Context): List<ModelEntry> {
        val bundled = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val base = parse(bundled)
        val override = File(context.filesDir, OVERRIDE_NAME)
        if (!override.isFile) return base
        val overrideEntries = runCatching { parse(override.readText()) }.getOrNull() ?: return base
        // Override replaces entries with the same id; unknown ids are appended.
        val byId = LinkedHashMap<String, ModelEntry>()
        base.forEach { byId[it.id] = it }
        overrideEntries.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    /** Parses one catalogue document. Throws on a malformed or unsupported schema. */
    fun parse(json: String): List<ModelEntry> {
        val root = JSONObject(json)
        require(root.getInt("schema") == SCHEMA) { "unsupported models.json schema" }
        val models: JSONArray = root.getJSONArray("models")
        return (0 until models.length()).map { parseEntry(models.getJSONObject(it)) }
    }

    private fun parseEntry(o: JSONObject): ModelEntry {
        val io = o.optJSONObject("io")
        val input = io?.optJSONArray("inputs")?.optJSONObject(0)
        val output = io?.optJSONArray("outputs")?.optJSONObject(0)
        return ModelEntry(
            id = o.getString("id"),
            version = o.getString("version"),
            fileName = o.getString("fileName"),
            bytes = if (o.isNull("bytes")) null else o.getLong("bytes"),
            sha256 = if (o.isNull("sha256")) null else o.getString("sha256"),
            checksumStatus = o.optString("checksumStatus", "").ifEmpty { null },
            source = o.getString("source"),
            frontend = o.optString("frontend", "").ifEmpty { null },
            inputName = input?.getString("name"),
            inputShape = input?.getJSONArray("shape")?.let { arr ->
                IntArray(arr.length()) { arr.getInt(it) }
            },
            outputName = output?.getString("name"),
            backends = (0 until o.getJSONArray("backends").length())
                .map { o.getJSONArray("backends").getString(it) },
            role = o.getString("role"),
            minEngine = o.getString("minEngine"),
        )
    }

    /**
     * Selects the model and backend for a role. QNN is chosen only when all hold:
     * the add-on is installed, the self-test passed for this (fingerprint, app, addon)
     * tuple, and NPU latency is strictly below CPU latency. Otherwise CPU.
     */
    fun select(
        entries: List<ModelEntry>,
        role: String,
        addon: AddonState,
        selfTest: SelfTestRecord?,
        currentFingerprint: String,
        currentAppVersionCode: Long,
    ): Selection? {
        val candidates = entries.filter { it.role == role }
        val cpu = candidates.firstOrNull { "cpu-ort" in it.backends } ?: return null
        val qnn = candidates.firstOrNull { "qnn-v73" in it.backends }
        if (qnn != null && qnnAllowed(addon, selfTest, currentFingerprint, currentAppVersionCode)) {
            return Selection(qnn, "qnn-v73", "npu self-test passed, ep=QNN")
        }
        return Selection(cpu, "cpu-ort", cpuReason(addon, selfTest, currentFingerprint, currentAppVersionCode))
    }

    /** True only when every QNN gate holds. Pure, no I/O. */
    fun qnnAllowed(
        addon: AddonState,
        selfTest: SelfTestRecord?,
        currentFingerprint: String,
        currentAppVersionCode: Long,
    ): Boolean {
        if (!addon.installed) return false
        if (selfTest == null || !selfTest.passed) return false
        if (selfTest.fingerprint != currentFingerprint) return false
        if (selfTest.appVersionCode != currentAppVersionCode) return false
        if (selfTest.addonVersionCode != addon.versionCode) return false
        return selfTest.npuMedianMs > 0.0 && selfTest.npuMedianMs < selfTest.cpuMedianMs
    }

    private fun cpuReason(
        addon: AddonState,
        selfTest: SelfTestRecord?,
        fingerprint: String,
        appVersionCode: Long,
    ): String = when {
        !addon.installed -> "ep=CPU (add-on not installed)"
        selfTest == null -> "ep=CPU (self-test not run)"
        !selfTest.passed -> "ep=CPU (self-test failed: ${selfTest.reason})"
        selfTest.fingerprint != fingerprint -> "ep=CPU (self-test for another device)"
        selfTest.appVersionCode != appVersionCode -> "ep=CPU (self-test for another app version)"
        selfTest.addonVersionCode != addon.versionCode -> "ep=CPU (self-test for another add-on version)"
        else -> "ep=CPU (npu not faster: ${selfTest.npuMedianMs} ms vs ${selfTest.cpuMedianMs} ms)"
    }
}

/**
 * Persisted self-test verdict. Keyed by (fingerprint, appVersionCode, addonVersionCode);
 * a change in any of them invalidates it.
 */
data class SelfTestRecord(
    val fingerprint: String,
    val appVersionCode: Long,
    val addonVersionCode: Long,
    val passed: Boolean,
    val beatPeakAgreement: Double,
    val cpuMedianMs: Double,
    val npuMedianMs: Double,
    val reason: String,
)
