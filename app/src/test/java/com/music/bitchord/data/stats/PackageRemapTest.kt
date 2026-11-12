package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageRemapTest {

    @Test
    fun rewritesPathsAuthoritiesAndComponentPackages() {
        assertEquals(
            "/data/user/0/com.music.bitchord.loopfix/files/downloads/a.m4a",
            PackageRemap.remap("/data/user/0/com.music.bitchord/files/downloads/a.m4a"),
        )
        assertEquals(
            "content://com.music.bitchord.loopfix.fileprovider/cache/share.jpg",
            PackageRemap.remap("content://com.music.bitchord.fileprovider/cache/share.jpg"),
        )
        assertEquals(
            "com.music.bitchord.loopfix/com.music.bitchord.LauncherDefault",
            PackageRemap.remap("com.music.bitchord/com.music.bitchord.LauncherDefault"),
        )
    }

    @Test
    fun leavesClassNamesAndOtherPackagesAlone() {
        val klass = "com.music.bitchord.MainActivity"
        assertEquals(klass, PackageRemap.remap(klass))
        val tv = "content://com.music.bitchord.tv.fileprovider/x"
        assertEquals(tv, PackageRemap.remap(tv))
        val already = "content://com.music.bitchord.loopfix.fileprovider/x"
        assertEquals(already, PackageRemap.remap(already))
        assertEquals("crossfade", PackageRemap.remap("crossfade"))
    }

    @Test
    fun rewritesEveryStringInASet() {
        val remapped = PackageRemap.remapValue(
            setOf("/data/user/0/com.music.bitchord/files/a", "plain"),
        ) as Set<*>
        assertTrue(remapped.contains("/data/user/0/com.music.bitchord.loopfix/files/a"))
        assertTrue(remapped.contains("plain"))
        assertFalse(remapped.contains("/data/user/0/com.music.bitchord/files/a"))
    }

    @Test
    fun officialBackupTagIsAccepted() {
        assertTrue(Backup.acceptsApp("bitchord"))
        assertTrue(Backup.acceptsApp("com.music.bitchord"))
        assertFalse(Backup.acceptsApp("something-else"))
    }
}
