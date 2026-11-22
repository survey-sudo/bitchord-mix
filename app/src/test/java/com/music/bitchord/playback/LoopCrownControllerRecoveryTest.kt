package com.music.bitchord.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * Admission/binding unit coverage plus the real classic begin -> ready -> fire path.
 * Reflection accesses existing private policy/lifecycle methods, not a native-success
 * seam. No test seeds LOOP_RUNNING, attaches a fake renderer or claims JNI execution.
 * Real first-ticker crown routing still requires Android URI/sink implementations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopCrownControllerRecoveryTest {
    @Test
    fun nativeLoopCompletionIsTerminalCompletedOnlyAfterObservedExecutionAndHandoff() {
        assertEquals("completed", automixTerminalOutcome(
            isTerminal = true, executed = true, handedOff = true, reason = "loop-complete",
        ))
        assertEquals("completed", automixTerminalOutcome(
            isTerminal = true, executed = true, handedOff = true, reason = "finish",
        ))
        assertEquals("completed", automixTerminalOutcome(
            isTerminal = true, executed = true, handedOff = true, reason = "planned-fade-done",
        ))
        assertEquals("completed", automixTerminalOutcome(
            isTerminal = true, executed = true, handedOff = true, reason = "loop-handover-unstarted-recovery",
        ))
        assertEquals("unknown", automixTerminalOutcome(
            isTerminal = true, executed = false, handedOff = true, reason = "loop-complete",
        ))
        assertEquals("unknown", automixTerminalOutcome(
            isTerminal = true, executed = true, handedOff = false, reason = "loop-complete",
        ))
        assertEquals("unknown", automixTerminalOutcome(
            isTerminal = false, executed = true, handedOff = true, reason = "loop-complete",
        ))
        assertEquals("cancelled", automixTerminalOutcome(
            isTerminal = true, executed = true, handedOff = false, reason = "seek-cancelled",
        ))
    }

    @Test
    fun sharedQualificationRejectsInvalidCachedGeometryWithItsExactReason() = withRig { r ->
        val good = crown()
        assertTrue(LoopMix.qualifyCrown(good).accepted)
        assertNull(r.refusal(good))
        val bad = good.copy(inn = good.inn.copy(bpm = 180.0, beatSec = 1.0 / 3,
            loopEnd = good.inn.loopStart + 16.0 / 3))
        val shared = LoopMix.qualifyCrown(bad)
        assertFalse(shared.accepted)
        assertEquals(shared.refusal, r.refusal(bad))
        // A previously cached invalid crown must be dropped with its real reason,
        // rather than being reported as a late singer or converted to a bridge.
        r.set("loopPlanned", bad)
        r.set("loopPlanKey", "A->B|${bad.out.loopStart}")
        r.call("dropRefusedLoop", "A->B", false, requireNotNull(shared.refusal), null)
        assertNull(r.get("loopPlanned"))
        assertNull(r.get("loopPlanKey"))
        assertEquals(shared.refusal, r.get("loopRefusal"))
    }

    @Test
    fun coarseMetadataDoesNotRejectHealthyCrownButLocalLyricsAlwaysDo() = withRig { r ->
        val plan = crown()
        val out = TrackAnalysis(key = "C major", keyConfidence = 0.99, vocalProbability = 0.99)
        val inn = TrackAnalysis(key = "F# major", keyConfidence = 0.99, vocalProbability = 0.99)
        assertNull(r.call("retainedPlanRefusal", plan, out, inn, null, null))
        val sung = listOf(LoopMix.Span(plan.out.loopStart, plan.out.loopEnd))
        assertEquals("sung veto", r.call("retainedPlanRefusal", plan, out, inn, sung, null))
        // The controller's local refusal is used before the shared armed hold.
        assertEquals(LoopMix.Hold.CLEAR, LoopMix.holdLoop(true, true, false, false, false, sungVeto = true))
    }

    @Test
    fun cachedBridgeAndBuiltConfigurationAreNotCrownEvidence() = withRig { r ->
        val plan = crown()
        val bridge = plan.copy(outLoops = false, inLoops = false, rung = "Echo", echoTail = true)
        assertEquals(CountdownStabilizer.Family.LOOP, r.call("nativeFamily", plan))
        assertEquals(CountdownStabilizer.Family.NATIVE_BRIDGE, r.call("nativeFamily", bridge))
        assertEquals("native-bridge", r.nativeFields(bridge, false, false)["technique"])
        // Even a Loop-labelled plan is not executed crown evidence when the
        // session's actual incoming configuration is straight or unknown.
        assertEquals("native-bridge", r.nativeFields(plan, true, false)["technique"])
        assertEquals("crown-loop", r.nativeFields(plan, null, null)["technique"]) // requested candidate, not execution
        assertEquals("unknown-native", r.nativeFields(plan, null, null, true)["technique"])
        val overridden = r.nativeFields(plan, coarseDecline = true)
        assertEquals(true, overridden["coarseDecline"])
        assertEquals(true, overridden["coarseOverride"])
        assertEquals(1.0, overridden["plannedOutMinRate"])
        assertEquals("constant-grid", overridden["plannedOutGridSource"])
        assertEquals("unknown", overridden["outGridSource"])
        assertEquals("unknown", overridden["inGridSource"])
        val anchored = plan.copy(out = plan.out.copy(anchorBeats = listOf(160.0, 160.5)),
            inn = plan.inn.copy(anchorBeats = listOf(8.0, 8.5)))
        val requested = r.nativeFields(anchored)
        assertEquals("tracked", requested["plannedOutGridSource"])
        assertEquals("tracked", requested["plannedInGridSource"])
        assertEquals("unknown", requested["outGridSource"])
        assertEquals("unknown", requested["inGridSource"])
        assertEquals(false, r.nativeFields(plan, false, false, actual = true, coarseDecline = true)["coarseOverride"])
        assertEquals("Echo", bridge.rung)
    }

    @Test
    fun priorSevenArgumentManualSessionKeepsActualGridAndConfigurationUnknown() = withRig { r ->
        val plan = crown()
        val clock = LoopMix.Clock(plan)
        // Exercise the existing manual-session constructor without attaching
        // ports, fabricating takeover or treating this readout as JNI proof.
        val renderer = LoopMixRenderer(48_000, object : LoopMixRenderer.Readout {
            override fun firstFrame() = 0L
            override fun heard(index: Int) = 0.0
            override fun exitGateViolations() = 0
        })
        val session = LoopMixSession::class.java.getDeclaredConstructor(
            LoopMix.Plan::class.java, LoopMix.Clock::class.java, String::class.java, String::class.java,
            LoopMixRenderer::class.java, Int::class.javaPrimitiveType, Double::class.javaPrimitiveType,
        ).apply { isAccessible = true }.newInstance(plan, clock, "A", "B", renderer, 48_000, clock.elementTrackTime)
        try {
            assertNull(session.configuredOutLoops)
            assertNull(session.configuredInLoops)
            assertEquals(LoopMixSession.GridSource.UNKNOWN, session.outGridSource)
            assertEquals(LoopMixSession.GridSource.UNKNOWN, session.inGridSource)
            val fields = r.call("nativeSessionRouteFields", session, null) as Map<*, *>
            assertEquals("unknown-native", fields["technique"])
            assertEquals("unknown", fields["outGridSource"])
            assertEquals("unknown", fields["inGridSource"])
            assertEquals("constant-grid", fields["plannedOutGridSource"])
            assertEquals("constant-grid", fields["plannedInGridSource"])
        } finally {
            session.close()
        }
    }

    @Test
    fun opportunitySurvivesFallbackCleanupAndRepeatedIdsGetFreshIdentity() = withRig { r ->
        r.beginClassic()
        val original = requireNotNull(r.get("opportunity"))
        r.call("loopFallBack", "incoming decode-null")
        assertEquals("IDLE", r.get("phase").toString())
        assertSame(original, r.get("opportunity"))
        assertFalse(r.events().any { it.contains("\"terminal\":true") })
        r.beginClassic()
        assertSame(original, r.get("opportunity"))
        r.call("cancelArming", "scrub-cancelled")
        assertNull(r.get("opportunity"))
        val terminal = r.events().filter { it.contains("\"terminal\":true") }
        assertEquals(1, terminal.size)
        assertTrue(terminal.single().contains("\"outId\":\"A\""))
        assertTrue(terminal.single().contains("\"inId\":\"B\""))
        r.beginClassic()
        assertNotEquals(original, r.get("opportunity"))
    }

    @Test
    fun loopBailEvidenceSurvivesSameOpportunityClassicFallbackAndClearsOnNewOpportunity() = withRig { r ->
        val opportunity = requireNotNull(r.call("ensureOpportunity", "A", "B"))
        val attemptId = opportunity.javaClass.getDeclaredField("attemptId").apply { isAccessible = true }
            .get(opportunity) as String
        val loopRunning = CrossfadeController::class.java.getDeclaredField("phase").let { field ->
            field.isAccessible = true
            field.type.enumConstants.single { it.toString() == "LOOP_RUNNING" }
        }
        r.set("phase", loopRunning)
        r.set("outgoing", r.out.player)
        r.set("executedRoute", mapOf("technique" to "native-bridge"))
        r.set("planRevision", 9)
        r.setNativeBinding(opportunity, revision = 7, generation = 42, coarseDecline = true)
        r.set("capturedTakeoverSpliceBufferStatus", LoopMixPort.TakeoverSpliceBufferStatus.UNRESOLVED)
        r.set("capturedLoopTookOver", true)

        // This is the production native->classic fallback path. Its internal
        // loopBail cleanup is nonterminal for the still-current opportunity.
        r.call("loopFallBack", "paused")
        assertEquals("IDLE", r.get("phase").toString())
        assertSame(opportunity, r.get("opportunity"))
        assertEquals("paused", r.get("initiatingExitReason"))
        val fallbackComplete = r.events().single {
            it.contains("\"ev\":\"mix.complete\"") && it.contains("\"terminal\":false")
        }
        // Emit the native attempt snapshot before clearing it for classic.
        assertTrue(fallbackComplete, fallbackComplete.contains("\"initiatingExitReason\":\"paused\""))
        assertTrue(fallbackComplete,
            fallbackComplete.contains("\"takeoverSpliceBufferDelegateStatus\":\"UNRESOLVED\""))
        assertTrue(fallbackComplete, fallbackComplete.contains("\"planRevision\":7"))
        assertTrue(fallbackComplete, fallbackComplete.contains("\"buildGeneration\":42"))
        assertNull(r.get("capturedTakeoverSpliceBufferStatus"))
        assertNull(r.get("capturedLoopTookOver"))
        assertNull(r.get("nativeBinding"))

        r.beginClassic()
        assertSame(opportunity, r.get("opportunity"))
        assertEquals("paused", r.get("initiatingExitReason"))
        assertNull("a new classic route must not inherit the old native arm status",
            r.get("capturedTakeoverSpliceBufferStatus"))
        assertNull(r.get("capturedLoopTookOver"))
        r.out.position = 196_000L
        r.call("driveArming")
        assertEquals("FADING", r.get("phase").toString())
        val fired = r.events().single { it.contains("\"ev\":\"mix.fire\"") }
        assertTrue(fired, fired.contains("\"attemptId\":\"$attemptId\""))
        assertTrue(fired, fired.contains("\"technique\":\"classic\""))

        r.call("finish")
        val terminal = r.events().single { it.contains("\"ev\":\"mix.complete\"") && it.contains("\"terminal\":true") }
        assertTrue(terminal, terminal.contains("\"attemptId\":\"$attemptId\""))
        assertTrue(terminal, terminal.contains("\"initiatingExitReason\":\"paused\""))
        assertTrue(terminal, terminal.contains("\"takeoverSpliceBufferDelegateStatus\":null"))
        assertTrue(terminal, terminal.contains("\"tookOver\":null"))
        assertTrue(terminal, terminal.contains("\"buildGeneration\":null"))
        assertTrue(terminal, terminal.contains("\"technique\":\"classic\""))
        assertNull(r.get("initiatingExitReason"))
        assertNull(r.get("capturedTakeoverSpliceBufferStatus"))
        assertNull(r.get("capturedLoopTookOver"))

        // A new Opportunity drops evidence independently of terminal cleanup.
        r.set("initiatingExitReason", "stale")
        r.set("capturedTakeoverSpliceBufferStatus", LoopMixPort.TakeoverSpliceBufferStatus.DELEGATE_FULLY_CONSUMED)
        r.set("capturedLoopTookOver", true)
        r.call("ensureOpportunity", "C", "D")
        assertNull(r.get("initiatingExitReason"))
        assertNull(r.get("capturedTakeoverSpliceBufferStatus"))
        assertNull(r.get("capturedLoopTookOver"))
    }

    @Test
    fun acceptedPreparedAndExecutedClassicUseEffectiveInFlightRoute() = withRig { r ->
        val effective = TransitionPlan(transitionStart = 196.0, transitionEnd = 200.0,
            fadeSeconds = 4.0, transitionStyle = TransitionStyle.EQUAL_POWER)
        r.beginClassic(effective)
        assertEquals("ARMING", r.get("phase").toString())
        val initial = r.events()
        assertTrue(initial.any { it.contains("\"stage\":\"accepted\"") && it.contains("\"technique\":\"classic\"") })
        assertTrue(initial.any { it.contains("\"stage\":\"prepared\"") })
        assertFalse(initial.any { it.contains("\"stage\":\"executed\"") })
        r.set("lastSmartPlan", effective.copy(transitionStyle = TransitionStyle.DJ_FILTER))
        r.out.position = 196_000L
        r.call("driveArming")
        assertEquals("FADING", r.get("phase").toString())
        val fired = r.events().single { it.contains("\"ev\":\"mix.fire\"") }
        assertTrue(fired.contains("\"stage\":\"executed\""))
        assertTrue(fired.contains("\"technique\":\"classic\""))
        assertTrue(fired.contains("\"transitionStyle\":\"EQUAL_POWER\""))
        assertTrue(fired.contains("classic-fade-fired"))
        assertTrue(fired.contains("\"outRenditionRevision\":\"unknown\""))
        assertTrue(fired.contains("\"outAnalysisRevision\":\"unknown\""))
    }

    @Test
    fun copiedBuildInputsDoNotFollowMutableMasksOrAnotherPair() = withRig { r ->
        val values = FloatArray(30) { 0.02f }
        val beats = mutableListOf(0.0, 0.5, 1.0)
        val original = TrackAnalysis(trackId = "A", downbeats = beats,
            vocalWindows = mutableListOf(CurveWindow(0.0, 1.0, values)))
        val captured = r.call("snapshotLoopAnalysis", original) as TrackAnalysis
        values.fill(0.99f)
        beats.clear()
        r.set("loopOutAnalysis", TrackAnalysis(trackId = "C"))
        assertEquals("A", captured.trackId)
        assertEquals(listOf(0.0, 0.5, 1.0), captured.downbeats)
        assertEquals(0.02f, captured.vocalWindows.single().values[0], 0f)
        assertNotSame(values, captured.vocalWindows.single().values)
    }

    @Test
    fun discardedGenerationAndOldOpportunityCannotClaimPrepared() = withRig { r ->
        val old = r.call("ensureOpportunity", "A", "B")
        val generation = r.get("buildGeneration") as Int
        r.call("discardPrime")
        assertTrue((r.get("buildGeneration") as Int) > generation)
        r.call("ensureOpportunity", "C", "D")
        r.call("preparedRoute", r.nativeFields(crown(), true, true), old, 1, generation)
        assertFalse(r.events().any { it.contains("\"stage\":\"prepared\"") })
    }

    @Test
    fun deadlineRefusalsDeduplicateStableCodesNotElapsedStrings() = withRig { r ->
        val context = r.call("ensureOpportunity", "A", "B")
        for (elapsed in listOf(12, 45, 800)) {
            r.call("routeRefusal", "mix.loop.fallback", "incoming decode $elapsed ms over",
                "prepared", "crown-loop", "classic", context, 1, 3, null)
        }
        val lines = r.events().filter { it.contains("\"ev\":\"mix.loop.fallback\"") }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\"refusalCode\":\"incoming decode deadline-over\""))
    }

    @Test
    fun identicalCandidateArmAndFallbackEventsReemitOnTheNextOpportunity() = withRig { r ->
        fun emit(): List<String> {
            // Exercise the real classic candidate/rejection guards, including
            // the second-tick candidate branch, rather than only routingEvidenceKeys.
            repeat(3) { r.call("considerSmartTransition", 200_000L, true) }
            repeat(2) { r.call("armDeath", "same-refusal") }
            repeat(2) { r.call("beginLoop", r.out.player, crown(), r.out.player.currentMediaItem,
                r.out.player.getMediaItemAt(r.out.player.nextMediaItemIndex), 200_000L, 200_000L) }
            assertEquals("IDLE", r.get("phase").toString())
            val context = r.get("opportunity")
            assertSame(context, r.call("ensureOpportunity", r.out.player.currentMediaItem?.mediaId,
                r.out.player.getMediaItemAt(r.out.player.nextMediaItemIndex).mediaId))
            val attempt = eventField(r.events().last { it.contains("\"ev\":\"mix.arm.death\"") }, "attemptId")
            return r.events().filter { eventField(it, "attemptId") == attempt && !it.contains("\"terminal\":true") }
        }
        fun assertEvents(events: List<String>) {
            assertEquals(1, events.count { it.contains("\"ev\":\"mix.plan\"") &&
                it.contains("\"stage\":\"candidate\"") && it.contains("\"technique\":\"classic\"") })
            assertEquals(1, events.count { it.contains("\"ev\":\"mix.plan.rejected\"") &&
                it.contains("\"stage\":\"candidate\"") && it.contains("\"technique\":\"classic\"") })
            assertEquals(1, events.count { it.contains("\"ev\":\"mix.arm.death\"") })
            assertEquals(1, events.count { it.contains("\"ev\":\"mix.loop.fallback\"") &&
                it.contains("\"reason\":\"no-out-port\"") })
        }
        val first = emit()
        assertEvents(first)
        // A changed pair reaches ensureOpportunity directly, without relying
        // on the separate natural-occurrence expiration to reset outer guards.
        r.out.player.setMediaItems(listOf("C", "D").map { MediaItem.Builder().setMediaId(it).build() }, 0, 10_000L)
        r.out.player.prepare()
        val second = emit()
        assertEvents(second)
        assertNotEquals(eventField(first.first(), "attemptId"), eventField(second.first(), "attemptId"))
        for (name in listOf("mix.plan", "mix.plan.rejected", "mix.arm.death", "mix.loop.fallback")) {
            val a = first.single { eventField(it, "ev") == name &&
                (name != "mix.plan" || (it.contains("\"stage\":\"candidate\"") && it.contains("\"technique\":\"classic\""))) &&
                (name != "mix.plan.rejected" || it.contains("\"technique\":\"classic\"")) }
            val b = second.single { eventField(it, "ev") == name &&
                (name != "mix.plan" || (it.contains("\"stage\":\"candidate\"") && it.contains("\"technique\":\"classic\""))) &&
                (name != "mix.plan.rejected" || it.contains("\"technique\":\"classic\"")) }
            assertEquals(eventField(a, "reason"), eventField(b, "reason"))
        }
    }

    @Test
    fun duplicateIdAutoAdvanceAndRepeatExpireOnlyIdleOccurrenceEvidence() = withRig(listOf("A", "A", "A")) { r ->
        r.controller.start()
        var previous = r.call("ensureOpportunity", "A", "A")
        val phraseLock = r.get("phraseLock")
        val countdown = r.get("countdownStabilizer")
        val plan = crown()
        r.set("loopPlanned", plan)
        r.set("shownCountdownKey", "unchanged-countdown")
        r.set("primedKey", "unchanged-build")
        r.set("primeRetryAt", 12_345L)
        val epoch = r.get("scrubEpoch")
        val generation = r.get("buildGeneration")
        for ((i, reason) in listOf(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO,
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT).withIndex()) {
            r.out.advance(reason)
            assertEquals(1, r.out.player.currentMediaItemIndex)
            assertEquals("A", r.out.player.currentMediaItem?.mediaId)
            assertNull(r.get("opportunity"))
            r.out.notifyTransition(reason) // no new opportunity: no duplicate terminal
            assertEquals(i + 1, r.events().count { it.contains("\"terminal\":true") })
            val current = r.call("ensureOpportunity", "A", "A")
            assertNotEquals(previous, current)
            r.call("preparedRoute", r.nativeFields(plan, true, true), previous, 1, generation)
            assertFalse(r.events().any { it.contains("\"stage\":\"prepared\"") })
            previous = current
            assertEquals(epoch, r.get("scrubEpoch"))
            assertEquals(generation, r.get("buildGeneration"))
            assertSame(plan, r.get("loopPlanned"))
            assertSame(phraseLock, r.get("phraseLock"))
            assertSame(countdown, r.get("countdownStabilizer"))
            assertEquals("unchanged-countdown", r.get("shownCountdownKey"))
            assertEquals("unchanged-build", r.get("primedKey"))
            assertEquals(12_345L, r.get("primeRetryAt"))
        }
        val terminals = r.events().filter { it.contains("\"terminal\":true") }
        assertTrue(terminals.all { it.contains("\"outcome\":\"unknown\"") &&
            it.contains("\"refusalCode\":\"media-occurrence-ended\"") })
        assertEquals(listOf("natural-auto-advance", "natural-repeat"), terminals.map { eventField(it, "reason") })
        assertEquals(2, terminals.map { eventField(it, "attemptId") }.distinct().size)
        assertFalse(r.events().any { it.contains("\"stage\":\"executed\"") })
    }

    @Test
    fun naturalOccurrenceTerminalUsesOriginalPairAndRepeatKeepsActiveClassicAttempt() = withRig(listOf("A", "B", "C")) { r ->
        r.controller.start()
        r.call("ensureOpportunity", "A", "B")
        r.out.advance(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        val terminal = r.events().single { it.contains("\"terminal\":true") }
        assertEquals("A", eventField(terminal, "outId"))
        assertEquals("B", eventField(terminal, "inId"))
        assertEquals("B", r.out.player.currentMediaItem?.mediaId)
        r.beginClassic()
        val active = r.get("opportunity")
        assertEquals("ARMING", r.get("phase").toString())
        r.out.advance(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
        assertSame(active, r.get("opportunity"))
        assertEquals("ARMING", r.get("phase").toString())
        assertEquals(1, r.events().count { it.contains("\"terminal\":true") })
    }

    private class Rig(val dir: File, ids: List<String>) : AutoCloseable {
        val out = Deck(ids.map { MediaItem.Builder().setMediaId(it).build() })
        private val into = Deck(emptyList())
        private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val controller = CrossfadeController(scope = scope, active = { out.player }, standby = { into.player },
            onHandoff = { _, _ -> }, nowMs = { 10_000L })

        fun get(name: String): Any? = field(name).get(controller)
        fun set(name: String, value: Any?) = field(name).set(controller, value)
        private fun field(name: String) = CrossfadeController::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun setNativeBinding(context: Any, revision: Int, generation: Int, coarseDecline: Boolean?) {
            val bindingClass = CrossfadeController::class.java.declaredClasses.single { it.simpleName == "NativeBinding" }
            val constructor = bindingClass.declaredConstructors.single().apply { isAccessible = true }
            set("nativeBinding", constructor.newInstance(context, revision, generation, coarseDecline))
        }
        fun call(name: String, vararg args: Any?): Any? = CrossfadeController::class.java.declaredMethods
            .single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(controller, *args)
        fun refusal(plan: LoopMix.Plan) = call("retainedPlanRefusal", plan, TrackAnalysis(), TrackAnalysis(), null, null)
        @Suppress("UNCHECKED_CAST")
        fun nativeFields(plan: LoopMix.Plan, outLoops: Boolean? = null, inLoops: Boolean? = null,
            actual: Boolean = false, coarseDecline: Boolean? = null) =
            call("nativeRouteFields", plan, outLoops, inLoops, actual, coarseDecline) as Map<String, Any?>
        fun beginClassic(plan: TransitionPlan? = null) {
            val begin = CrossfadeController::class.java.declaredMethods.single { it.name == "begin\$default" }
                .apply { isAccessible = true }
            assertEquals(true, begin.invoke(null, controller, 4_000L, 200_000L, false,
                0L, 1.0, 1.0, 0L, null, plan, 248, null))
        }
        fun events(): List<String> = File(dir, "automix-telemetry.jsonl").takeIf { it.exists() }?.readLines().orEmpty()
        override fun close() { controller.release(); scope.cancel() }
    }

    /** Real queue/prepare state changes; no native port or fabricated takeover. */
    private class Deck(initial: List<MediaItem>) {
        private val items = initial.toMutableList()
        private val listeners = mutableSetOf<Player.Listener>()
        private var index = if (items.isEmpty()) C.INDEX_UNSET else 0
        private var state = if (items.isEmpty()) Player.STATE_IDLE else Player.STATE_READY
        private var volume = 0f
        private var playing = true
        var position = 10_000L
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "getCurrentMediaItem" -> items.getOrNull(index)
                "getCurrentMediaItemIndex", "getCurrentPeriodIndex" -> index
                "getMediaItemCount" -> items.size
                "getMediaItemAt" -> items[args!![0] as Int]
                "getNextMediaItemIndex" -> (index + 1).takeIf { it in items.indices } ?: C.INDEX_UNSET
                "hasNextMediaItem" -> index + 1 in items.indices
                "getCurrentTimeline" -> Timeline.EMPTY
                "getCurrentPosition", "getContentPosition" -> position
                "getDuration" -> 200_000L
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playing
                "isPlaying" -> playing && state == Player.STATE_READY
                "setPlayWhenReady" -> { playing = args!![0] as Boolean; null }
                "play" -> { playing = true; null }
                "pause" -> { playing = false; null }
                "getVolume" -> volume
                "setVolume" -> { volume = args!![0] as Float; null }
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "setMediaItems" -> {
                    @Suppress("UNCHECKED_CAST") val queue = args!![0] as List<MediaItem>
                    items.clear(); items.addAll(queue); index = args[1] as Int; position = args[2] as Long
                    state = Player.STATE_IDLE; null
                }
                "prepare" -> { state = Player.STATE_READY; null }
                "stop" -> { state = Player.STATE_IDLE; null }
                "clearMediaItems" -> { items.clear(); index = C.INDEX_UNSET; null }
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

        fun advance(reason: Int) {
            require(reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                check(index + 1 in items.indices)
                index++
            }
            position = 10_000L
            notifyTransition(reason)
        }

        fun notifyTransition(reason: Int) {
            check(listeners.isNotEmpty()) { "The controller must register its actual Player.Listener" }
            listeners.toList().forEach { it.onMediaItemTransition(items.getOrNull(index), reason) }
        }
    }

    private fun withRig(ids: List<String> = listOf("A", "B"), test: (Rig) -> Unit) {
        val telemetryDir = AutomixTelemetry::class.java.getDeclaredField("dir").apply { isAccessible = true }
        val savedDir = telemetryDir.get(AutomixTelemetry)
        val savedSmart = AppSettings.smartFadeEnabled.value
        val savedSpeed = AppSettings.playbackSpeed.value
        val savedFade = AppSettings.crossfadeSeconds.value
        val restoreViews = listOf(
            preserve(AppSettings.smartTransitionWindow), preserve(AppSettings.smartMixInProgress),
            preserve(AppSettings.smartMixBlend), preserve(AppSettings.smartMixView),
            preserve(AppSettings.smartMixPreview), preserve(AppSettings.smartMixProgress),
            preserve(AppSettings.automixStatus), preserve(AppSettings.automixDetail),
        )
        val dir = Files.createTempDirectory("loop-crown-controller-").toFile()
        Dispatchers.setMain(StandardTestDispatcher())
        try {
            telemetryDir.set(AutomixTelemetry, dir)
            AppSettings.smartFadeEnabled.value = false
            AppSettings.playbackSpeed.value = 1f
            AppSettings.crossfadeSeconds.value = 4
            Rig(dir, ids).use(test)
        } finally {
            telemetryDir.set(AutomixTelemetry, savedDir)
            AppSettings.smartFadeEnabled.value = savedSmart
            AppSettings.playbackSpeed.value = savedSpeed
            AppSettings.crossfadeSeconds.value = savedFade
            restoreViews.forEach { it() }
            Dispatchers.resetMain()
            dir.deleteRecursively()
        }
    }

    private fun <T> preserve(flow: MutableStateFlow<T>): () -> Unit {
        val saved = flow.value
        return { flow.value = saved }
    }

    private fun eventField(event: String, key: String): String? = Regex("\"$key\":\"([^\"]*)\"")
        .find(event)?.groupValues?.get(1)

    companion object {
        private fun crown(): LoopMix.Plan = LoopMix.Plan(
            out = LoopMix.Pick(160.0, 168.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(8.0, 16.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            fold = 1.0, enterBars = 4, rampBars = 4, exitBars = 4, totalBars = 12,
        )
    }
}
