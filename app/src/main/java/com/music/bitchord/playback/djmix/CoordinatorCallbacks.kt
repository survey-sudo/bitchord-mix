package com.music.bitchord.playback.djmix

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import androidx.annotation.OptIn


/** Controller-owned operations shared by the extracted coordinators. */
@OptIn(UnstableApi::class)
internal interface CoordinatorCallbacks {
    val nowMs: () -> Long
    var opportunity: Opportunity?
    var planRevision: Int
    fun acceptRoute(plan: Any, fields: Map<String, Any?>): Unit
    fun appSettingsMarkerShown(): Boolean
    fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>): Unit
    fun armDeath(reason: String): Boolean
    fun armLoop(session: LoopMixSession, out: ExoPlayer, into: ExoPlayer, nextIndex: Int, outPort: LoopMixPort, inPort: LoopMixPort): Unit
    fun askLyrics(item: MediaItem, durationMs: Long): Unit
    fun bail(force: Boolean = false): Unit
    fun begin(fade: Long, endMs: Long, smart: Boolean, cueTimeMs: Long = 0L, playbackRate: Double = 1.0, outgoingRate: Double = 1.0, beatMs: Long = 0L, renderStyle: RideSpec = RideSpec(), plan: TransitionPlan? = null): Boolean
    fun beginSleepFadeOut(fade: Long): Unit
    fun beginTailOrFinish(out: ExoPlayer, reason: String = "planned", retireSite: String = "driveFade-done"): Unit
    fun cancelArming(reason: String): Unit
    fun cancelAutoMixVisual(): Unit
    fun captureLoopExitObservation(session: LoopMixSession): Unit
    fun checkHardWatchdog(): Unit
    fun classicActiveFxNames(r: RideSpec): List<String>
    fun classicFallbackOnRendererUnavailable(player: ExoPlayer, currentItem: MediaItem, nextItem: MediaItem, duration: Long, nextDuration: Long, reason: String, markerOnly: Boolean, failedPlan: TransitionPlanV1?): Unit
    fun classicRouteFields(plan: TransitionPlan?): Map<String, Any?>
    fun clearAutoMixVisual(): Unit
    fun clearMixView(): Unit
    fun closeLater(renderer: LoopMixRenderer): Unit
    fun configuredFadeMs(): Long
    fun considerSmartTransition(duration: Long, markerOnly: Boolean = false): Unit
    fun disarmTailLoopSession(): Unit
    fun discardPrime(): Unit
    fun dispatchPhaseTick()
    fun driveArming(): Unit
    fun ensureOpportunity(outId: String, inId: String): Opportunity
    fun expireIdleOpportunity(reason: String): Unit
    fun expireMixPreview(): Unit
    fun fadeFor(duration: Long): Long
    fun finish(): Unit
    fun finishLoop(): Unit
    fun frozenPreparedFor(outItem: MediaItem, inItem: MediaItem, generation: Long, sampleRate: Int, level: com.music.bitchord.playback.smart.choreo.DjLevel = AppSettings.automixDjLevel.value): PreparedPair?
    fun handOff(out: ExoPlayer, into: ExoPlayer, visualAudioSeconds: Double? = null, visualEndSeconds: Double? = null): Unit
    fun holdAhead(player: ExoPlayer, duration: Long): Unit
    fun holdAtEnd(out: ExoPlayer): Unit
    fun isArming(): Boolean
    fun isBailing(): Boolean
    fun isFading(): Boolean
    fun isIdle(): Boolean
    fun isLoopArming(): Boolean
    fun isLoopRunning(): Boolean
    fun isNativeRendererAvailable(player: ExoPlayer): Boolean
    fun isStraightRoute(plan: LoopMix.Plan): Boolean
    fun isTail(): Boolean
    fun leavingOnBail(): ExoPlayer?
    fun listenTo(target: ExoPlayer): Unit
    fun loopBail(reason: String, settle: Boolean = true, preserveSeek: Boolean = false, deferDisarm: Boolean = false): Unit
    fun loopBail(reason: String, settle: Boolean = true, preserveSeek: Boolean = false, deferDisarm: Boolean = false, force: Boolean = false): Unit
    fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity? = opportunity): Unit
    fun mixGeometry(): MixGeometry
    fun nativeRouteFields(plan: LoopMix.Plan, outLoops: Boolean? = null, inLoops: Boolean? = null, actualConfiguration: Boolean = false, coarseDecline: Boolean? = null): Map<String, Any?>
    fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean? = null): Map<String, Any?>
    fun nativeUnavailabilityReason(player: ExoPlayer): String
    fun nextItemDurationMs(nextIndex: Int, item: MediaItem): Long
    fun notePrep(player: ExoPlayer): Unit
    fun onArming()
    fun onBailing()
    fun onFading()
    fun onIdle()
    fun onLoopArming()
    fun onLoopRunning()
    fun onSleepFade()
    fun onTail()
    fun openFiltersWhenSilent(): Unit
    fun openSpareFiltersWhenSilent(): Unit
    fun openSurvivorFiltersWhenRetireeSilent(): Unit
    fun opportunityFields(context: Opportunity?): Map<String, Any?>
    fun planAgeMs(now: Long = nowMs()): Long?
    fun planAutoTransition(player: ExoPlayer): Unit
    fun positionMs(player: ExoPlayer): Double
    fun prefetchMixWaves(outItem: MediaItem, inItem: MediaItem, outDurationMs: Long, inDurationMs: Long, outStart: Double, outEnd: Double, inStart: Double, ratio: Double, wantOut: Boolean = true, wantIn: Boolean = true): Unit
    fun preparedRoute(fields: Map<String, Any?>, context: Opportunity? = opportunity, revision: Int = planRevision, generation: Int? = null): Unit
    fun primeKey(plan: LoopMix.Plan, outItem: MediaItem, inItem: MediaItem, sampleRate: Int): String
    fun publishAnalysisState(): Unit
    fun publishAutomixDetail(): Unit
    fun publishBlend(out: ExoPlayer, into: ExoPlayer, force: Boolean = false): Unit
    fun publishMixPreview(currentItem: MediaItem, nextItem: MediaItem, duration: Long, nextDuration: Long, currentAnalysis: TrackAnalysis, nextAnalysis: TrackAnalysis, state: SmartAnalysis, plan: TransitionPlan, advanced: Boolean): Unit
    fun publishMixView(out: ExoPlayer, into: ExoPlayer): Unit
    fun queuedDurationMs(item: MediaItem): Long
    fun ranOn(out: ExoPlayer): Boolean
    fun reconcileQueue(out: ExoPlayer, into: ExoPlayer): Boolean
    fun refusalCode(reason: String): String
    fun releaseHold(): Unit
    fun rememberLoopPair(outItem: MediaItem, inItem: MediaItem, outAnalysis: TrackAnalysis, inAnalysis: TrackAnalysis, durationMs: Long, nextDurationMs: Long): Unit
    fun renderFor(plan: TransitionPlan, advanced: Boolean): RideSpec
    fun requestAnalysisAround(player: ExoPlayer, duration: Long): Unit
    fun reviseRoute(plan: Any): Int
    fun routeRefusal(name: String, reason: String, stage: String, technique: String, destination: String? = null, context: Opportunity? = opportunity, revision: Int = planRevision, generation: Int? = null, coarseDecline: Boolean? = null): Unit
    fun sameVisualQueueItem(expected: MediaItem, actual: MediaItem?): Boolean
    fun seekPhase(tookOver: Boolean): SeekAction.Phase
    fun startAnalysisCollector(): Unit
    fun startLoopBuild(key: String, plan: LoopMix.Plan, out: ExoPlayer, into: ExoPlayer, outItem: MediaItem, inItem: MediaItem, durationMs: Long, nextDurationMs: Long, sampleRate: Int, armWhenReady: Boolean, preparedOverride: com.music.bitchord.playback.djmix.PreparedPair? = null, forceNoStems: Boolean = false): Unit
    fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState
    fun stillWorthFading(): Boolean
    fun syncInspectorWithUiState(ui: MixUiState): Unit
    fun tickCadenceMs(): Long
    fun toCoreLevel(level: com.music.bitchord.playback.smart.choreo.DjLevel): CoreDjLevel
}
