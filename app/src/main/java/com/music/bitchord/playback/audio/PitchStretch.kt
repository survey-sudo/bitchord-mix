package com.music.bitchord.playback.audio

/**
 * Pitch correction for a beat-matched blend, via Signalsmith Stretch.
 *
 * The loop mix already time-stretches in its own Signalsmith deck. A blend
 * does not: its playback rate moves the timeline, and on the float route
 * that rate also shifts pitch. This undoes that pitch, and only while the
 * rate is away from 1. A missing native library leaves the audio as it was.
 */
object PitchStretch {
    private var loaded = false
    private var unavailable = false

    fun create(): Long {
        if (!ensureLoaded()) return 0L
        return nativeCreate()
    }

    fun destroy(handle: Long) {
        if (handle != 0L && loaded) nativeDestroy(handle)
    }

    fun configure(handle: Long, channels: Int, sampleRate: Int) {
        if (handle != 0L && loaded) nativeConfigure(handle, channels, sampleRate)
    }

    fun setTempo(handle: Long, tempo: Float) {
        if (handle != 0L && loaded) nativeSetTempo(handle, tempo)
    }

    fun process(handle: Long, samples: FloatArray, offset: Int, frames: Int, channels: Int) {
        if (handle != 0L && loaded && frames > 0) {
            nativeProcess(handle, samples, offset, frames, channels)
        }
    }

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (unavailable) return false
        return try {
            System.loadLibrary("bitchord_stretch")
            loaded = true
            true
        } catch (_: UnsatisfiedLinkError) {
            unavailable = true
            false
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeConfigure(handle: Long, channels: Int, sampleRate: Int)
    private external fun nativeSetTempo(handle: Long, tempo: Float)
    private external fun nativeProcess(handle: Long, samples: FloatArray, offset: Int, frames: Int, channels: Int)
}
