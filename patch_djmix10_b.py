#!/usr/bin/env python3
"""djmix10 patch B: freeze-once-armed, native gains, telemetry hooks."""
import sys, os

ROOT = os.path.dirname(os.path.abspath(__file__))

def edit(path, old, new, required=True):
    full = os.path.join(ROOT, path)
    with open(full, encoding="utf-8") as f:
        src = f.read()
    n = src.count(old)
    if n != 1:
        if not required:
            print(f"SKIP (found {n}): {path} :: {old[:60]!r}")
            return
        print(f"FAIL (found {n}, want 1): {path} :: {old[:80]!r}")
        sys.exit(1)
    src = src.replace(old, new, 1)
    with open(full, "w", encoding="utf-8") as f:
        f.write(src)
    print(f"OK: {path} :: {old[:50]!r}")

CC = "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt"

# ------------------------------------------------ freeze-once-armed: bail()
edit(CC,
     """    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) {
            loopBail("interrupted", settle = false)
            return
        }""",
     """    private fun bail() {
        if (phase == Phase.IDLE || phase == Phase.BAILING) return
        // djmix10 freeze-once-armed: once the blend is audible (FADING,
        // or a live loop session), a load/queue/seek/error must not
        // destroy it. The mix is already paid for (decks decoded,
        // gains ramped); finishing it is always more graceful than
        // aborting it. Pre-arm phases may still bail (costs only the
        // standby decoder).
        if (phase == Phase.FADING || ((phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) && loopSession != null)) {
            AutomixTelemetry.event("mix.freeze.onInterruption", mapOf("phase" to phase.name, "reason" to "interrupted-while-audible"))
            return
        }
        if (phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) {
            loopBail("interrupted", settle = false)
            return
        }""")

# ------------------------------------------------ freeze-once-armed: queue edit must not clear an audible mix
edit(CC,
     """    private fun dropForQueueEdit(why: String) {
        Log.d(TAG, "playlist changed: $why")
        discardPrime()
        loopPlanned = null
        loopScript = null
        loopPlanKey = null
        plannedNextId = null
        if (phase != Phase.IDLE) bail()
    }""",
     """    private fun dropForQueueEdit(why: String) {
        Log.d(TAG, "playlist changed: $why")
        // djmix10: an audible mix keeps its plan (plan vars are only
        // cleared pre-audible); the blend finishes and hands off.
        val audible = phase == Phase.FADING ||
            ((phase == Phase.LOOP_ARMING || phase == Phase.LOOP_RUNNING) && loopSession != null)
        if (audible) {
            AutomixTelemetry.event("mix.freeze.queueEdit", mapOf("why" to why, "phase" to phase.name))
            return
        }
        discardPrime()
        loopPlanned = null
        loopScript = null
        loopPlanKey = null
        plannedNextId = null
        if (phase != Phase.IDLE) bail()
    }""")

# ------------------------------------------------ telemetry: loopFallBack reason
edit(CC,
     """    private fun loopFallBack(reason: String) {
        Log.d(TAG, "loop mix falls back to a blend: $reason")""",
     """    private fun loopFallBack(reason: String) {
        AutomixTelemetry.event("mix.loop.fallback", mapOf("reason" to reason, "phase" to phase.name))
        Log.d(TAG, "loop mix falls back to a blend: $reason")""")

# ------------------------------------------------ telemetry: watchdog
edit(CC,
     """        watchdogFired = true
        val reason = "watchdog: shown countdown unarmed 1.2s after its bar"
        Log.i("AutomixCrash", reason + " key=$key pos=${player.currentPosition} start=$shownLoopStartMs")
        loopFallBack(reason)""",
     """        watchdogFired = true
        val reason = "watchdog: shown countdown unarmed 1.2s after its bar"
        AutomixTelemetry.event("mix.watchdog", mapOf("key" to key, "pos" to player.currentPosition, "start" to shownLoopStartMs))
        Log.i("AutomixCrash", reason + " key=$key pos=${player.currentPosition} start=$shownLoopStartMs")
        loopFallBack(reason)""")

# ------------------------------------------------ telemetry: player error
edit(CC,
     """        override fun onPlayerError(error: PlaybackException) = bail()""",
     """        override fun onPlayerError(error: PlaybackException) {
            AutomixTelemetry.event("mix.error", mapOf("error" to error.message.orEmpty(), "phase" to phase.name))
            bail()
        }""")

# ------------------------------------------------ LoopMixSession: clamp +-3dB + equal-power native gains
LS = "app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt"
edit(LS,
     """            val level = (matched ?: inLevel).coerceIn(0.4467f, 1.7783f)""",
     """            // djmix10: symmetric +-3 dB (was -7..+5 on the old line; the
            // djmix9 RegionalLoudness clamp was undone here).
            val level = (matched ?: inLevel).coerceIn(0.708f, 1.413f)""")

edit(LS,
     """            // Gains: the outgoing deck takes over from its song, the incoming loop
            // rises over the entry and the ramp, the outgoing one falls away.
            val h = LoopMix.HANDOVER_SEC
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                floatArrayOf(0f, 0.55f, 1f),
            )""",
     """            // Gains (djmix10): equal-power sin/cos across the overlap so
            // the sum stays flat instead of the old plateaus (1.55 then
            // 1.6) that the limiter had to crush. Outgoing attacks in
            // h seconds, holds through the swap, falls cos to 0 at
            // releaseAt; incoming rises sin to 1 at rampEnd and holds.
            // sin^2+cos^2 = 1, so the power sum is flat by law.
            val h = LoopMix.HANDOVER_SEC
            val mixDe = clock.rampEnd - clock.enterEnd
            if (mixDe > 1e-6) {
                val outFall = com.music.bitchord.playback.smart.MixGain.fallPoints(clock.rampEnd, clock.releaseAt)
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                    doubleArrayOf(-h, 0.0, clock.enterEnd, *outFall.first.sliceArray(1 until outFall.first.size)),
                    floatArrayOf(0f, 1f, 1f, *outFall.second.sliceArray(1 until outFall.second.size)),
                )
                val inRise = com.music.bitchord.playback.smart.MixGain.risePoints(0.0, clock.rampEnd)
                // Hold 1.0 after the rise: append releaseAt at 1.0.
                renderer.setEnvelope(
                    LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                    doubleArrayOf(*inRise.first, clock.releaseAt),
                    floatArrayOf(*inRise.second, 1f),
                )
            } else {
                // Degenerate overlap: keep the old shape rather than emit
                // a zero-length envelope.
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                    doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                    floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
                )
                renderer.setEnvelope(
                    LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                    doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                    floatArrayOf(0f, 0.55f, 1f),
                )
            }""")

print("controller/session phase done")
