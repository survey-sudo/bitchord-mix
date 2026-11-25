package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Exact-ID, disk-only lookup. No matching, upstream fetch, permission request, or guessed recording. */
@UnstableApi
internal class BackgroundTrackResolver(private val context: Context, private val cache: AudioCache) {
    data class Source(val uri: Uri, val durationSeconds: Double)

    suspend fun resolve(trackId: String, knownUri: Uri?, knownDuration: Double?): Source? = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val duration = knownDuration?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        // A previously pinned source is authoritative, including when it has become unavailable.
        if (knownUri != null) return@withContext checked(knownUri, duration)
        val literal = Uri.parse(trackId)
        if (LocalAudioSource.isLocal(literal) || literal.scheme == "bitchord" ||
            literal.scheme == "http" || literal.scheme == "https") {
            return@withContext checked(literal, duration)
        }

        val liked = OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty()
            .filter { it.videoId == trackId }
        val saved = Downloads.saved.value[trackId]
        val localUris = (liked.mapNotNull { it.localUri } + listOfNotNull(saved)).distinct()
        // Two files associated with the same ID are not evidence of the same rendition.
        if (localUris.size > 1) return@withContext null
        if (localUris.isNotEmpty()) {
            val uri = Uri.parse(localUris.single())
            if (!LocalAudioSource.isLocal(uri)) return@withContext null
            val downloads = if (saved == null) emptyList() else Downloads.getDownloadedSongs(context)
            val metadata = liked.filter { it.localUri == uri.toString() } +
                downloads.filter { it.localUri == uri.toString() }
            currentCoroutineContext().ensureActive()
            val seconds = metadata.firstNotNullOfOrNull { it.durationMillis().takeIf { ms -> ms > 0L } }
                ?.div(1000.0) ?: duration
            return@withContext checked(uri, seconds)
        }

        val cached = cache.cachedSongs().singleOrNull { it.song.videoId == trackId } ?: return@withContext null
        currentCoroutineContext().ensureActive()
        if (SourceRegistry.parseTrackKey(trackId) != null) return@withContext null
        // This is the existing canonical cache address, used only when exact-ID metadata AND
        // its unsuffixed complete rendition exist. A sibling #alt/#watch/#hifi never qualifies.
        val canonical = Uri.parse(AutomixAnalysisSource.opusUri(trackId))
        if (canonical.getQueryParameter("v") != trackId) return@withContext null
        checked(canonical, cached.song.durationMillis().takeIf { it > 0L }?.div(1000.0) ?: duration)
    }

    private fun checked(candidate: Uri, duration: Double): Source? {
        if (candidate.isOpaque || candidate.authority == "source") return null
        val videoId = candidate.getQueryParameter("v")
        val uri = if (candidate.scheme == "bitchord" && candidate.authority == "watch" && !videoId.isNullOrBlank()) {
            Uri.parse(AutomixAnalysisSource.opusUri(videoId))
        } else candidate
        if (uri.getQueryParameter("v") != videoId) return null
        if (LocalAudioSource.isLocal(uri)) {
            LocalAudioSource.open(context.contentResolver, uri)?.use { source ->
                val byte = ByteArray(1)
                if (source.readAt(0L, byte, 0, 1) != 1) return null
            } ?: return null
        } else {
            if (uri.scheme != "bitchord" && uri.scheme != "http" && uri.scheme != "https") return null
            if (uri.scheme == "bitchord" && (uri.authority != "watch" || uri.getQueryParameter("v").isNullOrBlank())) {
                return null
            }
            if (cache.renditionsOf(uri).none {
                    it.isComplete && AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key)
                }) return null
        }
        return Source(uri, duration)
    }
}
