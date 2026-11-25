package com.music.bitchord.playback.djmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/** Checks live host dispatch without constructing a controller, player, or Android runtime. */
class CoordinatorBindingsDispatchTest {
    @Test
    fun mixViewIdleReadsTheHostAfterEachPhaseChange() {
        val rig = Rig()
        assertTrue(rig.bindings.mixViewHost.isIdle())
        rig.idle = false
        assertFalse(rig.bindings.mixViewHost.isIdle())
        assertEquals(listOf("isIdle", "isIdle"), rig.calls)
    }

    @Test
    fun mixEventIdleReadsTheHostAfterEachPhaseChange() {
        val rig = Rig()
        rig.idle = false
        assertFalse(rig.bindings.mixEventHost.isIdle())
        rig.idle = true
        assertTrue(rig.bindings.mixEventHost.isIdle())
        assertEquals(listOf("isIdle", "isIdle"), rig.calls)
    }

    @Test
    fun mixEventPhaseNameReadsTheCurrentHostPhase() {
        val rig = Rig()
        rig.phaseName = "FADING"
        assertEquals("FADING", rig.bindings.mixEventHost.phaseName)
        rig.phaseName = "LOOP_RUNNING"
        assertEquals("LOOP_RUNNING", rig.bindings.mixEventHost.phaseName)
        assertEquals(listOf("getPhaseName", "getPhaseName"), rig.calls)
    }

    private class Rig {
        var idle = true
        var phaseName = "IDLE"
        val calls = mutableListOf<String>()
        private val host = Proxy.newProxyInstance(
            CoordinatorBindingsHost::class.java.classLoader,
            arrayOf(CoordinatorBindingsHost::class.java),
        ) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "isIdle" -> idle
                "getPhaseName" -> phaseName
                else -> error("Unexpected host call: ${method.name}")
            }
        } as CoordinatorBindingsHost
        val bindings = CoordinatorBindings(host)
    }
}
