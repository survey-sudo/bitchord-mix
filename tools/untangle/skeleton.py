#!/usr/bin/env python3
"""Skeleton extractor for the Automix (Part D2) scope.

Walks the Part D2 automix scope (Kotlin + C/C++), strips function bodies,
and emits a signature-only skeleton with original 1-based line numbers so
Wave 1C (and later waves) can reason about structure without loading
~100k-line bodies (PlaybackService.kt ~11k lines, CrossfadeController.kt
~9.8k lines at time of writing).

Output: a single skeleton.txt, one block per file, sections:
  header (path, package, imports/includes, line count)
  types        class/interface/object/enum/struct/namespace declarations
               with start-end spans
  fields       val/var (Kotlin) / member fields (C++) -- `var` marked MUTABLE
  functions    signatures verbatim (continuation lines joined in display
               order) with start-end spans; bodies replaced by a note
Every emitted source line is prefixed `  L<num>| ` so original line
numbers are preserved 1:1.

Scope (repo-relative, Part D2 step 1):
  app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt
  app/.../playback/djmix/**, playback/loopmix/**, playback/smart/**
  app/.../playback/AutoMixVisualFeed.kt, AutomixTelemetry.kt, Bounded*.kt
      (discovered under playback/)
  shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/**
      (+ playback/djmix/**, playback/loopmix/** if present)
  shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/**
      (discovered: JVM/Android adapters for the same smart engine --
       BeatTracker, StemSeparator, NpuSession, AnalysisStore, ...)
  native/loopmix/**, native/analyzer/**, app/src/main/cpp/**
  Named files (automix parts; file taken whole, automix references are
  inside): PlaybackService.kt, smart/TrackAnalyzer.kt (also via dir),
  AppSettings.kt, SettingsSheet.kt, MixViewPopup.kt, PlayerControls.kt,
  MiniPlayer.kt.
Exact paths verified with `find` on branch ae3 (HEAD c1498781).

Python 3 stdlib only. Deterministic: files sorted by repo-relative path.

Usage:
  python3 tools/untangle/skeleton.py [--repo DIR] [--out FILE]
Defaults: --repo = repo root inferred from this script's location
(tools/untangle/../../), --out = <repo>/../Bitchord/work/ae3-untangle/
skeleton.txt (coordination workspace sibling).
"""

import argparse
import re
import sys
from pathlib import Path

# --------------------------------------------------------------------------
# Scope
# --------------------------------------------------------------------------

EXPLICIT_FILES = [
    "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt",
    "app/src/main/java/com/music/bitchord/playback/PlaybackService.kt",
    "app/src/main/java/com/music/bitchord/playback/smart/TrackAnalyzer.kt",
    "app/src/main/java/com/music/bitchord/playback/AutoMixVisualFeed.kt",
    "app/src/main/java/com/music/bitchord/playback/AutomixTelemetry.kt",
    "app/src/main/java/com/music/bitchord/data/settings/AppSettings.kt",
    "app/src/main/java/com/music/bitchord/ui/screens/SettingsSheet.kt",
    "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/MixViewPopup.kt",
    "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/player/PlayerControls.kt",
    "app/src/main/java/com/music/bitchord/ui/components/MiniPlayer.kt",
]

KT_DIRS = [
    "app/src/main/java/com/music/bitchord/playback/djmix",
    "app/src/main/java/com/music/bitchord/playback/loopmix",
    "app/src/main/java/com/music/bitchord/playback/smart",
    "shared/src/commonMain/kotlin/com/music/bitchord/playback/smart",
    "shared/src/commonMain/kotlin/com/music/bitchord/playback/djmix",
    "shared/src/commonMain/kotlin/com/music/bitchord/playback/loopmix",
    "shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart",
]

KT_GLOBS = [
    "app/src/main/java/com/music/bitchord/playback/Bounded*.kt",
]

CXX_DIRS = [
    "native/loopmix",
    "native/analyzer",
    "app/src/main/cpp",
]

CXX_EXTS = {".c", ".cc", ".cpp", ".h", ".hpp", ".cxx", ".hxx"}


def find_scope(repo: Path):
    out = set()
    for rel in EXPLICIT_FILES:
        p = repo / rel
        if p.is_file():
            out.add(rel)
        else:
            print(f"WARN: explicit scope file missing: {rel}", file=sys.stderr)
    for d in KT_DIRS:
        base = repo / d
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*.kt")):
            out.add(str(p.relative_to(repo)))
    for pat in KT_GLOBS:
        for p in sorted(repo.glob(pat)):
            if p.is_file():
                out.add(str(p.relative_to(repo)))
    for d in CXX_DIRS:
        base = repo / d
        if not base.is_dir():
            continue
        for p in sorted(base.rglob("*")):
            if p.is_file() and p.suffix in CXX_EXTS:
                out.add(str(p.relative_to(repo)))
    return sorted(out)


# --------------------------------------------------------------------------
# Comment/string blanking (brace counting must not see braces in literals)
# --------------------------------------------------------------------------

def blank_literals(text: str, kotlin: bool) -> str:
    # Return text with comments and string/char literals replaced by spaces
    # (newlines preserved), so brace/paren counting is sound.
    # Kotlin block comments nest; triple-quoted raw strings supported.
    chars = list(text)
    n = len(text)
    i = 0
    # states: 0 normal, 1 line comment, 2 block comment, 3 string, 4 raw, 5 char
    state = 0
    depth = 0
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state == 0:
            if c == "/" and nxt == "/":
                chars[i] = chars[i + 1] = " "
                state = 1
                i += 2
                continue
            if c == "/" and nxt == "*":
                chars[i] = chars[i + 1] = " "
                state = 2
                depth = 1
                i += 2
                continue
            if kotlin and c == '"' and text.startswith('"""', i):
                chars[i] = chars[i + 1] = chars[i + 2] = " "
                state = 4
                i += 3
                continue
            if c == '"':
                chars[i] = " "
                state = 3
                i += 1
                continue
            if c == "'":
                chars[i] = " "
                state = 5
                i += 1
                continue
            i += 1
        elif state == 1:
            if c == "\n":
                state = 0
            else:
                chars[i] = " "
            i += 1
        elif state == 2:
            if kotlin and c == "/" and nxt == "*":
                chars[i] = chars[i + 1] = " "
                depth += 1
                i += 2
                continue
            if c == "*" and nxt == "/":
                chars[i] = chars[i + 1] = " "
                depth -= 1
                i += 2
                if depth <= 0:
                    state = 0
                continue
            if c != "\n":
                chars[i] = " "
            i += 1
        elif state == 3:
            if c == "\\" and i + 1 < n:
                chars[i] = " "
                if text[i + 1] != "\n":
                    chars[i + 1] = " "
                i += 2
                continue
            if c == '"':
                chars[i] = " "
                state = 0
                i += 1
                continue
            if c == "\n":
                # unterminated (should not happen in valid code) -- bail out
                state = 0
            else:
                chars[i] = " "
            i += 1
        elif state == 4:
            if text.startswith('"""', i):
                chars[i] = chars[i + 1] = chars[i + 2] = " "
                state = 0
                i += 3
                continue
            if c != "\n":
                chars[i] = " "
            i += 1
        elif state == 5:
            if c == "\\" and i + 1 < n:
                chars[i] = " "
                if text[i + 1] != "\n":
                    chars[i + 1] = " "
                i += 2
                continue
            if c == "'":
                chars[i] = " "
                state = 0
                i += 1
                continue
            if c == "\n":
                state = 0
            else:
                chars[i] = " "
            i += 1
    return "".join(chars)


# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------

def line_of(text_clean: str, off: int) -> int:
    return text_clean.count("\n", 0, off) + 1


def brace_span(clean_lines, start_idx: int):
    """Given 0-based start line idx, find the block starting at the first
    '{' at/after it (ignoring text before). Return (end_idx_0based, opened).
    opened=False if no '{' found before a `;` at depth 0 (declaration)."""
    depth = 0
    opened = False
    i = start_idx
    while i < len(clean_lines):
        line = clean_lines[i]
        for ch in line:
            if ch == "{":
                depth += 1
                opened = True
            elif ch == "}":
                depth -= 1
                if opened and depth == 0:
                    return i, True
        # If a declaration ends with ';' before any '{', not a block.
        if not opened and ";" in line:
            return start_idx, False
        i += 1
    return len(clean_lines) - 1, opened


def rstrip_none(s: str) -> str:
    return s.rstrip()


def fmt_line(num: int, raw: str) -> str:
    return f"{num:>6} | {rstrip_none(raw)}"


# --------------------------------------------------------------------------
# Kotlin
# --------------------------------------------------------------------------

KT_PACKAGE_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$")
KT_IMPORT_RE = re.compile(r"^\s*import\s+(.+?)\s*$")
KT_TYPE_RE = re.compile(
    r"(?:^|[\s;])(class|interface|object)\s+(\w+)"
)
KT_TYPE_LINE_RE = re.compile(r"\b(class|interface|object)\s+(\w+)")
KT_FUN_RE = re.compile(r"\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w.<>?,\s]+\.)?(\w+)\s*\(")
KT_TYPEALIAS_RE = re.compile(r"^\s*typealias\s+(\w+)")
KT_FIELD_RE = re.compile(
    r"^\s*(?:@[\w.]+(?:\([^)]*\))?\s*)?"
    r"(?:(?:private|public|protected|internal|lateinit|const|override|abstract|"
    r"open|final|inline|external|actual|expect|suspend|crossinline|noinline)\s+)*"
    r"(val|var)\s+(\w+)\b"
)


def parse_kotlin(lines, clean_lines, rel):
    # package / imports from raw lines (safe: they never occur in literals
    # as full-line matches in this codebase's style)
    package = ""
    imports = []
    for idx, raw in enumerate(lines, 1):
        m = KT_PACKAGE_RE.match(raw)
        if m and not package:
            package = m.group(1).strip()
        mi = KT_IMPORT_RE.match(raw)
        if mi:
            imports.append((idx, mi.group(1).strip()))

    # ---- functions (span via brace counting) ----
    funcs = []
    for idx0, cline in enumerate(clean_lines, 0):
        m = KT_FUN_RE.search(cline)
        if not m:
            continue
        name = m.group(1)
        # Determine kind: expression body (`= ...`) vs block vs abstract.
        # Gather the header extent: up to first '{', or '=' after matching
        # ')' , or ';' / end of declaration. We scan lines forward.
        depth_paren = 0
        seen_paren = False
        j = idx0
        body_open_line = None
        expr_eq_line = None
        abstract = False
        header_lines = []
        while j < len(clean_lines):
            l = clean_lines[j]
            header_lines.append(j)
            for ch in l:
                if ch == "(":
                    depth_paren += 1
                    seen_paren = True
                elif ch == ")":
                    depth_paren -= 1
                if seen_paren and depth_paren == 0 and body_open_line is None and expr_eq_line is None:
                    # after close of param list, look ahead on remainder
                    pass
            # Checks (only once params closed at least once on this line or later)
            if seen_paren and depth_paren == 0:
                if "{" in l:
                    # ensure '{' is after ')' on this logical segment:
                    # find last ')' then look for '{' after it
                    rp = l.rfind(")")
                    if rp >= 0 and "{" in l[rp:]:
                        body_open_line = j
                        break
                    if j == idx0 and "{" in l:
                        # '{' before ')' : lambda/default -- keep scanning
                        pass
                    elif body_open_line is None and expr_eq_line is None and j > idx0:
                        # '{' may be function body even if on later line
                        body_open_line = j
                        break
                if "=" in l:
                    rp = l.rfind(")")
                    if rp >= 0 and "=" in l[rp:]:
                        expr_eq_line = j
                        break
                    if j == idx0:
                        expr_eq_line = j
                        break
            if j - idx0 > 20:  # pathological header; bail
                break
            j += 1
        if body_open_line is not None:
            end0, _ = brace_span(clean_lines, body_open_line)
            # brace_span searches from body_open_line; it returns the line
            # where depth returns to 0. But it counts from first '{' it sees.
            # If body_open_line line itself has the '{', that line is correct.
            end = end0 + 1
            start = idx0 + 1
            sig_lines = [k + 1 for k in header_lines if k <= body_open_line]
            funcs.append({
                "name": name, "start": start, "end": end,
                "sig": sig_lines, "body": f"<body stripped: lines "
                f"{start}-{end}, {end - start + 1} lines>",
                "body_note": "",
            })
        elif expr_eq_line is not None:
            # Expression body: span covers the expression. If the expression
            # opens a block (when/if with '{'), count braces from there.
            sub = "\n".join(clean_lines[idx0: expr_eq_line + 1])
            if "{" in sub:
                # find first '{' line; count block; span ends there.
                for k in range(idx0, expr_eq_line + 1):
                    if "{" in clean_lines[k]:
                        e0, _ = brace_span(clean_lines, k)
                        funcs.append({
                            "name": name, "start": idx0 + 1, "end": e0 + 1,
                            "sig": [idx0 + 1], "body": "",
                            "body_note": "expression body (= ...) with block; "
                            "span shown",
                        })
                        break
                else:
                    funcs.append({"name": name, "start": idx0 + 1,
                                  "end": expr_eq_line + 1, "sig": [idx0 + 1],
                                  "body": "", "body_note": "expression body (= ...)"})
            else:
                # multi-line expression without braces: extend while the
                # following lines look like continuation (start with op dot?)
                # Keep span to expr line for determinism.
                funcs.append({"name": name, "start": idx0 + 1,
                              "end": expr_eq_line + 1, "sig": [idx0 + 1],
                              "body": "", "body_note": "expression body (= ...)"})
        else:
            # No body found (abstract/interface member with no initializer)
            funcs.append({"name": name, "start": idx0 + 1, "end": idx0 + 1,
                          "sig": [idx0 + 1], "body": "",
                          "body_note": "no body (abstract/interface)"})

    # function line spans (1-based, inclusive) for field filtering
    fun_ranges = [(f["start"], f["end"]) for f in funcs]

    def inside_fun(ln: int) -> bool:
        return any(a <= ln <= b for a, b in fun_ranges)

    # ---- types ----
    types = []
    for idx0, cline in enumerate(clean_lines, 0):
        if KT_TYPEALIAS_RE.match(cline):
            m = KT_TYPEALIAS_RE.match(cline)
            types.append({"name": m.group(1), "kind": "typealias",
                          "start": idx0 + 1, "end": idx0 + 1,
                          "line": idx0 + 1})
            continue
        # companion object
        if re.search(r"\bcompanion\s+object\b", cline):
            # span via first '{'
            if "{" in cline or True:
                e0, opened = brace_span(clean_lines, idx0)
                types.append({"name": "companion object",
                              "kind": "companion object",
                              "start": idx0 + 1, "end": e0 + 1,
                              "line": idx0 + 1})
            continue
        m = KT_TYPE_LINE_RE.search(cline)
        if not m:
            continue
        # Avoid matching `class` inside a string/comment (already blanked)
        # and 'object :' inside `object : Expression` anonymous -- those ARE
        # anonymous objects; skip them (no name captured anyway).
        kind_word = m.group(1)
        name = m.group(2)
        # Skip function-call contexts like `Foo.class`? not Kotlin-real but
        # the regex requires `class Name`; '::class' won't match. Also skip
        # `where class` constraints -- rare; accept.
        # Heuristic: `object` must be a declaration: line up to match should
        # contain only modifiers / separators, not `=`, `(`, `,`.
        lead = cline[: m.start()]
        if kind_word == "object" and ("=" in lead or "(" in lead or "," in lead or ":" in lead):
            continue
        if "=" in lead and kind_word in ("class", "interface"):
            continue
        # modifiers prefix for display
        prefix = cline.strip()
        # drop trailing '{' / ':' for kind display
        e0, opened = brace_span(clean_lines, idx0)
        # brace_span returns start line for `;`-terminated (no body)
        end = (e0 + 1) if opened else idx0 + 1
        types.append({"name": name, "kind": prefix[:60],
                      "start": idx0 + 1, "end": end, "line": idx0 + 1})

    # ---- fields (outside function bodies) ----
    fields = []
    for idx0, (raw, cline) in enumerate(zip(lines, clean_lines)):
        ln = idx0 + 1
        if inside_fun(ln):
            continue
        if re.search(r"\bfun\b", cline):
            continue
        m = KT_FIELD_RE.match(cline)
        if m:
            fields.append((ln, m.group(1), m.group(2)))

    return package, imports, types, fields, funcs


# --------------------------------------------------------------------------
# C / C++
# --------------------------------------------------------------------------

CXX_INCLUDE_RE = re.compile(r'^\s*#\s*include\s*(<[^>]+>|"[^"]+")')
CXX_NAMESPACE_RE = re.compile(r"^\s*namespace\s+([\w:]+)")
CXX_TYPE_RE = re.compile(r"^\s*(?:enum\s+class|class|struct|union|enum)\s+(\w+)")
CXX_CONTROL = {"if", "while", "for", "switch", "catch", "return", "sizeof"}


def parse_cxx(lines, clean_lines, rel):
    includes = []
    for idx, raw in enumerate(lines, 1):
        m = CXX_INCLUDE_RE.match(raw)
        if m:
            includes.append((idx, m.group(1).strip()))
    namespaces = []
    types = []
    funcs = []
    fields = []
    for idx0, cline in enumerate(clean_lines, 0):
        ln = idx0 + 1
        m = CXX_NAMESPACE_RE.match(cline)
        if m and "{" in cline:
            e0, _ = brace_span(clean_lines, idx0)
            namespaces.append({"name": m.group(1), "start": ln, "end": e0 + 1})
            continue
        m = CXX_TYPE_RE.match(cline)
        if m:
            e0, opened = brace_span(clean_lines, idx0)
            types.append({"name": m.group(1), "start": ln,
                          "end": (e0 + 1) if opened else ln,
                          "line": ln})
            continue
        # function definition: signature line containing '(' ... ')' with a
        # '{'-opened body, and NOT a control statement / continuation line /
        # 'try|catch|else' fragment / type or namespace decl. Continuation
        # lines of `if (` conditions start with `&&`/`||`/`)` and catch
        # closers start with `}` -- both must be excluded.
        stripped = cline.strip()
        _skip_start = stripped[:1] in ("}", "{", "&", "|", ")", "]", "#")
        _has_kw = bool(re.search(
            r"\b(if|while|for|switch|catch|return|sizeof|else|do|try)\s*\(",
            cline))
        if (not _skip_start) and (not _has_kw) and (
                stripped.endswith("{")
                or ("{" in cline and "(" in cline and ")" in cline)):
            if "(" in cline and ")" in cline:
                first_word = re.match(r"^\s*([A-Za-z_][\w:]*)\s*\(", cline)
                kw = first_word.group(1).split("::")[-1] if first_word else ""
                # 'class Foo {' has no '(' so excluded; 'namespace {' excluded
                # 'if (' 'for (' excluded; macros like 'FOO(' rare here.
                if kw not in CXX_CONTROL and not CXX_NAMESPACE_RE.match(cline) \
                        and not CXX_TYPE_RE.match(cline):
                    e0, opened = brace_span(clean_lines, idx0)
                    if opened:
                        # signature = this line (most definitions keep the
                        # signature on one line; if the '{' is on a following
                        # line, that line is included because it is the
                        # brace_span search start... handled: if '{' not on
                        # this line, brace_span still finds it; end correct.
                        fname = "(unknown)"
                        fm = re.search(r"([~A-Za-z_][\w:]*)\s*\([^)]*\)", cline)
                        if fm:
                            fname = fm.group(1)
                        funcs.append({"name": fname, "start": ln,
                                      "end": e0 + 1, "sig": [ln],
                                      "body_note": ""})
    # fields: lines ending ';' inside a type span, not function prototypes,
    # not preprocessor, not namespace/type decl lines. Runs after types so
    # spans known.
    fun_ranges = [(f["start"], f["end"]) for f in funcs]
    type_ranges = [(t["start"], t["end"]) for t in types]

    def inside(ranges, ln):
        return any(a <= ln <= b for a, b in ranges)

    for idx0, cline in enumerate(clean_lines, 0):
        ln = idx0 + 1
        s = cline.strip()
        if not s.endswith(";"):
            continue
        if s.startswith("#"):
            continue
        if inside(fun_ranges, ln):
            continue
        if not inside(type_ranges, ln):
            continue
        if "(" in s and ")" in s:
            # prototype inside type -> treat as function declaration
            fm = re.search(r"([~A-Za-z_][\w:]*)\s*\(", s)
            if fm:
                funcs.append({"name": fm.group(1), "start": ln, "end": ln,
                              "sig": [ln], "body_note": "declaration (prototype)"})
            continue
        if re.match(r"^(?:class|struct|union|enum)\b", s):
            continue
        # member field (including initializers, arrays, refs)
        fields.append((ln, s))
    funcs.sort(key=lambda f: f["start"])
    type_ranges_all = namespaces + types
    return includes, namespaces, types, fields, funcs


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------

def render_kotlin(rel, lines, clean_lines, package, imports, types, fields, funcs):
    out = []
    out.append("=" * 78)
    out.append(f"FILE: {rel}  (lines 1-{len(lines)})")
    out.append("=" * 78)
    if package:
        out.append(f"package: {package}")
    if imports:
        out.append("--- imports ---")
        for num, imp in imports:
            out.append(f"  {fmt_line(num, 'import ' + imp)}")
    if types:
        out.append("--- types (declarations, spans) ---")
        for t in types:
            span = f"[{t['start']}-{t['end']}]"
            out.append(f"  L{t['line']}| {t['kind']}  {span}")
    if fields:
        out.append("--- fields / properties (function locals excluded) ---")
        for ln, kind, name in fields:
            mut = "  /* MUTABLE (var) */" if kind == "var" else ""
            out.append(f"  {fmt_line(ln, lines[ln - 1].rstrip())}{mut}")
    if funcs:
        out.append("--- functions (signatures; bodies stripped) ---")
        for f in funcs:
            for sn in f["sig"]:
                note = ""
                out.append(f"  {fmt_line(sn, lines[sn - 1].rstrip())}")
            span = f"(L{f['start']}-L{f['end']}, {f['end'] - f['start'] + 1} lines)"
            extra = f"  [{f['body_note']}]" if f.get("body_note") else ""
            body = f["body"] if f.get("body") else ""
            out.append(f"      {span} {body}{extra}".rstrip())
    out.append("")
    return "\n".join(out)


def render_cxx(rel, lines, includes, namespaces, types, fields, funcs):
    out = []
    out.append("=" * 78)
    out.append(f"FILE: {rel}  (lines 1-{len(lines)})")
    out.append("=" * 78)
    if includes:
        out.append("--- includes ---")
        for num, inc in includes:
            out.append(f"  {fmt_line(num, '#include ' + inc)}")
    if namespaces:
        out.append("--- namespaces ---")
        for ns in namespaces:
            out.append(f"  namespace {ns['name']}  [L{ns['start']}-L{ns['end']}]")
    if types:
        out.append("--- types (declarations, spans) ---")
        for t in types:
            out.append(f"  L{t['line']}| {lines[t['line'] - 1].strip()} "
                       f"[{t['start']}-{t['end']}]")
    if fields:
        out.append("--- fields (member declarations) ---")
        for ln, s in fields:
            out.append(f"  {fmt_line(ln, lines[ln - 1].rstrip())}")
    if funcs:
        out.append("--- functions (signatures; bodies stripped) ---")
        for f in funcs:
            for sn in f["sig"]:
                out.append(f"  {fmt_line(sn, lines[sn - 1].rstrip())}")
            extra = f"  [{f['body_note']}]" if f.get("body_note") else ""
            out.append(f"      (L{f['start']}-L{f['end']}, "
                       f"{f['end'] - f['start'] + 1} lines){extra}")
    out.append("")
    return "\n".join(out)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Automix skeleton extractor")
    here = Path(__file__).resolve()
    default_repo = here.parent.parent.parent
    ap.add_argument("--repo", default=str(default_repo))
    ap.add_argument("--out", default=None,
                    help="default: <repo>/../Bitchord/work/ae3-untangle/skeleton.txt")
    args = ap.parse_args(argv)
    repo = Path(args.repo).resolve()
    if args.out:
        out_path = Path(args.out).expanduser().resolve()
    else:
        out_path = (repo.parent / "Bitchord" / "work" / "ae3-untangle"
                    / "skeleton.txt")
    scope = find_scope(repo)
    if not scope:
        print("ERROR: empty scope", file=sys.stderr)
        return 1

    parts = []
    tot_lines = 0
    tot_types = 0
    tot_fields = 0
    tot_funcs = 0
    tot_var = 0
    for rel in scope:
        path = repo / rel
        text = path.read_text(encoding="utf-8", errors="replace")
        lines = text.splitlines()
        tot_lines += len(lines)
        is_kt = rel.endswith(".kt")
        clean = blank_literals(text, kotlin=is_kt)
        clean_lines = clean.splitlines()
        # splitlines drops a trailing empty line; pad to len(lines)
        while len(clean_lines) < len(lines):
            clean_lines.append("")
        if is_kt:
            package, imports, types, fields, funcs = parse_kotlin(
                lines, clean_lines, rel)
            parts.append(render_kotlin(rel, lines, clean_lines,
                                       package, imports, types, fields, funcs))
            tot_types += len(types)
            tot_fields += len(fields)
            tot_funcs += len(funcs)
            tot_var += sum(1 for _, k, _ in fields if k == "var")
        else:
            includes, namespaces, types, fields, funcs = parse_cxx(
                lines, clean_lines, rel)
            parts.append(render_cxx(rel, lines, includes, namespaces,
                                    types, fields, funcs))
            tot_types += len(types)
            tot_fields += len(fields)
            tot_funcs += len(funcs)

    header = [
        "AUTOMIX (Part D2) SKELETON -- signature-only, bodies stripped",
        "Generated by: tools/untangle/skeleton.py (Python 3 stdlib)",
        f"Scope files: {len(scope)}  (repo-relative, sorted)",
        f"Total source lines: {tot_lines}",
        f"Declarations: types={tot_types} fields={tot_fields} "
        f"(mutable var={tot_var}) functions={tot_funcs}",
        "Format: every source line emitted as `  L<num>| <verbatim text>` "
        "with ORIGINAL 1-based line numbers; function bodies replaced by "
        "`<body stripped ...>` span notes. Field lines outside function "
        "bodies only (locals excluded).",
        "",
    ]
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text("\n".join(header) + "\n\n" + "\n".join(parts),
                        encoding="utf-8")
    print(f"skeleton.py: {len(scope)} files, {tot_lines} lines -> {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
