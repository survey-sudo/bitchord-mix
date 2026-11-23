package com.music.bitchord.playback.smart

/**
 * Per-transition decision evidence: why this mix point was chosen,
 * which gates passed or failed, and the vocal, beat, key, and flow scores
 * behind it. Orchard records every pair planner input and decision to
 * ORCHARD_MIX_DUMP=<dir> as one JSON record per pair for offline replay
 * (orchard-adaptive-mix planner.rs dump: input, live, native). BitChord
 * writes the same shape to the app automix-telemetry.jsonl stream, so a
 * transition can be replayed from the field report without guessing which
 * plan ran. The in-memory cache keeps the most recent decisions for the
 * live controller to dump; nothing here blocks playback or touches PCM.
 *
 * Threading: the planner runs on one player thread, so record is treated as
 * single-writer. Readers get snapshots. The cache is bounded and never
 * throws.
 */
object TransitionEvidenceCache {
    /** How many decisions the cache keeps. */
    const val CAPACITY = 200

    /** One recorded transition decision. */
    data class Evidence(
        val outId: String,
        val inId: String,
        val style: String,
        val reason: String,
        val gates: Map<String, String> = emptyMap(),
        val scores: Map<String, Double> = emptyMap(),
        val confidence: Double = 0.0,
        val flowScore: Double = 0.5,
    ) {
        /**
         * One JSONL record with the Orchard dump input, live, and native
         * sub-objects, flattened for the BitChord telemetry stream. Every
         * string is JSON-escaped; doubles keep full precision.
         */
        fun toJson(): String {
            val out = StringBuilder()
            out.append("{")
            out.append(quoted("outId")).append(":").append(quoted(outId)).append(",")
            out.append(quoted("inId")).append(":").append(quoted(inId)).append(",")
            out.append(quoted("style")).append(":").append(quoted(style)).append(",")
            out.append(quoted("reason")).append(":").append(quoted(reason)).append(",")
            out.append(quoted("confidence")).append(":").append(number(confidence)).append(",")
            out.append(quoted("flowScore")).append(":").append(number(flowScore)).append(",")
            out.append(quoted("gates")).append(":{")
            var first = true
            for ((gate, verdict) in gates) {
                if (!first) out.append(",")
                first = false
                out.append(quoted(gate)).append(":").append(quoted(verdict))
            }
            out.append("},")
            out.append(quoted("scores")).append(":{")
            first = true
            for ((name, value) in scores) {
                if (!first) out.append(",")
                first = false
                out.append(quoted(name)).append(":").append(number(value))
            }
            out.append("}")
            out.append("}")
            return out.toString()
        }

        private fun quoted(text: String): String = "\"" + escape(text) + "\""

        private fun number(value: Double): String = if (value.isFinite()) value.toString() else "null"

        private fun escape(text: String): String {
            // Char codes only: the quote is 34, the backslash is 92.
            val quote = Char(34)
            val backslash = Char(92)
            val out = StringBuilder()
            for (ch in text) {
                when (ch) {
                    quote -> out.append(backslash).append(quote)
                    backslash -> out.append(backslash).append(backslash)
                    Char(10) -> out.append(backslash).append("n")
                    Char(13) -> out.append(backslash).append("r")
                    Char(9) -> out.append(backslash).append("t")
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }
    }

    private val entries: ArrayDeque<Evidence> = ArrayDeque()

    /** Records one decision, dropping the oldest past the capacity. Never throws. */
    fun record(evidence: Evidence) {
        try {
            while (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(evidence)
        } catch (_: Throwable) {
            // Evidence is diagnostics only: a full cache never costs a transition.
        }
    }

    /** Newest-first snapshot, at most limit entries. */
    fun recent(limit: Int = 16): List<Evidence> {
        if (entries.isEmpty() || limit <= 0) return emptyList()
        val want = minOf(entries.size, limit)
        return entries.toList().takeLast(want).reversed()
    }

    /** Entries as JSONL records, newest first. */
    fun recentJson(limit: Int = 16): List<String> = recent(limit).map { it.toJson() }

    /** For tests and playback stops that start a fresh watch. */
    fun clear() {
        entries.clear()
    }

    /** Current size, for tests. */
    fun size(): Int = entries.size
}

/**
 * Builds the evidence record for a standard plan (no alternative family and
 * no decline): the verdict, its policy reasons, and the scalar scores
 * behind it. Alt records are richer (full gate maps) and take precedence in
 * the cache ordering; this keeps every steady transition replayable too.
 */
fun standardEvidence(
    plan: TransitionPlan,
    out: TrackAnalysis,
    inn: TrackAnalysis,
): TransitionEvidenceCache.Evidence {
    val beatConfidence = minOf(out.beatConfidence, inn.beatConfidence)
    val tempoDelta = if (out.bpm > 0.0 && inn.bpm > 0.0) {
        kotlin.math.abs(alignTempoOctave(out.bpm, inn.bpm) / out.bpm - 1.0)
    } else {
        0.0
    }
    val flow = if (out.lowEnergyCurve.isEmpty() || inn.lowEnergyCurve.isEmpty() || plan.fadeSeconds <= 0.0) {
        BeatFlow.FlowResult()
    } else {
        BeatFlow.beatFlow(
            out,
            inn,
            plan.transitionStart,
            plan.incomingCueTime,
            plan.fadeSeconds,
            anchorTime = plan.incomingCueTime,
        )
    }
    return TransitionEvidenceCache.Evidence(
        outId = out.trackId,
        inId = inn.trackId,
        style = plan.transitionStyle.name,
        reason = plan.reason,
        gates = buildMap {
            put("policy", plan.policyReasons.joinToString(";"))
            put("kind", plan.alternativeKind)
            // Octave ambiguity is recorded, never corrected: a raw ratio
            // near a factor 2 may be a half/double-time reading. Landed in
            // mix.dump via the telemetry stream.
            TransitionAlternatives.octaveGateDescription(out, inn)?.let {
                put("octave-relationship", it)
            }
        },
        scores = mapOf(
            "vocalOverlap" to plan.vocalOverlap,
            "fadeSeconds" to plan.fadeSeconds,
            "beatConfidenceMin" to beatConfidence,
            "tempoDelta" to tempoDelta,
            "flow" to flow.score,
        ),
        confidence = beatConfidence,
        flowScore = flow.score,
    )
}
