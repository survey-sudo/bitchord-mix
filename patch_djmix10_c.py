#!/usr/bin/env python3
"""djmix10 patch C: telemetry init, plan event, tests."""
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

PS = "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt"
# init telemetry at the single controller-construction call site.
edit(PS,
     """        val controller = createCrossfadeController()
        crossfade = controller""",
     """        AutomixTelemetry.init(this)
        val controller = createCrossfadeController()
        crossfade = controller""")

CC = "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt"
# plan event at the (deduped) publish site: logKey change gate.
edit(CC,
     """        val logKey = "${choice.template}|${phraseLock.locked}|${phraseLock.slides}|${choice.exit.releaseBar}"
        if (logKey != phraseLogKey) {
            phraseLogKey = logKey""",
     """        val logKey = "${choice.template}|${phraseLock.locked}|${phraseLock.slides}|${choice.exit.releaseBar}"
        if (logKey != phraseLogKey) {
            phraseLogKey = logKey
            AutomixTelemetry.event("mix.plan", mapOf("template" to choice.template, "locked" to phraseLock.locked, "slides" to phraseLock.slides))""")

print("patch C done")
