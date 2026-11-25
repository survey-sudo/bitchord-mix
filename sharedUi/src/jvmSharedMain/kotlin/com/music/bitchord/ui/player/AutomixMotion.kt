package com.music.bitchord.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.playback.smart.AutomixStatus
import com.music.bitchord.playback.smart.MixInspectorState
import kotlinx.coroutines.delay

/** Observe both the in-app preference and Compose's platform animation scale. */
@Composable
fun rememberAutomixReducedMotion(): Boolean {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val systemDurationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    return automixReducedMotionEnabled(reduceAnimation, systemDurationScale)
}

/** Bound the moved notice even when preparation remains in the same engine phase. */
@Composable
fun rememberAutomixChipLabel(status: AutomixStatus?, inspector: MixInspectorState?): String? {
    val movedKey = status?.takeIf { it.state == "preparing" && it.style == "moved" }
        ?.let { inspector?.key to it.headline }
    var showMoved by remember(movedKey) { mutableStateOf(movedKey != null) }
    LaunchedEffect(movedKey) {
        if (movedKey != null) {
            delay(MOVED_NOTICE_DURATION_MS)
            showMoved = false
        }
    }
    return automixChipLabel(status, inspector, showMoved)
}

private const val MOVED_NOTICE_DURATION_MS: Long = 3_000L
