package com.music.bitchord.playback.smart

/**
 * AUTOMIX-V2(A): persisted NPU policy state. One record per install; reset when the app
 * versionCode changes (new models / new runtime deserve a fresh chance).
 */
data class NpuPolicyState(
    val versionCode: Long = -1L,
    val consecutiveFailures: Int = 0,
    val autoDisabled: Boolean = false,
    /** Why the NPU was auto-disabled ("" while enabled). */
    val reason: String = "",
    /** Last self-test verdict for this versionCode ("" = not run yet), see [NpuVerdict]. */
    val selfTestVerdict: String = "",
)

/**
 * Storage + user switch behind [NpuPolicy]. The Android implementation is
 * SharedPreferences-backed (NpuAndroid); tests use [InMemoryNpuPolicyStore].
 */
interface NpuPolicyStore {
    /** The user's "Use the NPU" switch (AppSettings.automixNpuEnabled on Android). */
    fun userEnabled(): Boolean

    /** The persisted state, or null when storage is not readable yet (e.g. Service not attached). */
    fun load(): NpuPolicyState?

    fun save(state: NpuPolicyState)
}

class InMemoryNpuPolicyStore(
    var enabled: Boolean = true,
    var state: NpuPolicyState? = null,
) : NpuPolicyStore {
    override fun userEnabled(): Boolean = enabled
    override fun load(): NpuPolicyState? = state
    override fun save(state: NpuPolicyState) {
        this.state = state
    }
}

/**
 * Pure policy over an [NpuPolicyStore]: the user switch, auto-disable after
 * [AutomixV2Npu.maxConsecutiveFailures] consecutive QNN failures or one self-test mismatch,
 * and a versionCode reset. State is cached in memory after the first successful load, so
 * [enabled] is cheap enough to call before every session open. Mutations are
 * read-modify-write without a lock (commonMain has none); a lost update only miscounts one
 * failure, never enables a disabled NPU, because autoDisabled is sticky in every copy.
 */
class NpuPolicy(
    private val store: NpuPolicyStore,
    private val versionCode: Long,
) {
    @Volatile private var cached: NpuPolicyState? = null

    fun state(): NpuPolicyState {
        cached?.let { return it }
        val loaded = runCatching { store.load() }.getOrNull()
            ?: return NpuPolicyState(versionCode = versionCode)
        val current = if (loaded.versionCode != versionCode) {
            NpuPolicyState(versionCode = versionCode).also { fresh -> runCatching { store.save(fresh) } }
        } else {
            loaded
        }
        cached = current
        return current
    }

    fun userEnabled(): Boolean = runCatching { store.userEnabled() }.getOrDefault(false)

    /** User switch on and not auto-disabled for this versionCode. */
    fun enabled(): Boolean = userEnabled() && !state().autoDisabled

    fun selfTestPassed(): Boolean = state().selfTestVerdict == NpuVerdict.PASS.name

    /** True when a self-test verdict already exists for this versionCode. */
    fun selfTestDone(): Boolean = state().selfTestVerdict.isNotEmpty()

    /** Records one QNN failure; returns true when this failure tripped the auto-disable. */
    fun recordFailure(reason: String): Boolean {
        val s = state()
        val count = s.consecutiveFailures + 1
        val trip = !s.autoDisabled && count >= AutomixV2Npu.maxConsecutiveFailures
        update(
            s.copy(
                consecutiveFailures = count,
                autoDisabled = s.autoDisabled || trip,
                reason = if (trip) "failures:" + reason.take(120) else s.reason,
            ),
        )
        return trip
    }

    /** A QNN success ends the failure streak (writes only when there was one). */
    fun recordSuccess() {
        val s = state()
        if (s.consecutiveFailures != 0) update(s.copy(consecutiveFailures = 0))
    }

    /** Feeds a self-test verdict: MISMATCH disables, QNN_FAILED counts as a failure. */
    fun recordSelfTest(verdict: NpuVerdict, detail: String) {
        when (verdict) {
            NpuVerdict.PASS -> update(state().copy(selfTestVerdict = verdict.name, consecutiveFailures = 0))
            NpuVerdict.SLOW -> update(state().copy(selfTestVerdict = verdict.name))
            NpuVerdict.MISMATCH -> update(
                state().copy(
                    selfTestVerdict = verdict.name,
                    autoDisabled = true,
                    reason = "selftest-mismatch:" + detail.take(120),
                ),
            )
            NpuVerdict.QNN_FAILED -> {
                recordFailure("selftest:" + detail)
                update(state().copy(selfTestVerdict = verdict.name))
            }
            NpuVerdict.SKIPPED -> Unit
        }
    }

    /** Clears auto-disable and the verdict (e.g. a debug action). */
    fun reset() {
        update(NpuPolicyState(versionCode = versionCode))
    }

    fun summary(): String {
        val s = state()
        return "user=" + userEnabled() + " autoDisabled=" + s.autoDisabled + " failures=" + s.consecutiveFailures +
            " selftest=" + s.selfTestVerdict.ifEmpty { "none" } + (if (s.reason.isNotEmpty()) " reason=" + s.reason else "")
    }

    private fun update(next: NpuPolicyState) {
        cached = next
        runCatching { store.save(next) }
    }
}
