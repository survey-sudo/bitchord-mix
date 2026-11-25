package com.music.bitchord.playback.djmix

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlin.math.roundToLong
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.isVideoOrigin


/** State observed or updated by [PrepAnalysisCoordinator]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface PrepAnalysisCoordinatorHost : CoordinatorCallbacks {
    val active: () -> ExoPlayer
    var analysisCollectorJob: Job?
    val analysisFor: (MediaItem) -> TrackAnalysis
    var analysisReplanJob: Job?
    val analysisRunningFor: (MediaItem) -> Boolean
    val analysisUpdates: SharedFlow<String>
    var fallbackNaturalGeneration: Long
    var lastPairAnalysisSignature: String?
    var lastSmartPlan: TransitionPlan?
    val lyricSpans: java.util.concurrent.ConcurrentHashMap<String, LyricSpans>
    val lyricsAsked: MutableSet<String>
    val lyricsAskedAt: java.util.concurrent.ConcurrentHashMap<String, Long>
    var naturalProvisionalUntilPosMs: Long
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    var planGeneration: Long
    var planResultGeneration: Long
    var plannedTransition: Pair<String, Long>?
    var replanning: Boolean
    val requestAnalysis: (MediaItem, Long) -> Unit
    val scope: CoroutineScope
    val syncedLines: suspend (MediaItem, Long) -> List<LyricLine>?
    val versionSwapActive: () -> Boolean
}

/** Coordinates analysis observation, debounced replanning, duration hints, and lyric requests. */
@MainThread
@OptIn(UnstableApi::class)
internal class PrepAnalysisCoordinator(private val host: PrepAnalysisCoordinatorHost) {
    /**
     * Collects [analysisUpdates] for the current pair in the controller's own
     * main-thread scope. Re-reads [analysisFor] through the ordinary planning
     * path on emission rather than waiting for the next IDLE tick.
     */
    fun startAnalysisCollector() {
        with(host) {
            analysisCollectorJob?.cancel()
            analysisCollectorJob = scope.launch {
                analysisUpdates.collect { trackId ->
                    if (trackId.isBlank()) return@collect
                    val pair = runCatching {
                        val p = active()
                        val cur = p.currentMediaItem?.mediaId
                        val idx = p.nextMediaItemIndex
                        val next = if (idx != C.INDEX_UNSET) p.getMediaItemAt(idx).mediaId else null
                        cur to next
                    }.getOrNull() ?: return@collect
                    if (trackId != pair.first && trackId != pair.second) return@collect
                    this@PrepAnalysisCoordinator.onPairAnalysisChanged(trackId)
                }
            }
        }
    }

    /**
     * A background analysis pass landed for one side of the pending pair.
     * Coalesces bursts, bumps the generation token so a plan computed against
     * older evidence is discarded, and re-plans only inside the pre-arm window:
     * a plan already committed or firing is never cancelled or blocked.
     */
    fun onPairAnalysisChanged(trackId: String) {
        with(host) {
            if (!isIdle()) return
            if (versionSwapActive() || ListenTogether.state.value.inParty) return
            // When MixExecutor + PairPreparer own planning, PairPreparer handles analysisUpdates directly.
            if (pairPreparer != null && runCatching { isNativeRendererAvailable(active()) }.getOrDefault(false)) return
            if (fallbackNaturalGeneration >= 0L) {
                val untilPos = naturalProvisionalUntilPosMs
                val pos = runCatching { active().currentPosition }.getOrNull()
                if (pos == null || pos < untilPos || untilPos < 0L) {
                    fallbackNaturalGeneration = -1L
                    naturalProvisionalUntilPosMs = -1L
                    lastSmartPlan = null
                    plannedTransition = null
                    AppSettings.smartTransitionWindow.value = null
                }
            }
            analysisReplanJob?.cancel()
            analysisReplanJob = scope.launch {
                delay(ANALYSIS_REPLAN_DEBOUNCE_MS)
                if (!isActive || !isIdle()) return@launch
                val token = ++planGeneration
                runCatching { this@PrepAnalysisCoordinator.replanForAnalysis(token) }
            }
        }
    }

    /** One plan pass for the current pair; the result is tagged with the token it used. */
    fun replanForAnalysis(token: Long) {
        with(host) {
            if (replanning) return
            replanning = true
            try {
                planAutoTransition(active())
                planResultGeneration = token
            } finally {
                replanning = false
            }
        }
    }

    /**
     * Queues the playing track and the one queued after it for analysis.
     *
     * Cheap no-ops once a track is analysed or already in flight; called every
     * tick so a track that finishes caching mid-song is picked up without a
     * separate trigger.
     *
     * Not folded into [considerSmartTransition], because the pair still needs
     * measuring in the one case that never plans a transition at all: a track
     * on repeat-one, which will hand over to this same next track as soon as
     * the loop is switched off.
     */
    fun requestAnalysisAround(player: ExoPlayer, duration: Long) {
        with(host) {
            val currentItem = player.currentMediaItem ?: return
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == C.INDEX_UNSET) return
            val nextItem = player.getMediaItemAt(nextIndex)
            if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return
            requestAnalysis(currentItem, duration)
            // djmix20 analyse-ahead (P0-1): next AND next+1 are queued
            // immediately (the architecture already supports next+1 lookahead).
            // The old 12 s post-mix settle gate starved the incoming entry half
            // (phrase-null tried=0 / before-smart fallback) whenever a skip or
            // quick mix followed a handover. TrackAnalyzer.request is a cheap
            // no-op when already analysed/in-flight, and head work runs on its
            // own single-thread executor, so eager queueing costs nothing when
            // warm and wins the whole lead when cold.
            requestAnalysis(nextItem, this@PrepAnalysisCoordinator.nextItemDurationMs(nextIndex, nextItem))
            this@PrepAnalysisCoordinator.askLyrics(currentItem, duration)
            this@PrepAnalysisCoordinator.askLyrics(nextItem, this@PrepAnalysisCoordinator.nextItemDurationMs(nextIndex, nextItem))
            val after = nextIndex + 1
            if (after < player.mediaItemCount) {
                val later = player.getMediaItemAt(after)
                if (!later.isVideoOrigin) {
                    val laterDuration = this@PrepAnalysisCoordinator.nextItemDurationMs(after, later)
                    this@PrepAnalysisCoordinator.askLyrics(later, laterDuration)
                    requestAnalysis(later, laterDuration)
                }
            }
        }
    }

    /**
     * Keeps the stats line describing the pair that is actually playing.
     *
     * Cheap enough to run unconditionally — two concurrent-map lookups and a
     * set membership test — and running it unconditionally is the point: any
     * gating reintroduces the staleness this exists to remove.
     */
    fun publishAnalysisState() {
        with(host) {
            val player = active()
            val currentItem = player.currentMediaItem
            val nextIndex = player.nextMediaItemIndex
            val nextItem = if (nextIndex == C.INDEX_UNSET) null else player.getMediaItemAt(nextIndex)
            val outAnalysis = currentItem?.let { analysisFor(it) }
            val inAnalysis = nextItem?.let { analysisFor(it) }
            AppSettings.smartAnalysis.value = SmartAnalysis(
                current = currentItem?.let { this@PrepAnalysisCoordinator.stateOf(it, outAnalysis ?: TrackAnalysis()) } ?: TrackAnalysisState.WAITING,
                next = nextItem?.let { this@PrepAnalysisCoordinator.stateOf(it, inAnalysis ?: TrackAnalysis()) } ?: TrackAnalysisState.WAITING,
            )
            // Safety net for hosts that do not pass [analysisUpdates]: the tick's
            // own observation of a fresher analysis feeds the same debounced
            // re-plan path, so an analysis landing mid-song still replaces a
            // provisional NATURAL instead of leaving it latched.
            val signature = listOf(
                currentItem?.mediaId.orEmpty(), nextItem?.mediaId.orEmpty(),
                outAnalysis?.status.orEmpty(), outAnalysis?.quality.orEmpty(),
                inAnalysis?.status.orEmpty(), inAnalysis?.quality.orEmpty(),
                outAnalysis?.bpm ?: 0.0, inAnalysis?.bpm ?: 0.0,
            ).joinToString("|")
            val previous = lastPairAnalysisSignature
            lastPairAnalysisSignature = signature
            if (previous != null && previous != signature) {
                // Either side of the pair changed; passing the outgoing id is enough
                // for onPairAnalysisChanged to recognise the pending pair.
                val pairId = currentItem?.mediaId ?: nextItem?.mediaId
                if (!pairId.isNullOrBlank()) this@PrepAnalysisCoordinator.onPairAnalysisChanged(pairId)
            }
        }
    }

    /**
     * Where one track stands, for the stats line. "Analysing" is asked for
     * first because a track can be in flight while a superseded provisional
     * result is already on record, and the work in progress is the more useful
     * thing to say about it.
     */
    fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = with(host) {
        when {
            // Usable first, and a pass in flight *second*. The other order was
            // right up to the point a head-only result started arriving before the
            // whole-track one: a track measured off its opening reads as analysed,
            // then finishes caching, then has the full pass run over it to replace
            // the provisional numbers — and reported "analysing" again throughout.
            // Going backwards from analysed reads as something having broken, when
            // what is happening is a better answer being computed. Confidence on one
            // such track went 0.39 to 0.94 and its cue moved from 0.1s to 9.5s.
            analysis.isUsable ->
                if (analysisRunningFor(item)) TrackAnalysisState.REFINING else TrackAnalysisState.ANALYSED
            analysisRunningFor(item) -> TrackAnalysisState.ANALYSING
            // A recorded-but-unusable result is the analyzer's way of saying it
            // tried and got nothing, and that it will not try again — it writes a
            // ready-but-empty entry precisely so the track stops being retried. A
            // track nothing has looked at yet has no status at all, which is the
            // only case that is still merely waiting.
            analysis.status == TrackAnalysis.STATUS_READY -> TrackAnalysisState.FAILED
            else -> TrackAnalysisState.WAITING
        }
    }

    /**
     * The next queue item's own duration.
     *
     * Media3 fills a timeline window's duration in when the item is *prepared*,
     * which for the track after this one happens a few seconds before it starts
     * playing. So for almost the whole of the current track this answered zero —
     * and zero is not a harmless "don't know" downstream. It reaches
     * [com.music.bitchord.playback.smart.TrackAnalyzer.request] as the next
     * track's duration, and with no duration to check a sibling copy against the
     * analyzer will only read the rendition the cache key resolves to *right
     * now*, which with source substitution on is the `#alt` entry — while the
     * copy actually on disk is the plain one its own head fetch just pulled
     * down. Nothing matches, the pass returns silently, and it does that on every
     * tick for the rest of the track. Measured: a fully cached next track sat
     * unread for three minutes and was analysed eight seconds before the fade it
     * was meant to inform, having been analysable the whole time.
     *
     * A zero here means *unknown*, not *no such track*: the analyser is still
     * asked about [item] by mediaId/trackId, so its cache's sibling-rendition
     * lookup runs regardless of the number returned. The runtime is on the item
     * already — queued from a row that knew it and carried on the playback URI
     * as `d=` (see `Song.matchQuery`) — and a stored analysis is another hint.
     * Both are hints, never preconditions: a missing one leaves 0 (unknown), and
     * analysis still proceeds with the only copy on disk.
     */
    fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long {
        with(host) {
            val timeline = active().currentTimeline
            if (!timeline.isEmpty) {
                timeline.getWindow(nextIndex, Timeline.Window()).durationMs
                    .takeIf { it != C.TIME_UNSET && it > 0 }
                    ?.let { return it }
            }
            return this@PrepAnalysisCoordinator.queuedDurationMs(item).takeIf { it > 0L }
                ?: (analysisFor(item).duration * 1000).roundToLong().takeIf { it > 0L }
                ?: 0L // unknown: requestAnalysis still runs, keyed by mediaId
        }
    }

    /**
     * The runtime the queue row carried, in milliseconds, or 0 when the item
     * doesn't state one — a local file, or a track queued without a duration.
     *
     * Deliberately forgiving: [Uri.getQueryParameter] throws on an opaque URI,
     * and a missing or unparsable value is simply an absent duration rather than
     * anything worth failing a tick over.
     */
    fun queuedDurationMs(item: MediaItem): Long {
        with(host) {
            val uri = item.localConfiguration?.uri ?: return 0L
            val seconds = runCatching { uri.getQueryParameter("d") }.getOrNull()?.toLongOrNull() ?: return 0L
            return if (seconds > 0) seconds * 1000L else 0L
        }
    }

    /** Asks for [item]'s synced lyrics once; a loop they vouch for needs no vocal mask. */
    fun askLyrics(item: MediaItem, durationMs: Long) {
        with(host) {
            val id = item.mediaId
            if (!lyricsAsked.add(id)) return
            lyricsAskedAt[id] = nowMs()
            scope.launch(Dispatchers.IO) {
                val lines = runCatching { syncedLines(item, durationMs) }.getOrNull()
                // Only line-synced lyrics say where a voice is; plain text says nothing about time.
                val spans = lines?.takeIf { found -> found.count { it.timeMs > 0 } >= 4 }?.let { found ->
                    LoopMix.sungSpans(
                        found.map { line ->
                            val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs
                            LoopMix.Line(
                                line.timeMs,
                                end,
                                blank = line.isGap || line.text.isBlank(),
                                wordEndMs = line.words.lastOrNull()?.endMs,
                            )
                        },
                    )
                }
                val cues = lines.orEmpty().map { line ->
                    val end = line.sungUntilMs ?: line.words.lastOrNull()?.endMs ?: (line.timeMs + 2_000L)
                    com.music.bitchord.playback.smart.LyricCue(
                        line.timeMs / 1000.0,
                        end / 1000.0,
                        line.text,
                    )
                }
                lyricSpans[id] = LyricSpans(spans, cues)
            }
        }
    }
}
