package com.music.bitchord.playback.djmix.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the B3.2.1 queue-job budgets in [QueueAnalysisWorker]:
 * one run lifts the next 5 non-blank tracks to FULL analysis and plans +
 * stem-preps the next 2 pairs, in queue order.
 */
class QueueAnalysisWorkerTest {

    @Test
    fun analysisTargetsCapAtFiveNonBlankTracks() {
        val targets = QueueAnalysisWorker.analysisTargets(
            listOf("t1", "", "t2", "   ", "t3", "t4", "t5", "t6", "t7"),
        )
        assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), targets)
        assertEquals(QueueAnalysisWorker.QUEUE_ANALYSIS_BUDGET, 5)
    }

    @Test
    fun analysisTargetsPassThroughShorterQueues() {
        assertEquals(
            listOf("a", "b"),
            QueueAnalysisWorker.analysisTargets(listOf("a", "b")),
        )
        assertTrue(QueueAnalysisWorker.analysisTargets(emptyList()).isEmpty())
        assertTrue(QueueAnalysisWorker.analysisTargets(listOf("", "")).isEmpty())
    }

    @Test
    fun pairTargetsPlanTheFirstTwoAdjacentPairs() {
        val pairs = QueueAnalysisWorker.pairTargets(listOf("t1", "t2", "t3", "t4", "t5"))
        assertEquals(listOf("t1" to "t2", "t3" to "t4"), pairs)
        assertEquals(QueueAnalysisWorker.PAIR_PREP_BUDGET, 2)
    }

    @Test
    fun pairTargetsNeedAtLeastTwoTracks() {
        assertTrue(QueueAnalysisWorker.pairTargets(emptyList()).isEmpty())
        assertTrue(QueueAnalysisWorker.pairTargets(listOf("only")).isEmpty())
        assertEquals(listOf("a" to "b"), QueueAnalysisWorker.pairTargets(listOf("a", "b")))
    }

    @Test
    fun pairTargetsRespectTheSameBudgetAsAnalysisTargets() {
        // The pair budget applies after the 5-track analysis budget, so a
        // 7-track queue yields pairs from the first 5 tracks only.
        val pairs = QueueAnalysisWorker.pairTargets(listOf("1", "2", "3", "4", "5", "6", "7"))
        assertEquals(listOf("1" to "2", "3" to "4"), pairs)
    }

    @Test
    fun pairTargetsSkipBlankEntries() {
        assertEquals(
            listOf("a" to "b"),
            QueueAnalysisWorker.pairTargets(listOf("a", "", "b")),
        )
    }
}
