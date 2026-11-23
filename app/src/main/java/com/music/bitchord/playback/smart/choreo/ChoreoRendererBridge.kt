package com.music.bitchord.playback.smart.choreo

import android.util.Log
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.StemSet

/**
 * AUTOMIX-V2(E) -> C adapter: the ONLY place the choreographer touches stream
 * C's renderer additions (V2_CAPABILITIES, KIND_STEM_*, KIND_FILTER_RES ..
 * KIND_BRAKE, setDeckStems, setPhaser/Gate/Riser/Pump/Brake). A late rename
 * or renumbering in C is a fix in this file only.
 *
 * Worker thread only (called from LoopMixSession.build before prepare()).
 */
internal object ChoreoRendererBridge {
    private const val TAG = "BitChordChoreo"

    // Stream C's native capability bits (native/loopmix/dj_fx.h `Capability`),
    // which LoopMixRenderer.V2_CAPABILITIES mirrors. Translated to ChoreoCaps.
    private const val C_STEMS = 1 shl 0
    private const val C_FILTER_RES = 1 shl 1
    private const val C_PHASER = 1 shl 3
    private const val C_GATE = 1 shl 4
    private const val C_RISER = 1 shl 6
    private const val C_PUMP = 1 shl 7
    private const val C_BRAKE = 1 shl 8
    private const val C_LOUDNESS = 1 shl 10

    /** The renderer's v2 abilities in [ChoreoCaps] bits; 0 when the library or the constant is missing. */
    fun capabilities(): Int {
        if (!LoopMixRenderer.available) return 0
        val native = runCatching { LoopMixRenderer.v2Capabilities() }.getOrDefault(0) // AUTOMIX-V2(orch): runtime caps, not the compile-time const
        var caps = 0
        if ((native and C_STEMS) != 0) caps = caps or ChoreoCaps.STEMS
        if ((native and C_FILTER_RES) != 0) caps = caps or ChoreoCaps.FILTER_RES
        if ((native and C_PHASER) != 0) caps = caps or ChoreoCaps.PHASER
        if ((native and C_GATE) != 0) caps = caps or ChoreoCaps.GATE
        if ((native and C_RISER) != 0) caps = caps or ChoreoCaps.RISER
        if ((native and C_PUMP) != 0) caps = caps or ChoreoCaps.PUMP
        if ((native and C_BRAKE) != 0) caps = caps or ChoreoCaps.BRAKE
        if ((native and C_LOUDNESS) != 0) caps = caps or ChoreoCaps.LOUDNESS
        return caps
    }

    fun rendererKind(kind: EnvKind): Int = when (kind) {
        EnvKind.GAIN -> LoopMixRenderer.KIND_GAIN
        EnvKind.LOW_DB -> LoopMixRenderer.KIND_LOW_DB
        EnvKind.MID_DB -> LoopMixRenderer.KIND_MID_DB
        EnvKind.HIGH_DB -> LoopMixRenderer.KIND_HIGH_DB
        EnvKind.HIGH_PASS_HZ -> LoopMixRenderer.KIND_HIGH_PASS_HZ
        EnvKind.LOW_PASS_HZ -> LoopMixRenderer.KIND_LOW_PASS_HZ
        EnvKind.ECHO_SEND -> LoopMixRenderer.KIND_ECHO_SEND
        EnvKind.REVERB_SEND -> LoopMixRenderer.KIND_REVERB_SEND
        EnvKind.FLANGER_WET -> LoopMixRenderer.KIND_FLANGER_WET
        EnvKind.STEM_VOCALS -> LoopMixRenderer.KIND_STEM_VOCALS
        EnvKind.STEM_DRUMS -> LoopMixRenderer.KIND_STEM_DRUMS
        EnvKind.STEM_BASS -> LoopMixRenderer.KIND_STEM_BASS
        EnvKind.STEM_OTHER -> LoopMixRenderer.KIND_STEM_OTHER
        EnvKind.FILTER_RES -> LoopMixRenderer.KIND_FILTER_RES
        EnvKind.PHASER_WET -> LoopMixRenderer.KIND_PHASER_WET
        EnvKind.GATE_DEPTH -> LoopMixRenderer.KIND_GATE_DEPTH
        EnvKind.RISER_LEVEL -> LoopMixRenderer.KIND_RISER_LEVEL
        EnvKind.PUMP_DEPTH -> LoopMixRenderer.KIND_PUMP_DEPTH
        EnvKind.BRAKE -> LoopMixRenderer.KIND_BRAKE
    }

    /** Hands [stems] to deck [index]; false when the renderer refused (the deck then stays dry). */
    fun attachStems(renderer: LoopMixRenderer, index: Int, stems: StemSet?): Boolean {
        if (stems == null) return false
        return runCatching { renderer.setDeckStems(index, stems) }
            .onFailure { Log.w(TAG, "setDeckStems($index) failed", it) }
            .getOrDefault(false)
    }

    /** Back to a dry deck (contract: null = dry). */
    fun detachStems(renderer: LoopMixRenderer, index: Int) {
        runCatching { renderer.setDeckStems(index, null) }
    }

    /**
     * Writes [script] over whatever the vc80 code already set: every vc80 kind
     * on both decks (so the script owns the transition), v2 kinds only where
     * [caps] has the bit, stem kinds only on decks whose stems were attached.
     */
    fun apply(
        renderer: LoopMixRenderer,
        script: ChoreoScript,
        caps: Int,
        outStemsAttached: Boolean,
        inStemsAttached: Boolean,
    ) {
        writeDeck(renderer, LoopMixRenderer.OUT, script.outLanes, caps, outStemsAttached)
        writeDeck(renderer, LoopMixRenderer.IN, script.inLanes, caps, inStemsAttached)
        writeFx(renderer, LoopMixRenderer.OUT, script.outFx, caps)
        writeFx(renderer, LoopMixRenderer.IN, script.inFx, caps)
        if (script.isolate) {
            renderer.setIsolate(LoopMixRenderer.OUT, true)
            renderer.setIsolate(LoopMixRenderer.IN, true)
        }
        // AUTOMIX-V2(orch): the loudness guard (no dip / no bump across the overlap) and the -1 dBTP
        // true-peak limiter were built in C but never switched on. Choreographed transitions only, so
        // vc91 paths stay bit-identical; setLoudnessMatch also enables the true-peak limiter.
        if (ChoreoCaps.has(caps, ChoreoCaps.LOUDNESS)) {
            renderer.setLoudnessMatch(true, LOUDNESS_TARGET_LUFS)
        }
    }

    /** Static normalisation reference for the loudness guard's bump cap (streaming-typical). */
    private const val LOUDNESS_TARGET_LUFS = -14f

    private fun writeDeck(renderer: LoopMixRenderer, index: Int, lanes: Map<EnvKind, Env>, caps: Int, stems: Boolean) {
        for ((kind, env) in lanes) {
            if (kind.v2 && !ChoreoCaps.has(caps, kind.cap)) continue
            if (kind.isStem && !stems) continue
            renderer.setEnvelope(index, rendererKind(kind), env.times, env.values, env.exponential)
        }
    }

    private fun writeFx(renderer: LoopMixRenderer, index: Int, fx: DeckFx, caps: Int) {
        if (fx.echoDelaySec > 0.0) renderer.setEcho(index, fx.echoDelaySec, fx.echoFeedback, fx.echoWet)
        if (fx.reverb) renderer.setReverb(index, fx.reverbRoom, fx.reverbDamping, fx.reverbWet)
        if (fx.flanger) renderer.setFlanger(index, fx.flangerLfoHz, fx.flangerDepthSec, fx.flangerFeedback, fx.flangerWet)
        if (index == LoopMixRenderer.OUT) {
            // vc80 may have armed a roll / a buildup Q on the outgoing deck; the script decides now.
            if (fx.loopRoll) {
                renderer.setLoopRoll(index, true, fx.rollStartSec, fx.rollBeatSec, fx.rollReleaseSec, fx.rollTotalBeats)
            } else {
                renderer.setLoopRoll(index, false, 0.0, fx.rollBeatSec, 0.0, 0.0)
            }
            renderer.setSvfQ(index, fx.svfQ ?: 0.707f)
        } else {
            fx.svfQ?.let { renderer.setSvfQ(index, it) }
        }
        // v2 setters: only when the native side says it has them.
        if (fx.phaser && ChoreoCaps.has(caps, ChoreoCaps.PHASER)) {
            renderer.setPhaser(index, fx.phaserRateBeats, fx.phaserDepth, fx.phaserFeedback)
        }
        if (fx.gate && ChoreoCaps.has(caps, ChoreoCaps.GATE)) {
            renderer.setGate(index, fx.gateBeatSec, fx.gateDivision, fx.gateSwing)
        }
        if (fx.riser && ChoreoCaps.has(caps, ChoreoCaps.RISER)) {
            renderer.setRiser(index, fx.riserBeatSec, fx.riserLengthBeats)
        }
        if (fx.pump && ChoreoCaps.has(caps, ChoreoCaps.PUMP)) {
            renderer.setPump(index, fx.pumpBeatSec)
        }
        if (fx.brake && ChoreoCaps.has(caps, ChoreoCaps.BRAKE)) {
            renderer.setBrake(index, fx.brakeStartSec, fx.brakeLengthSec)
        }
    }
}
