package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.DjEffect
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionEvidenceCache
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.TransitionFilterProcessor.Companion.OFF_HZ
import com.music.bitchord.playback.TransitionFilterProcessor.Companion.OPEN_HZ
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Processor/controller diagnostic, NOT an Android sink or complete playback-engine render.
 * No source normalization, LoudnessProcessor smoothing, limiter, buffering, or native Echo.
 * Synthetic inputs are mechanism probes, not songs, voices, or a reported bad pair.
 *
 * Real start/tick -> planner -> prepare -> startFade -> incremental FADING -> one role swap
 * -> finish -> B/C planning. Controller fields are only inspected, never seeded/reset.
 * PCM uses zero sink lead: filter.process(AudioBlock), callback trim, then player.volume.
 * Unequal nonzero leads have a separate command/routing test; future targets are not
 * represented as already-heard PCM. Both physical processors persist across handoff;
 * their only flush is silent initialization, before the first source sample.
 *
 * Optional artifacts: AUTOMIX_OVERLAP_OUTPUT_DIR=/workspace/diagnostics/automix-overlap
 * and AUTOMIX_OVERLAP_LABEL=baseline|candidate (inherited by Gradle's Test worker).
 * Each probe writes out/in/mix IEEE Float32 stereo WAVs and a block-level CSV, with
 * command cutoffs distinguished from the processor's clamped targets/current glide.
 * Compare the compatible phase-control WAV hashes across the two actual sources.
 * No energy threshold here purports to establish audibility or musical suitability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeclineOverlapRenderTest {
    @Test fun renderFourSecondDeclineMultitoneAndTransients() = runProbe(Probe.MULTITONE)

    @Test fun renderSynthetic120Over87PercussiveCollision() = runProbe(Probe.PERCUSSION)

    @Test fun renderCompatibleSamePhaseControl() = runProbe(Probe.PHASE_CONTROL)

    @Test fun unequalPhysicalSinkLeadsStayWithTheirDeckAcrossOneHandoff() =
        runProbe(Probe.MULTITONE, routingOnly = true)

    @Test fun delayedIncomingReadinessKeepsSoloOutgoingOpenAndStartsFadeWithoutReopen() {
        for ((late, leads) in listOf(
            true to (0L to 0L),
            true to (2_500L to 2_500L),
            false to (2_500L to 2_500L),
        )) withRig(compatible = false, leads = leads) { r ->
            r.second.delayPrepareCompletion = late
            val plan = r.startAndPlan()
            assertEquals(FilterTransitionVariant.DECLINE, plan.filterVariant)
            assertEquals(TransitionStyle.DJ_FILTER, plan.transitionStyle)
            assertEquals(4_000L, plan.fadeMs)
            val delayMs = if (late) 2_000L else 0L
            val start = (plan.transitionStart * 1_000).roundToLong()
            val preRollMs = 3_000L
            r.first.position = start - preRollMs
            r.tick()
            assertEquals("ARMING", r.phase)
            assertEquals(listOf("B"), r.armedItems)
            assertEquals(1, r.second.prepares)

            val outBlock = AudioBlock(CHANNELS, BLOCK_FRAMES)
            val inBlock = AudioBlock(CHANNELS, BLOCK_FRAMES)
            var sawStart = false
            var sawReadyPreFilter = false
            var fadingBlocks = 0
            var prevFadingLow = Float.MAX_VALUE

            for (t in -preRollMs until 7_000L step BLOCK_MS) {
                if (late && t == delayMs) {
                    r.second.completePrepare()
                }
                r.tick()
                if (late && t < delayMs) {
                    assertEquals("ARMING at t=$t (leads=$leads)", "ARMING", r.phase)
                    assertEquals(Player.STATE_BUFFERING, r.second.player.playbackState)
                    assertFalse(r.second.playWhenReady)
                    assertEquals(0f, r.second.player.volume, 0f)
                    assertEquals(1f, r.first.player.volume, 0f)
                    assertTrue(r.handoffs.isEmpty())
                    assertEquals("solo A must stay LP-open while B buffers at t=$t", OPEN_HZ, r.first.lowCommand, 0f)
                    assertEquals("solo A must stay HP-off while B buffers at t=$t", OFF_HZ, r.first.highCommand, 0f)
                    assertEquals(OPEN_HZ, r.first.filterValue("currentLowPassHz"), 0f)
                    assertEquals(OFF_HZ, r.first.filterValue("currentHighPassHz"), 0f)
                    assertEquals(0f, r.first.midDbCommand, 0f)
                }
                if (!late && t == -1_000L) {
                    assertEquals("ARMING", r.phase)
                    assertTrue("READY incoming with 2.5s lead must preserve pre-filter at t=-1000", r.first.lowCommand < OPEN_HZ)
                    sawReadyPreFilter = true
                }
                if (r.phase == "FADING") {
                    if (!sawStart) {
                        assertEquals(delayMs, t)
                        assertEquals(start + delayMs, r.get("fadeOutOriginMs"))
                        assertEquals(4_000L, r.get("fadeOutBudgetMs"))
                        assertEquals(Player.STATE_READY, r.second.player.playbackState)
                        assertTrue(r.second.playWhenReady)
                        if (!late && leads.first > 0L) {
                            // Continuity from on-time READY pre-filter into startFade.
                            assertTrue(r.first.lowCommand <= prevFadingLow + 1f)
                        }
                        if (late && leads.first == 0L) {
                            assertTrue("zero-lead late start must not retain 800Hz solo muffle (${r.first.lowCommand})", r.first.lowCommand >= 6_999f)
                        }
                        sawStart = true
                    } else {
                        assertTrue(
                            "outgoing LP must not reopen upward during FADING (t=$t prev=$prevFadingLow cur=${r.first.lowCommand}, leads=$leads)",
                            r.first.lowCommand <= prevFadingLow + 1f,
                        )
                    }
                    prevFadingLow = r.first.lowCommand
                    fadingBlocks++
                } else if (!late && r.phase == "ARMING") {
                    assertTrue("on-time READY pre-filter must stay non-increasing ($prevFadingLow -> ${r.first.lowCommand})", r.first.lowCommand <= prevFadingLow + 1f)
                    prevFadingLow = r.first.lowCommand
                }
                for ((side, deck) in listOf(r.first, r.second).withIndex()) {
                    val block = if (side == 0) outBlock else inBlock
                    block.reset(BLOCK_FRAMES)
                    val sounding = deck.playWhenReady && deck.ready && deck.items.isNotEmpty()
                    for (frame in 0 until BLOCK_FRAMES) for (channel in 0 until CHANNELS) {
                        val seconds = deck.position / 1_000.0 + frame.toDouble() / RATE
                        block.samples[frame * CHANNELS + channel] = if (sounding) {
                            (0.12 * sin(2 * PI * (if (side == 0) 173 else 227) * seconds + channel * 0.19) +
                                0.12 * sin(2 * PI * (if (side == 0) 5_000 else 6_197) * seconds + side + channel * 0.19)).toFloat()
                        } else 0f
                    }
                    deck.filter.process(block)
                }
                assertTrue(r.handoffs.size <= 1)
                r.advanceBlock()
            }

            assertTrue(sawStart)
            if (!late) assertTrue(sawReadyPreFilter)
            assertTrue(fadingBlocks >= 190)
            assertEquals("IDLE", r.phase)
            assertEquals(1, r.settled)
            assertEquals(listOf("A->B"), r.handoffs)
            assertSame(r.second, r.activeDeck)
            assertSame(r.first, r.spareDeck)
            assertEquals(1, r.first.stops)
            assertEquals(1, r.first.clears)
            assertEquals(0, r.second.stops)
            assertEquals(0, r.second.clears)
            assertEquals(0, r.second.seeks)
            assertTrue(r.first.items.isEmpty())
            assertEquals(listOf("A", "B", "C"), r.second.items.map { it.mediaId })
            assertEquals((plan.incomingCueTime * 1_000).roundToLong() + 7_000L - delayMs, r.second.position)
            assertEquals(false, r.get("handedOff"))
            assertEquals(0, r.get("queuedItemCount"))

            r.second.exactClock = false
            r.second.position = maxOf(r.second.position, 36_000L)
            r.tick()
            assertEquals("IDLE", r.phase)
            assertNotNull(r.controller.plannedTransitionFor("B"))
            val evidence = TransitionEvidenceCache.recent(1).single()
            assertEquals("B", evidence.outId)
            assertEquals("C", evidence.inId)
            r.assertTicker()
        }
    }

    @Test fun readyThenRebufferRestoresSoloOutgoingOpenBeforeFade() {
        withRig(compatible = false, leads = 2_500L to 2_500L) { r ->
            val plan = r.startAndPlan()
            val start = (plan.transitionStart * 1_000).roundToLong()
            val preRollMs = 3_000L
            r.first.position = start - preRollMs
            r.tick()
            assertEquals("ARMING", r.phase)

            val outBlock = AudioBlock(CHANNELS, BLOCK_FRAMES)
            var sawPreFilter = false
            var sawRecoveryAfterRebuffer = false
            var sawStart = false
            for (t in -preRollMs until 7_000L step BLOCK_MS) {
                if (t == -500L) r.second.rebuffer()
                if (t == 2_000L) r.second.completePrepare()
                r.tick()
                outBlock.reset(BLOCK_FRAMES)
                r.first.filter.process(outBlock)
                if (t == -1_000L) {
                    assertEquals("ARMING", r.phase)
                    assertTrue("READY incoming with 2.5s lead should pre-filter A", r.first.lowCommand < OPEN_HZ)
                    sawPreFilter = true
                }
                if (t in -500L until 2_000L) {
                    assertEquals("ARMING", r.phase)
                    assertEquals("rebuffer must restore solo A LP open at t=$t", OPEN_HZ, r.first.lowCommand, 0f)
                    assertEquals("rebuffer must restore solo A HP off at t=$t", OFF_HZ, r.first.highCommand, 0f)
                    assertEquals(0f, r.first.midDbCommand, 0f)
                    if (t == 1_500L) {
                        assertTrue(
                            "filter currentLowPassHz must recover to open after rebuffer, got ${r.first.filterValue("currentLowPassHz")}",
                            r.first.filterValue("currentLowPassHz") >= 19_000f,
                        )
                        sawRecoveryAfterRebuffer = true
                    }
                }
                if (r.phase == "FADING" && !sawStart) {
                    assertEquals(2_000L, t)
                    assertEquals(start + 2_000L, r.get("fadeOutOriginMs"))
                    sawStart = true
                }
                r.advanceBlock()
            }
            assertTrue(sawPreFilter)
            assertTrue(sawRecoveryAfterRebuffer)
            assertTrue(sawStart)
            assertEquals("IDLE", r.phase)
            assertEquals(listOf("A->B"), r.handoffs)
            r.second.exactClock = false
            r.second.position = maxOf(r.second.position, 36_000L)
            r.tick()
            assertNotNull(r.controller.plannedTransitionFor("B"))
            r.assertTicker()
        }
    }

    @Test fun disablingSmartFadeAndPausingDuringArmingCancelCleanlyWithoutFreezingOrMuffling() {
        // Case A: disabling smartFade while in ARMING with an already pre-filtered solo A
        withRig(compatible = false, leads = 2_500L to 2_500L) { r ->
            val plan = r.startAndPlan()
            val start = (plan.transitionStart * 1_000).roundToLong()
            r.first.position = start - 3_000L
            r.tick()
            for (t in -3_000L until -980L step BLOCK_MS) {
                r.tick()
                r.advanceBlock()
            }
            assertEquals("ARMING", r.phase)
            assertTrue("precondition: solo A was pre-filtered while READY", r.first.lowCommand < OPEN_HZ)
            val opensBefore = r.globalOpens
            val posBefore = r.first.position

            AppSettings.smartFadeEnabled.value = false
            r.tick()

            assertEquals("IDLE", r.phase)
            assertEquals(1f, r.first.player.volume, 0f)
            assertTrue(r.first.playWhenReady)
            assertEquals(posBefore, r.first.position)
            assertEquals(0, r.first.stops)
            assertEquals(0, r.first.clears)
            assertEquals(0, r.first.seeks)
            assertEquals(listOf("A", "B", "C"), r.first.items.map { it.mediaId })
            assertTrue(r.second.stops >= 1)
            assertTrue(r.second.items.isEmpty())
            assertTrue(r.handoffs.isEmpty())
            assertEquals(OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)
            assertEquals(0f, r.first.midDbCommand, 0f)
            assertEquals("Seam A: must not call global filters.open() while solo A is audible", opensBefore, r.globalOpens)

            AppSettings.smartFadeEnabled.value = true
            r.tick()
            assertEquals(start, r.controller.plannedTransitionFor("A"))
            r.assertTicker()
        }

        // Case B: pausing outgoing while in ARMING cancels cleanly without auto-resuming
        withRig(compatible = false, leads = 2_500L to 2_500L) { r ->
            val plan = r.startAndPlan()
            val start = (plan.transitionStart * 1_000).roundToLong()
            r.first.position = start - 3_000L
            r.tick()
            for (t in -3_000L until -980L step BLOCK_MS) {
                r.tick()
                r.advanceBlock()
            }
            assertEquals("ARMING", r.phase)
            assertTrue(r.first.lowCommand < OPEN_HZ)
            val opensBefore = r.globalOpens

            r.first.player.pause()
            r.tick()

            assertEquals("IDLE", r.phase)
            assertFalse("paused outgoing must not auto-resume", r.first.playWhenReady)
            assertEquals(1f, r.first.player.volume, 0f)
            assertEquals(OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)
            assertEquals(0f, r.first.midDbCommand, 0f)
            assertEquals("Seam A: must not call global filters.open() while solo A is audible", opensBefore, r.globalOpens)
            assertEquals(listOf("A", "B", "C"), r.first.items.map { it.mediaId })
            assertTrue(r.handoffs.isEmpty())

            repeat(3) {
                r.scheduler.advanceTimeBy(500L)
                r.scheduler.runCurrent()
                r.tick()
            }
            assertFalse("sticky pause must persist across subsequent ticks", r.first.playWhenReady)
            assertEquals("IDLE", r.phase)

            r.first.player.play()
            r.tick()
            assertEquals(start, r.controller.plannedTransitionFor("A"))
            r.assertTicker()
        }
    }

    @Test fun armingTimeoutWithGraceReleasesToIdleAndKeepsSoloOpen() {
        withRig(compatible = false, leads = 2_500L to 2_500L, captureTelemetry = true) { r ->
            r.second.delayPrepareCompletion = true
            val plan = r.startAndPlan()
            val start = (plan.transitionStart * 1_000).roundToLong()
            r.first.position = start - 1_000L
            r.wallMs = 1_000L
            r.tick()
            assertEquals("ARMING", r.phase)
            val initialDeadline = r.get("armDeadline") as Long
            assertTrue(initialDeadline > r.wallMs)
            val opensBefore = r.globalOpens

            // At exact initial deadline: not yet expired; solo A remains open.
            r.wallMs = initialDeadline
            r.tick()
            assertEquals("ARMING", r.phase)
            assertTrue(r.telemetry("mix.arm.grace").isEmpty())
            assertEquals(OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)

            // Past initial deadline: single grace extension, still ARMING, solo A open.
            r.wallMs = initialDeadline + 1L
            r.tick()
            assertEquals("ARMING", r.phase)
            assertEquals(1, r.telemetry("mix.arm.grace").size)
            assertTrue(r.telemetry("mix.arm.death").isEmpty())
            val graceDeadline = r.get("armDeadline") as Long
            assertTrue(graceDeadline > initialDeadline + 1L)
            assertEquals(OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)

            // Past grace deadline: terminal arm-expired releases to IDLE cleanly.
            r.wallMs = graceDeadline + 1L
            r.first.position = start + 1_000L
            r.tick()
            assertEquals("IDLE", r.phase)
            assertEquals(1f, r.first.player.volume, 0f)
            assertTrue(r.first.playWhenReady)
            assertEquals(0, r.first.stops)
            assertEquals(0, r.first.clears)
            assertEquals(0, r.first.seeks)
            assertEquals(listOf("A", "B", "C"), r.first.items.map { it.mediaId })
            assertTrue(r.second.stops >= 1)
            assertTrue(r.second.items.isEmpty())
            assertTrue(r.handoffs.isEmpty())
            assertEquals(OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)
            assertEquals(0f, r.first.midDbCommand, 0f)
            assertEquals("Seam A: audible solo survivor must not trigger global open()", opensBefore, r.globalOpens)
            assertEquals(1, r.telemetry("mix.arm.grace").size)
            assertEquals(1, r.telemetry("mix.arm.expired").size)
            val death = r.telemetry("mix.arm.death").single()
            assertTrue(death.contains("\"reason\":\"arm-expired\""))
            val complete = r.telemetry("mix.complete").single()
            assertTrue(complete.contains("\"handoff\":false"))
            assertTrue(complete.contains("\"reason\":\"arm-expired\""))
            r.assertTicker()
        }

        // Subcase: B was READY (solo A pre-filtered), then rebuffers on the exact grace tick.
        withRig(compatible = false, leads = 2_500L to 2_500L, captureTelemetry = true) { r ->
            val plan = r.startAndPlan()
            val start = (plan.transitionStart * 1_000).roundToLong()
            r.first.position = start - 3_000L
            r.wallMs = 1_000L
            r.tick()
            for (t in -3_000L until -980L step BLOCK_MS) {
                r.tick()
                r.advanceBlock()
            }
            assertEquals("ARMING", r.phase)
            assertTrue("precondition: solo A pre-filtered before grace tick", r.first.lowCommand < OPEN_HZ)
            val opensBefore = r.globalOpens
            val graceAt = (r.get("armDeadline") as Long) + 1L

            r.second.rebuffer()
            r.wallMs = graceAt
            r.tick()
            assertEquals("ARMING", r.phase)
            assertEquals("grace tick after rebuffer must immediately restore solo A LP open", OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)
            assertEquals(1, r.telemetry("mix.arm.grace").size)

            val expireAt = (r.get("armDeadline") as Long) + 1L
            r.wallMs = expireAt
            r.tick()
            assertEquals("IDLE", r.phase)
            assertEquals(OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals(OFF_HZ, r.first.highCommand, 0f)
            assertEquals(0f, r.first.midDbCommand, 0f)
            assertEquals("Seam A: audible solo survivor must not trigger global open()", opensBefore, r.globalOpens)
            assertEquals(listOf("A", "B", "C"), r.first.items.map { it.mediaId })
            r.assertTicker()
        }
    }

    @Test fun seekDuringPreFilteredArmingRestoresSoloOutgoingWithoutGlobalOpen() {
        withRig(compatible = false, leads = 2_500L to 2_500L, captureTelemetry = true) { r ->
            val plan = r.startAndPlan()
            val start = (plan.transitionStart * 1_000).roundToLong()
            r.first.position = start - 3_000L
            r.tick()
            for (t in -3_000L until -980L step BLOCK_MS) {
                r.tick()
                r.advanceBlock()
            }
            assertEquals("ARMING", r.phase)
            assertTrue("precondition: solo A pre-filtered before scrub", r.first.lowCommand < OPEN_HZ)
            val opensBefore = r.globalOpens

            // Real listener seek during pre-filtered ARMING triggers SeekAction.Action.CANCEL.
            r.first.player.seekTo(10_000L)

            assertEquals("IDLE", r.phase)
            assertEquals(1f, r.first.player.volume, 0f)
            assertTrue(r.first.playWhenReady)
            assertEquals(10_000L, r.first.position)
            assertEquals(listOf("A", "B", "C"), r.first.items.map { it.mediaId })
            assertTrue(r.second.stops >= 1)
            assertTrue(r.second.items.isEmpty())
            assertTrue(r.handoffs.isEmpty())
            assertEquals("seek cancel must restore solo A LP open", OPEN_HZ, r.first.lowCommand, 0f)
            assertEquals("seek cancel must restore solo A HP off", OFF_HZ, r.first.highCommand, 0f)
            assertEquals(0f, r.first.midDbCommand, 0f)
            assertEquals("Seam A: audible solo survivor must not trigger global open()", opensBefore, r.globalOpens)

            val block = AudioBlock(CHANNELS, BLOCK_FRAMES)
            repeat(10) {
                block.reset(BLOCK_FRAMES)
                r.first.filter.process(block)
            }
            assertTrue(
                "filter processor must glide back toward OPEN_HZ, got ${r.first.filterValue("currentLowPassHz")}",
                r.first.filterValue("currentLowPassHz") >= 19_000f,
            )

            // Advance past SCRUB_SETTLE_MS on the shared injected wall clock and verify clean replan.
            r.wallMs += 400L
            r.tick()
            assertEquals("IDLE", r.phase)
            assertEquals(start, r.controller.plannedTransitionFor("A"))
            assertFalse(AppSettings.automixStatus.value?.headline?.contains("Replanning") == true)
            r.assertTicker()
        }
    }

    private enum class Probe(val fileName: String) {
        MULTITONE("decline-multitone"), PERCUSSION("decline-percussion-120-87"),
        PHASE_CONTROL("compatible-phase-control"),
    }

    private fun runProbe(probe: Probe, routingOnly: Boolean = false) = withRig(
        compatible = probe == Probe.PHASE_CONTROL,
        leads = if (routingOnly) 80L to 160L else 0L to 0L,
    ) { r ->
        val plan = r.startAndPlan()
        if (probe == Probe.PHASE_CONTROL) {
            assertFalse("control must not use DECLINE", plan.filterVariant == FilterTransitionVariant.DECLINE)
            assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle)
        } else {
            assertEquals("fixture must exercise the actual classic DECLINE plan", TransitionStyle.DJ_FILTER, plan.transitionStyle)
            assertEquals(FilterTransitionVariant.DECLINE, plan.filterVariant)
            assertEquals(4_000L, plan.fadeMs)
            assertEquals(196.0, plan.transitionEnd, 1e-9)
            assertEquals(1.0, plan.incomingPlaybackRate, 0.0)
            assertEquals(1.0, plan.outgoingPlaybackRate, 0.0)
        }
        val start = (plan.transitionStart * 1_000).roundToLong()
        r.first.position = start - PAD_MS
        r.tick()
        assertEquals("ARMING", r.phase)
        assertEquals(listOf("B"), r.armedItems)
        assertEquals(1, r.second.prepares)
        assertEquals("B", r.second.player.currentMediaItem?.mediaId)
        assertEquals(3, r.get("queuedItemCount"))
        val inFlight = r.get("planInFlight") as TransitionPlan
        assertEquals(plan.transitionStart, inFlight.transitionStart, 0.0)
        assertEquals(plan.filterVariant, inFlight.filterVariant)

        val name = if (routingOnly) "unequal-leads-routing-only" else probe.fileName
        Artifacts(name, !routingOnly, plan).use { artifacts ->
            val outBlock = AudioBlock(CHANNELS, BLOCK_FRAMES)
            val inBlock = AudioBlock(CHANNELS, BLOCK_FRAMES)
            val mixed = FloatArray(BLOCK_FRAMES * CHANNELS)
            val totals = DoubleArray(3)
            var changedByFilter = false
            var fadingBlocks = 0
            var observedHandoff = false
            var expectedSurvivorPosition: Long? = null
            val blocks = ((PAD_MS * 2 + plan.fadeMs) / BLOCK_MS).toInt()
            for (blockIndex in 0 until blocks) {
                val t = blockIndex * BLOCK_MS - PAD_MS
                r.tick()
                if (r.phase == "FADING") {
                    // At the exact start, execute the first production FADING tick too.
                    if (t == 0L) r.tick()
                    fadingBlocks++
                    assertTrue(r.first.playWhenReady && r.second.playWhenReady)
                }
                if (r.handoffs.isNotEmpty()) {
                    observedHandoff = true
                    assertSame(r.second, r.activeDeck)
                    assertSame(r.first, r.spareDeck)
                    assertEquals(listOf("A->B"), r.handoffs)
                }
                if (r.settled == 1) {
                    expectedSurvivorPosition?.let { assertEquals(it, r.second.position) }
                    assertEquals("IDLE", r.phase)
                    assertTrue(r.first.items.isEmpty())
                    assertEquals(1, r.first.stops)
                    assertEquals(1, r.first.clears)
                    assertEquals(0, r.second.stops)
                    assertEquals(0, r.second.clears)
                    assertEquals(0, r.second.seeks)
                    expectedSurvivorPosition = r.second.position + BLOCK_MS
                }
                if (!routingOnly) {
                    fun process(deck: Deck, into: AudioBlock, side: Int) {
                        into.reset(BLOCK_FRAMES)
                        val sounding = deck.playWhenReady && deck.ready && deck.items.isNotEmpty()
                        for (frame in 0 until BLOCK_FRAMES) for (channel in 0 until CHANNELS) {
                            val seconds = deck.position / 1_000.0 + frame.toDouble() / RATE
                            val wallSeconds = (t + PAD_MS) / 1_000.0 + frame.toDouble() / RATE
                            into.samples[frame * CHANNELS + channel] = if (sounding) {
                                source(probe, side, seconds, wallSeconds, channel)
                            } else 0f
                        }
                        val before = into.samples.copyOf()
                        deck.filter.process(into)
                        if (sounding && !before.contentEquals(into.samples)) changedByFilter = true
                        // retire() resets volume to 1 for future reuse, but a stopped sink
                        // emits nothing; do not turn its remaining integrator state into PCM.
                        val gain = if (sounding) deck.player.volume * r.headroom else 0f
                        for (sample in into.samples.indices) {
                            into.samples[sample] *= gain
                            assertTrue("finite PCM at t=$t side=$side", into.samples[sample].isFinite())
                            if (r.phase == "FADING") totals[side] += into.samples[sample].toDouble() * into.samples[sample]
                        }
                    }
                    process(r.first, outBlock, 0)
                    process(r.second, inBlock, 1)
                    for (i in mixed.indices) {
                        mixed[i] = outBlock.samples[i] + inBlock.samples[i]
                        assertTrue(mixed[i].isFinite())
                        if (r.phase == "FADING") totals[2] += mixed[i].toDouble() * mixed[i]
                    }
                    artifacts.pcm(outBlock.samples, inBlock.samples, mixed)
                }
                artifacts.row(t, r)
                assertTrue("actual callback trim must remain valid", r.headroom > 0f && r.headroom <= 1f)
                assertTrue("no duplicate handoff", r.handoffs.size <= 1)
                r.advanceBlock()
            }
            assertTrue("must traverse incremental FADING", fadingBlocks >= 100)
            assertTrue(observedHandoff)
            assertEquals(listOf("A->B"), r.handoffs)
            assertEquals(1, r.settled)
            assertEquals("IDLE", r.phase)
            assertEquals(false, r.get("handedOff"))
            assertEquals(0, r.get("queuedItemCount"))
            if (routingOnly) r.assertUnequalLeadRouting() else {
                assertTrue("real DSP must process the probe", changedByFilter)
                totals.forEach { assertTrue("each rendered contribution must carry energy", it > 0.0 && it.isFinite()) }
                artifacts.report(totals)
            }
            // Return to the fake's ordinary playing getter only after completion so the
            // same controller/ticker can actually plan B -> C. No controller fields reset.
            r.second.exactClock = false
            r.second.position = maxOf(r.second.position, 36_000L)
            r.tick()
            assertEquals("IDLE", r.phase)
            assertNotNull(r.controller.plannedTransitionFor("B"))
            val evidence = TransitionEvidenceCache.recent(1).single()
            assertEquals("B", evidence.outId)
            assertEquals("C", evidence.inId)
            assertEquals(TrackAnalysisState.ANALYSED, AppSettings.smartAnalysis.value.next)
            assertEquals(listOf("A->B"), r.handoffs)
            assertEquals(listOf("B"), r.armedItems)
            r.assertTicker()
        }
    }

    private class Rig(
        val scheduler: TestCoroutineScheduler,
        compatible: Boolean,
        leads: Pair<Long, Long>,
        captureTelemetry: Boolean = false,
    ) : AutoCloseable {
        private val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler) +
            CoroutineExceptionHandler { _, failure -> failures.add(failure) })
        private val telemetryField = if (captureTelemetry) {
            AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        } else null
        private val savedTelemetryDir = telemetryField?.get(AutomixTelemetry)
        private val telemetryDir = if (captureTelemetry) Files.createTempDirectory("decline-overlap-telemetry").toFile() else null
        init {
            telemetryField?.set(AutomixTelemetry, telemetryDir)
        }
        var wallMs = 1_000L
        private val items = listOf("A", "B", "C").map { MediaItem.Builder().setMediaId(it).build() }
        private val analyses = items.associate { it.mediaId to analysis(it.mediaId, compatible) }
        val first = Deck("first", items, 10_000L, true, leads.first)
        val second = Deck("second", emptyList(), leadMs = leads.second).also { deck ->
            // startFade's actual playWhenReady edge freezes extrapolation BEFORE any
            // scheduled or manual FADING read. Planning/ARMING see normal isPlaying.
            // These proxies then advance media reports while isPlaying=false, keeping
            // playWhenReady=true/STATE_READY: a deterministic fake, not Media3's contract.
            deck.onStart = { first.exactClock = true; deck.exactClock = true }
        }
        var activeDeck = first
            private set
        var spareDeck = second
            private set
        val handoffs = mutableListOf<String>()
        val armedItems = mutableListOf<String>()
        private val leadReads = mutableListOf<LeadRead>()
        private val cutoffWrites = mutableListOf<Pair<Boolean, Deck>>()
        var headroom = 1f
            private set
        var settled = 0
            private set
        var globalOpens = 0
            private set
        private var ticker: Job? = null
        private val tickMethod = CrossfadeController::class.java.getDeclaredMethod("tick").apply { isAccessible = true }
        val phase get() = get("phase").toString()
        private fun cutoffs(deck: Deck, low: Float, high: Float) {
            cutoffWrites.add((get("handedOff") as Boolean) to deck)
            deck.lowCommand = low
            deck.highCommand = high
            deck.filter.setCutoffs(low, high)
        }
        private fun lead(deck: Deck, activeRole: Boolean): Long {
            val value = deck.filter.leadUs / 1_000
            leadReads.add(LeadRead(get("handedOff") as Boolean, activeRole, deck, value))
            return value
        }
        // PlaybackService wiring: incoming means CURRENT session role, not a captured B.
        private val filters = object : TransitionFilters {
            override fun incoming(lowPassHz: Float, highPassHz: Float) = cutoffs(activeDeck, lowPassHz, highPassHz)
            override fun outgoing(lowPassHz: Float, highPassHz: Float) = cutoffs(spareDeck, lowPassHz, highPassHz)
            override fun incomingLeadMs() = lead(activeDeck, true)
            override fun outgoingLeadMs() = lead(spareDeck, false)
            override fun incomingMidDb(gainDb: Float) {
                activeDeck.midDbCommand = gainDb
                activeDeck.filter.setMidGainDb(gainDb)
            }
            override fun outgoingMidDb(gainDb: Float) {
                spareDeck.midDbCommand = gainDb
                spareDeck.filter.setMidGainDb(gainDb)
            }
            override fun incomingTempo(rate: Float) = activeDeck.filter.setBeatmatchTempo(rate)
            override fun outgoingTempo(rate: Float) = spareDeck.filter.setBeatmatchTempo(rate)
            override fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) = activeDeck.filter.setEcho(delaySeconds, send, dry)
            override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) = spareDeck.filter.setEcho(delaySeconds, send, dry)
            override fun incomingColour(effect: DjEffect, amount: Float, beatSeconds: Float, scoop: Float) =
                activeDeck.filter.setColour(effect, amount, beatSeconds, scoop)
            override fun outgoingColour(effect: DjEffect, amount: Float, beatSeconds: Float, scoop: Float) =
                spareDeck.filter.setColour(effect, amount, beatSeconds, scoop)
            override fun parkEchoes() { first.filter.parkEcho(); second.filter.parkEcho() }
            override fun releaseColour() { first.filter.releaseColour(); second.filter.releaseColour() }
            override fun open() {
                globalOpens++
                super.open()
            }
        }
        val controller = CrossfadeController(
            scope = scope, active = { activeDeck.player }, standby = { spareDeck.player },
            onHandoff = { out, into ->
                assertSame(first.player, out)
                assertSame(second.player, into)
                assertSame(activeDeck.player, out)
                assertSame(spareDeck.player, into)
                handoffs.add("${out.currentMediaItem?.mediaId}->${into.currentMediaItem?.mediaId}")
                val previous = activeDeck
                activeDeck = spareDeck
                spareDeck = previous
            },
            analysisFor = { analyses.getValue(it.mediaId) }, filters = filters,
            onArmIncoming = { item, _ -> armedItems.add(item.mediaId) },
            onBlendHeadroom = { headroom = it }, onSettled = { settled++ },
            nowMs = { wallMs },
            // Deliberately no loop ports or decoded loop regions.
        )

        fun telemetry(event: String): List<String> = telemetryDir
            ?.let { File(it, "automix-telemetry.jsonl") }
            ?.takeIf { it.exists() }
            ?.readLines()
            ?.filter { it.contains("\"ev\":\"$event\"") }
            .orEmpty()

        fun get(name: String): Any? = CrossfadeController::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(controller)

        fun startAndPlan(): TransitionPlan {
            assertFalse("this JVM harness requires the native renderer to be unavailable", LoopMixRenderer.available)
            controller.start()
            ticker = get("tickerJob") as Job
            scheduler.runCurrent()
            tick()
            assertEquals("IDLE", phase)
            assertNotNull(controller.plannedTransitionFor("A"))
            assertEquals(TrackAnalysisState.ANALYSED, AppSettings.smartAnalysis.value.current)
            val plan = get("lastSmartPlan") as TransitionPlan
            assertFalse(plan.blocked)
            assertTrue(plan.fadeMs > 0L)
            return plan
        }

        fun tick() {
            try { tickMethod.invoke(controller) } catch (failure: InvocationTargetException) {
                throw AssertionError("production tick failed", failure.targetException)
            }
            failures.firstOrNull()?.let { throw AssertionError("controller coroutine failed", it) }
            assertTicker()
        }

        fun advanceBlock() {
            for (deck in listOf(first, second)) if (deck.playWhenReady && deck.ready && deck.items.isNotEmpty()) {
                assertEquals("these probes isolate unit-rate playback", 1f, deck.player.playbackParameters.speed, 0f)
                deck.position += BLOCK_MS
            }
            wallMs += BLOCK_MS
            // Keep the original production ticker and scheduled jobs alive. The explicit
            // tick at every PCM block provides 20 ms command sampling; scheduled ticks can
            // also see that exact media report, with no extra PCM and no wall-clock drift.
            scheduler.advanceTimeBy(BLOCK_MS)
            scheduler.runCurrent()
        }

        fun assertTicker() {
            assertSame(ticker, get("tickerJob"))
            assertTrue(ticker?.isActive == true)
        }

        fun assertUnequalLeadRouting() {
            for (after in listOf(false, true)) for (activeRole in listOf(false, true)) {
                val expected = if (after == activeRole) second else first
                val reads = leadReads.filter { it.afterHandoff == after && it.activeRole == activeRole }
                assertTrue("both physical lead getters must run on both sides of handoff", reads.isNotEmpty())
                reads.forEach { read ->
                    assertSame(expected, read.deck)
                    assertEquals(if (expected === first) 80L else 160L, read.value)
                }
                assertTrue("both physical filters must receive commands before and after handoff",
                    cutoffWrites.any { it.first == after && it.second === expected })
            }
            assertEquals(1, first.stops)
            assertEquals(0, second.stops)
            assertEquals(1, first.clears)
            assertEquals(0, second.clears)
            assertEquals(0, second.seeks)
        }

        override fun close() {
            controller.release()
            scope.cancel()
            telemetryField?.set(AutomixTelemetry, savedTelemetryDir)
            telemetryDir?.deleteRecursively()
        }
    }

    private data class LeadRead(val afterHandoff: Boolean, val activeRole: Boolean, val deck: Deck, val value: Long)

    /** Minimal identity/queue/lifecycle proxy adapted from AutomixReplanLifecycleTest. */
    private class Deck(
        val name: String, initialItems: List<MediaItem>, var position: Long = 0L,
        var playWhenReady: Boolean = false, leadMs: Long = 0L,
    ) {
        val filter = TransitionFilterProcessor().apply { configure(RATE, CHANNELS); flush(); leadUs = leadMs * 1_000 }
        var lowCommand = TransitionFilterProcessor.OPEN_HZ
        var highCommand = TransitionFilterProcessor.OFF_HZ
        var midDbCommand = 0f
        val items = initialItems.toMutableList()
        private var index = if (items.isEmpty()) C.INDEX_UNSET else 0
        private var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        val ready get() = state == Player.STATE_READY
        var exactClock = false
        var onStart: (() -> Unit)? = null
        var delayPrepareCompletion = false
        fun completePrepare() {
            check(state == Player.STATE_BUFFERING)
            state = Player.STATE_READY
            listeners.toList().forEach { it.onPlaybackStateChanged(state) }
        }
        fun rebuffer() {
            check(state == Player.STATE_READY)
            state = Player.STATE_BUFFERING
            listeners.toList().forEach { it.onPlaybackStateChanged(state) }
        }
        fun filterValue(name: String): Float = TransitionFilterProcessor::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.getFloat(filter)
        private fun playing(wanted: Boolean) {
            val starting = wanted && !playWhenReady
            playWhenReady = wanted
            if (starting) onStart?.invoke()
        }
        var prepares = 0
        var stops = 0
        var clears = 0
        var seeks = 0
        private var volume = 1f
        private var parameters = PlaybackParameters.DEFAULT
        private var repeat = Player.REPEAT_MODE_OFF
        private var shuffle = false
        private var skipSilence = false
        private var pauseAtEnd = false
        private val listeners = mutableSetOf<Player.Listener>()
        private val timeline = QueueTimeline { items }
        private fun nextIndex() = (index + 1).takeIf { index >= 0 && it in items.indices } ?: C.INDEX_UNSET
        private fun positionInfo() = Player.PositionInfo(
            items.getOrNull(index)?.mediaId, index, items.getOrNull(index),
            items.getOrNull(index)?.mediaId, index, position, position, C.INDEX_UNSET, C.INDEX_UNSET,
        )
        val player: ExoPlayer = Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "$name:${items.getOrNull(index)?.mediaId}"
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> index
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getNextMediaItemIndex" -> nextIndex()
                "hasNextMediaItem" -> nextIndex() != C.INDEX_UNSET
                "getCurrentTimeline" -> timeline
                "getCurrentPosition", "getContentPosition" -> position
                "getDuration" -> if (index in items.indices) DURATION_MS else C.TIME_UNSET
                "getPlaybackState" -> state
                "isPlaying" -> !exactClock && playWhenReady && ready
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { playing(args!![0] as Boolean); null }
                "play" -> { playing(true); null }
                "pause" -> { playing(false); null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getPlaybackParameters" -> parameters
                "setPlaybackSpeed" -> { parameters = PlaybackParameters(args!![0] as Float); null }
                "getRepeatMode" -> repeat
                "setRepeatMode" -> { repeat = args!![0] as Int; null }
                "getShuffleModeEnabled" -> shuffle
                "setShuffleModeEnabled" -> { shuffle = args!![0] as Boolean; null }
                "getSkipSilenceEnabled" -> skipSilence
                "setSkipSilenceEnabled" -> { skipSilence = args!![0] as Boolean; null }
                "getPauseAtEndOfMediaItems" -> pauseAtEnd
                "setPauseAtEndOfMediaItems" -> { pauseAtEnd = args!![0] as Boolean; null }
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "setMediaItems" -> {
                    check(args!!.size == 3)
                    @Suppress("UNCHECKED_CAST") val queue = args[0] as List<MediaItem>
                    items.clear(); items.addAll(queue)
                    index = args[1] as Int; position = args[2] as Long
                    state = Player.STATE_IDLE
                    null
                }
                "prepare" -> { prepares++; state = if (delayPrepareCompletion) Player.STATE_BUFFERING else Player.STATE_READY; null }
                "removeMediaItems" -> { items.subList(args!![0] as Int, args[1] as Int).clear(); null }
                "addMediaItems" -> {
                    @Suppress("UNCHECKED_CAST") val appended = args!![0] as List<MediaItem>
                    items.addAll(appended); null
                }
                "stop" -> { stops++; state = Player.STATE_IDLE; null }
                "clearMediaItems" -> { clears++; items.clear(); index = C.INDEX_UNSET; position = 0L; null }
                "seekTo" -> {
                    val before = positionInfo()
                    if (args!!.size == 2) index = args[0] as Int
                    position = args.last() as Long
                    listeners.toList().forEach { it.onPositionDiscontinuity(before, positionInfo(), Player.DISCONTINUITY_REASON_SEEK) }
                    seeks++; null
                }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as ExoPlayer
    }

    private class QueueTimeline(private val items: () -> List<MediaItem>) : Timeline() {
        override fun getWindowCount() = items().size
        override fun getPeriodCount() = items().size
        override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
            val item = items()[windowIndex]
            window.uid = item.mediaId; window.mediaItem = item
            window.durationUs = DURATION_MS * 1_000; window.defaultPositionUs = 0L
            window.firstPeriodIndex = windowIndex; window.lastPeriodIndex = windowIndex
            window.isSeekable = true
            return window
        }
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
            val uid = items()[periodIndex].mediaId.takeIf { setIds }
            return period.set(uid, uid, periodIndex, DURATION_MS * 1_000, 0L)
        }
        override fun getIndexOfPeriod(uid: Any) = items().indexOfFirst { it.mediaId == uid }
        override fun getUidOfPeriod(periodIndex: Int): Any = items()[periodIndex].mediaId
    }

    private class Artifacts(name: String, pcm: Boolean, plan: TransitionPlan) : AutoCloseable {
        private val digests = List(3) { MessageDigest.getInstance("SHA-256") }
        private val bytes = ByteBuffer.allocate(BLOCK_FRAMES * CHANNELS * 4).order(ByteOrder.LITTLE_ENDIAN)
        private val root = System.getenv("AUTOMIX_OVERLAP_OUTPUT_DIR")?.takeIf { it.isNotBlank() }?.let { raw ->
            val folder = File(raw).canonicalFile
            require(File(raw).isAbsolute && folder.toPath().any { it.toString() == "diagnostics" }) {
                "AUTOMIX_OVERLAP_OUTPUT_DIR must be an absolute directory under diagnostics, outside the repository"
            }
            val repo = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
                .firstOrNull { File(it, ".git").exists() }
            require(repo == null || !folder.toPath().startsWith(repo.toPath())) { "diagnostics must be outside the repository" }
            val label = System.getenv("AUTOMIX_OVERLAP_LABEL") ?: "unlabelled"
            require(label.matches(Regex("[A-Za-z0-9_-]+"))) { "invalid AUTOMIX_OVERLAP_LABEL" }
            File(folder, label).apply { check(isDirectory || mkdirs()) }
        }
        private val probeName = name
        private val csv = root?.let { File(it, "$name.csv").bufferedWriter() }
        private val wavs = if (pcm && root != null) listOf("out", "in", "mix").map { Wav(File(root, "$name-$it.wav")) } else emptyList()
        init {
            csv?.appendLine("t_ms,a_position_ms,b_position_ms,a_gain,b_gain,headroom,phase,handed_off,active_deck,spare_deck,a_lp_command,a_hp_command,b_lp_command,b_hp_command,a_lp_target,a_hp_target,b_lp_target,b_hp_target,a_lp_current,a_hp_current,b_lp_current,b_hp_current,a_lead_ms,b_lead_ms,handoffs,settled")
            if (root != null) {
                // Per-case sidecars let individual method runs rebuild the same manifest.
                val metadata = """{"rate":$RATE,"channels":$CHANNELS,"sampleFormat":"IEEE_FLOAT32_LE","zeroLead":$pcm,"audio":$pcm,"blockFrames":$BLOCK_FRAMES,"fadeStartSec":1.0,"fadeEndSec":${1.0 + plan.fadeMs / 1_000.0},"mediaFadeStartSec":${plan.transitionStart},"mediaFadeEndSec":${plan.transitionEnd},"csvTimeOrigin":"fade-start","filterVariant":"${plan.filterVariant}","style":"${plan.transitionStyle}","synthetic":true}"""
                File(root, "$name.metadata.json").writeText(metadata)
                val cases = (Probe.entries.map { it.fileName } + "unequal-leads-routing-only").mapNotNull { case ->
                    File(root, "$case.metadata.json").takeIf { it.isFile }?.let { "\"$case\":${it.readText()}" }
                }
                File(root, "metadata.json").writeText("{\"schema\":1,\"cases\":{${cases.joinToString(",")}}}\n")
            }
        }
        fun pcm(out: FloatArray, into: FloatArray, mix: FloatArray) {
            listOf(out, into, mix).forEachIndexed { index, samples ->
                bytes.clear()
                samples.forEach { bytes.putFloat(it) }
                digests[index].update(bytes.array())
                wavs.getOrNull(index)?.write(bytes.array())
            }
        }
        fun row(t: Long, r: Rig) {
            if (csv == null) return
            fun value(deck: Deck, name: String) = TransitionFilterProcessor::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.getFloat(deck.filter)
            csv.appendLine(listOf(
                t, r.first.position, r.second.position, r.first.player.volume, r.second.player.volume,
                r.headroom, r.phase, r.get("handedOff"), r.activeDeck.name, r.spareDeck.name,
                r.first.lowCommand, r.first.highCommand, r.second.lowCommand, r.second.highCommand,
                value(r.first, "targetLowPassHz"), value(r.first, "targetHighPassHz"),
                value(r.second, "targetLowPassHz"), value(r.second, "targetHighPassHz"),
                value(r.first, "currentLowPassHz"), value(r.first, "currentHighPassHz"),
                value(r.second, "currentLowPassHz"), value(r.second, "currentHighPassHz"),
                r.first.filter.leadUs / 1_000, r.second.filter.leadUs / 1_000, r.handoffs.size, r.settled,
            ).joinToString(","))
        }
        fun report(energy: DoubleArray) {
            val hashes = digests.map { digest -> digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } }
            println("OVERLAP $probeName synthetic processor render: out/in/mix energy=${energy.toList()} sha256=$hashes output=$root")
        }
        override fun close() { csv?.close(); wavs.forEach { it.close() } }
    }

    /** RIFF IEEE Float32, little-endian, including the non-PCM fact chunk. No clipping. */
    private class Wav(file: File) : AutoCloseable {
        private val output = RandomAccessFile(file, "rw")
        private var dataBytes = 0
        init {
            output.setLength(0)
            val header = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(0).put("WAVEfmt ".toByteArray()).putInt(16)
            header.putShort(3).putShort(CHANNELS.toShort()).putInt(RATE).putInt(RATE * CHANNELS * 4)
            header.putShort((CHANNELS * 4).toShort()).putShort(32)
            header.put("fact".toByteArray()).putInt(4).putInt(0).put("data".toByteArray()).putInt(0)
            output.write(header.array())
        }
        fun write(bytes: ByteArray) { output.write(bytes); dataBytes += bytes.size }
        override fun close() {
            fun integerAt(offset: Long, value: Int) {
                output.seek(offset)
                output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            }
            integerAt(4, dataBytes + 48)
            integerAt(44, dataBytes / (CHANNELS * 4))
            integerAt(52, dataBytes)
            output.close()
        }
    }

    private fun withRig(compatible: Boolean, leads: Pair<Long, Long>, captureTelemetry: Boolean = false, test: (Rig) -> Unit) {
        val restore = listOf(
            preserve(AppSettings.smartFadeEnabled), preserve(AppSettings.crossfadeSeconds),
            preserve(AppSettings.playbackSpeed), preserve(AppSettings.mixViewOpen), preserve(SleepTimer.afterTrack),
            preserve(AppSettings.smartAnalysis), preserve(AppSettings.smartTransitionWindow),
            preserve(AppSettings.smartMixInProgress), preserve(AppSettings.smartMixBlend),
            preserve(AppSettings.smartMixView), preserve(AppSettings.smartMixPreview), preserve(AppSettings.smartMixProgress),
            preserve(AppSettings.automixStatus), preserve(AppSettings.automixDetail),
        )
        val scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        try {
            AppSettings.smartFadeEnabled.value = true
            AppSettings.crossfadeSeconds.value = 0
            AppSettings.playbackSpeed.value = 1f
            AppSettings.mixViewOpen.value = false
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixView.value = null
            AppSettings.smartMixPreview.value = null
            SleepTimer.afterTrack.value = false
            Rig(scheduler, compatible, leads, captureTelemetry).use(test)
        } finally { restore.forEach { it() }; Dispatchers.resetMain() }
    }

    private fun <T> preserve(flow: MutableStateFlow<T>): () -> Unit {
        val saved = flow.value
        return { flow.value = saved }
    }

    companion object {
        private const val RATE = 48_000
        private const val CHANNELS = 2
        private const val BLOCK_MS = 20L
        private const val BLOCK_FRAMES = 960
        private const val PAD_MS = 1_000L
        private const val DURATION_MS = 200_000L
        private val OUT_FREQUENCIES = intArrayOf(61, 173, 631, 1_919, 5_093, 11_017)
        private val IN_FREQUENCIES = intArrayOf(83, 227, 883, 2_411, 6_197, 12_179)

        private fun analysis(id: String, compatible: Boolean): TrackAnalysis {
            val bpm = if (!compatible && id == "B") 87.0 else 120.0
            val beat = 60.0 / bpm
            val energy = (0..200).map { EnergySample(it.toDouble(), 0.8) }
            return TrackAnalysis(
                status = TrackAnalysis.STATUS_READY, trackId = id, duration = 200.0,
                bpm = bpm, beatInterval = beat, beatConfidence = 0.9,
                downbeats = generateSequence(0.5) { it + 4 * beat }.takeWhile { it <= 200.0 }.toList(),
                firstBeat = 2.5, key = if (!compatible && id == "B") "F# major" else "C major", keyConfidence = 0.9,
                contentEndTime = 196.0, energyCurve = energy,
                vocalActivityMask = energy.map { if (compatible) 0.0 else 0.95 },
                vocalProbability = if (compatible) 0.1 else 0.95,
                // Alternatives require this evidence. Empty is a stable fixture, not tick mutation.
                lowEnergyCurve = emptyList(), quality = TrackAnalysis.QUALITY_FULL,
            )
        }

        private fun source(probe: Probe, side: Int, time: Double, wall: Double, channel: Int): Float {
            val phase = channel * 0.19
            if (probe == Probe.PHASE_CONTROL) {
                // Both sources share exactly the same phase at each output sample despite
                // different media cues. Baseline/candidate should be bit-identical here.
                return (0.10 * sin(2 * PI * 137 * wall + phase) +
                    0.08 * sin(2 * PI * 1_903 * wall + phase) + 0.06 * sin(2 * PI * 7_111 * wall + phase)).toFloat()
            }
            val beatAge = time % (60.0 / if (side == 0) 120.0 else 87.0)
            val attack = exp(-beatAge * if (probe == Probe.PERCUSSION) 38.0 else 90.0)
            if (probe == Probe.PERCUSSION) {
                val kick = sin(2 * PI * (if (side == 0) 59.0 else 83.0) * beatAge)
                val click = sin(2 * PI * (if (side == 0) 4_609.0 else 6_137.0) * time + phase)
                return (attack * (0.23 * kick + 0.11 * click) +
                    0.025 * sin(2 * PI * (if (side == 0) 347.0 else 521.0) * time + phase)).toFloat()
            }
            val frequencies = if (side == 0) OUT_FREQUENCIES else IN_FREQUENCIES
            var value = 0.0
            frequencies.forEachIndexed { i, hz -> value += 0.035 * sin(2 * PI * hz * time + phase + i * 0.71 + side) }
            return (value + 0.10 * attack * sin(2 * PI * 3_719 * time + phase)).toFloat()
        }
    }
}
