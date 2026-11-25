package com.music.bitchord.playback.smart

import com.music.bitchord.playback.smart.choreo.dbToLin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioMathAndKeyBoundaryTest {
    @Test
    fun equalPowerCurvesClampAndMeetAtMuteAndUnity() {
        for (p in listOf(-1f, 0f, 0.25f, 0.5f, 0.75f, 1f, 2f)) {
            assertEquals(1f, MixGain.powerSum(p), 2e-7f)
        }
        assertEquals(0f, MixGain.rise(-1f))
        assertEquals(1f, MixGain.rise(2f))
        assertEquals(1f, MixGain.fall(-1f))
        assertTrue(abs(MixGain.fall(2f)) < 1e-6f)
        assertEquals(1f, MixGain.headroomTrim(0f, 0f))
        assertEquals(MixGain.HEADROOM_FLOOR, MixGain.headroomTrim(1f, 1f))
        assertEquals(0f, dbToLin(Float.NEGATIVE_INFINITY))
        assertEquals(0f, dbToLin(-80f))
        assertEquals(0f, dbToLin(-81f))
        assertTrue(dbToLin(-79.999f) > 0f)
        assertEquals(1f, dbToLin(0f))
        assertEquals(0.5f, dbToLin(-6.0206f), 1e-6f)
        assertEquals(Double.NEGATIVE_INFINITY, RetireGuard.gainDb(0f))
        assertEquals(-80.0, RetireGuard.gainDb(0.0001f), 1e-5)
    }

    @Test
    fun sampledGainCurvesRetainTheirEndpointsWithMinimumSteps() {
        val (times, gains) = MixGain.risePoints(2.0, 3.0, steps = 0)
        assertTrue(times.contentEquals(doubleArrayOf(2.0, 3.0)))
        assertTrue(gains.contentEquals(floatArrayOf(0f, 1f)))
        val (_, falling) = MixGain.fallPoints(2.0, 3.0)
        assertEquals(1f, falling.first())
        assertTrue(abs(falling.last()) < 1e-6f)
    }

    private fun tone(amplitude: Float, size: Int = 48_000) =
        FloatArray(size) { (amplitude * sin(2 * PI * 1000 * it / 48_000)).toFloat() }

    @Test
    fun regionalLoudnessRejectsMuteMinus80DbAndInsufficientWindows() {
        for (samples in listOf(FloatArray(48_000), tone(0.0001f))) {
            assertNull(RegionalLoudness.lufs(samples, samples, 0, samples.size, 48_000))
            assertNull(RegionalLoudness.matchGain(samples, samples, 0, samples.size,
                tone(0.2f), tone(0.2f), 0, 48_000, 48_000))
        }
        val samples = tone(0.2f)
        assertNull(RegionalLoudness.lufs(samples, samples, 0, 9_599, 48_000))
        assertNull(RegionalLoudness.lufs(samples, samples, 0, samples.size, 0))
    }

    @Test
    fun regionalLoudnessMatchesUnityAndClampsBothGainExtremes() {
        fun gain(out: FloatArray, inn: FloatArray) = requireNotNull(
            RegionalLoudness.matchGain(out, out, 0, out.size, inn, inn, 0, inn.size, 48_000),
        ).toDouble()
        val quiet = tone(0.02f)
        val loud = tone(0.5f)
        assertEquals(1.0, gain(loud, loud), 1e-6)
        assertEquals(10.0.pow(4.5 / 20), gain(loud, quiet), 1e-6)
        assertEquals(10.0.pow(-3.5 / 20), gain(quiet, loud), 1e-6)
        val medium = tone(0.1f)
        val quieter = tone(0.08f)
        val delta = requireNotNull(RegionalLoudness.lufs(medium, medium, 0, 48_000, 48_000)) -
            requireNotNull(RegionalLoudness.lufs(quieter, quieter, 0, 48_000, 48_000))
        assertEquals(20 * kotlin.math.log10(1.25), delta, 1e-4)
    }

    @Test
    fun peakVerificationAcceptsTheCeilingAndRejectsTheNextFloat() {
        val ceiling = MixVerify.MAX_PEAK_LINEAR
        assertTrue(MixVerify.verify(0.0, ceiling, 0.0, 0).ok)
        val above = Float.fromBits(ceiling.toBits() + 1)
        val result = MixVerify.verify(0.0, above, 0.0, 0)
        assertFalse(result.ok)
        assertTrue(result.reasons.single().startsWith("peak "))
        assertTrue(MixVerify.verify(0.0, 0f, 0.0, 0).ok)
    }

    @Test
    fun unmeasuredPhraseLoudnessDefaultsToMinus14Lufs() {
        val section = PhraseSection(0, 4, SectionFn.INTRO, SectionRole.GROOVE, 1f,
            0, 0, 0.5f, 5, 0f, 0f, 0f, 0f, false)
        assertEquals(-14f, section.lufs)
    }

    @Test
    fun camelotDistanceIsSymmetricOverAll24Keys() {
        val keys = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
            .flatMap { listOf("$it major", "$it minor") }
        assertEquals(24, keys.map { camelotCode(it) }.toSet().size)
        for (a in keys) for (b in keys) {
            val distance = requireNotNull(camelotSteps(a, b))
            assertEquals(distance, camelotSteps(b, a), "$a / $b")
            assertTrue(distance in 0..7)
            assertEquals(camelotCompatible(requireNotNull(camelotCode(a)), requireNotNull(camelotCode(b))),
                camelotCompatible(requireNotNull(camelotCode(b)), requireNotNull(camelotCode(a))))
        }
    }

    @Test
    fun camelotWrapRelativeKeysEnharmonicsAndInvalidEdges() {
        assertEquals("12B", camelotCode("E major"))
        assertEquals("1B", camelotCode("B major"))
        assertEquals(1, camelotSteps("E major", "B major"))
        assertTrue(camelotCompatible("12A", "1A"))
        assertTrue(camelotCompatible("12B", "1B"))
        assertFalse(camelotCompatible("12A", "1B"))
        assertEquals(0, camelotSteps("C major", "A minor"))
        assertEquals(camelotCode("C# major"), camelotCode("D♭ major"))
        assertEquals(camelotCode("F# minor"), camelotCode(" F♯ min "))
        for (bad in listOf("", "H major", "C dorian", "1A", "0A", "13B")) {
            assertNull(camelotCode(bad))
            assertNull(camelotSteps(bad, "C major"))
        }
        for (bad in listOf("", "0A", "13B", "1C", "1a", "A")) {
            assertFalse(camelotCompatible(bad, "1A"))
            assertFalse(camelotCompatible("1A", bad))
        }
    }

    private fun choice() = PhraseChoice(
        template = MixTemplate.M1, rung = "R1", score = -0.12345,
        exit = ExitCue(ExitKind.OUTRO_START, 50, 100.1234, 4, 4, 0, SectionFn.OUTRO,
            SectionRole.GROOVE, 0.5f, 5, false, 0, 0),
        entry = EntryCue(EntryKind.HEAD, 0, 1.0, 2, SectionFn.INTRO, SectionRole.GROOVE,
            2, 0f, false, 0.4f, 4, 0, 0.5f),
        overlapBars = 4, swapBar = -2.0, tailBeats = 2.0, loopK = 0, echoGated = false,
        vocalProduct = 0.125f, tempoPrepBars = 0, echoDelayBeats = 0.75,
        echoDelayName = "dotted", swapRampMs = 40, swapRampName = "lin", tempoPolicy = "b",
        vocalOverlapBeats = 0.0, energyDelta = 0f, struct = 0.8, vocalScore = 0.7,
        phraseScore = 0.6, energyScore = 0.5, lengthScore = 0.4, keyScore = 0.3,
        tempoScore = 0.2, cost = 0.0,
    )

    private fun script(c: PhraseChoice = choice(), trim: Float = 0f, phase: Double = 25.0): PhraseScript {
        val base = TransitionMoves.compile(choice(), 120.0, 120.0)
        return PhraseScript(c, base.plan, base.outGain, base.inGain, base.outLow, base.inLow,
            base.outMid, base.inMid, base.outHigh, base.inHigh, base.outHp, base.inHp,
            base.outLp, base.echoSend, base.echoDelaySec, base.echoFeedback, base.incomingHoldSec,
            base.flanger, trim, phase)
    }

    @Test
    fun scriptJsonRetainsValidWireFormatAndTruncation() {
        val expected = """{"template":"M1","rung":"R1","score":-0.123,"conf":{"struct":0.8,"vocal":0.7,"phrase":0.6,"energy":0.5,"length":0.4,"key":0.3,"tempo":0.2},"exit":{"type":"OUTRO_START","section":"OUTRO","bar":50,"sec":100.123,"energy":5},"entry":{"type":"HEAD","section":"INTRO","bar":2,"sec":5.0,"energy":4,"pickup":0.5},"L":{"aSec":100.123,"bSec":5.0},"P":4,"T":2.0,"bpm":{"a":120.0,"b":120.0},"aRampBars":0,"camelot":-1,"tempoPolicy":"b","prepBars":0,"swapRamp":"lin","swapRampMs":40,"echo":"dotted","echoBeats":0.75,"vocalOverlapBeats":0.0,"bTrimDb":0.0,"phaseMs":25.0,"lockedAt":-1.0,"slides":0,"u":1,"g":2,"l":3,"v":0.125}"""
        assertEquals(expected, TransitionMoves.scriptJson(script(), 1, 2, 3, 120.0, 120.0))
        val defaults = Json.parseToJsonElement(TransitionMoves.scriptJson(script(), 0, 0, 0)).jsonObject
        assertEquals("-1", defaults.getValue("camelot").jsonPrimitive.content)
        assertFalse(defaults.getValue("camelot").jsonPrimitive.isString)
        assertEquals("-1.0", defaults.getValue("lockedAt").jsonPrimitive.content)
    }

    @Test
    fun scriptJsonEscapesAllFreeTextWithoutInjectingFields() {
        val special = "quote\" slash\\ newline\n tab\t return\r back\b control\u0001 → ♭"
        val c = choice().copy(rung = special, tempoPolicy = special, swapRampName = special,
            echoDelayName = "\"},\"injected\":true,\"x\":\"" + special)
        val json = TransitionMoves.scriptJson(script(c), 1, 2, 3)
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(special, parsed.getValue("rung").jsonPrimitive.content)
        assertEquals(special, parsed.getValue("tempoPolicy").jsonPrimitive.content)
        assertEquals(special, parsed.getValue("swapRamp").jsonPrimitive.content)
        assertEquals(c.echoDelayName, parsed.getValue("echo").jsonPrimitive.content)
        assertFalse("injected" in parsed)
        assertFalse('\n' in json)
        assertFalse('\u0001' in json)
    }

    @Test
    fun scriptJsonRejectsEveryNonFiniteFloatingField() {
        val c = choice()
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val variants = listOf(c.copy(score = bad), c.copy(struct = bad), c.copy(vocalScore = bad),
                c.copy(phraseScore = bad), c.copy(energyScore = bad), c.copy(lengthScore = bad),
                c.copy(keyScore = bad), c.copy(tempoScore = bad), c.copy(tailBeats = bad),
                c.copy(echoDelayBeats = bad), c.copy(vocalOverlapBeats = bad),
                c.copy(vocalProduct = bad.toFloat()), c.copy(exit = c.exit.copy(releaseSec = bad)),
                c.copy(entry = c.entry.copy(startSec = bad)),
                c.copy(entry = c.entry.copy(pickupBeats = bad.toFloat())))
            for (variant in variants) {
                assertFailsWith<IllegalArgumentException> { TransitionMoves.scriptJson(script(variant), 0, 0, 0) }
            }
            assertFailsWith<IllegalArgumentException> { TransitionMoves.scriptJson(script(), 0, 0, 0, bpmA = bad) }
            assertFailsWith<IllegalArgumentException> { TransitionMoves.scriptJson(script(), 0, 0, 0, bpmB = bad) }
            assertFailsWith<IllegalArgumentException> { TransitionMoves.scriptJson(script(), 0, 0, 0, lockedAt = bad) }
            assertFailsWith<IllegalArgumentException> { TransitionMoves.scriptJson(script(trim = bad.toFloat()), 0, 0, 0) }
            assertFailsWith<IllegalArgumentException> { TransitionMoves.scriptJson(script(phase = bad), 0, 0, 0) }
        }
    }
}
