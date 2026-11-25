package com.music.bitchord.playback.smart

/** Decoded mono PCM at the container's own sample rate; the caller resamples. */
data class Pcm(val samples: FloatArray, val sampleRate: Double)

/**
 * Decoded planar stereo PCM at the container's own sample rate.
 *
 * Planar rather than interleaved because the only consumer is the vocal
 * front end, which wants one array per channel; a mono source is widened
 * by sharing the same samples on both sides, which is what the model was
 * trained to see for centre-panned material anyway.
 */
data class StereoPcm(val left: FloatArray, val right: FloatArray, val sampleRate: Double)
