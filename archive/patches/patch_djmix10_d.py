#!/usr/bin/env python3
"""djmix10 patch D: major 2.0 branding, honest UI states, richer plan telemetry."""
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

edit("build.gradle.kts",
     'extra["bitchordVersionName"] = "1.9.1-djmix10"',
     'extra["bitchordVersionName"] = "2.0.0-djmix10"')

CC = "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt"
# honest UI states: preparing (no lock yet) / ready (locked or shown) / mixing
edit(CC,
     """                state = "planned",
                planType = planned.rung,""",
     """                state = when {
                    phase == Phase.LOOP_RUNNING || phase == Phase.FADING -> "mixing"
                    lockedNow -> "ready"
                    else -> "preparing"
                },
                planType = planned.rung,""")

edit(CC,
     """                state = if (inSeconds > 0) "planned" else "mixing",
                planType = style.replaceFirstChar { it.uppercase() },""",
     """                state = when {
                    inSeconds > 0 && lockedNow -> "ready"
                    inSeconds > 0 -> "preparing"
                    else -> "mixing"
                },
                planType = style.replaceFirstChar { it.uppercase() },""")

# richer plan telemetry: BPMs + analysis honesty at the (deduped) publish site
edit(CC,
     """            AutomixTelemetry.event("mix.plan", mapOf("template" to choice.template, "locked" to phraseLock.locked, "slides" to phraseLock.slides))""",
     """            AutomixTelemetry.event("mix.plan", mapOf("template" to choice.template, "locked" to phraseLock.locked, "slides" to phraseLock.slides, "bpmA" to out.bpm, "bpmB" to inn.bpm, "statusA" to out.status, "statusB" to inn.status, "qualityA" to out.quality, "qualityB" to inn.quality))""")

print("patch D done")
