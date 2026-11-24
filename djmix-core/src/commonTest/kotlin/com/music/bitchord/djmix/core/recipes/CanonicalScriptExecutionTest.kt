package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract and physical-coordinate regressions; PCM byte/device parity remains an adapter gate. */
class CanonicalScriptExecutionTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun absentAndExplicitNullLegacyGeometryRemainInspectableButNeverExecutable() {
        val f = CanonicalFixture()
        val legacy = f.script().copy(renderGeometry = null)
        val fields = json.encodeToJsonElement(TransitionScriptV1.serializer(), legacy).jsonObject
        for (payload in listOf(JsonObject(fields - "renderGeometry"), JsonObject(fields + ("renderGeometry" to JsonNull)))) {
            val decoded = json.decodeFromJsonElement(TransitionScriptV1.serializer(), payload)
            assertEquals(legacy, decoded)
            assertEquals(decoded, json.decodeFromString(TransitionScriptV1.serializer(), json.encodeToString(TransitionScriptV1.serializer(), decoded)))
            for (result in listOf(ScriptExecutionGuard.validate(decoded), ScriptExecutionGuard.validateFrozen(decoded, f.plan()))) {
                assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, assertIs<MixOutcome.Refused>(result).reason)
                assertEquals("render_geometry_missing", result.detail)
            }
        }
    }

    @Test
    fun fractionalTwoDeckGeometryAndFloatSettersRoundTripWithoutRounding() {
        val f = CanonicalFixture(hold = 0.375000123, inLoop = true)
        val script = f.script().copy(fxParams = ScriptFxParams(outSvfQ = 0.9234567f, inSvfQ = 1.1234567f))
        val decoded = json.decodeFromString(TransitionScriptV1.serializer(), json.encodeToString(TransitionScriptV1.serializer(), script))
        assertEquals(script, decoded)
        assertNotEquals(f.geometry.out.knotFrames, f.geometry.inbound.knotFrames)
        assertNotEquals(f.geometry.out.knotPositions, f.geometry.inbound.knotPositions)
        assertTrue(f.geometry.out.knotFrames.any { it != it.toLong().toDouble() })
        assertTrue(f.geometry.inbound.knotPositions.any { it != it.toLong().toDouble() })
        assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validateFrozen(decoded, f.plan()))
    }

    @Test
    fun malformedCoordinatesAndConflictingProjectionsReturnTypedRefusals() {
        val f = CanonicalFixture()
        val g = f.geometry
        val malformed = listOf(
            g.copy(out = g.out.copy(knotPositions = g.out.knotPositions.dropLast(1))),
            g.copy(out = g.out.copy(knotFrames = listOf(0.0, Double.NaN, 800000.0))),
            g.copy(out = g.out.copy(levelGain = Float.POSITIVE_INFINITY)),
            g.copy(out = g.out.copy(frameCount = 0)),
            g.copy(out = g.out.copy(regionStartSec = -1.0)),
            g.copy(out = g.out.copy(prerollSec = -0.1)),
            g.copy(out = g.out.copy(stopAtMixSec = 0.0)),
            g.copy(out = g.out.copy(loopStartFrame = -1)),
            g.copy(out = g.out.copy(loopEndFrame = g.out.frameCount + 1L)),
            g.copy(out = g.out.copy(loopStartFrame = 10, loopEndFrame = 100, releaseAtFrame = 99.0)),
            g.copy(out = g.out.copy(releaseAtFrame = Double.POSITIVE_INFINITY)),
            g.copy(inbound = g.inbound.copy(fadeOutTrackSec = g.handoverTrackSec + 1.0)),
            g.copy(handoverTrackSec = Double.NaN),
            g.copy(incomingStartTrackSec = g.incomingStartTrackSec + 0.1),
        )
        for (bad in malformed) {
            val result = ScriptExecutionGuard.validate(f.script().copy(renderGeometry = bad))
            assertIs<MixOutcome.Refused>(result, "Accepted $bad")
            assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, result.reason)
        }
        val script = f.script()
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(knots = listOf(TempoKnot(0, 0)))))
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(loopBounds = LoopBounds())))
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(outRegion = script.outRegion.copy(stopAtMixMs = 1))))
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(inRegion = script.inRegion.copy(runOn = false))))
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(outRegion = script.outRegion.copy(levelGain = 1f))))
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(envelopes = script.envelopes +
            EnvelopeLane(DeckTarget.OUT, EnvelopeKind.LOW_DB, listOf(0.0, 1.0), listOf(0f, Float.NaN)))))
    }

    @Test
    fun initialIncomingHoldIsDeclaredAndOnlyInitialParkingIsAllowed() {
        val f = CanonicalFixture(hold = 0.5)
        assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validateFrozen(f.script(), f.plan()))
        val missingDeclaration = ScriptExecutionGuard.validateFrozen(f.script(), f.plan().copy(recipeParams = emptyMap()))
        assertEquals(RefusalReason.HANDOFF_HOLD_MISMATCH, assertIs<MixOutcome.Refused>(missingDeclaration).reason)
        val d = f.geometry.inbound
        for (positions in listOf(
            d.knotPositions.dropLast(1) + d.knotPositions[d.knotPositions.lastIndex - 1],
            d.knotPositions.dropLast(1) + (d.knotPositions[d.knotPositions.lastIndex - 1] - 1.0),
        )) {
            assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script().copy(
                renderGeometry = f.geometry.copy(inbound = d.copy(knotPositions = positions)),
            )))
        }
        val out = f.geometry.out
        assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script().copy(renderGeometry = f.geometry.copy(
            out = out.copy(knotPositions = listOf(out.knotPositions.first(), out.knotPositions.first(), out.knotPositions.last())),
        ))))
    }

    @Test
    fun physicalEofDuringWetDrainIsAcceptedButDryTruncationIsRejected() {
        val f = CanonicalFixture(release = 8.0)
        val d = f.geometry.out
        val p0 = d.knotPositions.first()
        // Exactly rate one: dry closes at t=2; PCM ends there, wet can drain to t=10.
        val out = d.copy(frameCount = (p0 + 2.0 * f.sr).roundToLong().toInt(),
            knotFrames = listOf(0.0, 10.0 * f.sr), knotPositions = listOf(p0, p0 + 10.0 * f.sr))
        val g = f.geometry.copy(out = out)
        val script = f.script(g).copy(
            envelopes = listOf(
                EnvelopeLane(DeckTarget.OUT, EnvelopeKind.GAIN, listOf(-out.prerollSec, 0.0, 2.0, 8.0), listOf(0f, 1f, 0f, 0f)),
                EnvelopeLane(DeckTarget.IN, EnvelopeKind.GAIN, listOf(0.0, 8.0), listOf(0f, 1f)),
                EnvelopeLane(DeckTarget.OUT, EnvelopeKind.ECHO_SEND, listOf(0.0, 1.5, 2.0, 10.0), listOf(0f, 0.7f, 0f, 0f)),
            ),
            fxParams = ScriptFxParams(echo = EchoFxParams(enabled = true, delaySec = 0.25)),
        )
        assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validateFrozen(script, f.plan()))
        val truncated = g.copy(out = out.copy(frameCount = out.frameCount - 100))
        val bad = script.copy(renderGeometry = truncated, outRegion = f.region(DeckTarget.OUT, truncated))
        assertEquals(RefusalReason.PCM_COVERAGE_INCOMPLETE, assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(bad)).reason)
    }

    @Test
    fun loopBodySeamAndIncomingFadeNeedPhysicalPcmEvenWithUnwrappedKnots() {
        val f = CanonicalFixture(outLoop = true, inLoop = true)
        assertTrue(f.geometry.inbound.knotPositions.last() > f.geometry.inbound.loopEndFrame)
        assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validateFrozen(f.script(), f.plan()))
        val seamShort = f.geometry.copy(out = f.geometry.out.copy(frameCount = f.geometry.out.loopEndFrame.toInt()))
        assertEquals(RefusalReason.PCM_COVERAGE_INCOMPLETE, assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script(seamShort))).reason)
        val bodyShort = seamShort.copy(out = seamShort.out.copy(frameCount = seamShort.out.frameCount - 1))
        assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script(bodyShort))).reason)
        val fadeEndFrame = ((f.geometry.handoverTrackSec - f.geometry.inbound.regionStartSec) * f.sr).roundToLong().toInt()
        val fadeShort = f.geometry.copy(inbound = f.geometry.inbound.copy(frameCount = fadeEndFrame))
        assertEquals(RefusalReason.PCM_COVERAGE_INCOMPLETE, assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script(fadeShort))).reason)
    }

    @Test
    fun assetsFrozenIdentityAndCapabilitiesCannotBeSilentlyIgnored() {
        val f = CanonicalFixture()
        val script = f.script()
        for (plan in listOf(f.plan().copy(planId = "other"), f.plan().copy(generation = 8), f.plan().copy(inTrackId = "other"),
            f.plan().copy(frozenExitMs = 61_000))) {
            assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validateFrozen(script, plan))
        }
        val stem = StemRegionRef(DeckTarget.OUT, StemKind.BASS, "out", script.outRegion.startMs, script.outRegion.endMs)
        for (badStem in listOf(stem.copy(trackId = "wrong"), stem.copy(deck = DeckTarget.IN),
            stem.copy(sampleRateHz = 44_100), stem.copy(startMs = stem.startMs + 1), stem.copy(endMs = stem.endMs - 1))) {
            assertEquals(RefusalReason.STEM_DUCK_UNAVAILABLE, assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(stems = listOf(badStem)))).reason)
        }
        val phaser = script.copy(fxParams = ScriptFxParams(phaser = PhaserFxParams(enabled = true)))
        assertEquals("capability_missing:PHASER", assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(phaser,
            FxCapability.entries.toSet() - FxCapability.PHASER)).detail)
        val filterLane = script.copy(envelopes = script.envelopes + EnvelopeLane(DeckTarget.OUT,
            EnvelopeKind.FILTER_RES, listOf(0.0, 8.0), listOf(1f, 1f)))
        assertEquals("capability_missing:FILTER_RES", assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(filterLane,
            FxCapability.entries.toSet() - FxCapability.FILTER_RES)).detail)
        val unresolved = script.copy(fxParams = ScriptFxParams(sweep = SweepFxParams(enabled = true)))
        assertEquals("sweep_unresolved_or_invalid:OUT", assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(unresolved)).detail)
        for (q in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.49f, 1.93f)) {
            assertEquals("base_svf_q", assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(script.copy(fxParams = ScriptFxParams(outSvfQ = q)))).detail)
        }
        assertNull(script.renderGeometry?.out?.fadeOutTrackSec)
    }

    @Test
    fun rendererRateAndQBoundariesAreUsedWithoutAnInventedGainCap() {
        val f = CanonicalFixture()
        val out = f.geometry.out
        val p0 = out.knotPositions.first()
        for (rate in listOf(0.749, 1.351)) {
            val bad = f.geometry.copy(out = out.copy(knotFrames = listOf(0.0, 1000.0), knotPositions = listOf(p0, p0 + 1000.0 * rate)))
            assertEquals(RefusalReason.CROWN_EXECUTION_RATE, assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script(bad))).reason)
        }
        val gain = f.geometry.copy(out = out.copy(levelGain = 7.25f))
        assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validate(f.script(gain)))
        for (q in listOf(0.5f, 1.92f)) {
            assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validate(f.script().copy(fxParams = ScriptFxParams(outSvfQ = q))))
        }
        val nonfinite = listOf(
            ScriptFxParams(echo = EchoFxParams(enabled = true, delaySec = Double.NaN)),
            ScriptFxParams(phaser = PhaserFxParams(enabled = true, feedback = Float.POSITIVE_INFINITY)),
            ScriptFxParams(roll = RollFxParams(enabled = true, scheduleBeats = listOf(Double.POSITIVE_INFINITY))),
            ScriptFxParams(flanger = FlangerFxParams(enabled = true, lfoHz = 1.0, depthSec = Double.NaN)),
        )
        for (fx in nonfinite) assertIs<MixOutcome.Refused>(ScriptExecutionGuard.validate(f.script().copy(fxParams = fx)))
    }
}

/** Shared deterministic physical fixture with exact, unequal tracked maps and optional loops. */
internal class CanonicalFixture(
    val release: Double = 16.0,
    val hold: Double = 0.0,
    val outLoop: Boolean = false,
    val inLoop: Boolean = false,
) {
    val sr = 48_000
    private val outOrigin = 59.123456789
    private val inOrigin = 4.123456789
    private val outP0 = (60.0 - outOrigin) * sr
    private val inP0 = 12345.678901234
    private val middle = release * sr * 0.4 + 0.375
    private val last = (release + 1.0) * sr
    private fun inPosition(frame: Double): Double = if (frame <= hold * sr) inP0 else if (frame <= middle) {
        inP0 + (frame - hold * sr) * 1.01
    } else inP0 + (middle - hold * sr) * 1.01 + (frame - middle) * 0.99

    private val outSetup = DeckRenderSetupV1(
        regionStartSec = outOrigin, frameCount = 4_000_000,
        loopStartFrame = if (outLoop) outP0.toLong() else 0,
        loopEndFrame = if (outLoop) outP0.toLong() + 8L * sr else 0,
        releaseAtFrame = null,
        knotFrames = listOf(0.0, release * sr * 0.4 + 0.125, (release + 2.0) * sr),
        knotPositions = listOf(outP0, outP0 + release * sr * 0.4 + 0.25, outP0 + (release + 2.0) * sr + 0.5),
        runOn = false, stopAtMixSec = release + 2.0, prerollSec = 0.025000007,
        levelGain = 1.1234567f, fadeOutTrackSec = null,
    )
    private val inSetupBase = DeckRenderSetupV1(
        regionStartSec = inOrigin, frameCount = 4_000_000,
        loopStartFrame = if (inLoop) inP0.toLong() else 0,
        loopEndFrame = if (inLoop) inP0.toLong() + 4L * sr else 0,
        releaseAtFrame = if (inLoop) inPosition(release * sr) else null,
        knotFrames = if (hold > 0.0) listOf(0.0, hold * sr, middle, last) else listOf(0.0, middle, last),
        knotPositions = if (hold > 0.0) listOf(inP0, inP0, inPosition(middle), inPosition(last)) else listOf(inP0, inPosition(middle), inPosition(last)),
        runOn = true, stopAtMixSec = release + 1.05, prerollSec = 0.025000009,
        levelGain = 0.9234567f, fadeOutTrackSec = null,
    )
    private val handoverTrack = inOrigin + (if (inLoop) {
        inSetupBase.loopEndFrame + inPosition(last) - requireNotNull(inSetupBase.releaseAtFrame)
    } else inPosition(last)) / sr
    val geometry = RenderGeometryV1(
        out = outSetup, inbound = inSetupBase.copy(fadeOutTrackSec = handoverTrack),
        handoverMixSec = release + 1.0, handoverTrackSec = handoverTrack,
        incomingStartMixSec = 0.5, incomingStartTrackSec = maxOf(0.0, handoverTrack - (release + 0.5)), highQuality = true,
    )

    fun region(deck: DeckTarget, g: RenderGeometryV1 = geometry): AudioRegionRef {
        val d = if (deck == DeckTarget.OUT) g.out else g.inbound
        fun ms(sec: Double) = (sec * 1000.0).roundToLong()
        return AudioRegionRef(deck, if (deck == DeckTarget.OUT) "out" else "in", ms(d.regionStartSec),
            ms(d.regionStartSec + d.frameCount.toDouble() / sr), sr, prerollMs = ms(d.prerollSec),
            stopAtMixMs = ms(d.stopAtMixSec), fadeOutTrackMs = d.fadeOutTrackSec?.let(::ms), levelGain = d.levelGain, runOn = d.runOn)
    }

    fun plan(recipe: RecipeV2 = requireNotNull(RecipeRegistry.builtIn("S5")), level: DjLevel = recipe.level): TransitionPlanV1 = TransitionPlanV1(
        planId = "frozen-${recipe.id}", generation = 7, outTrackId = "out", inTrackId = "in", djLevel = level,
        geometry = if (outLoop) TransitionGeometry.LOOP else TransitionGeometry.BRIDGE,
        frozenExitMs = 60_000, frozenEntryMs = ((inOrigin + inP0 / sr) * 1000.0).roundToLong(),
        swapDownbeatMs = 60_000 + (release * 500.0).roundToLong(), overlapDurationMs = (release * 1000.0).roundToLong(),
        barDurationMs = 2000, recipeId = recipe.id, recipeName = recipe.name,
        recipeParams = if (hold > 0.0) mapOf("incomingHoldSec" to hold) else emptyMap(),
    )

    fun script(g: RenderGeometryV1 = geometry): TransitionScriptV1 = TransitionScriptV1(
        planId = plan().planId, generation = 7, outRegion = region(DeckTarget.OUT, g), inRegion = region(DeckTarget.IN, g),
        envelopes = listOf(
            EnvelopeLane(DeckTarget.OUT, EnvelopeKind.GAIN, listOf(-g.out.prerollSec, 0.0, release), listOf(0f, 1f, 0f)),
            EnvelopeLane(DeckTarget.IN, EnvelopeKind.GAIN, listOf(0.0, release), listOf(0f, 1f)),
        ), renderGeometry = g,
    )
}
