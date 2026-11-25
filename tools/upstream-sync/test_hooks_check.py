#!/usr/bin/env python3
"""Regression check for conflict markers in untracked source files."""

from __future__ import annotations

import shutil
import subprocess
import tempfile
from pathlib import Path


SOURCE_ROOT = Path(__file__).resolve().parents[2]


def run(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="hooks-check-") as directory:
        root = Path(directory)
        (root / "tools/upstream-sync").mkdir(parents=True)
        shutil.copy2(SOURCE_ROOT / "tools/upstream-sync/hooks-check.sh", root / "tools/upstream-sync/hooks-check.sh")
        manifest = (SOURCE_ROOT / "tools/upstream-sync/hooks.txt").read_text(encoding="utf-8")
        (root / "tools/upstream-sync/hooks.txt").write_text(manifest, encoding="utf-8")
        (root / "app/src/main/java/com/music/bitchord/data").mkdir(parents=True)
        rows = [line.split("|") for line in manifest.splitlines() if line and not line.startswith("#")]
        required: dict[str, list[str]] = {}
        for hook, path, minimum in rows:
            required.setdefault(path, []).extend([f"// FORK-HOOK({hook})"] * int(minimum))
        for path, markers in required.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("\n".join(markers) + "\n", encoding="utf-8")
        assert len(rows) == 32, f"expected 32 manifest rows, got {len(rows)}"
        assert run(["git", "init", "-q"], root).returncode == 0

        clean = run(["bash", "tools/upstream-sync/hooks-check.sh"], root)
        assert clean.returncode == 0, clean.stdout + clean.stderr
        assert clean.stdout.count("ok   ") == 32, clean.stdout

        source = root / "src/Untracked.kt"
        source.parent.mkdir()
        for marker in ("<<<<<<< HEAD", "=======", ">>>>>>> incoming"):
            source.write_text(f"fun source() {{\n{marker}\n}}\n", encoding="utf-8")
            conflict = run(["bash", "tools/upstream-sync/hooks-check.sh"], root)
            assert conflict.returncode != 0, f"untracked marker escaped: {marker}"
            assert "conflict markers present" in conflict.stdout

        (root / "src/Documentation.md").write_text("<<<<<<< example\n=======\n>>>>>>> example\n", encoding="utf-8")
        source.unlink()
        docs = run(["bash", "tools/upstream-sync/hooks-check.sh"], root)
        assert docs.returncode == 0, docs.stdout + docs.stderr
    print("ok: hooks conflict scan fixtures (32/32 hooks + marker cases)")


if __name__ == "__main__":
    main()
