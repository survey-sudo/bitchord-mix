package com.music.bitchord.playback.smart

/**
 * Every tunable the phrase planner reads. Where the two briefs disagreed, both
 * values are named and the default is the one the listening notes picked.
 * The planner logs the name of the value it actually used.
 */
object PhraseConfig {
    /** Master switch. False, or any thrown error, and the djmix6 planner runs. */
    const val PHRASE_PLANNER_ENABLED = true

    const val ALLOW_RADIO_EDIT = false

    /** One diagnostic line in the expanded Automix panel. The compact row is untouched. */
    const val DEBUG_PHRASE_LINE = true

    const val SONGMAP_SCHEMA = 1

    /** Spec 1 minimum score. Below this the djmix6 planner runs. */
    const val S_MIN = 0.55

    const val O_MAX_BARS = 32
    const val O_MAX_SEC = 40.0
    const val O_MIN_BARS = 4
    const val O_MIN_SEC = 6.0

    const val GROUP_SIM = 0.80
    const val NOVELTY_KERNEL_HALF_BARS = 4
    const val NOVELTY_KERNEL_HALF_BARS_WIDE = 8

    /** Spec 1 SSM mix was 0.5/0.5. Spec 2 is the default. */
    const val SSM_CHROMA = 0.45
    const val SSM_TIMBRE = 0.40
    const val SSM_ENERGY = 0.15
    const val SSM_CHROMA_SPEC1 = 0.50
    const val SSM_TIMBRE_SPEC1 = 0.50

    /** Spec 1 novelty was 0.5/0.25/0.25. Spec 2 adds bass and is the default. */
    const val NOV_SSM = 0.50
    const val NOV_ENERGY = 0.20
    const val NOV_VOCAL = 0.20
    const val NOV_BASS = 0.10

    const val CHROMA_ROTATION_PENALTY = 0.05
    const val DIAGONAL_PATH_BARS = 4

    const val LEN_COST_8 = 0.0
    const val LEN_COST_16 = 0.0
    const val LEN_COST_4 = 0.15
    const val LEN_COST_32 = 0.20
    const val LEN_COST_12 = 0.25
    const val LEN_COST_24 = 0.30
    const val LEN_COST_6 = 0.45
    const val LEN_COST_2 = 0.50
    const val LEN_COST_OTHER = 0.80
    const val LEN_COST_WEIGHT = 0.6
    const val LEN_MIN_BARS = 2
    const val LEN_MAX_BARS = 32

    /**
     * Tempo policy. "a" is the smootherstep bridge with B at 1.000× from −2 beats.
     * "b" (the default) puts the ramp on A, at most [TEMPO_STEP_PER_BAR], before the overlap.
     * Both hand B over at exactly 1.000×.
     */
    const val TEMPO_POLICY = "b"
    const val TEMPO_POLICY_BRIDGE = "a"
    const val TEMPO_STEP_PER_BAR = 0.005
    const val TEMPO_PREP_BARS = 16
    const val B_UNITY_BARS_BEFORE_LANDING = 2

    /** Bass-swap raised cosine. Drops, cuts and lifts use the short ramp. */
    const val SWAP_RAMP_MS = 40
    const val SWAP_RAMP_BLEND_MS = 120

    const val PHASE_GATE_LONG_MS = 15
    const val PHASE_GATE_SHORT_MS = 25
    const val PHASE_GATE_LONG_BARS = 8

    /** Echo delay in beats. At or under [ECHO_BPM_SPLIT] the dotted eighth; above it a straight eighth. */
    const val ECHO_DELAY_BEATS = 0.75
    const val ECHO_DELAY_BEATS_FAST = 0.5
    const val ECHO_DELAY_BEATS_ROLLING_FAST = 1.0
    const val ECHO_BPM_SPLIT = 128.0
    const val ECHO_HPF_HZ = 300.0
    const val ECHO_LPF_HZ = 6000.0
    const val ECHO_TAIL_CAP_SEC = 4.0
    const val ECHO_FEEDBACK = 0.45f
    const val ECHO_SEND_PEAK = 0.6f

    const val ENERGY_DIP_LEVELS = 2
    const val ENERGY_JUMP_LEVELS = 3
    const val OUTRO_ENERGY_MAX = 4
    const val LIFT_MIN_CHORUSES = 2
    const val LIFT_MIN_ENERGY = 5
    const val BALLAD_ENERGY = 0.3f

    const val PLAN_LEAD_SEC = 60.0
    const val REPLAN_SEC = 5.0
    const val LOCK_LEAD_SEC = 25.0
    const val PREP_LEAD_SEC = 30.0

    const val HEAD_STRUCTURE_SEC = 120.0
    const val HEAD_STRUCTURE_BARS = 64
    const val VOCAL_MASK_SEC = 120.0

    const val STRUCT_TIMEOUT_MS = 2_000L
    const val PLAN_TIMEOUT_MS = 40L
    const val ACTIVATION_FLOOR = 0.15f
    const val GRID_COARSE = 0.45f
    const val FADE_USABLE_BARS = 8
    const val FADE_MAX_DROP_DB = 6.0
    const val MIN_PLAY_FRACTION = 0.6

    const val FLANGER_MIN_MS = 1.0
    const val FLANGER_MAX_MS = 6.0
    const val FLANGER_LFO_BEATS = 8.0
    const val FLANGER_WET_BARS = 2
    const val FLANGER_HASH_MOD = 4

    fun lenCost(bars: Int): Double = when (bars) {
        8 -> LEN_COST_8
        16 -> LEN_COST_16
        4 -> LEN_COST_4
        32 -> LEN_COST_32
        12 -> LEN_COST_12
        24 -> LEN_COST_24
        6 -> LEN_COST_6
        2 -> LEN_COST_2
        else -> LEN_COST_OTHER
    }

    /** Which echo delay the planner should log and use. */
    fun echoDelayBeats(bpm: Double, rolling: Boolean): Pair<String, Double> = when {
        rolling && bpm > ECHO_BPM_SPLIT -> "ECHO_DELAY_BEATS_ROLLING_FAST" to ECHO_DELAY_BEATS_ROLLING_FAST
        bpm > ECHO_BPM_SPLIT -> "ECHO_DELAY_BEATS_FAST" to ECHO_DELAY_BEATS_FAST
        else -> "ECHO_DELAY_BEATS" to ECHO_DELAY_BEATS
    }

    fun swapRampMs(template: String): Pair<String, Int> = when (template) {
        "M7", "M8", "M10", "CUT_ON_ONE", "ENERGY_LIFT" -> "SWAP_RAMP_MS" to SWAP_RAMP_MS
        else -> "SWAP_RAMP_BLEND_MS" to SWAP_RAMP_BLEND_MS
    }
}
