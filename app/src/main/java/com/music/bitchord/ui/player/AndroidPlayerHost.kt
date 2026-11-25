package com.music.bitchord.ui.player

import com.music.bitchord.wear.WearBridge
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.BitChordApplication
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.canvas.CanvasRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.lyrics.LyricLine
import com.music.bitchord.data.lyrics.LyricsTranslation
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.LastPlayerScreen
import com.music.bitchord.playback.MusicOnlyPlayback
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.AudioRouting
import com.music.bitchord.playback.AutoMixVisualFeed
import com.music.bitchord.playback.djmix.DjmixServices
import com.music.bitchord.playback.djmix.ExplicitRatingIdentity
import com.music.bitchord.playback.djmix.RetainedMixContext
import com.music.bitchord.playback.djmix.diagnostics.DiagnosticsBundler
import com.music.bitchord.playback.djmix.diagnostics.DjmixFlags
import com.music.bitchord.playback.djmix.store.DjmixAnalysisStore
import com.music.bitchord.playback.djmix.store.TransitionRecord
import com.music.bitchord.playback.smart.MixInspectorFeed
import com.music.bitchord.playback.smart.MixInspectorKey
import com.music.bitchord.playback.smart.MixInspectorState
import java.io.File
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.playback.audio.bitperfect.DacVolume
import com.music.bitchord.playback.cast.CastController
import com.music.bitchord.ui.components.backdrop.backdrops.SnapshotBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.rememberSnapshotBackdrop
import com.music.bitchord.ui.components.backdrop.backdrops.snapshotBackdropSource
import com.music.bitchord.ui.components.isGlassSupported
import com.music.bitchord.ui.components.menuBackdropFrost
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The phone's answers to [PlayerHost]: AppSettings, the Media3 Canvas decoder,
 * AudioRouting and the music stream's volume, the party, and the lyric
 * translation endpoint — each exactly what the player read directly before it
 * moved into the shared module.
 */
class AndroidPlayerHost(context: Context) : PlayerHost {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())

    override val autoMixVisualState get() = AutoMixVisualFeed.state

    // FORK-HOOK(mix-inspector): only the playback engine can accept a re-pick for this preparation.
    override fun remixCurrentTransition(key: MixInspectorKey): Boolean = MixInspectorFeed.remixCurrentTransition(key)

    override fun onAutoMixScrubIntent() = AutoMixVisualFeed.cancel()

    @Composable
    override fun autoMixIncomingReveal(
        eased: () -> Float,
        reducedMotion: () -> Boolean,
        generation: Long,
        blur: Boolean,
    ): Modifier = androidAutoMixIncomingReveal(eased, reducedMotion, generation, blur)

    override val supportsAutoMixCanvas: Boolean get() = true

    // Inspector, History and playback all use the application's process owner.
    private val djmixServices: DjmixServices get() = (app as BitChordApplication).djmixServices
    val djmixFlags: DjmixFlags get() = requireNotNull(djmixServices.flags)
    val djmixStore: DjmixAnalysisStore get() = requireNotNull(djmixServices.store)

    init {
        // :sharedUi cannot import app/ and PlayerHost has no rating/flag
        // hooks, so the Mix Inspector popup reads these handlers from
        // [MixInspectorActions] (simple same-package bridge, KMP-safe).
        MixInspectorActions.thumbs = { state, rating -> rateTransition(state, rating) }
        MixInspectorActions.flag = { state -> flagTransition(state) }
    }

    /** Rate only the archived frozen generation currently shown by the inspector. */
    fun rateTransition(state: MixInspectorState, rating: Int) {
        val identity = djmixServices.retainedMixContextFor(state)?.ratingIdentity()
        if (identity == null) {
            showMessage("No recorded transition for this generation yet")
            return
        }
        scope.launch {
            val saved = djmixServices.recordExplicitRating(identity, rating).await()
            showMessage(
                when {
                    !saved -> "Rating not saved"
                    rating > 0 -> "Rated up"
                    rating < 0 -> "Rated down"
                    else -> "Rating cleared"
                },
            )
        }
    }

    /** Flag the inspected transition: write a diagnostics bundle and share it. */
    fun flagTransition(state: MixInspectorState) {
        bundleAndShare(djmixServices.retainedMixContextFor(state))
    }

    /** Flag a history row (Settings → History long-press / menu). */
    fun flagRecord(record: TransitionRecord) {
        bundleAndShare(djmixServices.retainedMixContextFor(record))
    }

    /** Newest-first transition history for Settings → History (last 100). */
    suspend fun historyRecords(limit: Int = 100): List<TransitionRecord> =
        withContext(Dispatchers.IO) { djmixStore.recentTransitions(limit) }

    /** Re-rate a history row; `1` up, `-1` down, `0` clear. */
    suspend fun updateHistoryRating(id: Long, rating: Int): Boolean {
        val record = historyRecords().firstOrNull { it.id == id } ?: return false
        val identity = djmixServices.ratingIdentityFor(record) ?: return false
        return djmixServices.recordExplicitRating(identity, rating).await()
    }

    /** Clear all learned taste weights (Settings → "Reset learned preferences"). */
    suspend fun resetLearnedPreferences(): Boolean = djmixServices.resetLearnedPreferences().await()

    /** Share the newest diagnostics bundle, if one exists. */
    suspend fun shareLatestDiagnostics(): Boolean {
        val latest = withContext(Dispatchers.IO) {
            djmixServices.diagnosticsBundler?.listBundles()?.firstOrNull()
        } ?: run {
            showMessage("No diagnostics bundle yet")
            return false
        }
        return withContext(Dispatchers.Main) { shareFile(latest) }
    }

    private fun bundleAndShare(context: RetainedMixContext?) {
        if (context == null) {
            showMessage("No diagnostics context for this transition")
            return
        }
        val pendingBundle = djmixServices.flagSelectedMixAsync(context)
        scope.launch(Dispatchers.IO) {
            val bundle = pendingBundle.await()
            withContext(Dispatchers.Main) {
                showMessage(
                    when {
                        bundle == null -> "Diagnostics bundle not written"
                        !shareFile(bundle) -> "Diagnostics saved; could not open sharing"
                        else -> "Flagged for diagnostics"
                    },
                )
            }
        }
    }

    private fun shareFile(file: File): Boolean {
        val intent = DiagnosticsBundler.buildShareChooser(app, file)
        if (intent == null) return false
        return runCatching {
            app.startActivity(intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            true
        }.getOrDefault(false)
    }


    override val settings: PlayerSettingsSource = object : PlayerSettingsSource {
        override val animatedCanvas get() = AppSettings.animatedCanvas
        override val audioQualityCellular get() = AppSettings.audioQualityCellular
        override val audioQualityWifi get() = AppSettings.audioQualityWifi
        override val canvasOverCellular get() = AppSettings.canvasOverCellular
        override val fullBleedArtwork get() = AppSettings.fullBleedArtwork
        override val showTotalDuration get() = AppSettings.showTotalDuration
        override val hideSongStatus get() = AppSettings.hideSongStatus
        override val hideLyricsPreview get() = AppSettings.hideLyricsPreview
        override val showQueueHistory get() = AppSettings.showQueueHistory
        override val hideVolumeBar get() = AppSettings.hideVolumeBar
        override val hideStatusBar get() = AppSettings.hideStatusBarInPlayer
        override val lastPlayerScreen get() = AppSettings.lastPlayerScreen
        override val playerTheme get() = AppSettings.playerTheme
        override val lyricsBlur get() = AppSettings.lyricsBlur
        override val lyricsOffsetMs get() = AppSettings.lyricsOffsetMs
        override val lyricsSourceOrder get() = AppSettings.lyricsSourceOrder
        override val meteredConnection get() = AppSettings.meteredConnection
        override val streamCeilingOverride get() = AppSettings.streamCeilingOverride
        override val preferUsbDac get() = AppSettings.preferUsbDac
        override val disableSpotifyCanvas get() = AppSettings.disableSpotifyCanvas
        override val prioritizeSpotifyCanvas get() = AppSettings.effectivePrioritizeSpotifyCanvas
        override val reduceAnimation get() = AppSettings.reduceAnimation
        override val reduceDynamicBlur get() = AppSettings.reduceDynamicBlur
        override val disableBlendEffects get() = AppSettings.disableBlendEffects
        override val showNerdStats get() = AppSettings.showNerdStats
        override val showBestPart get() = AppSettings.showBestPart
        override val showListenTogetherButton get() = AppSettings.showListenTogetherButton
        override val smartAnalysis get() = AppSettings.smartAnalysis
        override val automixDetail get() = AppSettings.automixDetail
        // FORK-HOOK(player-status): expose AppSettings.automixStatus to sharedUi
        override val automixStatus get() = AppSettings.automixStatus
        // FORK-HOOK(mix-inspector): AppSettings exposes the shared feed read-only.
        override val mixInspector get() = AppSettings.mixInspector
        override val smartFadeEnabled get() = AppSettings.smartFadeEnabled
        override val autoMixArtwork get() = AppSettings.autoMixArtwork
        override val smartMixInProgress get() = AppSettings.smartMixInProgress
        override val smartMixBlend get() = AppSettings.smartMixBlend
        override val smartMixView get() = AppSettings.smartMixView
        override val smartMixProgress get() = AppSettings.smartMixProgress
        override val smartMixPreview get() = AppSettings.smartMixPreview
        override val smartTransitionWindow get() = AppSettings.smartTransitionWindow
        override val spotifyCanvasAutoHide get() = AppSettings.effectiveSpotifyCanvasAutoHide
        override val syncedLyrics get() = AppSettings.syncedLyrics
        override val translationLanguage get() = AppSettings.translationLanguage
        override val versionAlignmentInProgress get() = AppSettings.versionAlignmentInProgress

        override fun setLastPlayerScreen(value: LastPlayerScreen) = AppSettings.setLastPlayerScreen(value)
        override fun setLyricsOffsetMs(value: Int) = AppSettings.setLyricsOffsetMs(value)
        override fun setMixViewOpen(open: Boolean) {
            AppSettings.mixViewOpen.value = open
        }
    }

    override fun cachedCanvas(song: Song): CanvasArtwork? = CanvasRepository.cached(song)

    override suspend fun canvasFor(song: Song): CanvasArtwork? = CanvasRepository.canvasFor(song)

    override suspend fun bestPartMs(song: Song): Long? =
        com.music.bitchord.ui.replay.year.BestParts.startFor(app, song)
            .takeIf { it.source != com.music.bitchord.ui.replay.year.BestPartStart.Source.FALLBACK }
            ?.startMs

    @Composable
    override fun CanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) = AndroidCanvasVideo(spec, modifier)

    override val musicVideoSupported: Boolean get() = true

    @Composable
    override fun MusicVideo(videoId: String, fit: Boolean, onRenderedChanged: (Boolean) -> Unit, modifier: Modifier) =
        AndroidMusicVideo(videoId, fit, onRenderedChanged, modifier)

    override fun showsMusicVideoPicture(videoId: String): Boolean = MusicOnlyPlayback.showsPicture(videoId)

    override val musicVideoAspect: StateFlow<Float> get() = MusicVideoEngine.aspect

    override val musicVideoQuality: StateFlow<MusicVideoQualityUi> get() = MusicVideoEngine.quality

    override fun setMusicVideoQuality(height: Int?) = MusicVideoEngine.setQuality(height)

    @Composable
    override fun rememberRemoteArtworkUrl(song: Song?): String? =
        com.music.bitchord.ui.components.rememberRemoteArtworkUrl(song)

    // Lazy, all three: the player is not the first thing the process does, and
    // none of these objects should be woken for a launch that never opens it.
    override val volume: SystemVolume by lazy { CastAwareVolume(MusicStreamVolume(app), scope) }

    @Composable
    override fun rememberAudioOutputs(): List<AudioOutputDevice> {
        val outputs = rememberAndroidAudioOutputs()
        val cast by CastController.state.collectAsStateWithLifecycle()
        val watch by WearBridge.watchOutput.collectAsStateWithLifecycle()
        // While a receiver is the speaker no output of this phone is the one
        // playing, so none of them is lit; the Cast or watch row is.
        return if (cast.casting || watch.active) outputs.map { it.copy(isActive = false) } else outputs
    }

    @Composable
    override fun rememberOutputPicker(onOpen: () -> Unit): () -> Unit = rememberAndroidOutputPicker(onOpen)

    override fun selectAudioOutput(id: Int?) {
        // Picking an output of this phone is picking this phone: the receiver
        // is let go of, and the music carries on here if it was playing.
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = true)
        AudioRouting.select(id)
        WearBridge.leaveWatchOutput()
    }

    override val watchOutput: StateFlow<WatchOutputUi> by lazy {
        WearBridge.watchOutput
            .map { WatchOutputUi(available = it.available, name = it.name, active = it.active) }
            .stateIn(scope, SharingStarted.Eagerly, WatchOutputUi())
    }

    override fun refreshWatchOutput() = WearBridge.refreshWatchOutput(app)

    override fun selectWatchOutput() {
        // One speaker at a time: a receiver lets go first, quietly, and the
        // watch picks the queue up from where the phone's player is.
        if (CastController.state.value.casting) CastController.disconnect(resumeHere = false)
        WearBridge.selectWatchOutput()
    }

    override val outputFormat: StateFlow<OutputFormatUi> by lazy {
        combine(AudioOutputStatus.current, BitPerfectOutput.status) { status, bitPerfect ->
            status.toOutputFormat(bitPerfect)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            AudioOutputStatus.current.value.toOutputFormat(BitPerfectOutput.status.value),
        )
    }

    override val party: StateFlow<PartyUi> by lazy {
        ListenTogether.state
            .map { it.toPartyUi() }
            .stateIn(scope, SharingStarted.Eagerly, ListenTogether.state.value.toPartyUi())
    }

    override suspend fun translateLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsTranslationResult =
        when (val result = LyricsTranslation.translate(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.Result.Translated -> LyricsTranslationResult.Translated(result.lines)
            is LyricsTranslation.Result.SameLanguage -> LyricsTranslationResult.SameLanguage(result.language)
            LyricsTranslation.Result.Unavailable -> LyricsTranslationResult.Unavailable
        }

    override suspend fun romanizeLyrics(
        trackId: String,
        lines: List<LyricLine>,
        targetLanguageTag: String,
    ): LyricsRomanizationResult =
        when (val result = LyricsTranslation.romanize(trackId, lines, targetLanguageTag)) {
            is LyricsTranslation.RomanizationResult.Romanized -> LyricsRomanizationResult.Romanized(result.lines)
            LyricsTranslation.RomanizationResult.AlreadyRomanized -> LyricsRomanizationResult.AlreadyRomanized
            LyricsTranslation.RomanizationResult.Unavailable -> LyricsRomanizationResult.Unavailable
        }

    override fun showMessage(message: String) {
        main.post { Toast.makeText(app, message, Toast.LENGTH_SHORT).show() }
    }

    override val castState: StateFlow<CastUi> by lazy {
        CastController.ensureStarted(app)
        CastController.state
            .map { CastUi(supported = it.supported, connectedName = it.connectedName, connecting = it.connecting) }
            .stateIn(scope, SharingStarted.Eagerly, CastUi())
    }

    @Composable
    override fun CastDialog(hazeState: HazeState, onDismiss: () -> Unit) =
        com.music.bitchord.ui.components.CastDialog(hazeState = hazeState, onDismiss = onDismiss)

    @Composable
    override fun AudioPipelineDialog(hazeState: HazeState, isPlaying: Boolean, onDismiss: () -> Unit) =
        com.music.bitchord.ui.components.AudioPipelineDialog(
            hazeState = hazeState,
            onDismiss = onDismiss,
            isPlaying = isPlaying,
        )

    // The ⋯ menu's own material: a still of the player, blurred under each
    // panel exactly as [menuBackdropFrost] blurs it under the menu. Below
    // Android 12 there is no RenderEffect to blur it with.
    @Composable
    override fun rememberPlayerFrost(): PlayerFrost? {
        if (!isGlassSupported()) return null
        val backdrop = rememberSnapshotBackdrop()
        return remember(backdrop) { BackdropPlayerFrost(backdrop) }
    }

    // The phone is the platform that can turn picked lyrics into a picture: a
    // bitmap, a canvas, the gallery and the chooser are all Android's here. The
    // desktop has no answer to it, so it never sees the pick mode at all.
    override val lyricsShareAvailable: Boolean get() = true

    @Composable
    override fun LyricsShareSheet(
        hazeState: HazeState,
        request: LyricsShareRequest,
        onDismiss: () -> Unit,
    ) = AndroidLyricsShareSheet(hazeState = hazeState, request = request, onDismiss = onDismiss)
}

/** A held popup must never act on a newer generation of the same track pair. */
internal fun DjmixServices.retainedMixContextFor(state: MixInspectorState): RetainedMixContext? {
    val registry = retainedMixContexts.value
    return listOfNotNull(registry.active, registry.lastCompleted).firstOrNull {
        it.generation == state.generation && it.outTrackId == state.current.mediaId &&
            it.inTrackId == state.next.mediaId && it.transitionPlanV1.recipeId == state.recipeId
    }
}

internal fun DjmixServices.retainedMixContextFor(record: TransitionRecord): RetainedMixContext? {
    val registry = retainedMixContexts.value
    val generation = (archivedRatingFields(record)?.get("generation") as? JsonPrimitive)?.longOrNull
    return listOfNotNull(registry.active, registry.lastCompleted).firstOrNull {
        it.planId == record.planId && it.outTrackId == record.outId && it.inTrackId == record.inId &&
            it.transitionPlanV1.recipeId == record.recipe &&
            (it.recordId == record.id || (it.recordId == null && it.generation == generation)) &&
            (generation == null || it.generation == generation)
    }
}

/** Legacy rows without a frozen generation cannot safely create a learning signal. */
internal fun DjmixServices.ratingIdentityFor(record: TransitionRecord): ExplicitRatingIdentity? {
    val retained = retainedMixContextFor(record)
    val fields = archivedRatingFields(record)
    val generation = retained?.generation ?: (fields?.get("generation") as? JsonPrimitive)?.longOrNull ?: return null
    if (record.id <= 0L || generation < 0L || record.planId.isBlank() || record.recipe.isBlank()) return null
    return ExplicitRatingIdentity(
        recordId = record.id,
        planId = record.planId,
        generation = generation,
        recipeId = record.recipe,
        genreClass = retained?.genreClass ?: (fields?.get("genreClass") as? JsonPrimitive)?.contentOrNull,
    )
}

private fun archivedRatingFields(record: TransitionRecord) = runCatching {
    Json.parseToJsonElement(record.timingsJson).jsonObject
}.getOrNull()

/**
 * The output's format line, and whether the track is reaching the DAC
 * bit-perfect. On the exclusive output the format is the one written to
 * the DAC, not the ordinary AudioTrack's, which is idle meanwhile.
 */
private fun AudioOutputStatus.Snapshot.toOutputFormat(bitPerfect: BitPerfectOutput.Status): OutputFormatUi {
    val encoding = if (bitPerfect.active) "${bitPerfect.outputBits}-bit PCM" else AudioOutputStatus.encodingLabel(this)
    val rate = (if (bitPerfect.active) bitPerfect.sampleRateHz else actualSampleRateHz)?.takeIf { it > 0 }
    val format = if (rate == null) {
        encoding
    } else {
        "$encoding · ${"%.1f".format(Locale.ROOT, rate / 1000f).removeSuffix(".0")} kHz"
    }
    val dacLimited = bitPerfect.active && bitPerfect.dacLimited
    return OutputFormatUi(
        summary = if (dacLimited) "$format · DAC max" else format,
        bitPerfect = bitPerfect.active && bitPerfect.exact,
        dacLimited = dacLimited,
    )
}

private fun ListenTogether.State.toPartyUi() = PartyUi(
    inParty = inParty,
    controlsLocked = controlsLocked,
    members = members,
    you = you,
    code = code,
)

/**
 * The volume bar's level: the receiver's while the music is on one, the DAC's
 * own while direct USB output drives it, the music stream's otherwise. The two are different numbers on different devices, and
 * the bar must never show one while moving the other.
 */
private class CastAwareVolume(
    private val system: SystemVolume,
    scope: CoroutineScope,
) : SystemVolume {
    override val level: StateFlow<Float> = combine(
        system.level,
        CastController.volume,
        CastController.state.map { it.casting },
        WearBridge.watchOutput,
        DacVolume.level,
    ) { phone, receiver, casting, watch, dac ->
        when {
            casting -> receiver
            watch.active -> watch.volume
            // Direct USB: the music stream no longer reaches the DAC, so its
            // own hardware volume is the only one that means anything.
            dac != null -> dac
            else -> phone
        }
    }
        .stateIn(scope, SharingStarted.Eagerly, system.level.value)

    override fun set(level: Float) {
        when {
            CastController.state.value.casting -> CastController.setVolume(level)
            WearBridge.watchOutput.value.active -> WearBridge.setWatchVolume(level)
            DacVolume.level.value != null -> DacVolume.set(level)
            else -> system.set(level)
        }
    }

    override fun refresh() = system.refresh()
}

/**
 * The music stream's volume. Hardware keys and the system panel change it
 * behind the app's back, so Settings is watched and the level follows.
 */
private class MusicStreamVolume(private val context: Context) : SystemVolume {
    private val manager = context.getSystemService(AudioManager::class.java)
    private val _level = MutableStateFlow(read())
    override val level: StateFlow<Float> = _level.asStateFlow()

    init {
        context.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) = refresh()
            },
        )
    }

    private fun max(): Int = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 15

    private fun read(): Float =
        (manager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max()

    override fun set(level: Float) {
        manager?.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max()).roundToInt(), 0)
    }

    override fun refresh() {
        _level.value = read()
    }
}

private class BackdropPlayerFrost(private val backdrop: SnapshotBackdrop) : PlayerFrost {
    @Composable
    override fun Modifier.frostSource(): Modifier = snapshotBackdropSource(backdrop)

    override fun capture() = backdrop.capture()

    override fun release() = backdrop.release()

    override fun Modifier.frostPanel(shape: Shape, fill: Color): Modifier = menuBackdropFrost(backdrop, shape, fill)
}
