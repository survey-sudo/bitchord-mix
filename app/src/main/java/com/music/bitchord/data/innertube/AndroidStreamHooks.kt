package com.music.bitchord.data.innertube

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.MonotonicClock
import com.music.bitchord.data.TrackLog
import android.webkit.CookieManager
import com.music.bitchord.data.innertube.potoken.PoTokenGenerator
import com.music.bitchord.data.innertube.potoken.PoTokenWebView
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver

/**
 * The phone's answers to what the shared stream resolution asks of its
 * platform: the clock, logcat, the exported log's header, the quality
 * ceiling, and InnerTubeX's config store and BotGuard minter.
 */
object AndroidStreamHooks {

    /** Before anything logs or resolves. */
    fun installEarly() {
        MonotonicClock.nowMs = SystemClock::elapsedRealtime
        // logcat on debug builds only; a release build keeps the lines for Copy
        // Log and writes them nowhere else.
        if (BuildConfig.DEBUG) {
            TrackLog.echo = { level, tag, message, error ->
                when (level) {
                    'D' -> Log.d(tag, message, error)
                    'I' -> Log.i(tag, message, error)
                    'W' -> Log.w(tag, message, error)
                    else -> Log.e(tag, message, error)
                }
            }
        }
        TrackLog.sourcesLine = {
            "sources: substitution=${SourceResolver.canSubstituteForYouTube()} " +
                "request=${SourceResolver.requestForNow()}"
        }
        TrackLog.headerLines = {
            listOf(
                "build: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
                "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}",
            )
        }
        StreamResolver.maxKbps = { AppSettings.effectiveAudioQuality.maxKbps }
        StreamResolver.premiumAudio = { AppSettings.youtubePremiumAudio.value }
        InnerTubeXResolver.debugLogging = BuildConfig.DEBUG
    }

    /** A missing runtime API key leaves BotGuard PoTokens unavailable. */
    fun initInnerTubeX(context: Context, apiKey: String? = null) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        // BotGuard PoTokens minted in a hidden WebView; what WEB_REMIX needs for age-restricted tracks.
        val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
        // The existing player-config store has no BotGuard key source; never use a fallback.
        val resolvedApiKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        val poTokens = resolvedApiKey?.let { key ->
            PoTokenGenerator(
                createMinter = { PoTokenWebView.getNewPoTokenGenerator(app, key) },
                available = { webViewSupported },
            )
        }
        if (poTokens == null) {
            TrackLog.w("AndroidStreamHooks", "BotGuard PoToken unavailable: no API key configured")
        }
        InnerTubeXResolver.init(
            filesDir = app.filesDir,
            store = object : InnerTubeXResolver.ConfigStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
            },
            poTokenProvider = poTokens?.asTokenProvider(),
        )
    }
}
