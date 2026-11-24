package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/**
 * Native renderer envelope kind IDs. Matches 1:1 with `bitchord::loopmix::v2::Kind`
 * in `native/loopmix/dj_fx.h` (v2 kinds 9..19) and `LoopMixRenderer.KIND_*` (0..19).
 */
@Serializable
enum class EnvelopeKind(
    /** Native integer ID passed to `Renderer` / `LoopMixRenderer.setEnvelope`. */
    val nativeId: Int,
    /** Neutral bypass value for this automation lane. */
    val neutralValue: Float,
    /** True when interpolation defaults to the log/exponential domain (frequency sweeps). */
    val defaultExponential: Boolean = false,
) {
    /** Linear deck gain (0..1). Matches `LoopMixRenderer.KIND_GAIN = 0`. */
    GAIN(0, 1f),

    /** 250 Hz low-shelf gain in dB. Matches `LoopMixRenderer.KIND_LOW_DB = 1`. */
    LOW_DB(1, 0f),

    /** State-variable high-pass cutoff in Hz. Matches `LoopMixRenderer.KIND_HIGH_PASS_HZ = 2`. */
    HIGH_PASS_HZ(2, 20f, defaultExponential = true),

    /** State-variable low-pass cutoff in Hz. Matches `LoopMixRenderer.KIND_LOW_PASS_HZ = 3`. */
    LOW_PASS_HZ(3, 20_000f, defaultExponential = true),

    /** Ping-pong echo send (0..1). Matches `LoopMixRenderer.KIND_ECHO_SEND = 4`. */
    ECHO_SEND(4, 0f),

    /** LR4 isolator mid-band gain in dB. Matches `LoopMixRenderer.KIND_MID_DB = 5`. */
    MID_DB(5, 0f),

    /** LR4 isolator high-band gain in dB. Matches `LoopMixRenderer.KIND_HIGH_DB = 6`. */
    HIGH_DB(6, 0f),

    /** Jet flanger wet multiplier (0..1). Matches `LoopMixRenderer.KIND_FLANGER_WET = 7`. */
    FLANGER_WET(7, 0f),

    /** Reverb wash send (0..1). Matches `LoopMixRenderer.KIND_REVERB_SEND = 8`. */
    REVERB_SEND(8, 0f),

    /** Vocals stem linear gain. Matches `dj_fx.h` `KIND_STEM_VOCALS = 9`. */
    STEM_VOCALS(9, 1f),

    /** Drums stem linear gain. Matches `dj_fx.h` `KIND_STEM_DRUMS = 10`. */
    STEM_DRUMS(10, 1f),

    /** Bass stem linear gain. Matches `dj_fx.h` `KIND_STEM_BASS = 11`. */
    STEM_BASS(11, 1f),

    /** Other/accompaniment stem linear gain. Matches `dj_fx.h` `KIND_STEM_OTHER = 12`. */
    STEM_OTHER(12, 1f),

    /** Filter Q resonance multiplier (0.25..8). Matches `dj_fx.h` `KIND_FILTER_RES = 13`. */
    FILTER_RES(13, 1f),

    /** Phaser wet mix (0..1). Matches `dj_fx.h` `KIND_PHASER_WET = 14`. */
    PHASER_WET(14, 0f),

    /** Beat gate or stutter depth (0..1). Matches `dj_fx.h` `KIND_GATE_DEPTH = 15`. */
    GATE_DEPTH(15, 0f),

    /** Noise riser level (0..2). Matches `dj_fx.h` `KIND_RISER_LEVEL = 16`. */
    RISER_LEVEL(16, 0f),

    /** Sidechain-style beat pump depth (0..1). Matches `dj_fx.h` `KIND_PUMP_DEPTH = 17`. */
    PUMP_DEPTH(17, 0f),

    /** Turntable brake / spinback progress (0..1). Matches `dj_fx.h` `KIND_BRAKE = 18`. */
    BRAKE(18, 0f),

    /** DJM Color FX single filter knob (-1 LPF .. 0 flat .. +1 HPF). Matches `dj_fx.h` `KIND_FILTER_KNOB = 19`. */
    FILTER_KNOB(19, 0f),
    ;

    /** True when this lane controls a per-stem gain (requires attached stems on that deck). */
    val isStem: Boolean
        get() = nativeId in 9..12

    /** True when this lane requires a native v2 renderer capability. */
    val isV2: Boolean
        get() = nativeId >= 9

    companion object {
        private val BY_NATIVE_ID: Map<Int, EnvelopeKind> = entries.associateBy { it.nativeId }

        /** Looks up an [EnvelopeKind] by its native C++ `nativeId` (0..19), or null if unknown. */
        fun fromNativeId(nativeId: Int): EnvelopeKind? = BY_NATIVE_ID[nativeId]
    }
}

/**
 * Native renderer capability bitmask flags. Matches 1:1 with `bitchord::loopmix::v2::Capability`
 * in `native/loopmix/dj_fx.h` and `LoopMixRenderer.CAP_*`.
 */
@Serializable
enum class FxCapability(
    /** Bit flag in `Renderer::capabilities()`. */
    val bit: Int,
) {
    /** Per-deck stem mixing (`CAP_STEMS = 1 << 0`). */
    STEMS(1 shl 0),

    /** Resonant filter Q envelope (`CAP_FILTER_RES = 1 << 1`). */
    FILTER_RES(1 shl 1),

    /** DJM single bipolar filter knob (`CAP_FILTER_KNOB = 1 << 2`). */
    FILTER_KNOB(1 shl 2),

    /** 6-stage beat-synced phaser (`CAP_PHASER = 1 << 3`). */
    PHASER(1 shl 3),

    /** Beat-synced trance gate (`CAP_GATE = 1 << 4`). */
    GATE(1 shl 4),

    /** Live-capture slice stutter (`CAP_STUTTER = 1 << 5`). */
    STUTTER(1 shl 5),

    /** Tempo-synced noise/uplifter riser (`CAP_RISER = 1 << 6`). */
    RISER(1 shl 6),

    /** Beat-locked low-band or full-band pump (`CAP_PUMP = 1 << 7`). */
    PUMP(1 shl 7),

    /** Turntable vinyl brake / tape stop (`CAP_BRAKE = 1 << 8`). */
    BRAKE(1 shl 8),

    /** Reverse platter spinback (`CAP_SPINBACK = 1 << 9`). */
    SPINBACK(1 shl 9),

    /** BS.1770-4 short-term loudness matching and bus guard (`CAP_LOUDNESS = 1 << 10`). */
    LOUDNESS(1 shl 10),

    /** 4x oversampled true-peak limiter (`CAP_TRUE_PEAK = 1 << 11`). */
    TRUE_PEAK(1 shl 11),
    ;

    /** True when [capabilityMask] includes this capability bit. */
    fun isPresentIn(capabilityMask: Int): Boolean = (capabilityMask and bit) == bit

    companion object {
        /** Full v2 bitmask matching `bitchord::loopmix::v2::kCapabilities` (`(1 << 12) - 1`). */
        const val ALL_MASK: Int = (1 shl 12) - 1

        /** Encodes a set of capabilities into a native bitmask integer. */
        fun toMask(capabilities: Set<FxCapability>): Int =
            capabilities.fold(0) { acc, cap -> acc or cap.bit }

        /** Decodes a native bitmask integer into a set of [FxCapability] flags. */
        fun fromMask(capabilityMask: Int): Set<FxCapability> =
            entries.filterTo(mutableSetOf()) { it.isPresentIn(capabilityMask) }
    }
}

/** Reference to a decoded PCM audio region for one deck. */
@Serializable
data class AudioRegionRef(
    val deck: DeckTarget,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val sampleRateHz: Int = 48_000,
    val pcmPath: String? = null,
    val prerollMs: Long = 400L,
    val stopAtMixMs: Long? = null,
    val fadeOutTrackMs: Long? = null,
    val levelGain: Float = 1f,
    val runOn: Boolean = false,
) {
    init {
        require(startMs >= 0L) { "Region startMs must be non-negative, got $startMs" }
        require(endMs >= startMs) { "Region endMs ($endMs) must be >= startMs ($startMs)" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, got $sampleRateHz" }
    }
}

/** Loop boundaries and release instants (ms) for a looped transition. */
@Serializable
data class LoopBounds(
    val outLoopStartMs: Long? = null,
    val outLoopEndMs: Long? = null,
    val outReleaseAtMixMs: Long? = null,
    val inLoopStartMs: Long? = null,
    val inLoopEndMs: Long? = null,
    val inReleaseAtMixMs: Long? = null,
)

/** Piecewise-linear (or log-domain when [exponential]) automation lane over mix seconds. */
@Serializable
data class EnvelopeLane(
    val deck: DeckTarget,
    val kind: EnvelopeKind,
    val timesSec: List<Double>,
    val values: List<Float>,
    val exponential: Boolean = kind.defaultExponential,
) {
    init {
        require(timesSec.size == values.size && timesSec.isNotEmpty()) {
            "EnvelopeLane timesSec (${timesSec.size}) and values (${values.size}) must be non-empty and equal length"
        }
        for (i in 1 until timesSec.size) {
            require(timesSec[i] >= timesSec[i - 1]) {
                "EnvelopeLane timesSec must be non-decreasing at index $i: ${timesSec[i - 1]} -> ${timesSec[i]}"
            }
        }
    }
}

/** Reference to a separated stem PCM buffer aligned to a deck region. */
@Serializable
data class StemRegionRef(
    val deck: DeckTarget,
    val stem: StemKind,
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    val pcmPath: String? = null,
) {
    init {
        require(startMs >= 0L) { "Stem startMs must be non-negative, got $startMs" }
        require(endMs >= startMs) { "Stem endMs ($endMs) must be >= startMs ($startMs)" }
    }
}

/** BS.1770-4 loudness matching and -1 dBTP true-peak limiter configuration. */
@Serializable
data class LoudnessSettings(
    val enabled: Boolean = true,
    val targetLufs: Float = -14f,
    val truePeakLimiter: Boolean = true,
    val truePeakCeilingDbtp: Float = -1.0f,
    val dropBumpLu: Float = 0f,
)

/** Typed parameters for beat-locked loops (Loop extend / phrase loop). */
@Serializable
data class LoopFxParams(
    val enabled: Boolean = false,
    val bars: Int = 4,
    val seamCrossfadeMs: Double = 8.0,
    val holdPhrases: Int = 0,
)

/** Typed parameters for beat-quantized tightening loop rolls (1 -> 1/2 -> 1/4 -> 1/8 -> 1/16). */
@Serializable
data class RollFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val startMixSec: Double = 0.0,
    val releaseMixSec: Double = 0.0,
    val beatSec: Double = 0.5,
    val totalBeats: Double = 8.0,
    val scheduleBeats: List<Double> = listOf(4.0, 2.0, 1.0, 0.5, 0.25),
    val hpfStartHz: Float = 200f,
    val hpfEndHz: Float = 2_000f,
    val boostDb: Float = 2f,
    val stopOnDownbeat: Boolean = true,
)

/** Typed parameters for resonant HPF/LPF frequency sweeps. */
@Serializable
data class SweepFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val isHighPass: Boolean = true,
    val startHz: Float = 20f,
    val endHz: Float = 1_500f,
    val qStart: Float = 0.707f,
    val qEnd: Float = 1.6f,
    val bars: Int = 8,
    val exponential: Boolean = true,
)

/** Typed parameters for the tempo-synced stereo jet flanger. */
@Serializable
data class FlangerFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val lfoCyclesPerBars: Double = 2.0,
    val lfoHz: Double = 0.0,
    val depthSec: Double = 0.003,
    val feedback: Float = 0.64f,
    val wetPeak: Float = 0.6f,
    val stereoOffsetDeg: Float = 90f,
)

/** Typed parameters for the 6-8 stage stereo quadrature phaser. */
@Serializable
data class PhaserFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val stages: Int = 6,
    val rateBeats: Double = 4.0,
    val depth: Float = 0.7f,
    val feedback: Float = 0.6f,
    val wetPeak: Float = 0.7f,
    val stereoOffsetDeg: Float = 90f,
)

/** Typed parameters for the multi-layer tempo-synced build riser. */
@Serializable
data class RiserFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val beatSec: Double = 0.5,
    val lengthBeats: Double = 16.0,
    val noiseSweepStartHz: Float = 300f,
    val noiseSweepEndHz: Float = 8_000f,
    val noiseLevelPeak: Float = 0.7f,
    val uplifterOctaves: Float = 1.0f,
    val includeSnareRoll: Boolean = true,
    val reverseReverbTailBeats: Double = 1.0,
)

/** Typed parameters for the drop downbeat impact hit (low thump + short noise burst). */
@Serializable
data class ImpactFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.IN,
    val triggerMixSec: Double = 0.0,
    val peakDbfs: Float = -6.0f,
    val lowThumpHz: Float = 55f,
    val noiseBurstMs: Double = 180.0,
)

/** Typed parameters for pre-stretch exponential turntable brake. */
@Serializable
data class BrakeFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val startMixSec: Double = 0.0,
    val lengthSec: Double = 0.5,
    val endRate: Float = 0.05f,
    val lpfStartHz: Float = 18_000f,
    val lpfEndHz: Float = 800f,
    val endFadeMs: Double = 10.0,
)

/** Typed parameters for longer tape-stop pitch/speed decay with wow modulation. */
@Serializable
data class TapeStopFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val startMixSec: Double = 0.0,
    val lengthSec: Double = 1.0,
    val wowDepth: Float = 0.03f,
    val wowRateHz: Float = 5.0f,
    val lpfStartHz: Float = 18_000f,
    val lpfEndHz: Float = 600f,
    val endFadeMs: Double = 10.0,
)

/** Typed parameters for reverse-accelerating platter spinback + silence gap. */
@Serializable
data class SpinbackFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val startMixSec: Double = 0.0,
    val lengthSec: Double = 0.25,
    val peakReverseSpeed: Double = 3.0,
    val silenceGapBeats: Double = 1.0,
)

/** Typed parameters for tempo-synced stereo ping-pong echo out. */
@Serializable
data class EchoFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val delayBeats: Double = 0.5,
    val delaySec: Double = 0.0,
    val feedback: Float = 0.6f,
    val wet: Float = 0.55f,
    val wetHpfHz: Float = 250f,
    val wetLpfHz: Float = 6_000f,
    val pingPong: Boolean = true,
)

/** Typed parameters for tempo-synced trance gate or live-slice stutter. */
@Serializable
data class GateFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val beatSec: Double = 0.5,
    val divisionStepsPerBeat: Float = 4f,
    val swing: Float = 0f,
    val isStutter: Boolean = false,
    val depthPeak: Float = 1.0f,
    val rampBars: Double = 2.0,
)

/** Typed parameters for the pre-allocated stereo plate reverb wash. */
@Serializable
data class ReverbFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val roomSize: Float = 2.2f,
    val damping: Float = 0.55f,
    val wet: Float = 0.35f,
)

/** Typed parameters for beat-locked sidechain-style duck/pump. */
@Serializable
data class PumpFxParams(
    val enabled: Boolean = false,
    val deck: DeckTarget = DeckTarget.OUT,
    val beatSec: Double = 0.5,
    val fullBand: Boolean = false,
)

/**
 * Aggregated per-effect typed parameters for [TransitionScriptV1]. Covers all 12 Part A §5
 * effects (`Loop`, `Roll`, `Sweep`, `Flanger`, `Phaser`, `Riser`, `Impact`, `Brake`,
 * `TapeStop`, `Spinback`, `Echo`, `Gate`) plus reverb, pump, and isolator flags.
 */
@Serializable
data class ScriptFxParams(
    val loop: LoopFxParams? = null,
    val roll: RollFxParams? = null,
    val sweep: SweepFxParams? = null,
    val incomingSweep: SweepFxParams? = null,
    val flanger: FlangerFxParams? = null,
    val phaser: PhaserFxParams? = null,
    val riser: RiserFxParams? = null,
    val impact: ImpactFxParams? = null,
    val brake: BrakeFxParams? = null,
    val tapeStop: TapeStopFxParams? = null,
    val spinback: SpinbackFxParams? = null,
    val echo: EchoFxParams? = null,
    val gate: GateFxParams? = null,
    val reverb: ReverbFxParams? = null,
    val pump: PumpFxParams? = null,
    val isolate: Boolean = false,
)

/**
 * Complete, self-contained transition execution script (`schema = 1`).
 * Shared contract between the device renderer, host CLI `--render`, replay, and audition.
 */
@Serializable
data class TransitionScriptV1(
    val schema: Int = 1,
    val planId: String,
    val generation: Long,
    val sampleRateHz: Int = 48_000,
    val outRegion: AudioRegionRef,
    val inRegion: AudioRegionRef,
    val knots: List<TempoKnot> = emptyList(),
    val loopBounds: LoopBounds? = null,
    val envelopes: List<EnvelopeLane> = emptyList(),
    val fxParams: ScriptFxParams = ScriptFxParams(),
    val stems: List<StemRegionRef> = emptyList(),
    val loudness: LoudnessSettings = LoudnessSettings(),
    val dropWindowMs: TimeRangeMs? = null,
) {
    init {
        require(schema == 1) { "TransitionScriptV1 requires schema == 1, got $schema" }
        require(generation >= 0L) { "generation must be non-negative, got $generation" }
        require(sampleRateHz > 0) { "sampleRateHz must be positive, got $sampleRateHz" }
        require(outRegion.deck == DeckTarget.OUT) { "outRegion must target DeckTarget.OUT" }
        require(inRegion.deck == DeckTarget.IN) { "inRegion must target DeckTarget.IN" }
    }
}
