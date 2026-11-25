package com.music.bitchord.playback.djmix

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.data.model.QueueEntryIdentity
import com.music.bitchord.playback.smart.MixInspectorFeed
import com.music.bitchord.playback.smart.MixInspectorPhase
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.AutoMixVisualFeed
import com.music.bitchord.playback.AutoMixVisualTrack
import com.music.bitchord.playback.queueEntryId
import com.music.bitchord.playback.queueTier


/** State observed or updated by [VisualFeedPublisher]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface VisualFeedPublisherHost : CoordinatorCallbacks {
    var handedOff: Boolean
    var incoming: ExoPlayer?
    val mixExecutor: MixExecutor
    var outgoing: ExoPlayer?
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    var visualCurrentIndex: Int
    var visualGeneration: Long?
    var visualQueue: List<MediaItem>
}

/** Publishes and validates artwork generations and overlays execution state on the inspector feed. */
@MainThread
@OptIn(UnstableApi::class)
internal class VisualFeedPublisher(private val host: VisualFeedPublisherHost) {
    val visualDeckErrors: Player.Listener = with(host) { object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = this@VisualFeedPublisher.cancelAutoMixVisual()
    }
    }

    fun cancelAutoMixVisual() = with(host) {
        AutoMixVisualFeed.cancel(visualGeneration)
    }

    fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>) {
        with(host) {
            fun trackAt(index: Int): AutoMixVisualTrack? {
                val item = items.getOrNull(index) ?: return null
                val entryId = item.queueEntryId
                val ordinal = (0 until index).count {
                    val prior = items[it]
                    prior.mediaId == item.mediaId && prior.queueEntryId == entryId
                }
                return AutoMixVisualTrack(
                    QueueEntryIdentity(item.mediaId, entryId, ordinal),
                    index,
                    item.mediaMetadata.artworkUri?.toString(),
                )
            }
            val from = trackAt(out.currentMediaItemIndex) ?: return
            val to = trackAt(nextIndex) ?: return
            visualQueue = items
            visualCurrentIndex = from.armedIndex
            visualGeneration = AutoMixVisualFeed.arm(from, to)
            out.addListener(visualDeckErrors)
            into.addListener(visualDeckErrors)
        }
    }

    fun clearAutoMixVisual() {
        with(host) {
            if (visualGeneration == null) return
            this@VisualFeedPublisher.cancelAutoMixVisual()
            outgoing?.removeListener(visualDeckErrors)
            incoming?.removeListener(visualDeckErrors)
            visualGeneration = null
            visualQueue = emptyList()
            visualCurrentIndex = C.INDEX_UNSET
        }
    }

    // MediaMetadata.equals does not compare the contents of extras.
    fun sameVisualQueueItem(expected: MediaItem, actual: MediaItem?): Boolean = with(host) {
        actual != null && expected == actual && expected.queueEntryId == actual.queueEntryId &&
                expected.queueTier == actual.queueTier
    }

    /**
     * Raw playlist mutations can bypass the session wrapper. Allow the controller's pure
     * append reconciliation and the service's post-handoff history trim/rotation, but
     * not a replaced or reordered snapshot. Accepted user edits use the intent hook.
     */
    fun checkAutoMixVisualTimeline(player: ExoPlayer) {
        with(host) {
            val visual = AutoMixVisualFeed.pending(visualGeneration) ?: return
            val index = player.currentMediaItemIndex
            val trim = visualCurrentIndex - index
            val retained = visualQueue.size - trim
            if (index !in 0 until player.mediaItemCount || trim < 0 || (!handedOff && trim != 0) ||
                retained > player.mediaItemCount || (0 until retained).any {
                    !this@VisualFeedPublisher.sameVisualQueueItem(visualQueue[it + trim], player.getMediaItemAt(it))
                } || (!handedOff && player.nextMediaItemIndex != visual.incoming.armedIndex)
            ) {
                this@VisualFeedPublisher.cancelAutoMixVisual()
            } else {
                visualQueue = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                visualCurrentIndex = index
            }
        }
    }

    fun syncInspectorWithUiState(ui: MixUiState) {
        with(host) {
            val mState = mixExecutor.currentState()
            val pair = mState.pair
            if (ui.phase == MixUiPhase.IDLE || pair == null) {
                if (mState is MixState.Idle && (pairPreparer == null || MixInspectorFeed.state.value != null)) {
                    MixInspectorFeed.publish(null)
                }
                return
            }
            val cur = MixInspectorFeed.state.value ?: return
            if (cur.current.mediaId != pair.outTrackId || cur.next.mediaId != pair.inTrackId) return
            val mappedPhase = when (ui.phase) {
                MixUiPhase.IDLE -> MixInspectorPhase.IDLE
                MixUiPhase.PREPARING -> if (cur.phase.ordinal < MixInspectorPhase.PREPARED.ordinal) cur.phase else MixInspectorPhase.PREPARED
                MixUiPhase.MOVED -> MixInspectorPhase.PLANNED
                MixUiPhase.READY -> MixInspectorPhase.PREPARED
                MixUiPhase.ARMED -> MixInspectorPhase.ARMED
                MixUiPhase.RUNNING, MixUiPhase.HANDING_OVER -> MixInspectorPhase.RUNNING
                MixUiPhase.DONE -> MixInspectorPhase.DONE
                MixUiPhase.LAST_RESORT -> MixInspectorPhase.BUILDING
                MixUiPhase.SUSPENDED, MixUiPhase.FAILED -> MixInspectorPhase.CANCELLED
            }
            val updated = cur.copy(
                phase = mappedPhase,
                recipeName = ui.recipeName ?: cur.recipeName,
                reason = ui.inspectorReason.takeIf { it.isNotBlank() } ?: cur.reason,
                stemsReady = if (ui.readiness.stemsRequired == 0) true else (ui.readiness.stemsReady >= ui.readiness.stemsRequired),
                remixAvailable = ui.canRemix && cur.phase < MixInspectorPhase.ARMED,
                remixUnavailableReason = if (ui.canRemix) null else (cur.remixUnavailableReason ?: "Remix unavailable in ${ui.phase.name.lowercase()}."),
            )
            if (updated != cur) {
                MixInspectorFeed.publish(updated)
            }
        }
    }
}
