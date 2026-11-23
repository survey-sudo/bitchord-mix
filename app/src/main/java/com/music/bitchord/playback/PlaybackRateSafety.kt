package com.music.bitchord.playback

import com.music.bitchord.playback.smart.deckSpeed

/**
 * Hard deck-speed discipline (, HANDOVER_MASTER \u00a76 Bug 1 &amp; Bug 2): the beatmatch
 * rate a deck may play at. Returns `1.0f` when [raw] is not a beatmatch (the caller stacked
 * a listener speed on a non-mix rate) or not finite, and strictly clamps to
 * `[0.92f, 1.08f]` (\u00b18\u2009max) — never `1.94\u00d7` or `999\u00d7`, under any circumstance.
 * The listener's own speed is stacked on top of this in [deckSpeed]: clamping the rate here
 * keeps user speed (0.25\u20133\u00d7) working while the mix component can never run away.
 * Pure scalar; unit-tested in `Djmix18AppHandoffTest`.
 */
internal fun safeDeckSpeed(raw: Float, beatmatched: Boolean = true): Float {
    if (!beatmatched || !raw.isFinite()) return 1.0f
    return raw.coerceIn(0.92f, 1.08f)
}

/**
 * [safeDeckSpeed] applied to a mix [rate] (1.0 = no stretch) before [deckSpeed] stacks it with
 * the listener speed. [rate] may arrive as `NaN` (unknown BPM ratio): that is `1.0f`, never
 * a stretch. Every `setPlaybackSpeed` that carries a beatmatch rate goes through here so the
 * `[0.92f, 1.08f]` ceiling applies to ordinary unqualified requests. A locally
 * admitted classic blend uses its separately checked physical rate in incomingBaseSpeed.
 */
internal fun safeRateForDeck(rate: Double, beatmatched: Boolean = true): Double =
    safeDeckSpeed(rate.toFloat(), beatmatched).toDouble()

/**
 * Final `ExoPlayer` speed for a mix [rate]: listener speed stacked on [safeRateForDeck].
 * Use this for every beatmatch `setPlaybackSpeed`. Non-beatmatched decks play at the
 * listener speed (`rate` is forced to `1.0`).
 */
internal fun deckSpeedSafe(user: Float, rate: Double, beatmatched: Boolean = true): Float =
    deckSpeed(user, safeRateForDeck(rate, beatmatched))

/**
 * Applies a locally admitted physical stretch before the existing composite
 * player-speed cap. Invalid rates cannot bypass the ordinary deck-speed guard.
 */
internal fun deckSpeedForAdmittedBlend(user: Float, admittedRate: Double): Float {
    val base = if (user.isFinite() && user in 0.25f..3f) user else 1f
    val rate = if (admittedRate.isFinite() &&
        admittedRate in com.music.bitchord.playback.smart.MIN_INCOMING_RATE..
            com.music.bitchord.playback.smart.MAX_INCOMING_RATE
    ) admittedRate.toFloat() else 1f
    return (base * rate).coerceIn(0.25f, 3f)
}
