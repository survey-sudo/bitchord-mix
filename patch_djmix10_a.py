#!/usr/bin/env python3
"""djmix10 patch: P0 fixes (freeze-once-armed, persistent telemetry hooks,
native equal-power gains, analysis trust, EOF holdback anchor).
Apply from work/BitChord root. Each edit asserts exactly one match."""
import sys, os

ROOT = os.path.dirname(os.path.abspath(__file__))
# script lives in work/BitChord when run
def edit(path, old, new, required=True):
    full = os.path.join(ROOT, path)
    with open(full, encoding="utf-8") as f:
        src = f.read()
    n = src.count(old)
    if n != 1:
        if not required:
            print(f"SKIP (found {n}): {path} :: {old[:60]!r}")
            return
        print(f"FAIL (found {n}, want 1): {path} :: {old[:80]!r}")
        sys.exit(1)
    src = src.replace(old, new, 1)
    with open(full, "w", encoding="utf-8") as f:
        f.write(src)
    print(f"OK: {path} :: {old[:50]!r}")

# ---------------------------------------------------------------- version bump
edit("build.gradle.kts",
     'extra["bitchordVersionCode"] = 51',
     'extra["bitchordVersionCode"] = 52')
edit("build.gradle.kts",
     'extra["bitchordVersionName"] = "1.9.1-djmix9"',
     'extra["bitchordVersionName"] = "1.9.1-djmix10"')

# ---------------------------------------------------------------- MixGain: native equal-power envelope helpers
edit("shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/MixGain.kt",
     """    /** A filter ride. The low-pass is doing the tonal work; the fader just leaves. */
    fun filterFall(progress: Float): Float = fall(progress.coerceIn(0f, 1f).pow(1.6f))
}""",
     """    /** A filter ride. The low-pass is doing the tonal work; the fader just leaves. */
    fun filterFall(progress: Float): Float = fall(progress.coerceIn(0f, 1f).pow(1.6f))

    /**
     * Sampled equal-power envelope points for [rise] over [from]..[to]
     * (mix-clock seconds). The native renderer ramps gains per sample from
     * these knots; sampling the sin/cos law keeps the blend power-flat
     * instead of the old linear plateaus that overshoot then dip.
     */
    fun risePoints(from: Double, to: Double, steps: Int = 16): Pair<DoubleArray, FloatArray> {
        val n = steps.coerceAtLeast(1)
        val times = DoubleArray(n + 1) { from + (to - from) * it / n }
        val values = FloatArray(n + 1) { rise((it.toFloat() / n).coerceIn(0f, 1f)) }
        return times to values
    }

    /** Sampled equal-power [fall] points over [from]..[to]. See [risePoints]. */
    fun fallPoints(from: Double, to: Double, steps: Int = 16): Pair<DoubleArray, FloatArray> {
        val n = steps.coerceAtLeast(1)
        val times = DoubleArray(n + 1) { from + (to - from) * it / n }
        val values = FloatArray(n + 1) { fall((it.toFloat() / n).coerceIn(0f, 1f)) }
        return times to values
    }
}""")

# ---------------------------------------------------------------- TrackAnalysis: quality + trust docs
edit("shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt",
     """    /**
     * Blank means "no status was reported", which counts as ready. Any other
     * value must be [STATUS_READY] for the planner to trust the rest of the
     * fields.
     */
    val status: String = "",""" ,
     """    /**
     * Blank means "no status was reported": the planner treats blank as
     * NOT ready (djmix10) so default fields can never authorize beat
     * math. Any other value must be [STATUS_READY] for the planner to
     * trust the rest of the fields.
     */
    val status: String = "",""")

edit("shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TrackAnalysis.kt",
     """    companion object {
        const val STATUS_READY = "ready"
    }""",
     """    companion object {
        const val STATUS_READY = "ready"
        const val QUALITY_NONE = "none"
        const val QUALITY_HEAD = "head"
        const val QUALITY_FULL = "full"
    }""")

# ---------------------------------------------------------------- TransitionPlanner: strict analysis trust
edit("shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPlanner.kt",
     """private fun analysisReadyForTrack(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean {
    if (analysis.status.isBlank()) return true
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    return analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track.id
}""",
     """private fun analysisReadyForTrack(analysis: TrackAnalysis, track: TransitionTrackInfo?): Boolean {
    // djmix10: blank is NOT ready. A blank status means nothing was ever
    // measured; trusting its default BPM/grid authorizes beat math on
    // zeros. A ready analysis with no tempo is the same.
    if (analysis.status.isBlank()) return false
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    if (analysis.bpm <= 0) return false
    return analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track.id
}""")

# ---------------------------------------------------------------- TransitionPolicy: EOF holdback candidate
# (in policy layer so rankMixOutCandidates surfaces it and tests can pin it)
edit("shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt",
     """    detectBreakOutro(analysis, contentEnd)?.let { candidate ->
        if (candidates.none { abs(it.time - candidate.time) < 0.05 }) candidates += candidate
    }""",
     """    detectBreakOutro(analysis, contentEnd)?.let { candidate ->
        if (candidates.none { abs(it.time - candidate.time) < 0.05 }) candidates += candidate
    }
    // djmix10 EOF holdback: with no interior/break cue the blind content_end
    // is the only anchor and the outgoing blend rides straight into EOF.
    // Hold back up to 8 bars (bounded by the 12s discard cap so the
    // candidate survives ranking) so the overlap ends before the content
    // does. Ranks above content_end (0.75+0.75) but below every real cue
    // (energy_cliff 0.95, break_outro 0.93, outro_start 0.9).
    val holdBar = analysis.beatInterval.takeIf { it > 0 }
        ?: analysis.bpm.orZero().takeIf { it > 0 }?.let { 60.0 / it * 4 }
    if (holdBar != null && holdBar > 0 && contentEnd > holdBar * 8) {
        val held = contentEnd - minOf(8.0 * holdBar, MAX_DISCARDED_MUSIC_SECONDS)
        if (held > 0.3 * contentEnd && candidates.none { abs(it.time - held) < 0.05 }) {
            candidates += MixCandidate(held, 0.80, "eof_holdback")
        }
    }""")

edit("shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/TransitionPolicy.kt",
     """    "outro_start" to 0.9,
    "content_end" to 0.75,
)""",
     """    "outro_start" to 0.9,
    "eof_holdback" to 0.9,
    "content_end" to 0.75,
)""")

print("planner/config phase done")
