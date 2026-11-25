#!/usr/bin/env python3
"""Focused lexical/coverage fixtures for check_audiothread_alloc.py."""

from __future__ import annotations

import importlib.util
import tempfile
from pathlib import Path


SCRIPT = Path(__file__).with_name("check_audiothread_alloc.py")
SPEC = importlib.util.spec_from_file_location("alloc_gate", SCRIPT)
assert SPEC and SPEC.loader
alloc_gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(alloc_gate)


def violations(source: str) -> list[str]:
    with tempfile.TemporaryDirectory(prefix="alloc-fixture-") as directory:
        path = Path(directory) / "Fixture.kt"
        path.write_text(source, encoding="utf-8")
        return alloc_gate.scan_file(path)


def main() -> None:
    assert len(violations("@AudioThread fun oneLine() { FloatArray (1) }")) == 1
    assert len(violations("""@AudioThread
fun closing() {
    val value = 1
    FloatArray(1) }
""")) == 1
    assert not violations('''@AudioThread
fun strings() {
    val text = """FloatArray(1) ${"not an allocation"}"""
    val escaped = "quote\\\" then safe"
}
''')
    assert len(violations('''@AudioThread
fun tripleAndEscaped() {
    val text = """FloatArray(1)"""
    val escaped = "quote\\\" then safe"
    FloatArray(1)
}
''')) == 1
    assert len(violations('''@AudioThread
fun interpolation() {
    val text = "value ${FloatArray(1)}"
}
''')) == 1
    assert len(violations('''@AudioThread
fun tripleInterpolation() {
    val text = """value ${copyOf(1)}"""
}
''')) == 1
    assert len(violations('''@AudioThread
fun collectionCalls() {
    val a = samples.copyOf(2)
    val b = values.toList()
    Log.d("tag", "message")
}
''')) == 3
    assert len(violations('''@AudioThread
fun defaultArg(
    value: Int = if (true) 1 else 2
) {
    FloatArray(1)
}
''')) == 1
    assert not violations('''@AudioThread
fun legitimateDefault(
    value: Int = 1
) {
    nativeRender(value)
}
''')

    with tempfile.TemporaryDirectory(prefix="alloc-roots-") as directory:
        root = Path(directory)
        stable = root / "app/src/main/java/com/music/bitchord/playback"
        stable.mkdir(parents=True)
        (stable / "Stable.kt").write_text("class Stable", encoding="utf-8")
        discovered = root / "djmix-new/src/main/kotlin"
        discovered.mkdir(parents=True)
        source = discovered / "Discovered.kt"
        source.write_text("class Discovered", encoding="utf-8")
        files = alloc_gate.collect_kotlin_files(root, alloc_gate.DEFAULT_ROOTS)
        assert source in files
    print("ok: allocation parser/coverage fixtures (10/10)")


if __name__ == "__main__":
    main()
