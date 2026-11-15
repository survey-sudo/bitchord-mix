#!/usr/bin/env python3
"""Compare two APKs entry by entry, ignoring only APK signature files.

Why this exists: the release APK is built on two machines now (the build box and
GitHub Actions). Matching file sizes proves little, because zip metadata and
signing differ. This compares the actual bytes of every entry so a migration or a
CI build can be called faithful only when the payload matches.

Usage:
    apk-parity.py baseline.apk candidate.apk [--manifest out.json]

Exit status: 0 when the payloads match, 1 when they differ.
"""

import argparse
import hashlib
import json
import sys
import zipfile

# Signing artefacts differ by definition: a debug/absent keystore signs with
# nothing, and v1 signing rewrites MANIFEST.MF. Everything else must match.
SIGNATURE_FILES = {
    "META-INF/MANIFEST.MF",
    "META-INF/CERT.SF",
    "META-INF/CERT.RSA",
    "META-INF/CERT.DSA",
    "META-INF/CERT.EC",
}
SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")


def is_signature(name: str) -> bool:
    if name in SIGNATURE_FILES:
        return True
    if not name.startswith("META-INF/"):
        return False
    # Only top-level META-INF signature files; META-INF/services/... is payload.
    if "/" in name[len("META-INF/"):]:
        return False
    return name.endswith(SIGNATURE_SUFFIXES)


def manifest(path: str) -> dict:
    entries = {}
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            if info.is_dir() or is_signature(info.filename):
                continue
            digest = hashlib.sha256()
            with archive.open(info) as handle:
                for chunk in iter(lambda: handle.read(1 << 20), b""):
                    digest.update(chunk)
            entries[info.filename] = {
                "sha256": digest.hexdigest(),
                "size": info.file_size,
            }
    return entries


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("--manifest", help="write the candidate manifest here")
    args = parser.parse_args()

    base = manifest(args.baseline)
    cand = manifest(args.candidate)

    if args.manifest:
        with open(args.manifest, "w", encoding="utf-8") as handle:
            json.dump(cand, handle, indent=1, sort_keys=True)
            handle.write("\n")

    added = sorted(set(cand) - set(base))
    removed = sorted(set(base) - set(cand))
    changed = sorted(
        name for name in set(base) & set(cand)
        if base[name]["sha256"] != cand[name]["sha256"]
    )

    print(f"baseline : {args.baseline}  ({len(base)} entries)")
    print(f"candidate: {args.candidate}  ({len(cand)} entries)")
    for label, names in (("added", added), ("removed", removed), ("changed", changed)):
        print(f"{label:8s}: {len(names)}")
        for name in names[:40]:
            if label == "changed":
                print(f"    {name}  {base[name]['size']} -> {cand[name]['size']} B")
            else:
                print(f"    {name}")

    if not (added or removed or changed):
        print("\nPAYLOAD IDENTICAL (signature files ignored)")
        return 0

    print("\nPAYLOAD DIFFERS")
    return 1


if __name__ == "__main__":
    sys.exit(main())
