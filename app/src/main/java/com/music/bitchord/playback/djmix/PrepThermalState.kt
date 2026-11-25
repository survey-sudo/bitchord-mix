package com.music.bitchord.playback.djmix

import android.os.PowerManager

/** Pure admission state for new preparation; it never controls an executing mix. */
internal data class PrepThermalState(val paused: Boolean = false) {
    /** Unknown/unavailable readings are neutral and cannot leave preparation latched forever. */
    fun update(thermalStatus: Int?): PrepThermalState = when {
        thermalStatus == null || thermalStatus < PowerManager.THERMAL_STATUS_NONE -> PrepThermalState()
        thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> PrepThermalState(paused = true)
        thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE -> PrepThermalState()
        else -> this
    }
}
