package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.SectionKind
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Legacy [Move] requirement summary (kept for diagnostic / test inspection).
 */
data class Requirements(
    val outStems: Int = 0,
    val inStems: Int = 0,
    val caps: Int = 0,
    val beatMatched: Boolean = false,
    val minBeatConf: Double = 0.0,
    val minOverlapBars: Double = 0.0,
    val maxTempoGap: Double = Double.POSITIVE_INFINITY,
    val harmonicCompatible: Boolean = false,
    val outLoops: Boolean = false,
)

/** One builder call's result: lanes + FX, before scoring/guarding. */
class Built(
    val outLanes: Map<EnvKind, Env>,
    val inLanes: Map<EnvKind, Env>,
    val outFx: DeckFx,
    val inFx: DeckFx,
    val outUsesStems: Boolean,
    val inUsesStems: Boolean,
    val isolate: Boolean,
    val note: String,
    val resolvedMove: Move? = null,
)

/**
 * The catalogue: gates, swap-beat windows, and step-list interpreter for every
 * [Recipe] in [Recipes] (and legacy [Move] wrappers).
 */
object MoveCatalog {
    const val KILL_DB = -24f
    /** A muffled incoming keeps some low end (the low-pass already thins it). */
    const val MUFFLE_LOW_DB = -12f

    fun requirementsOf(move: Move): Requirements = when (move) {
        Move.SIMPLE_FADE -> Requirements()
        Move.APPLE_STYLE_SMOOTH -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 2.0, maxTempoGap = 0.08)
        Move.STEM_BASS_SWAP -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 2.0, maxTempoGap = 0.08)
        Move.DRUMS_FIRST -> Requirements(outStems = 4, inStems = 4, caps = ChoreoCaps.STEMS, beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.06)
        Move.DRUM_SWAP -> Requirements(outStems = 4, inStems = 4, caps = ChoreoCaps.STEMS, beatMatched = true, minBeatConf = 0.55, minOverlapBars = 4.0, maxTempoGap = 0.06)
        Move.ACAPELLA_OVER_INSTRUMENTAL -> Requirements(outStems = 2, caps = ChoreoCaps.STEMS, beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.06, harmonicCompatible = true)
        Move.INSTRUMENTAL_BED -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 2.0, maxTempoGap = 0.06)
        Move.MUFFLE_IN -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 3.0, maxTempoGap = 0.08)
        Move.FILTER_WASH_ECHO_OUT -> Requirements(minBeatConf = 0.4, minOverlapBars = 0.0)
        Move.LOOP_EXTEND -> Requirements(beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.08, outLoops = true)
        Move.EDM_BUILD_DROP -> Requirements(minBeatConf = 0.5, maxTempoGap = 0.08)
        Move.BRAKE_CUT -> Requirements(caps = ChoreoCaps.BRAKE, minBeatConf = 0.35)
        Move.PHASER_DISSOLVE -> Requirements(caps = ChoreoCaps.PHASER, beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.08)
        Move.FLANGER_DISSOLVE -> Requirements(beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.08)
        Move.PUMP_BLEND -> Requirements(caps = ChoreoCaps.PUMP, beatMatched = true, minBeatConf = 0.55, minOverlapBars = 4.0, maxTempoGap = 0.06)
    }

    // ---- Recipe gating -----------------------------------------------------------------------

    /**
     * Null when [recipe] may be scored for [c]; otherwise the rejection reason.
     * When [forcedMode] is true (forced recipe / no-stem same-recipe fallback),
     * stem and non-essential cap gates are relaxed so [Recipe.noStemSteps] can run.
     */
    fun reject(recipe: Recipe, c: PairContext, forcedMode: Boolean = false): String? {
        val inputs = c.inputs
        if (inputs.level != recipe.minLevel) {
            return "level ${inputs.level} != ${recipe.minLevel}"
        }
        if (inputs.albumOrder) {
            val allowedOnAlbum = when (inputs.level) {
                DjLevel.SUBTLE -> recipe.id == RecipeId.S5 || recipe.id == RecipeId.S1
                DjLevel.CLUB -> recipe.id == RecipeId.C2
                DjLevel.FESTIVAL -> recipe.id == RecipeId.F2 || recipe.id == RecipeId.F5
            }
            if (!allowedOnAlbum) return "album order"
        }
        if (recipe.requiresOutLoop && !c.plan.outLoops) return "outgoing does not loop"
        if (recipe.requiresHarmony && !c.harmony.compatible) return "keys ${c.harmony}"
        if (recipe.requiresDrops) {
            if (recipe.requiresOutDrop) {
                if (c.inputs.outStructure.drops.isEmpty() || c.inputs.inStructure.drops.isEmpty()) {
                    return "both tracks need drops"
                }
            } else if (dropBeat(c) == null) {
                return "no incoming drop in the window"
            }
        }
        if (recipe.requiresBeatMatch && !c.beatMatched) return "not beat-matched"
        if (recipe.needsGridConfidence && c.beatConf < recipe.minBeatConf) {
            return "beat confidence ${c.beatConf}"
        }
        if (c.overlapBars + 1e-9 < recipe.minOverlapBars) return "overlap ${c.overlapBars} bars"
        if (recipe.requiresBeatMatch) {
            val cap = min(recipe.stretchCap, inputs.level.maxStretch)
            if (c.tempoGap > cap) return "tempo gap ${c.tempoGap} > $cap"
        }
        if (!forcedMode) {
            if (!ChoreoCaps.has(inputs.caps, recipe.requiredCaps)) {
                return "renderer lacks caps ${recipe.requiredCaps}"
            }
            if (recipe.requiresStems > 0) {
                val needIn = if (recipe.id == RecipeId.C3 || recipe.id == RecipeId.F4) 0 else recipe.requiresStems
                if (!stemsOk(inputs.outStems, recipe.requiresStems, inputs)) return "outgoing stems < ${recipe.requiresStems}"
                if (needIn > 0 && !stemsOk(inputs.inStems, needIn, inputs)) return "incoming stems < $needIn"
            }
            if (recipe.id == RecipeId.S5) {
                val compatible = !PairScorer.incompatible(c) && c.genre != GenreClass.BALLAD && !inputs.albumOrder
                if (compatible) return "simple fade reserved for ballads/incompatible pairs"
            }
            if (recipe.id == RecipeId.F6 && c.genre != GenreClass.DANCE) {
                return "pump is for house/EDM"
            }
            if (recipe.id == RecipeId.F7 && c.genre != GenreClass.HIPHOP && c.tempoGap <= 0.06) {
                return "spinback is for hip-hop/R&B or tempo jump"
            }
        }
        return when (recipe.id) {
            RecipeId.C3, RecipeId.F4 -> if (forcedMode) null else {
                val outV = c.mean(c.inAudibleAt, c.release) { c.outVocalAt(it) } ?: c.outVocalProb
                val inV = c.mean(c.inAudibleAt, c.release) { c.inVocalAt(it) } ?: c.inVocalProb
                when {
                    outV < 0.25 -> "outgoing not singing ($outV)"
                    inV > 0.25 && inputs.inStems?.vocalSplit != true && !(inputs.stemsExpected && inputs.stemsExpectedCount >= 2) ->
                        "incoming intro sings ($inV)"
                    else -> null
                }
            }
            RecipeId.C4 -> if (forcedMode) null else {
                if (inputs.outStems == null && !inputs.stemsExpected &&
                    c.inVocalEntry(c.inAudibleAt, c.release) == null && c.inVocalProb < 0.35
                ) "no incoming vocal entry" else null
            }
            else -> null
        }
    }

    /** Legacy [Move] wrapper for diagnostics. */
    fun reject(move: Move, c: PairContext): String? {
        val matches = Recipes.forMove(c.inputs.level, move)
        if (matches.isEmpty()) return "level ${c.inputs.level} < ${move.minLevel}"
        val reasons = matches.mapNotNull { reject(it, c) }
        return if (reasons.size < matches.size) null else reasons.firstOrNull()
    }

    private fun stemsOk(view: StemView?, need: Int, inputs: ChoreoInputs): Boolean {
        if (!AutomixV2Choreo.useStems) return false
        if (view != null) return view.stemCount >= need
        return inputs.stemsExpected && inputs.stemsExpectedCount >= need
    }

    // ---- swap-beat windows -------------------------------------------------------------------

    fun swapBeats(recipe: Recipe, c: PairContext): List<Int> {
        val kIn = c.kIn
        val kRel = c.kRel
        if (kRel <= 0) return emptyList()
        val out = ArrayList<Int>()
        when (recipe.swapRule) {
            SwapWindowRule.DROP_BEAT -> {
                dropBeat(c)?.let { out += it }
                return out
            }
            SwapWindowRule.DROP_OR_LATE -> {
                dropBeat(c)?.let { out += it }
                if (out.isNotEmpty()) return out
                val preferred = (kRel - 4).coerceAtLeast(kIn + 1).coerceAtMost(kRel - 1)
                out += preferred
                return out
            }
            SwapWindowRule.DROP_OR_MID -> {
                dropBeat(c)?.let { out += it }
                if (out.isNotEmpty()) return out
            }
            SwapWindowRule.FULL_FADE -> {
                return barLinesIn(kIn, kRel, kIn).ifEmpty { listOf(kIn) }
            }
            else -> Unit
        }
        if (recipe.cut) {
            val first = when (recipe.swapRule) {
                SwapWindowRule.CUT_BAR_5_OR_LATE -> max(kIn, min(kRel - 1, kIn + 16))
                else -> max(kIn + 1, min(kRel - 1, 4))
            }
            val last = max(first, kRel - 1)
            val lines = barLinesIn(first, last, kIn)
            if (lines.isNotEmpty()) return lines
            return listOf((kRel - 1).coerceAtLeast(0))
        }
        val span = kRel - kIn
        val mid = kIn + (span / 8) * 4
        val (lo, hi) = when (recipe.swapRule) {
            SwapWindowRule.BAR_9_OR_MID -> {
                val bar9 = kIn + 32
                if (bar9 <= kRel - 4) bar9 to bar9 else (kIn + 4) to (kRel - 4)
            }
            SwapWindowRule.BAR_9_OR_LATE -> {
                val bar9 = kIn + 32
                if (bar9 <= kRel - 4) bar9 to bar9 else (kIn + 4) to (kRel - 4)
            }
            SwapWindowRule.BAR_9_OR_FIRST_THIRD -> {
                val t3 = kIn + ((span / 3) / 4) * 4
                val target = max(kIn + 4, t3).coerceAtMost(max(kIn + 4, kRel - 4))
                target to target
            }
            SwapWindowRule.EARLY_PHRASE -> (kIn + 4) to max(kIn + 4, min(kRel - 4, kIn + 16))
            SwapWindowRule.LAST_PHRASE, SwapWindowRule.LATE_PHRASE -> {
                val late = max(kIn + 4, kRel - 4)
                late to late
            }
            else -> (kIn + 4) to (kRel - 4)
        }
        val lines = if (lo <= hi) barLinesIn(lo, hi, kIn) else emptyList()
        if (lines.isNotEmpty()) return lines
        val fallback = mid.coerceIn(kIn, max(kIn, kRel - 1))
        return listOf(fallback)
    }

    fun swapBeats(move: Move, c: PairContext): List<Int> {
        val recipe = Recipes.forMove(c.inputs.level, move).firstOrNull()
            ?: Recipes.ALL.firstOrNull { it.move == move }
            ?: return emptyList()
        return swapBeats(recipe, c)
    }

    private fun barLinesIn(lo: Int, hi: Int, kIn: Int): List<Int> {
        if (lo > hi) return emptyList()
        val out = ArrayList<Int>()
        var k = lo + ((4 - (((lo - kIn) % 4 + 4) % 4)) % 4)
        while (k <= hi) {
            out += k
            k += 4
        }
        return out
    }

    /** The mix beat whose incoming position is a drop, if one sits on a bar line inside the window. */
    fun dropBeat(c: PairContext): Int? {
        val drops = c.inputs.inStructure.drops
        if (drops.isEmpty()) return null
        val firstAllowed = max(c.kIn, 8)
        var k = firstAllowed
        while (k <= c.kRel - 1) {
            val t = c.t(k)
            val p = c.inPos(t)
            if (p != null) {
                val tol = max(0.06, c.beatSecAt(k) * 0.25)
                if (drops.any { abs(it - p) <= tol }) return k
            }
            k++
        }
        return null
    }

    // ---- Step-list interpreter ---------------------------------------------------------------

    fun build(
        recipe: Recipe,
        c: PairContext,
        swapBeat: Int,
        forcedMove: Move? = null,
    ): Built {
        val compiler = StepCompiler(recipe, c, swapBeat, forcedMove)
        return compiler.compile()
    }

    fun build(move: Move, c: PairContext, swapBeat: Int): Built {
        val recipe = Recipes.forMove(c.inputs.level, move).firstOrNull()
            ?: Recipes.ALL.firstOrNull { it.move == move }
            ?: Recipes.S1
        return build(recipe, c, swapBeat, forcedMove = move)
    }

    private class StepCompiler(
        val recipe: Recipe,
        val c: PairContext,
        val k: Int,
        val forcedMove: Move?,
    ) {
        val S: Double = c.t(k)
        val A: Double = c.inAudibleAt
        val R: Double = c.release
        val b: Double = c.beatSecAt(k)
        val bar: Double = b * 4.0
        val depth: Float = c.inputs.level.fxDepth
        val h: Double = LoopMix.HANDOVER_SEC
        val outStems: StemView? = c.inputs.outStems
        val inStems: StemView? = c.inputs.inStems
        val outFull: Boolean = AutomixV2Choreo.useStems && outStems?.full == true
        val inFull: Boolean = AutomixV2Choreo.useStems && inStems?.full == true
        val outSplit: Boolean = AutomixV2Choreo.useStems && outStems?.vocalSplit == true
        val inSplit: Boolean = AutomixV2Choreo.useStems && inStems?.vocalSplit == true
        val o = HashMap<EnvKind, Env>()
        val i = HashMap<EnvKind, Env>()
        var outFx = DeckFx.NONE
        var inFx = DeckFx.NONE
        var isolate = false
        var outStemsUsed = false
        var inStemsUsed = false
        var resolvedMove: Move = forcedMove ?: recipe.move

        fun byR(t: Double): Double = min(t, (R - 0.005).coerceAtLeast(0.0))

        private fun resolveAnchor(anchor: Anchor, param: Double): Double = when (anchor) {
            Anchor.ZERO -> 0.0
            Anchor.IN_AUDIBLE -> A
            Anchor.SWAP -> S
            Anchor.RELEASE -> R
            Anchor.OUT_STOP -> c.outStop
            Anchor.PRE_FRAC -> A + (S - A).coerceAtLeast(0.0) * param
            Anchor.POST_FRAC -> S + (R - S).coerceAtLeast(0.0) * param
            Anchor.OVERLAP_FRAC -> A + (R - A).coerceAtLeast(0.0) * param
            Anchor.A_PLUS_CAPPED_S -> min(S, A + param * bar)
            Anchor.S_PLUS_CAPPED_R -> min(R, S + param * bar)
            Anchor.SWAP_BACK_A -> max(A, S - param * bar)
            Anchor.SWAP_BACK_ZERO -> max(0.0, S - param * bar)
            Anchor.RELEASE_BACK_S -> max(S, R - param * bar)
            Anchor.VOCAL_ENTRY -> (c.inVocalEntry(A, R) ?: (A + min(bar, (S - A).coerceAtLeast(0.05) * 0.6))).coerceIn(
                min(A + 0.02, R - 0.05).coerceAtLeast(0.0),
                (R - 0.05).coerceAtLeast(0.0),
            )
            Anchor.FADE_START -> max(A, R - min(10.0, max(4.0, (R - A) * 0.8)))
        }

        private fun resolveTime(st: StepTime): Double {
            var t = resolveAnchor(st.anchor, st.param) + st.offsetBars * bar + st.offsetBeats * b + st.offsetSec
            st.minAt?.let { t = max(t, resolveAnchor(it, 0.0)) }
            st.maxAt?.let { t = min(t, resolveAnchor(it, 0.0)) }
            return t.coerceAtLeast(0.0)
        }

        private fun useStemMode(): Boolean {
            if (!AutomixV2Choreo.useStems) return false
            if (recipe.stemModeMinStems == 0) return outFull || inFull || outSplit || inSplit
            return when (recipe.id) {
                RecipeId.C1, RecipeId.F3, RecipeId.F8 -> outFull && inFull
                RecipeId.F1 -> inFull
                RecipeId.C3, RecipeId.F4 -> outSplit
                RecipeId.C4 -> outSplit || inFull
                else -> outFull || inFull
            }
        }

        fun compile(): Built {
            val useStems = useStemMode()
            val stepList = if (useStems) recipe.steps else recipe.noStemSteps
            for (step in stepList) {
                when (step) {
                    is RecipeStep.EnableIsolator -> isolate = true
                    is RecipeStep.SwapLows -> applySwapLows(step, useStems)
                    is RecipeStep.LaneCurve -> applyCurve(step.deck, step.kind, step.shape, step.points, step.steps, step.holdNeutralAtStart, step.ensureNeutralAtReleaseForIn)
                    is RecipeStep.ConfigureFx -> applyFx(step)
                }
            }
            for (kind in EnvKind.LEGACY) {
                if (!o.containsKey(kind)) o[kind] = Env.constant(kind.neutral, kind.exponential)
                if (!i.containsKey(kind)) i[kind] = Env.constant(kind.neutral, kind.exponential)
            }
            if (outStemsUsed) for (kind in STEM_KINDS) if (!o.containsKey(kind)) o[kind] = Env.constant(1f)
            if (inStemsUsed) for (kind in STEM_KINDS) if (!i.containsKey(kind)) i[kind] = Env.constant(1f)
            val stemTag = if (outStemsUsed || inStemsUsed) "stems" else "no-stem"
            val fxNames = (outFx.activeFxNames + inFx.activeFxNames).distinct()
            val fxTag = if (fxNames.isEmpty()) "dry" else fxNames.joinToString("+")
            return Built(o, i, outFx, inFx, outStemsUsed, inStemsUsed, isolate, "${recipe.id.code} ${recipe.displayName} ($stemTag, $fxTag) @${r1(S)}", resolvedMove)
        }

        private fun evalWaypoint(wp: Waypoint): Float {
            return if (wp.scaleByFxDepth) {
                if (wp.depthBase > 0f) wp.depthBase + wp.value * depth else wp.value * depth
            } else {
                wp.value
            }
        }

        private fun applyCurve(
            deck: DeckTarget,
            kind: EnvKind,
            shape: CurveShape,
            points: List<Waypoint>,
            steps: Int,
            holdNeutralAtStart: Boolean,
            ensureNeutralAtReleaseForIn: Boolean,
        ) {
            if (kind.isStem) {
                if (deck == DeckTarget.OUT) {
                    if ((kind == EnvKind.STEM_DRUMS || kind == EnvKind.STEM_BASS) && !outFull) return
                    if (!outSplit) return
                    outStemsUsed = true
                } else {
                    if ((kind == EnvKind.STEM_DRUMS || kind == EnvKind.STEM_BASS) && !inFull) return
                    if (!inSplit) return
                    inStemsUsed = true
                }
            }
            val map = if (deck == DeckTarget.OUT) o else i
            val eb = EnvBuilder(kind.exponential || shape == CurveShape.EXPONENTIAL)
            val n = points.size
            if (n == 0) return

            val resolvedTimes = DoubleArray(n) { idx ->
                val raw = resolveTime(points[idx].time)
                if (deck == DeckTarget.IN) {
                    val maxAllowed = (R - 0.005 - 0.001 * (n - 1 - idx)).coerceAtLeast(0.0)
                    raw.coerceIn(0.0, maxAllowed)
                } else {
                    val limit = if (kind == EnvKind.REVERB_SEND || kind == EnvKind.ECHO_SEND) c.outStop else R
                    raw.coerceIn(0.0, limit)
                }
            }
            // Enforce non-decreasing waypoint times before curve generation.
            for (idx in 1 until n) {
                if (resolvedTimes[idx] <= resolvedTimes[idx - 1]) {
                    val cap = if (deck == DeckTarget.IN) (R - 0.005 - 0.001 * (n - 1 - idx)).coerceAtLeast(0.0) else c.outStop
                    resolvedTimes[idx] = min(cap, resolvedTimes[idx - 1] + 0.001)
                }
            }

            if (deck == DeckTarget.OUT) {
                if (kind == EnvKind.GAIN) {
                    eb.at(-h, 0f).at(0.0, 1f)
                    val firstT = resolvedTimes[0]
                    if (firstT > 0.002) eb.at(firstT, 1f)
                } else if (holdNeutralAtStart && resolvedTimes[0] > 1e-4) {
                    eb.at(0.0, kind.neutral)
                    if (resolvedTimes[0] > 0.002) eb.at(resolvedTimes[0], kind.neutral)
                }
            } else {
                // Incoming deck: keep held silent prior to A for GAIN.
                if (kind == EnvKind.GAIN && resolvedTimes[0] > 0.0) {
                    eb.at(0.0, 0f)
                } else if (holdNeutralAtStart && resolvedTimes[0] > 1e-4 && kind != EnvKind.GAIN) {
                    eb.at(0.0, evalWaypoint(points[0]))
                }
            }

            if (shape == CurveShape.EQUAL_POWER_FALL && n >= 2) {
                for (idx in 0 until n) {
                    val tv = resolvedTimes[idx]
                    val vv = evalWaypoint(points[idx])
                    if (idx == 0) {
                        if (deck != DeckTarget.OUT || kind != EnvKind.GAIN || tv > 0.002) eb.at(tv, vv)
                    } else if (vv < evalWaypoint(points[idx - 1]) && tv > resolvedTimes[idx - 1] + 0.002) {
                        eb.fall(resolvedTimes[idx - 1], tv, gain0 = evalWaypoint(points[idx - 1]), steps = steps)
                    } else {
                        eb.at(tv, vv)
                    }
                }
            } else if (shape == CurveShape.EQUAL_POWER_RISE && n >= 2) {
                for (idx in 0 until n) {
                    val tv = resolvedTimes[idx]
                    val vv = evalWaypoint(points[idx])
                    if (idx == 0) {
                        eb.at(tv, vv)
                    } else {
                        val prevV = evalWaypoint(points[idx - 1])
                        if (vv > prevV && tv > resolvedTimes[idx - 1] + 0.002) {
                            eb.rise(resolvedTimes[idx - 1], tv, g0 = prevV, g1 = vv, steps = steps)
                        } else {
                            eb.at(tv, vv)
                        }
                    }
                }
            } else {
                for (idx in 0 until n) {
                    val tv = resolvedTimes[idx]
                    val vv = evalWaypoint(points[idx])
                    if (deck == DeckTarget.OUT && kind == EnvKind.GAIN && idx == 0 && tv <= 0.002 && abs(vv - 1f) <= 0.02f) {
                        continue
                    }
                    eb.at(tv, vv)
                }
            }

            if (deck == DeckTarget.IN && ensureNeutralAtReleaseForIn) {
                val built = eb.build()
                if (!ChoreoGuard.neutral(kind, built.lastValue) || !ChoreoGuard.neutral(kind, built.at(R))) {
                    eb.at(byR(R), kind.neutral)
                }
            }
            map[kind] = eb.build()
        }

        private fun applySwapLows(step: RecipeStep.SwapLows, useStems: Boolean) {
            val swapAt = resolveTime(step.at)
            val halfW = (b * step.widthBeats * 0.5).coerceIn(0.01, 0.12)
            val a = max(0.002, swapAt - halfW)
            val zOut = max(a + 0.002, swapAt + halfW)
            val zIn = byR(zOut).coerceAtLeast(min(a + 0.002, byR(R)))
            val aIn = min(a, (zIn - 0.002).coerceAtLeast(0.0))
            o[EnvKind.LOW_DB] = EnvBuilder().at(0.0, 0f).at(a, 0f).at(zOut, step.outLowAfterDb).build()
            i[EnvKind.LOW_DB] = EnvBuilder().at(0.0, step.inLowBeforeDb).at(aIn, step.inLowBeforeDb).at(zIn, 0f).build()
            if (step.useBassStems && useStems) {
                if (outFull) {
                    o[EnvKind.STEM_BASS] = EnvBuilder().at(0.0, 1f).at(a, 1f).at(zOut, 0f).build()
                    outStemsUsed = true
                }
                if (inFull) {
                    i[EnvKind.STEM_BASS] = EnvBuilder().at(0.0, 0f).at(aIn, 0f).at(zIn, 1f).build()
                    inStemsUsed = true
                }
            }
        }

        private fun applyFx(step: RecipeStep.ConfigureFx) {
            val caps = c.inputs.caps
            val useFallback = step.requiredCap != 0 && !ChoreoCaps.has(caps, step.requiredCap)
            val forceFlangerInC6 = recipe.id == RecipeId.C6 && step.fx == FxType.PHASER &&
                (forcedMove == Move.FLANGER_DISSOLVE ||
                    (forcedMove != Move.PHASER_DISSOLVE && ((c.inputs.pairKey.hashCode() xor c.inputs.seed.toInt()) and 1) != 0))
            val activeFx = when {
                forceFlangerInC6 -> FxType.FLANGER
                useFallback -> step.fallbackFx ?: return
                else -> step.fx
            }
            if (recipe.id == RecipeId.C6) {
                resolvedMove = if (activeFx == FxType.FLANGER) Move.FLANGER_DISSOLVE else Move.PHASER_DISSOLVE
            }
            val current = if (step.deck == DeckTarget.OUT) outFx else inFx
            val rockOrBallad = step.heavyReverbOnRockBallad && (c.genre == GenreClass.ROCK || c.genre == GenreClass.BALLAD)
            val updated = when (activeFx) {
                FxType.ECHO -> {
                    val delay = if (step.flag && c.outBpm <= 120.0) b else b * step.param1.coerceAtLeast(0.25)
                    val fb = step.param2.coerceIn(0.1f, 0.75f)
                    val wet = (step.param3 * depth).coerceIn(0.1f, 0.75f)
                    current.copy(echoDelaySec = delay, echoFeedback = fb, echoWet = wet)
                }
                FxType.REVERB -> {
                    val room = if (rockOrBallad) (step.param1.toFloat() * 1.25f).coerceAtMost(4.0f) else step.param1.toFloat()
                    val damp = if (rockOrBallad) (step.param2 * 0.85f).coerceAtLeast(0.25f) else step.param2
                    val wet = ((if (rockOrBallad) step.param3 * 1.25f else step.param3) * depth).coerceIn(0.1f, 0.8f)
                    current.copy(reverb = true, reverbRoom = room, reverbDamping = damp, reverbWet = wet)
                }
                FxType.FLANGER -> {
                    val lfo = LoopMix.lfoHzForBeatSec(b)
                    val fb = step.param2.takeIf { it > 0f } ?: LoopMix.FLANGER_FEEDBACK
                    val wet = min(0.5f, (step.param3.takeIf { it > 0f } ?: LoopMix.FLANGER_WET) * depth)
                    current.copy(
                        flanger = true,
                        flangerLfoHz = lfo,
                        flangerDepthSec = LoopMix.FLANGER_DEPTH_COLOUR_SEC,
                        flangerFeedback = fb,
                        flangerWet = wet,
                    )
                }
                FxType.PHASER -> {
                    current.copy(
                        phaser = true,
                        phaserRateBeats = step.param1.takeIf { it > 0.0 } ?: 8.0,
                        phaserDepth = (step.param2 * depth).coerceIn(0.1f, 1.0f),
                        phaserFeedback = step.param3,
                    )
                }
                FxType.GATE -> {
                    current.copy(
                        gate = true,
                        gateBeatSec = b,
                        gateDivision = step.param1.toFloat(),
                        gateSwing = step.param2,
                    )
                }
                FxType.RISER -> {
                    current.copy(
                        riser = true,
                        riserBeatSec = b,
                        riserLengthBeats = step.param1.takeIf { it > 0.0 } ?: 16.0,
                    )
                }
                FxType.PUMP -> {
                    current.copy(
                        pump = true,
                        pumpBeatSec = b,
                        pumpFullBand = step.flag,
                    )
                }
                FxType.BRAKE -> {
                    val sT = step.startTime?.let { resolveTime(it) } ?: max(0.0, S - b)
                    val eT = step.endTime?.let { resolveTime(it) } ?: S
                    val len = (eT - sT).coerceIn(0.15, if (step.flag) 1.5 else 2.5)
                    current.copy(
                        brake = true,
                        brakeStartSec = max(0.0, eT - len),
                        brakeLengthSec = len,
                        brakeSpinback = step.flag,
                    )
                }
                FxType.LOOP_ROLL -> {
                    val sT = step.startTime?.let { resolveTime(it) } ?: max(0.0, S - 2.0 * bar)
                    val eT = step.endTime?.let { resolveTime(it) } ?: S
                    val beats = ((eT - sT) / b).coerceIn(2.0, step.param1.takeIf { it > 0.0 } ?: 8.0)
                    current.copy(
                        loopRoll = true,
                        rollStartSec = sT,
                        rollBeatSec = b,
                        rollReleaseSec = eT,
                        rollTotalBeats = beats,
                    )
                }
                FxType.SVF_Q -> current.copy(svfQ = step.param3)
            }
            if (step.deck == DeckTarget.OUT) outFx = updated else inFx = updated

            val envKind = when (activeFx) {
                FxType.ECHO -> EnvKind.ECHO_SEND
                FxType.REVERB -> EnvKind.REVERB_SEND
                FxType.FLANGER -> EnvKind.FLANGER_WET
                FxType.PHASER -> EnvKind.PHASER_WET
                FxType.GATE -> EnvKind.GATE_DEPTH
                FxType.RISER -> EnvKind.RISER_LEVEL
                FxType.PUMP -> EnvKind.PUMP_DEPTH
                FxType.BRAKE -> EnvKind.BRAKE
                FxType.LOOP_ROLL, FxType.SVF_Q -> null
            }
            val curvePts = when {
                activeFx != step.fx && step.fallbackWetCurve != null -> step.fallbackWetCurve
                else -> step.wetCurve
            }
            if (envKind != null && curvePts != null && curvePts.isNotEmpty()) {
                val adjusted = if (rockOrBallad && activeFx == FxType.REVERB) {
                    curvePts.map { pt -> if (pt.value > 0f) pt.copy(value = (pt.value * 1.25f).coerceAtMost(0.75f)) else pt }
                } else {
                    curvePts
                }
                applyCurve(
                    deck = step.deck,
                    kind = envKind,
                    shape = CurveShape.LINEAR,
                    points = adjusted,
                    steps = 8,
                    holdNeutralAtStart = true,
                    ensureNeutralAtReleaseForIn = true,
                )
            }
        }

        private fun r1(v: Double): String = ((v * 10.0).toLong() / 10.0).toString()
    }

    private val STEM_KINDS = listOf(EnvKind.STEM_VOCALS, EnvKind.STEM_DRUMS, EnvKind.STEM_BASS, EnvKind.STEM_OTHER)

    /** Section roles that make a good exit (outgoing at the swap) and entry (incoming at its audible start). */
    fun exitRole(kind: SectionKind?): Double = when (kind) {
        SectionKind.OUTRO, SectionKind.INST -> 1.0
        SectionKind.CHORUS, SectionKind.DROP -> 0.6
        SectionKind.BRIDGE, SectionKind.BUILD -> 0.55
        null -> 0.5
        SectionKind.VERSE, SectionKind.PRE_CHORUS -> 0.25
        else -> 0.5
    }

    fun entryRole(kind: SectionKind?, move: Move): Double = when (kind) {
        SectionKind.INTRO, SectionKind.INST -> 1.0
        SectionKind.BUILD -> if (move == Move.EDM_BUILD_DROP || move == Move.LOOP_EXTEND) 1.0 else 0.8
        SectionKind.DROP -> if (move.cut) 1.0 else 0.5
        null -> 0.5
        SectionKind.VERSE -> if (move == Move.INSTRUMENTAL_BED) 0.9 else 0.35
        SectionKind.CHORUS -> if (move.cut) 0.7 else 0.35
        else -> 0.5
    }
}
