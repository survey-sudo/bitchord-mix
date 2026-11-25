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
    ("array constructor", re.compile(
        r"\b(?:Byte|Short|Int|Long|Float|Double|Boolean|Char)Array\s*\(|"
        r"\b(?:ArrayList|LinkedList|HashMap|LinkedHashMap|mutableListOf|"
        r"mutableMapOf|mutableSetOf|arrayListOf|hashMapOf|listOf|mapOf|setOf)\s*\("
    )),
    ("copying/collection call", re.compile(
        r"\.(?:copyOf|toList|toMutableList|toSet|toMutableSet|map|mapNotNull|"
        r"filter|filterNot|sorted|sortedBy|distinct)\s*\(|\bcopyOf\s*\("
    )),
    ("logging call", re.compile(r"\b(?:Log|AnalysisLog|Timber)\s*\.")),
    ("logging/printing call", re.compile(r"\b(?:println|printStackTrace)\s*\(")),
]

# Scan roots relative to the repository root (Part B13 scope).
DEFAULT_ROOTS = [
    "app/src/main/java/com/music/bitchord/playback",
    "djmix-core",
    "djmix-analysis",
    "djmix-render",
    "djmix-android",
]


def _interpolation_end(text: str, start: int) -> int:
    """Return the index of the closing brace for ``${`` at *start*.

    The small scanner is deliberately lexical rather than Kotlin-AST based: it
    understands nested braces, comments, and quoted strings so expressions in
    interpolation remain visible without exposing braces from their strings.
    """
    depth = 1
    i = start
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            nl = text.find("\n", i + 2)
            i = n if nl < 0 else nl
            continue
        if text.startswith("/*", i):
            close = text.find("*/", i + 2)
            i = n if close < 0 else close + 2
            continue
        if text.startswith('"""', i):
            close = text.find('"""', i + 3)
            i = n if close < 0 else close + 3
            continue
        if text[i] == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
            continue
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n


def strip_comments_and_strings(text: str) -> str:
    """Blank out comments and string/char literals, keeping newlines so
    line numbers survive. Brace counting and pattern matching then never
    see braces or APIs mentioned inside comments or strings."""
    out = list(text)
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j == -1 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j == -1 else j + 2
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        elif text.startswith('"""', i):
            j = i + 3
            expressions: list[tuple[int, int, str]] = []
            while j < n:
                interpolation = text.find("${", j)
                close = text.find('"""', j)
                if interpolation < 0 or (close >= 0 and close < interpolation):
                    break
                for k in range(j, interpolation):
                    if out[k] != "\n":
                        out[k] = " "
                end = _interpolation_end(text, interpolation + 2)
                expression = strip_comments_and_strings(text[interpolation:end + 1])
                expressions.append((interpolation, min(end + 1, n), expression))
                j = end + 1 if end < n else n
            close = text.find('"""', j)
            end = n if close < 0 else close + 3
            for k in range(i, end):
                if out[k] != "\n":
                    out[k] = " "
            for start, stop, expression in expressions:
                for k, value in enumerate(expression, start):
                    if k < stop:
                        out[k] = value
            i = end
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                if text.startswith("${", j):
                    end = _interpolation_end(text, j + 2)
                    expression = strip_comments_and_strings(text[j:end + 1])
                    for k in range(j, min(end + 1, n)):
                        out[k] = expression[k - j]
                    j = end + 1 if end < n else n
                elif text[j] == "\\" and j + 1 < n:
                    out[j] = out[j + 1] = " "
                    j += 2
                else:
                    out[j] = " "
                    j += 1
            if j < n and text[j] == '"':
                out[j] = " "
                j += 1
            out[i] = " "
            i = j
        elif c == "'":
            out[i] = " "
            j = i + 1
            while j < n and text[j] != "'" and text[j] != "\n":
                if text[j] == "\\" and j + 1 < n:
                    out[j] = out[j + 1] = " "
                    j += 2
                    continue
                out[j] = " "
                j += 1
            if j < n and text[j] == "'":
                out[j] = " "
                j += 1
            i = j
        else:
            i += 1
    return "".join(out)


def _body_span(cleaned: str, annotation_offset: int) -> tuple[int, int] | None:
    fun = re.search(r"\bfun\b", cleaned[annotation_offset:])
    if not fun:
        return None
    fun_start = annotation_offset + fun.start()
    parens = 0
    open_brace = None
    expression = None
    i = fun_start
    while i < len(cleaned):
        c = cleaned[i]
        if c == "(":
            parens += 1
        elif c == ")" and parens:
            parens -= 1
        elif parens == 0 and c == "{" and open_brace is None:
            open_brace = i
            break
        elif parens == 0 and c == "=":
            expression = i + 1
            break
        i += 1
        if i - fun_start > 8192:
            break
    if open_brace is not None:
        depth = 0
        for j in range(open_brace, len(cleaned)):
            if cleaned[j] == "{":
                depth += 1
            elif cleaned[j] == "}":
                depth -= 1
                if depth == 0:
                    return open_brace, j + 1
        return None
    if expression is None:
        return None
    fun_line_start = cleaned.rfind("\n", 0, fun_start) + 1
    base_indent = fun_start - fun_line_start
    end = len(cleaned)
    line = cleaned.find("\n", expression)
    if line < 0:
        return expression, end
    current = line
    while current < len(cleaned):
        next_line = cleaned.find("\n", current + 1)
        if next_line < 0:
            next_line = len(cleaned)
        content = cleaned[current + 1:next_line]
        if content.strip():
            indent = len(content) - len(content.lstrip())
            if indent <= base_indent:
                break
        end = next_line
        current = next_line
    return expression, end


def _regions_with_spans(cleaned: str) -> list[tuple[int, int, int]]:
    regions = []
    for match in re.finditer(r"@(?:[\w.]+\.)?AudioThread\b", cleaned):
        span = _body_span(cleaned, match.end())
        if span is not None:
            regions.append((span[0], span[1], match.start()))
    return regions


def find_audiothread_regions(cleaned: str) -> list[tuple[int, int, int]]:
    """Return (start_line, end_line, annotation_line) 1-based ranges for
    every ``@AudioThread`` region: the function/block body following the
    annotation. Expression-body functions (``fun f() = ...``) cover the
    remainder of their declaration."""
    def line_number(offset: int) -> int:
        return cleaned.count("\n", 0, offset) + 1

    return [
        (line_number(start), line_number(end - 1), line_number(annotation))
        for start, end, annotation in _regions_with_spans(cleaned)
    ]


def scan_file(path: pathlib.Path) -> list[str]:
    """Return violation strings for one Kotlin file."""
    text = path.read_text(encoding="utf-8", errors="replace")
    cleaned = strip_comments_and_strings(text)
    lines = cleaned.split("\n")
    violations: list[str] = []
    for start, end, annotation in _regions_with_spans(cleaned):
        fragment = cleaned[start:end]
        fragment_start_line = cleaned.count("\n", 0, start) + 1
        for label, pattern in FORBIDDEN:
            for match in pattern.finditer(fragment):
                lineno = fragment_start_line + fragment.count("\n", 0, match.start())
                violations.append(
                    f"{path}:{lineno}: {label} '{match.group(0).strip()}' "
                    f"inside @AudioThread (annotated at line "
                    f"{cleaned.count(chr(10), 0, annotation) + 1})"
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
    # Always discover active djmix-* modules in addition to the stable roots.
    # The configured list is a minimum, not a reason to silently drop a module.
    for base in sorted(root.glob("djmix-*")):
        if base.is_dir():
            saw_root = True
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
