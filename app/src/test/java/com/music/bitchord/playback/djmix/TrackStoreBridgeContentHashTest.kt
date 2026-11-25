package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.djmix.store.TrackStoreBridge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackStoreBridgeContentHashTest {
    @Test
    fun metadataFingerprintChangesKey() {
        val first = TrackStoreBridge.contentHashFromFingerprint("track", "file:/music/a.flac:100:10")
        val changedSize = TrackStoreBridge.contentHashFromFingerprint("track", "file:/music/a.flac:101:10")
        val changedTime = TrackStoreBridge.contentHashFromFingerprint("track", "file:/music/a.flac:100:11")
        assertNotNull(first)
        assertNotEquals(first, changedSize)
        assertNotEquals(first, changedTime)
    }

    @Test
    fun emptyLegacyRowsCanBeMigratedButKnownMismatchCannot() {
        val expected = TrackStoreBridge.contentHashFromFingerprint("track", "uri:key:42")!!
        assertTrue(TrackStoreBridge.isContentHashCompatible("", expected))
        assertTrue(TrackStoreBridge.isContentHashCompatible(expected, expected))
        assertFalse(TrackStoreBridge.isContentHashCompatible("different", expected))
    }

    @Test
    fun noFingerprintKeepsLegacyCompatibility() {
        assertTrue(TrackStoreBridge.isContentHashCompatible("", null))
        assertTrue(TrackStoreBridge.isContentHashCompatible("known", null))
        assertTrue(TrackStoreBridge.contentHashFromFingerprint("", "metadata") == null)
        assertTrue(TrackStoreBridge.contentHashFromFingerprint("track", "") == null)
    }
}
