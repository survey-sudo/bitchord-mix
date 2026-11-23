package com.music.bitchord.playback.smart

/**
 * Ids whose pair hash lands in the requested FX slot.
 *
 * Shared by the regression suites that need a pair in a specific rotation
 * slot. [prefix] keeps the generated ids distinct between test files so one
 * suite's fixtures cannot collide with another's.
 */
internal fun fxSlotIds(slot: Int, prefix: String, limit: Int = 500): Pair<String, String> {
    for (i in 0 until limit) {
        val a = "$prefix-out-$i"
        val b = "$prefix-in"
        if (TransitionMoves.fxSlot(a, b) == slot) return a to b
    }
    for (i in 0 until limit) {
        val a = "$prefix-out"
        val b = "$prefix-in-$i"
        if (TransitionMoves.fxSlot(a, b) == slot) return a to b
    }
    throw AssertionError("no pairHash slot $slot for prefix $prefix")
}
