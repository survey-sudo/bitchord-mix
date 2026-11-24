package com.music.bitchord.playback.djmix

import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.data.settings.MixDeck
import com.music.bitchord.data.settings.MixTransitionView
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Narrow host seam supplying playback state, analysis, fade configuration,
 * and coroutine scope to [MixViewPublisher] without exposing controller internals.
 *
 * Thread owner: invoked from the main/controller thread.
 */
internal interface MixViewHost {
    val scope: CoroutineScope
    val fadeCurves: FadeRideCurves
    val tempoEngine: EaseTempoEngine
    val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?

    fun nowMs(): Long
    fun positionMs(player: ExoPlayer): Double
    fun isSmartFadeActive(): Boolean
    fun isHandedOff(): Boolean
    fun isIdle(): Boolean

    val outgoingAnalysis: TrackAnalysis
    val incomingAnalysis: TrackAnalysis
    val planInFlight: TransitionPlan?
    val fadeMs: Long
    val fadeEndMs: Long
    val incomingCueTimeMs: Long
    val mediaRatio: Double

    fun currentRideSpec(): RideSpec
    fun rideSpecFor(plan: TransitionPlan, advanced: Boolean): RideSpec
}

/**
 * The overlap on each track's own timeline, as the mix popup draws it,
 * and the context shown either side of it. Fixed by the plan, so arming
 * and the blend agree on it.
 */
internal class MixGeometry(
    val outStart: Double,
    val outEnd: Double,
    val inStart: Double,
    val inEnd: Double,
    val ratio: Double,
) {
    val outSpan get() = outEnd - outStart
    val context get() = outSpan * MixViewPublisher.MIX_VIEW_CONTEXT
    val outFrom get() = outStart - context
    val outTo get() = outEnd + context
    val inFrom get() = inStart - context * ratio
    val inTo get() = inEnd + context * ratio
}

/** A decoded waveform and the stretch of its track, in seconds, it covers. */
internal class DeckWave(
    val mediaId: String,
    val from: Double,
    val to: Double,
    val bands: MixWaveform.Bands,
) {
    fun covers(id: String, start: Double, end: Double): Boolean =
        id == mediaId && from <= start + 0.05 && to >= end - 0.05
}

/**
 * Owns mix view publishing, mix preview layout, waveform prefetching and caching,
 * and real-time beat grid synchronization with [AppSettings] (M9 slice C-3).
 *
 * Responsibility: waveform decode lifecycle, preview/active blend layout generation,
 * and smart mix visual synchronization.
 * Thread owner: main/controller thread ([MainThread]). Background waveform decodes
 * dispatch on [Dispatchers.IO] via [MixViewHost.scope].
 */
@OptIn(UnstableApi::class)
internal class MixViewPublisher(private val host: MixViewHost) {

    /**
     * The waveforms for the next transition. Decoded ahead of it — see
     * [prefetchMixWaves] — so the popup has them the moment the blend starts.
     */
    var outgoingWave: DeckWave? = null
        private set
    var incomingWave: DeckWave? = null
        private set

    /** Which pair [mixViewJob] is decoding, so a tick does not start it twice. */
    var mixWavePair: String? = null
        private set

    /** The media ids [mixViewJob] has still to decode, which the popup shows as skeletons. */
    val pendingWaveIds = mutableSetOf<String>()

    /**
     * When a media id's waveform last failed to decode. Held off for
     * [MIX_WAVE_RETRY_MS] rather than retried on the next tick, which with the
     * popup open would be four failing decodes a second.
     */
    private val failedWaveAt = mutableMapOf<String, Long>()

    var mixViewJob: Job? = null
        private set

    /** The media ids of the blend [AppSettings.smartMixView] is showing. */
    private var mixViewIds: Pair<String, String>? = null

    /** The media ids of the pair [AppSettings.smartMixPreview] lays out. */
    private var mixPreviewIds: Pair<String, String>? = null

    /** When [AppSettings.smartMixPreview] was last planned, so one nothing is planning any more is let go. */
    private var mixPreviewAt = 0L

    /** What the last preview showed, so a tick that changes nothing does not republish it. */
    private var mixPreviewKey = ""

    private fun waveFailedRecently(id: String): Boolean =
        failedWaveAt[id]?.let { host.nowMs() - it < MIX_WAVE_RETRY_MS } == true

    /**
     * Computed every fade tick but *published* only when it has moved: the
     * scrubber runs its own beat clock and only leans on this anchor, so
     * re-sending the same grid thirty times a second just wakes every
     * collector for nothing. A new value goes out when the tempo changes, the
     * anchor drifts past [ANCHOR_TOLERANCE_NANOS] — a pause, a stall, the
     * grid moving to the other song — or playback starts or stops.
     */
    @MainThread
    fun publishBlend(out: ExoPlayer, into: ExoPlayer, force: Boolean = false) {
        if (!host.isSmartFadeActive()) return
        val session = if (host.isHandedOff()) into else out
        val playing = session.isPlaying
        val last = AppSettings.smartMixBlend.value
        // A paused blend's grid is frozen along with its players; re-deriving
        // it against a clock that keeps running would only slide the anchor.
        if (!force && last != null && !playing && !last.playing) return

        // Placed with [beatAt], the grid the phase lock itself steers by: beats
        // counted within the analyzer's downbeat bars, not off one interval from
        // the first beat. That constant grid drifts — a 0.1% tempo error is
        // 180ms three minutes in, a third of a beat, right where a blend runs —
        // so the pulse sat visibly off audio the lock had put exactly on it.
        // The rate is each player's real speed, which includes any phase-lock
        // nudge in flight rather than the stretch it was armed with.
        val sides = if (host.isHandedOff()) {
            listOf(host.incomingAnalysis to into, host.outgoingAnalysis to out)
        } else {
            listOf(host.outgoingAnalysis to out, host.incomingAnalysis to into)
        }
        var beatMs = 0f
        var anchor = 0L
        for ((analysis, player) in sides) {
            if (!host.tempoEngine.beatAt(analysis, host.positionMs(player) / 1000.0)) continue
            val rate = player.playbackParameters.speed.toDouble().takeIf { it > 0.0 } ?: 1.0
            val sinceBeat = host.tempoEngine.beatSinceSec
            beatMs = (host.tempoEngine.beatIntervalSec * 1000.0 / rate).toFloat()
            anchor = System.nanoTime() - (sinceBeat / rate * 1e9).toLong()
            break
        }
        if (!force && last != null && last.playing == playing && sameGrid(last, beatMs, anchor)) return
        AppSettings.smartMixBlend.value = MixBlend(
            beatMs = beatMs,
            beatAnchorNanos = anchor,
            playing = playing,
        )
    }

    /** Computes the overlap geometry and context windows on each deck's timeline. */
    fun mixGeometry(): MixGeometry {
        val outEnd = host.fadeEndMs / 1000.0
        val outStart = outEnd - (host.fadeMs / 1000.0).coerceAtLeast(0.5)
        val ratio = if (host.currentRideSpec().advanced) host.mediaRatio else 1.0
        val inStart = host.incomingCueTimeMs / 1000.0
        return MixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
    }

    /**
     * Decodes both waveforms the mix popup will draw, once the next
     * transition is [MIX_PREFETCH_PAD_SECONDS] away. Padded beyond the popup's own
     * context, since the plan can still shift a little before it arms; the
     * popup places each waveform by its time, so the extra is simply offscreen.
     *
     * Only the windows themselves are decoded, never the whole file, and on a
     * background thread long before they are needed: a blend can be over in
     * a few seconds, which is less than a decode deep into a file can take.
     */
    fun prefetchMixWaves(
        outItem: MediaItem,
        inItem: MediaItem,
        outDurationMs: Long,
        inDurationMs: Long,
        outStart: Double,
        outEnd: Double,
        inStart: Double,
        ratio: Double,
        wantOut: Boolean = true,
        wantIn: Boolean = true,
    ) {
        val span = (outEnd - outStart).coerceAtLeast(0.5)
        val pad = span * MIX_VIEW_CONTEXT + MIX_PREFETCH_PAD_SECONDS
        val outFrom = (outStart - pad).coerceAtLeast(0.0)
        val outTo = outEnd + pad
        val inFrom = (inStart - pad * ratio).coerceAtLeast(0.0)
        val inTo = inStart + (span + pad) * ratio
        val pair = "${outItem.mediaId}->${inItem.mediaId}"
        if (mixWavePair != null && mixWavePair != pair) failedWaveAt.clear()
        val outReady = !wantOut || waveFailedRecently(outItem.mediaId) ||
            outgoingWave?.covers(outItem.mediaId, outFrom + MIX_PREFETCH_PAD_SECONDS, outTo - MIX_PREFETCH_PAD_SECONDS) == true
        val inReady = !wantIn || waveFailedRecently(inItem.mediaId) ||
            incomingWave?.covers(inItem.mediaId, inFrom + MIX_PREFETCH_PAD_SECONDS, inTo - MIX_PREFETCH_PAD_SECONDS) == true
        if (outReady && inReady) return
        if (mixViewJob?.isActive == true && mixWavePair == pair) return
        mixViewJob?.cancel()
        mixWavePair = pair
        pendingWaveIds.clear()
        if (!outReady) pendingWaveIds += outItem.mediaId
        if (!inReady) pendingWaveIds += inItem.mediaId
        republishWaves()
        mixViewJob = host.scope.launch {
            suspend fun wave(item: MediaItem, durationMs: Long, from: Double, to: Double): DeckWave? =
                withContext(Dispatchers.IO) {
                    val began = host.nowMs()
                    val bins = ((to - from) * MIX_WAVE_BINS_PER_SECOND).toInt().coerceIn(64, MIX_WAVE_MAX_BINS)
                    val bands = runCatching { host.mixWaveform(item, durationMs, from, to, bins) }
                        .onFailure { Log.w(TAG, "mix waveform failed for ${item.mediaId}", it) }
                        .getOrNull()
                    Log.d(
                        TAG,
                        "mix waveform ${item.mediaId} " +
                            String.format(Locale.ROOT, "%.1f-%.1fs", from, to) +
                            ": ${if (bands == null) "none" else "ok"} in ${host.nowMs() - began}ms",
                    )
                    bands?.let { DeckWave(item.mediaId, from, to, it) }
                }
            if (!outReady) {
                outgoingWave = wave(outItem, outDurationMs, outFrom, outTo)
                if (outgoingWave == null) failedWaveAt[outItem.mediaId] = host.nowMs()
                pendingWaveIds -= outItem.mediaId
                republishWaves()
            }
            if (!inReady) {
                incomingWave = wave(inItem, inDurationMs, inFrom, inTo)
                if (incomingWave == null) failedWaveAt[inItem.mediaId] = host.nowMs()
                pendingWaveIds -= inItem.mediaId
                republishWaves()
            }
        }
    }

    /** Puts whatever waveforms have arrived into the views the popup can be showing. */
    private fun republishWaves() {
        AppSettings.smartMixView.value?.let { AppSettings.smartMixView.value = withWaves(it, mixViewIds) }
        AppSettings.smartMixPreview.value?.let { AppSettings.smartMixPreview.value = withWaves(it, mixPreviewIds) }
    }

    /** [view] with the decoded waveforms of [ids] in, and each deck marked while its own is still coming. */
    private fun withWaves(view: MixTransitionView, ids: Pair<String, String>?): MixTransitionView {
        return view.withDecks(
            view.outgoing.with(outgoingWave, ids?.first),
            view.incoming.with(incomingWave, ids?.second),
        )
    }

    /** Decorates a [MixDeck] with decoded waveform bands and in-flight decoding status. */
    fun MixDeck.with(wave: DeckWave?, id: String?): MixDeck {
        val decoded = if (wave != null && wave.mediaId == id) {
            withWave(wave.bands.low, wave.bands.mid, wave.bands.high, wave.from, wave.to)
        } else {
            this
        }
        return decoded.withDecoding(id != null && id in pendingWaveIds)
    }

    /**
     * Lays the next transition out for a mix popup opened by hand between
     * blends: the same decks, frame and faders the blend will show, from the
     * plan as it stands. Only while the popup is up — nobody else reads it —
     * and its waveforms are decoded now rather than [MIX_PREFETCH_PAD_SECONDS] out,
     * because someone is looking.
     *
     * A side still being measured goes out flagged and without numbers, and
     * the popup draws a skeleton for it; the frame it sits in may still move
     * as that measurement lands.
     */
    @MainThread
    fun publishMixPreview(
        currentItem: MediaItem,
        nextItem: MediaItem,
        duration: Long,
        nextDuration: Long,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
        state: SmartAnalysis,
        plan: TransitionPlan,
        advanced: Boolean,
    ) {
        if (!AppSettings.mixViewOpen.value || AppSettings.smartMixView.value != null) return
        mixPreviewAt = host.nowMs()
        val outStart = plan.transitionStart
        val outEnd = maxOf(plan.transitionEnd, outStart + 0.5)
        val inStart = plan.incomingCueTime
        val ratio = plan.incomingPlaybackRate / plan.outgoingPlaybackRate.coerceAtLeast(0.01)
        val geometry = MixGeometry(outStart, outEnd, inStart, inStart + (outEnd - outStart) * ratio, ratio)
        val currentAnalysing = state.current == TrackAnalysisState.WAITING || state.current == TrackAnalysisState.ANALYSING
        val nextAnalysing = state.next == TrackAnalysisState.WAITING || state.next == TrackAnalysisState.ANALYSING
        val beatSeconds = if (currentAnalysis.isUsable) plan.beatSeconds else 0.0
        val beats = if (beatSeconds > 0) (geometry.outSpan / beatSeconds).roundToInt() else 0

        // Before the waveforms, so a decode that starts now is marked on the decks below.
        prefetchMixWaves(
            currentItem, nextItem, duration, nextDuration, outStart, outEnd, inStart, ratio,
            wantOut = currentAnalysis.isUsable,
            wantIn = nextAnalysis.isUsable,
        )

        val ids = currentItem.mediaId to nextItem.mediaId
        val key = "${ids.first}|${ids.second}|${round(outStart * 20)}|${round(outEnd * 20)}|${round(inStart * 20)}|" +
            "$beats|$currentAnalysing|$nextAnalysing|${currentAnalysis.bpm}|${nextAnalysis.bpm}|" +
            "${currentAnalysis.key}|${nextAnalysis.key}|${plan.transitionStyle}|${plan.bassSwap}"
        if (key == mixPreviewKey && mixPreviewIds == ids && AppSettings.smartMixPreview.value != null) return
        mixPreviewKey = key

        val planned = host.rideSpecFor(plan, advanced)
        val samples = MIX_VIEW_FADER_POINTS
        val wallSpan = (plan.fadeMs * if (planned.advanced) ratio else 1.0).toFloat()
        val fadeOut = FloatArray(samples) {
            host.fadeCurves.outgoingLevel(planned, it / (samples - 1f), wallSpan).coerceIn(0f, 1f)
        }
        val fadeIn = FloatArray(samples) {
            host.fadeCurves.mixRise(planned, it / (samples - 1f)).coerceIn(0f, 1f)
        }

        fun deck(item: MediaItem, analysis: TrackAnalysis, durationMs: Long, analysing: Boolean, from: Double, to: Double) = MixDeck(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            bpm = if (analysis.isUsable) analysis.bpm else 0.0,
            key = if (analysis.isUsable) analysis.key else "",
            durationMs = durationMs.takeIf { it > 0L } ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
            analysing = analysing,
        )

        mixPreviewIds = ids
        AppSettings.smartMixPreview.value = withWaves(
            MixTransitionView(
                outgoing = deck(currentItem, currentAnalysis, duration, currentAnalysing, geometry.outFrom, geometry.outTo),
                incoming = deck(nextItem, nextAnalysis, nextDuration, nextAnalysing, geometry.inFrom, geometry.inTo),
                outStart = outStart,
                outEnd = outEnd,
                inStart = geometry.inStart,
                inEnd = geometry.inEnd,
                beats = beats,
                beatMarks = beatMarks(currentAnalysis, outStart, outEnd, beatSeconds),
                fadeOut = fadeOut,
                fadeIn = fadeIn,
                bassSwapAt = when {
                    plan.transitionStyle == TransitionStyle.DJ_FILTER &&
                        (plan.filterVariant == FilterTransitionVariant.BASS_HANDOFF || plan.filterVariant == FilterTransitionVariant.DECLINE) -> planned.filterSwapAtF
                    planned.bassSwap -> planned.swapAtF
                    else -> -1f
                },
            ),
            ids,
        )
    }

    /**
     * Lets the preview go once the popup is closed, a blend has taken over, or
     * nothing has planned the pair for a while — the queue ran out, a party
     * started, Automix was switched off. Only between blends: arming plans
     * nothing, and the preview is still the right picture until the blend
     * replaces it.
     */
    @MainThread
    fun expireMixPreview() {
        if (AppSettings.smartMixPreview.value == null) return
        val stale = host.isIdle() && host.nowMs() - mixPreviewAt > MIX_PREVIEW_STALE_MS
        if (AppSettings.mixViewOpen.value && AppSettings.smartMixView.value == null && !stale) return
        AppSettings.smartMixPreview.value = null
        mixPreviewIds = null
        mixPreviewKey = ""
    }

    /** Beats inside [outStart]..[outEnd] on [analysis]'s grid, as fractions of it, for the popup's grid lines. */
    fun beatMarks(analysis: TrackAnalysis, outStart: Double, outEnd: Double, beatSeconds: Double): FloatArray {
        if (beatSeconds <= 0) return FloatArray(0)
        val span = outEnd - outStart
        val anchor = analysis.downbeats.lastOrNull { it <= outStart } ?: outStart
        val first = anchor + ceil((outStart - anchor) / beatSeconds) * beatSeconds
        return generateSequence(first) { it + beatSeconds }
            .takeWhile { it <= outEnd + 1e-6 }
            .map { ((it - outStart) / span).toFloat() }
            .toList().toFloatArray()
    }

    /**
     * Lays the blend just started out for the mix popup: both tracks' cards,
     * where the overlap sits on each timeline, its beats and the faders it
     * will ride, with whichever waveforms have already been decoded.
     */
    @MainThread
    fun publishMixView(out: ExoPlayer, into: ExoPlayer) {
        val plan = host.planInFlight ?: return
        val outItem = out.currentMediaItem ?: return
        val inItem = into.currentMediaItem ?: return
        val geometry = mixGeometry()
        val outStart = geometry.outStart
        val outEnd = geometry.outEnd
        val outSpan = geometry.outSpan
        val beatSeconds = plan.beatSeconds
        val beats = if (beatSeconds > 0) (outSpan / beatSeconds).roundToInt() else 0
        val marks = beatMarks(host.outgoingAnalysis, outStart, outEnd, beatSeconds)
        val rideSpec = host.currentRideSpec()
        val wallSpan = (host.fadeMs * if (rideSpec.advanced) host.mediaRatio else 1.0).toFloat()
        val samples = MIX_VIEW_FADER_POINTS
        val fadeOut = FloatArray(samples) {
            host.fadeCurves.outgoingLevel(rideSpec, it / (samples - 1f), wallSpan).coerceIn(0f, 1f)
        }
        val fadeIn = FloatArray(samples) {
            host.fadeCurves.mixRise(rideSpec, it / (samples - 1f)).coerceIn(0f, 1f)
        }

        fun deck(item: MediaItem, player: ExoPlayer, analysis: TrackAnalysis, from: Double, to: Double) = MixDeck(
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString(),
            bpm = analysis.bpm,
            key = analysis.key,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: (analysis.duration * 1000).roundToLong(),
            waveStart = from,
            waveEnd = to,
        )

        AppSettings.smartMixProgress.value = 0f
        mixViewIds = outItem.mediaId to inItem.mediaId
        // The blend is the picture from here; a popup opened on the plan moves onto it.
        AppSettings.smartMixPreview.value = null
        mixPreviewIds = null
        mixPreviewKey = ""
        AppSettings.smartMixView.value = MixTransitionView(
            outgoing = deck(outItem, out, host.outgoingAnalysis, geometry.outFrom, geometry.outTo),
            incoming = deck(inItem, into, host.incomingAnalysis, geometry.inFrom, geometry.inTo),
            outStart = outStart,
            outEnd = outEnd,
            inStart = geometry.inStart,
            inEnd = geometry.inEnd,
            beats = beats,
            beatMarks = marks,
            fadeOut = fadeOut,
            fadeIn = fadeIn,
            bassSwapAt = when {
                rideSpec.style == TransitionStyle.DJ_FILTER && (rideSpec.filterVariant == FilterTransitionVariant.BASS_HANDOFF || rideSpec.filterVariant == FilterTransitionVariant.DECLINE) ->
                    rideSpec.filterSwapAtF
                rideSpec.bassSwap -> rideSpec.swapAtF
                else -> -1f
            },
        )
        republishWaves()
    }

    @MainThread
    fun clearMixView() {
        mixViewJob?.cancel()
        mixViewJob = null
        mixWavePair = null
        mixViewIds = null
        outgoingWave = null
        incomingWave = null
        pendingWaveIds.clear()
        failedWaveAt.clear()
        AppSettings.smartMixView.value = null
    }

    /** Whether [beatMs] and [anchor] describe the grid [last] already published, to within a tick's jitter. */
    fun sameGrid(last: MixBlend, beatMs: Float, anchor: Long): Boolean {
        if (beatMs <= 0f || last.beatMs <= 0f) return beatMs <= 0f && last.beatMs <= 0f
        if (abs(beatMs - last.beatMs) > beatMs * TEMPO_TOLERANCE) return false
        val beatNanos = (beatMs * 1_000_000.0).toLong().coerceAtLeast(1L)
        // Distance between the two anchors, the short way round one beat.
        val offset = Math.floorMod(anchor - last.beatAnchorNanos, beatNanos)
        return minOf(offset, beatNanos - offset) <= ANCHOR_TOLERANCE_NANOS
    }

    companion object {
        private const val TAG = "MixViewPublisher"
        const val MIX_VIEW_CONTEXT = 0.24
        const val MIX_WAVE_BINS_PER_SECOND = 40.0
        const val MIX_WAVE_MAX_BINS = 3_000
        const val MIX_PREFETCH_PAD_SECONDS = 3.0
        const val MIX_VIEW_FADER_POINTS = 64
        const val MIX_WAVE_RETRY_MS = 5_000L
        const val MIX_PREVIEW_STALE_MS = 1_500L
        const val ANCHOR_TOLERANCE_NANOS = 25_000_000L
        const val TEMPO_TOLERANCE = 0.005f
    }
}
