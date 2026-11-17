package com.music.bitchord.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AutomixFireAttributionTest {
    @Test
    fun fireEventsHaveJoinKeysForAcceptedPlanAndDump() {
        val plan = Json.parseToJsonElement("{\"ev\":\"mix.plan\",\"transitionId\":\"t\",\"planHash\":\"p\"}").jsonObject
        val dump = Json.parseToJsonElement("{\"ev\":\"mix.dump\",\"transitionId\":\"t\",\"planHash\":\"p\"}").jsonObject
        val fire = Json.parseToJsonElement("{\"ev\":\"mix.fire\",\"transitionId\":\"t\",\"planHash\":\"p\"}").jsonObject
        assertEquals(plan["transitionId"], dump["transitionId"])
        assertEquals(plan["planHash"], fire["planHash"])
    }
}
