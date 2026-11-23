package com.music.bitchord.playback.smart

/**
 * Every tunable the phrase planner reads. Where the two briefs disagreed, both
 * values are named and the default is the one the listening notes picked.
 * The planner logs the name of the value it actually used.
 */
object PhraseConfig {
    /** Master switch. False, or any thrown error, and the classic planner runs. */
    const val PHRASE_PLANNER_ENABLED = true

    const val ALLOW_RADIO_EDIT = false

    /** One diagnostic line in the expanded Automix panel. The compact row is untouched. */
    const val DEBUG_PHRASE_LINE = true

    const val SONGMAP_SCHEMA = 1

    /** Spec 1 minimum score. Below this the classic planner runs. */
    const val S_MIN = 0.55

    const val O_MAX_BARS = 32
    const val O_MAX_SEC = 40.0
    const val O_MIN_BARS = 4
    const val O_MIN_SEC = 6.0

    const val GROUP_SIM = 0.80
    const val NOVELTY_KERNEL_HALF_BARS = 4
    const val NOVELTY_KERNEL_HALF_BARS_WIDE = 8

    /** Spec 2 mix (default); spec 1 used an even 0.5/0.5 split. */
    const val SSM_CHROMA = 0.45
    const val SSM_TIMBRE = 0.40
    const val SSM_ENERGY = 0.15
    const val SSM_CHROMA_SPEC1 = 0.50
    const val SSM_TIMBRE_SPEC1 = 0.50

    /** Spec 2 novelty (default); spec 1 used 0.5/0.25/0.25 without bass. */
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
    const val MAX_BPM_PCT_PER_BAR = TEMPO_STEP_PER_BAR
    const val TEMPO_PREP_BARS = 16
    const val B_UNITY_BARS_BEFORE_LANDING = 2

    /** Bass-swap raised cosine. Drops, cuts and lifts use the short ramp. */
    const val SWAP_RAMP_MS = 40
    const val SWAP_RAMP_BLEND_MS = 120

    /**
     * Graceful-exit guarantee: the outgoing deck may never go
     * audible→silence faster than the planned dissolve, and even emergency
     * paths (bail, fallback settle) take the shortened version. Planned
     * length is 2 beats with an 800 ms floor and a 2 s cap.
     */
    const val EXIT_TAIL_MIN_MS = 800.0
    const val EXIT_TAIL_EMERGENCY_MS = 350.0
    const val EXIT_TAIL_BEATS = 2.0
    const val EXIT_TAIL_MAX_SEC = 2.0

    /** Shared tempo discipline (after bkmasterplayer): beyond this post-fold
     * delta a long blend is never rampable -- downgrade to short/echo. */
    const val TEMPO_HARD_DELTA = 0.08
    /** Short-rung overlap cap for the downgraded rung. */
    const val TEMPO_HARD_MAX_BARS = 4
    /** Prep overrun tolerance (ms): a missed bar within this still arms. */
    const val PREP_TOLERANCE_MS = 50L

    /** Planned exit-dissolve length in seconds (800 ms floor, 2 s cap). */
    fun exitTailSeconds(beatSec: Double): Double =
        (EXIT_TAIL_BEATS * beatSec).coerceIn(EXIT_TAIL_MIN_MS / 1000.0, EXIT_TAIL_MAX_SEC)

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
    const val ECHO_FEEDBACK = 0.52f
    /** feedback tuning by fade length + template density. */
    const val ECHO_FEEDBACK_LONG = 0.55f
    const val ECHO_FEEDBACK_SHORT = 0.45f
    const val ECHO_SEND_PEAK = 0.6f
    /** reverb tuning for EDM buildups (plate). */
    const val REVERB_DAMPING_EDM = 0.48f
    const val REVERB_WET_EDM = 0.42f
    const val REVERB_DAMPING = 0.55f
    const val REVERB_WET = 0.35f

    /**
     * Echo density: long fades (>12s) ring fuller (0.55), short
     * (<=8s) sit tighter (0.45), mid keeps 0.52. CUT/ten-cut templates
     * get half the feedback (the cut itself is the gesture).
     */
    fun echoFeedbackFor(templateName: String, fadeSec: Double): Float {
        val base = when {
            fadeSec > 12.0 -> ECHO_FEEDBACK_LONG
            fadeSec <= 8.0 -> ECHO_FEEDBACK_SHORT
            else -> ECHO_FEEDBACK
        }
        return if (templateName == "M10" || templateName == "CUT_ON_ONE") base * 0.5f else base
    }

    const val ENERGY_DIP_LEVELS = 2
    const val ENERGY_JUMP_LEVELS = 3
    const val OUTRO_ENERGY_MAX = 4
    const val LIFT_MIN_CHORUSES = 2
    const val LIFT_MIN_ENERGY = 5
    const val BALLAD_ENERGY = 0.3f

    /** vocal continuity: a seam may not play more than this many
     * continuous no-vocal bars without trimming the entry or shortening. */
    const val VOCAL_GAP_SOFT_BARS = 8
    const val VOCAL_GAP_HARD_BARS = 16
    /** mid-line guard: extend to the line/section end by at most this. */
    const val LYRIC_EXTENSION_MAX_SEC = 6.0
    /** buildup: late/early energy ratio that counts as rising. */
    const val BUILDUP_RISE_MIN = 1.12

    const val PLAN_LEAD_SEC = 60.0
    const val REPLAN_SEC = 5.0
    const val LOCK_LEAD_SEC = 25.0
    const val PREP_LEAD_SEC = 30.0

    const val HEAD_STRUCTURE_SEC = 120.0
    const val HEAD_STRUCTURE_BARS = 64
    const val VOCAL_MASK_SEC = 120.0

    // A contended pass overshoots its nominal timeout by 4-6x, because the
    // checkpoints are coarse (fromPcm after frames(), novelty after the O(n^2)
    // similarity()). A shorter timeout published NO SongMap on a merely-busy
    // phone and left the phrase/loop planner with nothing, whereas a
    // slow-but-complete map degrades gracefully (coarse grid) instead of
    // vanishing.
    const val STRUCT_TIMEOUT_MS = 20_000L
    const val PLAN_TIMEOUT_MS = 120L
    const val ACTIVATION_FLOOR = 0.15f
    const val GRID_COARSE = 0.45f
    const val FADE_USABLE_BARS = 8
    const val FADE_MAX_DROP_DB = 6.0
    const val MIN_PLAY_FRACTION = 0.78

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
