package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc69 P2 evidence cache and mix dump: every decision carries why it was
 * chosen and what the gates saw (Orchard ORCHARD_MIX_DUMP JSON record).
 */
class TransitionEvidenceTest {

    @Test
    fun evidenceJsonEscapesStrings() {
        val evidence = TransitionEvidenceCache.Evidence(
            outId = "out\"track",
            inId = "in",
            style = "muffle",
            reason = "smart-muffle",
            gates = mapOf("min-body" to "thin quiet"),
            scores = mapOf("flow" to 0.75),
            confidence = 0.72,
            flowScore = 0.75,
        )
        val json = evidence.toJson()
        assertTrue(json.contains("" + Char(92) + Char(34)), json)
        assertTrue(json.contains("muffle"), json)
        assertTrue(json.contains("0.75"), json)
        assertTrue(json.startsWith("{") && json.endsWith("}"), json)
    }

    @Test
    fun cacheKeepsTheCapacityAndNewestFirst() {
        TransitionEvidenceCache.clear()
        for (index in 0..205) {
            TransitionEvidenceCache.record(
                TransitionEvidenceCache.Evidence(outId = "a" + index, inId = "b", style = "x", reason = "r" + index),
            )
        }
        assertEquals(TransitionEvidenceCache.CAPACITY, TransitionEvidenceCache.size())
        val recent = TransitionEvidenceCache.recent(3)
        assertEquals(3, recent.size)
        assertEquals("a205", recent[0].outId)
        assertEquals("a203", recent[2].outId)
        TransitionEvidenceCache.clear()
        assertEquals(0, TransitionEvidenceCache.size())
    }

    @Test
    fun recentJsonRendersNewestFirst() {
        TransitionEvidenceCache.clear()
        TransitionEvidenceCache.record(
            TransitionEvidenceCache.Evidence(outId = "o", inId = "i", style = "blend", reason = "smart"),
        )
        val lines = TransitionEvidenceCache.recentJson(1)
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("blend"), lines[0])
        TransitionEvidenceCache.clear()
    }

    @Test
    fun standardEvidenceCarriesPlanVerdictAndScores() {
        val out = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "o", bpm = 120.0, beatConfidence = 0.9)
        val inn = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "i", bpm = 121.0, beatConfidence = 0.8)
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            reason = "smart-blend",
            policyReasons = listOf("tempo-ok"),
            vocalOverlap = 0.25,
            fadeSeconds = 8.0,
        )
        val evidence = standardEvidence(plan, out, inn)
        assertEquals("o", evidence.outId)
        assertEquals("DJ_BLEND", evidence.style)
        assertEquals("smart-blend", evidence.reason)
        assertEquals(0.25, evidence.scores["vocalOverlap"] ?: -1.0, 1e-9)
        assertEquals(0.8, evidence.confidence, 1e-9)
    }
}
