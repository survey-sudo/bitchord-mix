package com.music.bitchord.djmix.core.contracts

import kotlinx.serialization.Serializable

/**
 * Exact installed deck coordinates. Frames are mix frames; positions and loop/release bounds
 * are unwrapped samples relative to [regionStartSec], at the script sample rate.
 *
 * No constructor repairs or defaults: [com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard]
 * returns typed refusals for malformed decoded values. A null release means never release;
 * only the native adapter may translate that meaning to its sentinel. Equal loop bounds mean
 * straight playback. A null fade means no physical-track fade.
 */
@Serializable
data class DeckRenderSetupV1(
    val regionStartSec: Double,
    val frameCount: Int,
    val loopStartFrame: Long,
    val loopEndFrame: Long,
    val releaseAtFrame: Double?,
    val knotFrames: List<Double>,
    val knotPositions: List<Double>,
    val runOn: Boolean,
    val stopAtMixSec: Double,
    val prerollSec: Double,
    val levelGain: Float,
    val fadeOutTrackSec: Double?,
)

/** Complete renderer and incoming-player setup frozen before compilation; no host defaults. */
@Serializable
data class RenderGeometryV1(
    val out: DeckRenderSetupV1,
    val inbound: DeckRenderSetupV1,
    val handoverMixSec: Double,
    val handoverTrackSec: Double,
    val incomingStartMixSec: Double,
    val incomingStartTrackSec: Double,
    val highQuality: Boolean,
)
