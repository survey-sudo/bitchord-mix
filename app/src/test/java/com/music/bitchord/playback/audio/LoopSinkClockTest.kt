package com.music.bitchord.playback.audio

import androidx.media3.exoplayer.audio.AudioSink
import com.music.bitchord.playback.loopmix.LoopMixPort
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class LoopSinkClockTest {
    @Test fun sinkRejectsPositionQueryRacingArmAndFlushAndRecoversAfterStartupFlush() {
        val chain = DspChain()
        val port = chain.transition.loop
        var duringQuery: (() -> Unit)? = null
        val delegate = Proxy.newProxyInstance(AudioSink::class.java.classLoader, arrayOf(AudioSink::class.java)) { _, method, _ ->
            when (method.name) {
                "getCurrentPositionUs" -> { duringQuery?.invoke(); 13_300_000L }
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
        val sink = PrecisionAudioSink(delegate, chain, true)
        port.armIncoming(LoopMixPort.InArm(13_000_000))
        duringQuery = { port.armIncoming(LoopMixPort.InArm(14_000_000)) }
        sink.getCurrentPositionUs(false)
        assertNull(port.presentedSeconds(System.nanoTime(), true))

        duringQuery = null
        sink.getCurrentPositionUs(false)
        assertNotNull(port.presentedSeconds(System.nanoTime(), true))
        sink.flush()
        assertNull(port.presentedSeconds(System.nanoTime(), true))
        assertNotNull(port.inArm)
        assertFalse(port.interrupted) // startup flush preserves arm, not the old clock
        sink.getCurrentPositionUs(false)
        assertNotNull(port.presentedSeconds(System.nanoTime(), true))

        duringQuery = { sink.flush() }
        sink.getCurrentPositionUs(false)
        assertNull(port.presentedSeconds(System.nanoTime(), true))
        duringQuery = null
        sink.getCurrentPositionUs(false)
        sink.reset()
        assertNull(port.presentedSeconds(System.nanoTime(), true))
    }
}
