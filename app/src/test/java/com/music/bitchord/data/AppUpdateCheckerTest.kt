package com.music.bitchord.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun djmixBuildsOutrankCleanUpstreamAndEarlierDjmixBuilds() {
        assertTrue(AppUpdateChecker.isNewer("1.10-djmix53", "1.10-djmix52"))
        assertTrue(AppUpdateChecker.isNewer("1.10-djmix52", "1.10"))
        assertTrue(AppUpdateChecker.isNewer("1.10", "1.10-beta2"))
        assertTrue(AppUpdateChecker.isNewer("1.10-djmix52", "1.10-beta2"))
        assertTrue(AppUpdateChecker.isNewer("1.10-djmix10", "1.10-djmix9"))
        assertTrue(AppUpdateChecker.isNewer("1.10-djmix52", "1.10-djmix52-beta2"))
    }

    @Test
    fun newerUpstreamVersionOutranksOlderDjmixBuild() {
        assertTrue(AppUpdateChecker.isNewer("1.11", "1.10-djmix52"))
        assertTrue(AppUpdateChecker.isNewer("v1.11", "1.10-djmix52"))
        assertTrue(AppUpdateChecker.isNewer("1.11-djmix53", "1.10-djmix52"))
    }

    @Test
    fun equalOrOlderVersionsDoNotTriggerUpdate() {
        assertFalse(AppUpdateChecker.isNewer("1.10-djmix52", "1.10-djmix52"))
        assertFalse(AppUpdateChecker.isNewer("1.10", "1.10-djmix52"))
        assertFalse(AppUpdateChecker.isNewer("1.10-beta2", "1.10"))
        assertFalse(AppUpdateChecker.isNewer("1.10-beta2", "1.10-djmix52"))
        assertFalse(AppUpdateChecker.isNewer("1.10-djmix51", "1.10-djmix52"))
        assertFalse(AppUpdateChecker.isNewer("1.10-djmix52", "1.11"))
    }

    @Test
    fun modelOnlyReleasesWithoutApkAssetNeverPrompt() {
        assertNull(
            AppUpdateChecker.selectUpdate(
                tag = "models-v1",
                releaseUrl = "https://github.com/survey-sudo/bitchord-mix/releases/tag/models-v1",
                apkUrl = null,
                notes = "Model weights only",
                currentVersion = "1.10-djmix52",
            ),
        )
        assertNull(
            AppUpdateChecker.selectUpdate(
                tag = "v1.10-djmix53",
                releaseUrl = "https://github.com/survey-sudo/bitchord-mix/releases/tag/v1.10-djmix53",
                apkUrl = null,
                notes = "Draft without APK",
                currentVersion = "1.10-djmix52",
            ),
        )
        val available = AppUpdateChecker.selectUpdate(
            tag = "v1.10-djmix53",
            releaseUrl = "https://github.com/survey-sudo/bitchord-mix/releases/tag/v1.10-djmix53",
            apkUrl = "https://github.com/survey-sudo/bitchord-mix/releases/download/v1.10-djmix53/BitChord-1.10-djmix53.apk",
            notes = "Release notes",
            currentVersion = "1.10-djmix52",
        )
        assertNotNull(available)
        assertEquals("1.10-djmix53", available?.version)
    }
}
