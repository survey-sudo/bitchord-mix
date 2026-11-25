package com.music.bitchord.playback.djmix.work

import android.os.PowerManager
import androidx.work.Constraints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the B3.2.2 / B15.4 library-job rules in
 * [LibraryAnalysisWorker]. The worker itself needs a real WorkManager
 * `Context`, so these pin the pure decision helpers the worker calls between
 * tracks plus the WorkManager constraint set it is enqueued with.
 *
 * Thermal status constants are Java compile-time constants, inlined here, so
 * no framework class is loaded at runtime.
 */
class LibraryAnalysisWorkerTest {

    // ---- battery < 30% stop ------------------------------------------------

    @Test
    fun batteryBelow30StopsTheRun() {
        for (capacity in 0..29) {
            assertNotNull(
                "battery $capacity% must yield",
                LibraryAnalysisWorker.yieldReason(
                    isStopped = false, batteryCapacityPercent = capacity, thermalStatus = null,
                ),
            )
        }
    }

    @Test
    fun batteryAtOrAbove30Continues() {
        for (capacity in 30..100) {
            assertNull(
                "battery $capacity% must continue",
                LibraryAnalysisWorker.yieldReason(
                    isStopped = false, batteryCapacityPercent = capacity, thermalStatus = null,
                ),
            )
        }
    }

    @Test
    fun unknownBatteryNeverYieldsOnItsOwn() {
        assertNull(
            LibraryAnalysisWorker.yieldReason(
                isStopped = false, batteryCapacityPercent = null, thermalStatus = null,
            ),
        )
    }

    @Test
    fun batteryYieldReasonNamesTheThreshold() {
        val reason = LibraryAnalysisWorker.yieldReason(
            isStopped = false, batteryCapacityPercent = 29, thermalStatus = null,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("30"))
        assertTrue(reason.contains("battery"))
    }

    // ---- thermal >= MODERATE pause ------------------------------------------

    @Test
    fun thermalAtOrAboveModeratePausesTheRun() {
        for (status in listOf(
            PowerManager.THERMAL_STATUS_MODERATE,
            PowerManager.THERMAL_STATUS_SEVERE,
            PowerManager.THERMAL_STATUS_CRITICAL,
            PowerManager.THERMAL_STATUS_EMERGENCY,
            PowerManager.THERMAL_STATUS_SHUTDOWN,
        )) {
            assertNotNull(
                "thermal status $status must yield",
                LibraryAnalysisWorker.yieldReason(
                    isStopped = false, batteryCapacityPercent = null, thermalStatus = status,
                ),
            )
        }
    }

    @Test
    fun thermalBelowModerateContinues() {
        for (status in listOf(
            PowerManager.THERMAL_STATUS_NONE,
            PowerManager.THERMAL_STATUS_LIGHT,
        )) {
            assertNull(
                "thermal status $status must continue",
                LibraryAnalysisWorker.yieldReason(
                    isStopped = false, batteryCapacityPercent = null, thermalStatus = status,
                ),
            )
        }
    }

    @Test
    fun unknownThermalNeverYieldsOnItsOwn() {
        // API < 29 / PowerManager unavailable: the worker keeps going rather
        // than pausing forever on a reading it cannot take.
        assertNull(
            LibraryAnalysisWorker.yieldReason(
                isStopped = false, batteryCapacityPercent = 80, thermalStatus = null,
            ),
        )
    }

    @Test
    fun thermalYieldReasonNamesModerate() {
        val reason = LibraryAnalysisWorker.yieldReason(
            isStopped = false, batteryCapacityPercent = 80,
            thermalStatus = PowerManager.THERMAL_STATUS_MODERATE,
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("MODERATE"))
    }

    // ---- stop, and rule combination ------------------------------------------

    @Test
    fun stoppedWorkerYieldsEvenOnFullBatteryAndCoolDevice() {
        val reason = LibraryAnalysisWorker.yieldReason(
            isStopped = true, batteryCapacityPercent = 100,
            thermalStatus = PowerManager.THERMAL_STATUS_NONE,
        )
        assertNotNull(reason)
        assertEquals("stopped", reason)
    }

    @Test
    fun lowBatteryWinsOverThermalOnlyWhenCheckedFirst() {
        // Battery is checked before thermal; both present must still yield.
        assertNotNull(
            LibraryAnalysisWorker.yieldReason(
                isStopped = false, batteryCapacityPercent = 10,
                thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
            ),
        )
        // Healthy battery + moderate thermal still yields (B15.4 pause rule).
        assertNotNull(
            LibraryAnalysisWorker.yieldReason(
                isStopped = false, batteryCapacityPercent = 90,
                thermalStatus = PowerManager.THERMAL_STATUS_MODERATE,
            ),
        )
    }

    // ---- WorkManager constraint checks (B3.2.2) --------------------------------

    @Test
    fun constraintsRequireChargingIdleAndBatteryNotLow() {
        val constraints: Constraints = LibraryAnalysisWorker.constraints()
        assertTrue("library job must require charging", constraints.requiresCharging())
        assertTrue("library job must require device idle", constraints.requiresDeviceIdle())
        assertTrue("library job must require battery not low", constraints.requiresBatteryNotLow())
    }

    @Test
    fun thresholdAndScheduleConstantsArePinned() {
        assertEquals(30, LibraryAnalysisWorker.MIN_BATTERY_PERCENT)
        assertEquals(6L, LibraryAnalysisWorker.PERIODIC_INTERVAL_HOURS)
        assertEquals("djmix_library_analysis_periodic", LibraryAnalysisWorker.UNIQUE_PERIODIC_WORK_NAME)
        assertEquals("djmix_library_analysis_once", LibraryAnalysisWorker.UNIQUE_ONCE_WORK_NAME)
    }

    // ---- newest-first ordering (liked / downloads / recent) ---------------------

    @Test
    fun mergeKeepsProviderOrderFirstAndDedupes() {
        val merged = LibraryAnalysisWorker.mergeNewestFirst(
            providerIds = listOf("recent1", "liked1", "recent2"),
            likedIds = listOf("liked1", "liked2"),
            downloadIdsNewestFirst = listOf("recent2", "dl1"),
        )
        // First occurrence wins: provider (recent, liked, downloads merged
        // newest-first) is the authority, later sources only append unknowns.
        assertEquals(listOf("recent1", "liked1", "recent2", "liked2", "dl1"), merged)
    }

    @Test
    fun mergeFiltersBlanksAndKeepsInsertionOrder() {
        val merged = LibraryAnalysisWorker.mergeNewestFirst(
            providerIds = listOf("", "a", " ", "b"),
            likedIds = listOf("b", ""),
            downloadIdsNewestFirst = listOf("", "c"),
        )
        assertEquals(listOf("a", "b", "c"), merged)
    }

    @Test
    fun mergeOfEmptySourcesIsEmpty() {
        assertTrue(
            LibraryAnalysisWorker.mergeNewestFirst(
                providerIds = emptyList(), likedIds = emptyList(), downloadIdsNewestFirst = emptyList(),
            ).isEmpty(),
        )
    }
}
