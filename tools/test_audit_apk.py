#!/usr/bin/env python3
"""Regression fixtures for the APK ELF PT_LOAD alignment predicate."""

from __future__ import annotations

import os
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
AUDIT = ROOT / "tools" / "audit-apk.sh"


def elf64(alignments: list[int]) -> bytes:
    header = bytearray(64 + 56 * len(alignments))
    header[:4] = b"\x7fELF"
    header[4:6] = bytes((2, 1))
    struct.pack_into("<Q", header, 32, 64)
    struct.pack_into("<HH", header, 54, 56, len(alignments))
    for index, alignment in enumerate(alignments):
        offset = 64 + index * 56
        struct.pack_into("<I", header, offset, 1)
        struct.pack_into("<Q", header, offset + 48, alignment)
    return bytes(header)


def make_zip(path: Path, payload: bytes) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("lib/arm64-v8a/libonnxruntime.so", payload + b"x" * (34208672 - len(payload)))
        jni_payload = (payload + b"\0" * max(0, 92064 - len(payload))) if payload.startswith(b"\x7fELF") else b"j" * 92064
        archive.writestr("lib/arm64-v8a/libonnxruntime4j_jni.so", jni_payload)
        for name in (
            "analysis",
            "audio",
            "loopmix",
            "stretch",
            "stem",
            "mel",
            "vocal",
        ):
            archive.writestr(f"lib/arm64-v8a/libbitchord_{name}.so", payload)
        archive.writestr("assets/nirovi_int8.ort", b"n" * 7948784, compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr("assets/syvenu.ort", b"s" * 2349304, compress_type=zipfile.ZIP_DEFLATED)
        archive.writestr("assets/models.json", b"{}")
        for index in range(26):
            archive.writestr(f"assets/recipes/r{index:02d}.json", b"{}")


def run_case(temp: Path, name: str, payload: bytes, readelf_output: str | None, force_python: bool) -> int:
    apk = temp / f"{name}.apk"
    make_zip(apk, payload)
    fake_bin = temp / f"bin-{name}"
    fake_bin.mkdir()
    if readelf_output is not None:
        (fake_bin / "readelf").write_text(
            "#!/bin/sh\nprintf '%s\\n' \"$FAKE_READELF\"\n", encoding="utf-8"
        )
        (fake_bin / "readelf").chmod(0o755)
    # Force the structural script to use its explicit Python parser. For the
    # readelf fixtures, the fake tool stands in for a real tool's output.
    env = os.environ.copy()
    env["PATH"] = f"{fake_bin}:{env['PATH']}" if readelf_output is not None else env["PATH"]
    env["FAKE_READELF"] = readelf_output or ""
    if force_python:
        env["AUDIT_FORCE_PYTHON_ELF"] = "1"
    else:
        env.pop("AUDIT_FORCE_PYTHON_ELF", None)
    # The audit checks pinned hashes; replace only sha256sum in this isolated
    # process so fixtures stay tiny and deterministic.
    (fake_bin / "sha256sum").write_text(
        "#!/bin/sh\ncase \"$1\" in\n"
        "*bitchord-ort-asset*) if [ -e \"$SHA_STATE\" ]; then n=\"1866a5955ed6b0e80f807cb49f56af530121064b8ea5322026566126eb3cd4f3\"; else touch \"$SHA_STATE\"; n=\"7454d98ab6a88c0b7fe5e91c556b4766ebfabfe3d31ebd61187a033fed489387\"; fi;;\n"
        "*bitchord-ort-core*) n=\"fcec92f4ef56964a2ba312cfcadbdbe03070a3165069712a41b4ce7115efa61f\";;\n"
        "*) n=fixture;;\nesac\nprintf '%s  %s\\n' \"$n\" \"$1\"\n",
        encoding="utf-8",
    )
    (fake_bin / "sha256sum").chmod(0o755)
    env["PATH"] = f"{fake_bin}:{env['PATH']}"
    env["SHA_STATE"] = str(temp / f"sha-state-{name}")
    result = subprocess.run(
        ["bash", str(AUDIT), "--structural-only", str(apk)],
        cwd=ROOT,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    return result.returncode


def main() -> None:
    cases = {
        "all-good": ([0x4000, 0x8000], 3),
        "mixed": ([0x4000, 0x1000], 1),
        "all-bad": ([0x1000, 0x1000], 1),
        "zero-load": ([], 1),
        "malformed": (None, 1),
    }
    with tempfile.TemporaryDirectory(prefix="audit-apk-test-") as directory:
        temp = Path(directory)
        for force_python in (False, True):
            for name, (alignments, expected) in cases.items():
                if force_python:
                    payload = elf64(alignments or []) if alignments is not None else b"not-elf"
                    readelf_output = None
                else:
                    payload = b"fixture"
                    readelf_output = (
                        "\n".join(f"  LOAD 0 0 0 0 0 0 0 0 0x{alignment:x}" for alignment in alignments)
                        if alignments is not None
                        else "  LOAD 0 0 0 0 0 0 0 0 malformed"
                    )
                got = run_case(temp, f"{name}-{'py' if force_python else 'tool'}", payload, readelf_output, force_python)
                assert got == expected, f"{name} force_python={force_python}: expected {expected}, got {got}"
    print("ok: audit-apk ELF alignment fixtures (10/10)")


if __name__ == "__main__":
    main()
