#!/usr/bin/env python3
"""@AudioThread allocation lint (Part B13).

Scans Kotlin sources under ``app/src/main/java/com/music/bitchord/playback/**``
and ``djmix-*/**`` for ``@AudioThread``-annotated functions/blocks and flags
allocation/logging APIs inside them:

    FloatArray( DoubleArray( IntArray( ArrayList( HashMap( listOf(
    mutableListOf( mapOf( setOf( Log.

The audio thread must not allocate or log: no decode, no inference, no
allocation at transition time (Part A section 2.3). Build the renderer on a
prep thread during READY; arming only swaps a pointer.

Exit code: 0 when no violations, 1 when any violation is found, 2 on
usage/IO errors. With no ``@AudioThread`` annotations in the tree (the
current state) this passes trivially.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

# Allocation/logging APIs forbidden inside @AudioThread regions (B13).
FORBIDDEN = [
    "FloatArray(",
    "DoubleArray(",
    "IntArray(",
    "ArrayList(",
    "HashMap(",
    "mutableListOf(",
    "listOf(",
    "mapOf(",
    "setOf(",
    "Log.",
]

# Scan roots relative to the repository root (Part B13 scope).
DEFAULT_ROOTS = [
    "app/src/main/java/com/music/bitchord/playback",
    "djmix-core",
    "djmix-analysis",
    "djmix-render",
    "djmix-android",
]


def strip_comments_and_strings(text: str) -> str:
    """Blank out comments and string/char literals, keeping newlines so
    line numbers survive. Brace counting and pattern matching then never
    see braces or APIs mentioned inside comments or strings."""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        two = text[i : i + 2]
        if two == "//":
            j = text.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif two == "/*":
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif two == '"""':
            j = text.find('"""', i + 3)
            j = n if j == -1 else j + 3
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif c == '"':
            out[i] = " "
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                if text[j] == "\\" and j + 1 < n:
                    out[j] = out[j + 1] = " "
                    j += 2
                else:
                    out[j] = " "
                    j += 1
            i = j
        elif c == "'":
            out[i] = " "
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                out[j] = " "
                j += 1
            i = j
        else:
            i += 1
    return "".join(out)


def find_audiothread_regions(cleaned: str) -> list[tuple[int, int, int]]:
    """Return (start_line, end_line, annotation_line) 1-based ranges for
    every ``@AudioThread`` region: the function/block body following the
    annotation. Expression-body functions (``fun f() = ...``) cover the
    remainder of their declaration."""
    lines = cleaned.split("\n")
    regions: list[tuple[int, int, int]] = []
    for idx, line in enumerate(lines):
        if "@AudioThread" not in line:
            continue
        annotation_line = idx + 1
        # Scan forward for the first '{' (the body) starting at the
        # annotation. If a line with 'fun ' and '=' and no '{' appears
        # first, it is an expression body.
        depth = 0
        seen_open = False
        end = None
        for j in range(idx, len(lines)):
            cur = lines[j]
            if not seen_open and "fun " in cur and "=" in cur and "{" not in cur:
                # Expression body: covers this line plus any continuation
                # lines indented further than the declaration.
                base_indent = len(cur) - len(cur.lstrip())
                k = j
                while k + 1 < len(lines):
                    nxt = lines[k + 1]
                    if nxt.strip() == "":
                        k += 1
                        break
                    indent = len(nxt) - len(nxt.lstrip())
                    if indent > base_indent:
                        k += 1
                    else:
                        break
                end = k + 1
                break
            for ch in cur:
                if ch == "{":
                    depth += 1
                    seen_open = True
                elif ch == "}":
                    depth -= 1
                    if seen_open and depth == 0:
                        end = j + 1
                        break
            if end is not None:
                break
            # Give up if the annotation is not attached to a body within
            # a reasonable window (e.g. it annotates a class).
            if j - idx > 10 and not seen_open:
                break
        if end is None:
            # No body found (annotation on a class/property): flag nothing.
            continue
        regions.append((annotation_line, end - 1, annotation_line))
    # Regions are (start_of_body_approx=annotation, end, annotation); the
    # caller scans [start .. end] inclusive.
    return regions


def scan_file(path: pathlib.Path) -> list[str]:
    """Return violation strings for one Kotlin file."""
    text = path.read_text(encoding="utf-8", errors="replace")
    cleaned = strip_comments_and_strings(text)
    lines = cleaned.split("\n")
    violations: list[str] = []
    for _start, end, annotation_line in find_audiothread_regions(cleaned):
        # Locate the body start: scan from the annotation line to the
        # first '{' (or '=' for expression bodies) and start scanning
        # there; the end line comes from brace matching.
        body_start = annotation_line - 1  # 0-based fallback
        for j in range(annotation_line - 1, end):
            if "{" in lines[j] or "=" in lines[j]:
                body_start = j
                break
        for lineno in range(body_start + 1, end + 1):
            line = lines[lineno - 1]
            for pattern in FORBIDDEN:
                if pattern in line:
                    violations.append(
                        f"{path}:{lineno}: allocation/logging API '{pattern}' "
                        f"inside @AudioThread (annotated at line {annotation_line})"
                    )
    return violations


def collect_kotlin_files(root: pathlib.Path, roots: list[str]) -> list[pathlib.Path]:
    files: list[pathlib.Path] = []
    saw_root = False
    for rel in roots:
        base = root / rel
        if not base.exists():
            continue
        saw_root = True
        files.extend(sorted(base.rglob("*.kt")))
    # Fall back to the djmix-* glob for any djmix module not listed above.
    if not any(r.startswith("djmix-") for r in roots):
        for base in sorted(root.glob("djmix-*")):
            files.extend(sorted(base.rglob("*.kt")))
    if not saw_root:
        raise FileNotFoundError("no scan roots found under " + str(root))
    # De-duplicate while keeping order.
    seen: set[pathlib.Path] = set()
    unique: list[pathlib.Path] = []
    for f in files:
        if f not in seen:
            seen.add(f)
            unique.append(f)
    return unique


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument(
        "--root",
        default=str(pathlib.Path(__file__).resolve().parent.parent),
        help="repository root (default: parent of tools/)",
    )
    args = ap.parse_args()

    root = pathlib.Path(args.root)
    try:
        files = collect_kotlin_files(root, DEFAULT_ROOTS)
    except FileNotFoundError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    total_regions = 0
    all_violations: list[str] = []
    for path in files:
        try:
            cleaned = strip_comments_and_strings(path.read_text(encoding="utf-8", errors="replace"))
        except OSError as e:
            print(f"error: cannot read {path}: {e}", file=sys.stderr)
            return 2
        total_regions += len(find_audiothread_regions(cleaned))
        all_violations.extend(scan_file(path))

    print(f"checked {len(files)} Kotlin files, {total_regions} @AudioThread regions")
    if all_violations:
        for v in all_violations:
            print(f"FAIL  {v}")
        print(f"{len(all_violations)} @AudioThread allocation/logging violation(s)")
        return 1
    print("ok    no allocation/logging APIs inside @AudioThread regions")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
