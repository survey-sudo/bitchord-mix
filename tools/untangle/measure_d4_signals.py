#!/usr/bin/env python3
"""Read-only Part D4 signal measurement for the automix surface.

Reports the special-case counts tracked in ``docs/AE3_LEDGER.md`` Table 3 and
the structural signals from ``docs/untangle/DECISION_LOG.md``: file sizes
against the controller/helper budgets, layer and strongly-connected-component
approximations of the import graph, duplicated top-level helper names, imports
of ``CrossfadeController`` from the playback helpers, and the lexical signal
counts (swallowed exceptions, retry/sleep/timeout calls, fallback entry points,
plan-identity comparisons, TODO/FIXME comments).

Python 3 standard library only, deterministic, and read-only: it writes no
repository file. ``--out`` writes the report where the caller asks; the
default prints to stdout.

Honesty rules that shape the implementation:

* Every count is explicitly lexical. A chained ``runCatching {...}.getOrNull()``
  contributes two swallowed-exception signals; that is stated in the output, not
  hidden behind a single "silent failure" number.
* A duplicated helper *name* is not a duplicated *helper*. Clusters are reported
  with a normalized-body digest so identical bodies and same-named-but-different
  bodies are distinguishable.
* The import graph is built from resolvable explicit/wildcard imports. It cannot
  see implicit same-package references or runtime wiring, so components are
  labelled an approximation.
* Targets are read from the committed docs. A target that cannot be found is
  reported as unverified rather than defaulted to a convenient number.

Exit codes: 0 when the measurement completed (debt is reported, not fatal),
1 when ``--strict`` is given and a measured target misses, 2 on bad input.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True

# --------------------------------------------------------------------------- #
# Scope
# --------------------------------------------------------------------------- #

EXCLUDED_DIRS = {
    ".git", ".gradle", ".cxx", ".kotlin", ".idea", "build", "out", "dist",
    "node_modules", "__pycache__", ".venv", "venv", "generated",
}
PLAYBACK = "app/src/main/java/com/music/bitchord/playback"
CONTROLLER = PLAYBACK + "/CrossfadeController.kt"
SCOPE_ROOTS = ("app/src/main/java/com/music/bitchord/playback", "shared/src",
               "djmix-core/src", "djmix-analysis/src", "djmix-render/src")
# The helper trees the D4 decision says must not import the controller.
HELPER_AREAS = ("playback/djmix", "playback/loopmix", "playback/smart")
# The automix surface the committed D4 targets were measured on: the 11 layers
# of docs/untangle/deps_report.md section 1 plus the djmix-* modules. Files
# outside it (data/innertube, AudioCache, lyrics, ...) are still scanned and
# reported, but a target scored on them would be a different, uncommitted
# metric, so they are labelled informational instead.
AUTOMIX_AREAS = {
    "playback/controller", "playback/djmix", "playback/loopmix",
    "playback/smart", "playback/analyzer", "playback/service",
    "playback/settings_telemetry", "playback/ui", "shared/smart",
    "djmix-core", "djmix-analysis", "djmix-render", "djmix-android",
    "native/loopmix", "native/analyzer", "app/cpp",
}

# Rank vocabulary. ``app/*`` ranks are the committed ones in
# ``tools/untangle/deps.py`` / ``docs/untangle/deps_report.md`` §1; the
# ``djmix-*`` ranks follow the B1.1 module order documented in
# ``docs/AE3_LEDGER.md`` (djmix-core -> analysis/render -> android -> app).
LAYER_RANK = {
    "native/loopmix": 0, "native/analyzer": 0, "app/cpp": 1, "shared/smart": 1,
    "djmix-core": 0, "djmix-analysis": 1, "djmix-render": 1, "djmix-android": 2,
    "playback/smart": 2, "playback/djmix": 2, "playback/loopmix": 2,
    "playback/analyzer": 2, "playback/controller": 3, "playback/service": 4,
    "playback/settings_telemetry": 4, "playback/ui": 4, "playback/other": 4,
    "shared/other": 1,
}
# Volatility vocabulary, matching tools/untangle/deps.py so the numbers are
# comparable with the committed deps_report.md counts: a foundation layer that
# imports a UI/service/settings layer is an inversion (HARD if the source is a
# native/pure layer, SOFT otherwise).
HARD_SRC = {"shared/smart", "native/loopmix", "native/analyzer", "djmix-core"}
SOFT_SRC = HARD_SRC | {"app/cpp", "playback/djmix", "playback/loopmix",
                       "playback/analyzer", "djmix-analysis", "djmix-render"}
VOLATILE_DST = {"playback/ui", "playback/service",
                "playback/settings_telemetry"}

# --------------------------------------------------------------------------- #
# Lexical helpers (self-contained: this tool must not depend on the AE3 gate)
# --------------------------------------------------------------------------- #

def mask_source(text: str) -> tuple[str, list[tuple[int, str]]]:
    """Blank comments and literals in one pass, and collect every comment.

    Returns ``(code, comments)`` where ``code`` has comments and string/char
    literals replaced by spaces (offsets and newlines preserved) and
    ``comments`` is ``[(1-based line, comment text)]``. One pass matters: an
    earlier version re-scanned the text to recover comments and silently
    returned none, which made the patch-history count a false zero.
    """
    out = list(text)
    comments: list[tuple[int, str]] = []
    i, n, line = 0, len(text), 1
    while i < n:
        char = text[i]
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            comments.append((line, text[i:end]))
            for k in range(i, end):
                out[k] = " "
            i = end
        elif text.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                elif text.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            end = min(j, n)
            # One entry per physical line of the block comment.
            for offset in range(i, end):
                if offset == i or text[offset - 1] == "\n":
                    stop = text.find("\n", offset)
                    stop = end if stop < 0 or stop > end else stop
                    comments.append((text.count("\n", 0, offset) + 1,
                                     text[offset:stop]))
            line += text.count("\n", i, end)
            for k in range(i, end):
                if text[k] not in "\r\n":
                    out[k] = " "
            i = end
        elif text.startswith('"""', i):
            j = i + 3
            while j < n and not text.startswith('"""', j):
                j += 2 if text[j] == "\\" else 1
            end = min(n, j + 3)
            line += text.count("\n", i, end)
            for k in range(i, end):
                if text[k] not in "\r\n":
                    out[k] = " "
            i = end
        elif char in "\"'":
            quote, j = char, i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                elif text[j] == quote or text[j] == "\n":
                    j += 1
                    break
                else:
                    j += 1
            end = min(j, n)
            line += text.count("\n", i, end)
            for k in range(i, end):
                if text[k] not in "\r\n":
                    out[k] = " "
            i = end
        else:
            if char == "\n":
                line += 1
            i += 1
    return "".join(out), comments


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def production(relative: Path) -> bool:
    """True for a production Kotlin source file in the D4 scope."""
    parts = relative.parts
    if set(parts) & EXCLUDED_DIRS:
        return False
    if relative.suffix != ".kt" or "src" not in parts:
        return False
    index = parts.index("src")
    source_set = parts[index + 1] if index + 1 < len(parts) else ""
    if source_set != "main" and not source_set.endswith("Main"):
        return False
    if "test" in source_set.lower():
        return False
    return any(relative.as_posix().startswith(root + "/") for root in SCOPE_ROOTS)


def area_of(relative: str) -> str:
    if relative == CONTROLLER or relative.endswith("/playback/CrossfadeController.kt"):
        return "playback/controller"
    for module in ("djmix-core", "djmix-analysis", "djmix-render", "djmix-android"):
        if relative.startswith(module + "/"):
            return module
    if relative.startswith("shared/"):
        return "shared/smart" if "/playback/" in relative else "shared/other"
    for name in ("djmix", "loopmix", "smart", "analyzer"):
        if PLAYBACK + "/" + name + "/" in relative:
            return "playback/" + name
    if relative.endswith("/playback/PlaybackService.kt"):
        return "playback/service"
    if (relative.endswith("/playback/AutomixTelemetry.kt")
            or relative.endswith("/data/settings/AppSettings.kt")):
        return "playback/settings_telemetry"
    if any(relative.endswith(name) for name in
           ("MixViewPopup.kt", "PlayerControls.kt", "MiniPlayer.kt",
            "SettingsSheet.kt", "AutoMixVisualFeed.kt")):
        return "playback/ui"
    return "playback/other"

# --------------------------------------------------------------------------- #
# Signal patterns
# --------------------------------------------------------------------------- #

SWALLOW_PATTERNS = {
    "runCatching": re.compile(r"\brunCatching\s*[({]"),
    "getOrNull": re.compile(r"\.\s*getOrNull\s*\(\s*\)"),
    "getOrDefault": re.compile(r"\.\s*getOrDefault\s*\("),
    "empty_catch": re.compile(r"\bcatch\s*\([^)]*\)\s*\{\s*\}"),
}
CALL = re.compile(r"(?<![\w.$])([A-Za-z_]\w*)\s*\(")
RETRY_NAMES = {"delay", "sleep", "withTimeout", "withTimeoutOrNull", "retry",
               "await", "awaitSignal", "backoff"}
RETRY_PATTERN = re.compile(r"^(?:retry|.*Retry.*|.*backoff.*|.*watchdog.*)$")
FUN_DECL = re.compile(
    r"\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w<>?,.`]+\.)?(`[^`]+`|\w+)\s*\(")
TYPE_DECL = re.compile(r"^\s*(?:@\w+\s+)*"
                       r"(?:public |internal |private |protected |open |abstract |"
                       r"sealed |data |value |annotation |expect |actual |enum |"
                       r"inline |suspend )*"
                       r"(class|interface|object|typealias|enum\s+class)\s+(\w+)")
TOP_LEVEL_VALUE = re.compile(r"^(?:@\w+\s+)*"
                             r"(?:public |internal |private |const |val |var )+"
                             r"(\w+)\s*[:=]")
IMPORT = re.compile(r"^\s*import\s+([\w.*]+)(?:\s+as\s+\w+)?\s*;?\s*$", re.MULTILINE)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*$", re.MULTILINE)
PLAN_COMPARE = re.compile(r"(?<![=!<>])===?(?!=)")
IDENT = re.compile(r"([\w.?!]+)\s*$")
IDENT_RIGHT = re.compile(r"\s*([\w.?!]+)")
TODO_PATTERN = re.compile(r"\b(TODO|FIXME|HACK)\b")
STDLIB = ("kotlin.", "java.", "kotlinx.", "javax.", "org.w3c.dom.", "org.xml.sax.")


def normalized_digest(body: str) -> str:
    """Digest of a function body with whitespace and comments removed."""
    flat = re.sub(r"\s+", "", mask_source(body)[0])
    return hashlib.sha256(flat.encode("utf-8")).hexdigest()[:16]


def function_spans(code: str) -> list[tuple[str, int, int, str]]:
    """(name, start offset, end offset, body text) for every ``fun`` declaration.

    ``body`` is the text from the declaration start to the matching closing
    brace (or the end of the expression body). This is a brace-counting
    approximation: it is exact for balanced Kotlin and sufficient for comparing
    helper bodies, which is all it is used for.
    """
    spans: list[tuple[str, int, int, str]] = []
    for match in FUN_DECL.finditer(code):
        name = match.group(1).strip("`")
        depth, index, n = 0, match.end() - 1, len(code)
        started = False
        while index < n:
            char = code[index]
            if char == "{":
                depth += 1
                started = True
            elif char == "}":
                depth -= 1
                if started and depth == 0:
                    index += 1
                    break
            elif char == "\n" and not started:
                # Expression body or abstract declaration: end at the line end.
                break
            index += 1
        spans.append((name, match.start(), index, code[match.start():index]))
    return spans


def top_level_symbols(code: str) -> list[tuple[str, str]]:
    """(fq-name, package) for every top-level declaration in the file."""
    package_match = PACKAGE.search(code)
    package = package_match.group(1) if package_match else ""
    symbols: list[tuple[str, str]] = []
    depth, parentheses = 0, 0
    for line in code.splitlines():
        if depth == 0 and parentheses == 0:
            match = TYPE_DECL.search(line) or TOP_LEVEL_VALUE.search(line)
            if match:
                name = match.group(match.lastindex or 1)
                symbols.append((package + "." + name if package else name, package))
            else:
                fun = FUN_DECL.search(line)
                if fun and not line.lstrip().startswith(("private ", "internal ")):
                    name = fun.group(1).strip("`")
                    symbols.append((package + "." + name if package else name, package))
        depth += line.count("{") - line.count("}")
        parentheses += line.count("(") - line.count(")")
    return symbols

def read_scope(root: Path) -> list[dict]:
    """Every production Kotlin file in scope, with its masked code and signals."""
    files: list[dict] = []
    for top in SCOPE_ROOTS:
        base = root / top
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*.kt")):
            relative = path.relative_to(root)
            if not production(relative) or path.is_symlink():
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
            code, comments = mask_source(text)
            files.append({
                "path": relative.as_posix(),
                "area": area_of(relative.as_posix()),
                "lines": len(text.splitlines()),
                "text": text,
                "code": code,
                "comments": comments,
            })
    return files


def in_automix(relative: str) -> bool:
    """True when a file belongs to the surface the D4 targets were measured on."""
    return area_of(relative) in AUTOMIX_AREAS


def lexical_signals(item: dict) -> dict[str, list[dict]]:
    path, code = item["path"], item["code"]
    found: dict[str, list[dict]] = defaultdict(list)

    for name, pattern in SWALLOW_PATTERNS.items():
        for match in pattern.finditer(code):
            found["swallowed_exceptions"].append(
                {"path": path, "line": line_of(code, match.start()), "kind": name})

    declarations = {match.start(1) for match in FUN_DECL.finditer(code)}
    for match in CALL.finditer(code):
        name = match.group(1)
        if match.start(1) in declarations:
            continue
        if not (name in RETRY_NAMES or RETRY_PATTERN.match(name)):
            continue
        if name[0].isupper():
            continue            # types such as PhrasePlanner.Timeout
        found["retry_sleep_timeout"].append(
            {"path": path, "line": line_of(code, match.start()), "kind": name})

    for name, start, _end, body in function_spans(code):
        lower = name.lower()
        if "fallback" in lower or "rescue" in lower or lower.startswith("bail") \
                or lower.endswith("bail"):
            found["fallback_entry_points"].append(
                {"path": path, "line": line_of(code, start), "kind": name,
                 "digest": normalized_digest(body)})

    for match in PLAN_COMPARE.finditer(code):
        left = IDENT.search(code[:match.start()])
        right = IDENT_RIGHT.match(code[match.end():])
        names = []
        for side in (left, right):
            if side:
                names.append(side.group(1).rstrip("?!").split(".")[-1])
        if "plan" in names and "null" not in names:
            found["plan_identity_checks"].append(
                {"path": path, "line": line_of(code, match.start()),
                 "kind": match.group(0)})

    for line, body in item["comments"]:
        for match in TODO_PATTERN.finditer(body):
            found["todo_comments"].append(
                {"path": path, "line": line, "kind": match.group(1)})

    return {key: value for key, value in found.items()}


def brace_depth(code: str, offset: int) -> int:
    """Brace nesting depth at ``offset`` (masked code, so strings are blank)."""
    return code.count("{", 0, offset) - code.count("}", 0, offset)


def duplicate_helpers(files: list[dict]) -> list[dict]:
    """Top-level helper names declared in more than one file.

    Each cluster reports how many declarations share an identical normalized
    body, so a name collision is never silently reported as duplicated logic.
    Only depth-0 declarations count: a nested or local ``fun`` belongs to its
    enclosing symbol, not to the cross-module vocabulary.
    """
    by_name: dict[str, list[dict]] = defaultdict(list)
    for item in files:
        for name, start, _end, body in function_spans(item["code"]):
            if brace_depth(item["code"], start) != 0:
                continue
            by_name[name].append({"path": item["path"],
                                  "line": line_of(item["code"], start),
                                  "digest": normalized_digest(body)})
    clusters = []
    for name in sorted(by_name):
        sites = by_name[name]
        if len({site["path"] for site in sites}) < 2:
            continue
        digests = {site["digest"] for site in sites}
        clusters.append({
            "name": name,
            "declarations": len(sites),
            "files": sorted({site["path"] for site in sites}),
            "distinct_bodies": len(digests),
            "identical_body": len(digests) == 1,
            "sites": sites,
        })
    return clusters


def import_graph(files: list[dict]) -> tuple[dict, list[dict], list[dict], list[dict]]:
    """Resolve imports to file edges plus the layer and controller signals."""
    symbols: dict[str, set[str]] = defaultdict(set)
    packages: dict[str, set[str]] = defaultdict(set)
    for item in files:
        for name, package in top_level_symbols(item["code"]):
            symbols[name].add(item["path"])
            packages[package].add(item["path"])
        match = PACKAGE.search(item["code"])
        if match:
            packages[match.group(1)].add(item["path"])

    graph: dict[str, set[str]] = {item["path"]: set() for item in files}
    controller_imports: list[dict] = []
    inversions: list[dict] = []
    external: list[dict] = []
    for item in files:
        source, code = item["path"], item["code"]
        source_area = item["area"]
        for match in IMPORT.finditer(code):
            imported = match.group(1)
            line = line_of(code, match.start())
            wildcard = imported.endswith(".*")
            root = imported[:-2] if wildcard else imported

            if source_area in HELPER_AREAS and (
                    "CrossfadeController" in imported.split(".") or
                    (imported == "com.music.bitchord.playback.*"
                     and re.search(r"\bCrossfadeController\b", code[match.end():]))):
                controller_imports.append({"path": source, "line": line,
                                           "kind": "forbidden controller import",
                                           "import": imported})

            if source_area == "djmix-core" and not imported.startswith(
                    ("com.music.bitchord.djmix.core.",) + STDLIB):
                external.append({"path": source, "line": line, "import": imported})

            targets = set()
            if wildcard:
                targets = packages.get(root, set())
            else:
                prefix = imported
                while prefix not in symbols and "." in prefix:
                    prefix = prefix.rsplit(".", 1)[0]
                targets = symbols.get(prefix, set())
            resolved = {target for target in targets if target != source}
            graph[source].update(resolved)

            for target in sorted(resolved):
                target_area = area_of(target)
                if source_area in HARD_SRC and (
                        LAYER_RANK.get(target_area, 0)
                        > LAYER_RANK.get(source_area, 0) + 1
                        or target_area in VOLATILE_DST):
                    kind = "HARD"
                elif source_area in SOFT_SRC and target_area in VOLATILE_DST:
                    kind = "SOFT"
                else:
                    continue
                inversions.append({
                    "path": source, "line": line, "import": imported,
                    "kind": kind, "from": source_area, "to": target_area,
                })
    return graph, controller_imports, inversions, external


def components(graph: dict[str, set[str]]) -> list[list[str]]:
    """Strongly connected file components of size > 1 (iterative Kosaraju)."""
    visited: set[str] = set()
    order: list[str] = []
    for start in sorted(graph):
        if start in visited:
            continue
        visited.add(start)
        stack = [(start, iter(sorted(graph[start])))]
        while stack:
            node, edges = stack[-1]
            nxt = next(edges, None)
            if nxt is None:
                order.append(node)
                stack.pop()
            elif nxt not in visited:
                visited.add(nxt)
                stack.append((nxt, iter(sorted(graph.get(nxt, ())))))
    reverse: dict[str, set[str]] = defaultdict(set)
    for node, edges in graph.items():
        for target in edges:
            reverse[target].add(node)
    assigned: set[str] = set()
    found: list[list[str]] = []
    for start in reversed(order):
        if start in assigned:
            continue
        stack, component = [start], []
        assigned.add(start)
        while stack:
            node = stack.pop()
            component.append(node)
            for target in sorted(reverse[node]):
                if target not in assigned:
                    assigned.add(target)
                    stack.append(target)
        if len(component) > 1:
            found.append(sorted(component))
    return sorted(found)


# --------------------------------------------------------------------------- #
# Budgets and committed targets
# --------------------------------------------------------------------------- #

DEFAULT_CONTROLLER_MAX = 2500
DEFAULT_FILE_MAX = 1500
# Committed D4 targets (docs/AE3_LEDGER.md Table 3 and docs/UNTANGLE_SKILL.md
# "D4 -- Done criteria"). They are read from the docs when possible; these are
# the documented fallbacks, quoted here so a moved or edited doc is visible.
TARGET_DOCS = ("docs/AE3_LEDGER.md", "docs/UNTANGLE_SKILL.md")
TARGETS = {
    "swallowed_exceptions": 10,
    "retry_sleep_timeout": 5,
    "fallback_entry_points": 1,
    "plan_identity_checks": 1,
}
# Signals with no committed numeric target: reported, never scored.
REPORT_ONLY = ("todo_comments", "patch_history_comments",
               "patch_history_tokens", "duplicate_helpers", "controller_imports",
               "layer_inversions", "import_cycles", "core_external_imports")
PATCH_HISTORY = re.compile(r"\b(?:djmix|vc)\d{1,3}\b", re.IGNORECASE)


def read_committed_targets(root: Path) -> tuple[dict[str, int], list[str]]:
    """Pull the four Table-3 numbers out of the committed docs.

    Returns the targets found plus notes for anything unverified. A missing or
    unparseable doc never invents a target: the metric is compared against the
    documented fallback and the report says the doc could not be read.
    """
    targets: dict[str, int] = {}
    notes: list[str] = []
    pattern = re.compile(
        r"swallowed exceptions[^.\n]*?(?:at or )?under\s+(\d+)"
        r"|retry/sleep/timeout[^.\n]*?(?:at or )?under\s+(\d+)"
        r"|fallback entry points\s*=\s*(\d+)"
        r"|plan-identity checks\s*=\s*(\d+)", re.IGNORECASE)
    for relative in TARGET_DOCS:
        path = root / relative
        if not path.is_file():
            notes.append(f"targets: {relative} is missing; using documented fallbacks")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in pattern.finditer(text):
            for index, name in enumerate(TARGETS):
                value = match.group(index + 1)
                if value is not None and name not in targets:
                    targets[name] = int(value)
        if targets:
            notes.append(f"targets: read {len(targets)} value(s) from {relative}")
            break
    for name in TARGETS:
        if name not in targets:
            targets[name] = TARGETS[name]
            notes.append(f"targets: {name} not found in the docs; "
                         f"documented fallback {TARGETS[name]} used")
    return targets, notes


def measure(root: Path, controller_max: int, file_max: int) -> dict:
    files = read_scope(root)
    if not files:
        raise ValueError("no production Kotlin file found under " + str(root))
    targets, target_notes = read_committed_targets(root)

    sizes: list[dict] = []
    overs: list[dict] = []
    for item in files:
        automix = in_automix(item["path"])
        controller = item["path"].endswith("/playback/CrossfadeController.kt")
        limit = controller_max if controller else file_max
        size = {"path": item["path"], "area": item["area"], "lines": item["lines"],
                "limit": limit, "controller": controller, "automix": automix}
        sizes.append(size)
        if item["lines"] > limit:
            overs.append({"path": item["path"], "area": item["area"],
                          "lines": item["lines"], "limit": limit,
                          "overage": item["lines"] - limit,
                          "controller": controller, "automix": automix})

    signals: dict[str, list[dict]] = defaultdict(list)
    patch_comments: list[dict] = []
    patch_tokens = 0
    for item in files:
        for name, rows in lexical_signals(item).items():
            signals[name].extend(rows)
        for line, body in item["comments"]:
            tags = PATCH_HISTORY.findall(body)
            if tags:
                patch_tokens += len(tags)
                patch_comments.append({"path": item["path"], "line": line,
                                       "kind": body.strip()[:120],
                                       "tags": tags})
    signals["patch_history_comments"] = patch_comments
    signals["patch_history_tokens"] = [
        {"path": row["path"], "line": row["line"], "kind": tag}
        for row in patch_comments for tag in row["tags"]]
    clusters = duplicate_helpers(files)
    signals["duplicate_helpers"] = [
        {"path": cluster["files"][0], "line": 0, "kind": cluster["name"],
         "files": cluster["files"], "declarations": cluster["declarations"],
         "distinct_bodies": cluster["distinct_bodies"],
         "identical_body": cluster["identical_body"]}
        for cluster in clusters]

    graph, controller_imports, inversions, external = import_graph(files)
    signals["controller_imports"] = controller_imports
    signals["layer_inversions"] = inversions
    signals["core_external_imports"] = external
    import_cycles = components(graph)
    signals["import_cycles"] = [
        {"path": component[0], "line": 0, "kind": f"SCC of {len(component)} files",
         "files": component} for component in import_cycles]

    controller = next((size for size in sizes
                       if size["path"].endswith("/playback/CrossfadeController.kt")), None)
    scored: dict[str, list[dict]] = {
        name: [row for row in signals.get(name, []) if in_automix(row["path"])]
        for name in signals
    }
    metrics = {}
    for name, target in targets.items():
        # Score the automix surface only: the committed targets were measured
        # there, so a raw whole-repository count would be a different metric.
        value = len(scored.get(name, []))
        metrics[name] = {"count": value, "target": target,
                         "pass": value <= target,
                         "out_of_scope": len(signals.get(name, [])) - value}
    for name in REPORT_ONLY:
        metrics[name] = {"count": len(signals.get(name, [])), "target": None,
                         "pass": None}
    metrics["patch_history_tokens"] = {
        "count": len(signals["patch_history_tokens"]), "target": None, "pass": None}

    breakdown = {
        "swallowed_by_kind": {kind: sum(row["kind"] == kind
                                        for row in scored.get("swallowed_exceptions", []))
                              for kind in SWALLOW_PATTERNS},
        "swallowed_by_area": {},
        "fallback_by_area": {},
        "retry_by_kind": {},
    }
    for row in scored.get("swallowed_exceptions", []):
        key = area_of(row["path"])
        breakdown["swallowed_by_area"][key] = breakdown["swallowed_by_area"].get(key, 0) + 1
    for row in scored.get("fallback_entry_points", []):
        key = area_of(row["path"])
        breakdown["fallback_by_area"][key] = breakdown["fallback_by_area"].get(key, 0) + 1
    for row in scored.get("retry_sleep_timeout", []):
        breakdown["retry_by_kind"][row["kind"]] = \
            breakdown["retry_by_kind"].get(row["kind"], 0) + 1

    ok = bool(files) and not overs and all(
        metric["pass"] is not False for metric in metrics.values())
    return {
        "root": str(root),
        "scope": ("production Kotlin in playback/, shared/src, djmix-core, "
                  "djmix-analysis, djmix-render; tests, generated and build "
                  "output excluded"),
        "scored_scope": ("automix surface of docs/untangle/deps_report.md "
                         "section 1 and the djmix-* modules; other scanned "
                         "files are reported separately and excluded from the "
                         "committed targets"),
        "files": len(files),
        "automix_files": sum(1 for item in files if in_automix(item["path"])),
        "controller": controller,
        "sizes": sorted(sizes, key=lambda size: (-size["lines"], size["path"]))[:15],
        "overages": sorted(overs, key=lambda over: (-over["lines"], over["path"])),
        "metrics": metrics,
        "targets": targets,
        "target_notes": target_notes,
        "duplicate_helpers": clusters,
        "import_cycles": import_cycles,
        "breakdown": breakdown,
        "definitions": DEFINITIONS,
        "caveat": ("Counts are lexical supersets of the reasoned counts the "
                   "committed targets were written against; a chained "
                   "runCatching{}.getOrNull() contributes two signals. A FAIL "
                   "means the lexical surface greatly exceeds the target's "
                   "definition, not that N independent silent failures exist."),
        "ok": ok,
        "evidence": {name: rows for name, rows in signals.items()},
    }


DEFINITIONS = {
    "swallowed_exceptions": (
        "additive lexical signals: runCatching call, zero-argument .getOrNull(), "
        ".getOrDefault(...), empty catch. A chained runCatching{}.getOrNull() "
        "counts twice; this is not a count of distinct failures"),
    "retry_sleep_timeout": (
        "calls to delay/sleep/withTimeout*/retry/backoff/watchdog by name; "
        "declarations and upper-case type references excluded"),
    "fallback_entry_points": (
        "fun declarations whose name contains fallback/rescue or starts/ends "
        "with bail; overloads and forwarders each count"),
    "plan_identity_checks": (
        "== or === with a terminal identifier `plan`, excluding null comparisons"),
    "todo_comments": "comment lines containing TODO, FIXME or HACK",
    "patch_history_comments": (
        "comment lines mentioning a djmix<N> or vc<N> milestone tag"),
    "duplicate_helpers": (
        "top-level fun names declared in two or more files, with a normalized "
        "body digest so equal bodies are distinguishable from name reuse"),
    "controller_imports": (
        "imports of CrossfadeController from playback/djmix, playback/loopmix "
        "or playback/smart, including wildcard playback imports used with it"),
    "layer_inversions": (
        "HARD: a native/pure source layer (native/*, shared/smart, djmix-core) "
        "importing a layer two or more ranks above it or any UI/service/settings "
        "layer. SOFT: a mid source layer (playback/{djmix,loopmix,analyzer}, "
        "djmix-analysis, djmix-render, app/cpp) importing UI/service/settings. "
        "Vocabulary matches tools/untangle/deps.py"),
    "core_external_imports": (
        "djmix-core imports outside its own namespace and the Kotlin/Java/"
        "kotlinx/javax standard libraries"),
    "import_cycles": (
        "file-level strongly connected components of size>1 from resolved "
        "explicit/wildcard imports; implicit same-package and runtime "
        "dependencies are invisible to this approximation"),
    "sizes": (
        "physical line counts against the controller and helper budgets"),
}

# --------------------------------------------------------------------------- #
# Reporting
# --------------------------------------------------------------------------- #

def status(metric: dict) -> str:
    if metric["pass"] is None:
        return "report"
    return "PASS" if metric["pass"] else "FAIL"


def markdown(report: dict, details: bool) -> str:
    lines = [
        "# Part D4 special-case signals",
        "",
        report["scope"] + ".",
        "",
        "Scored scope: " + report["scored_scope"] + ".",
        "",
        f"Files scanned: **{report['files']}** "
        f"(**{report['automix_files']}** on the scored automix surface).",
        "",
        "## Size budgets",
        "",
        "| File | Area | Lines | Limit | Overage |",
        "|---|---|---:|---:|---:|",
    ]
    top = report["sizes"][:10]
    over_by_path = {over["path"]: over for over in report["overages"]}
    for size in top:
        overage = over_by_path.get(size["path"], {}).get("overage")
        lines.append(f'| `{size["path"]}` | {size["area"]} | {size["lines"]:,} | '
                     f'{size["limit"]:,} | '
                     f'{"BEYOND +" + format(overage, ",") if overage else "--"} |')
    if report["controller"]:
        controller = report["controller"]
        headroom = controller["limit"] - controller["lines"]
        lines += ["", f'`CrossfadeController.kt`: **{controller["lines"]:,}** lines '
                      f'against a {controller["limit"]:,} budget '
                      f'({headroom:+,} headroom).']
    if report["overages"]:
        lines += ["", "Files beyond budget:", ""]
        for over in report["overages"]:
            note = "" if over["automix"] else " (outside the scored automix surface)"
            lines.append(f'- `{over["path"]}` ({over["area"]}): '
                         f'{over["lines"]:,} lines, limit {over["limit"]:,}, '
                         f'over by **{over["overage"]:,}**{note}')
    else:
        lines += ["", "No file exceeds its budget."]

    lines += ["", "## Special-case counts", "",
              "| Signal | Measured | D4 target | Out of scope | Result |",
              "|---|---:|---:|---:|---|"]
    for name, metric in report["metrics"].items():
        target = "--" if metric["target"] is None else metric["target"]
        outside = metric.get("out_of_scope", 0)
        lines.append(f'| {name} | {metric["count"]:,} | {target} | '
                     f'{outside:,} | {status(metric)} |')

    lines += ["", "### Breakdown", "",
              "Swallowed by kind: " + json.dumps(
                  dict(sorted(report["breakdown"]["swallowed_by_kind"].items()))),
              "",
              "Swallowed by area: " + json.dumps(
                  dict(sorted(report["breakdown"]["swallowed_by_area"].items()))),
              "",
              "Retry/sleep/timeout by name: " + json.dumps(
                  dict(sorted(report["breakdown"]["retry_by_kind"].items()))),
              "",
              "Fallback entry points by area: " + json.dumps(
                  dict(sorted(report["breakdown"]["fallback_by_area"].items()))),
              ""]

    clusters = report["duplicate_helpers"]
    lines += ["## Duplicated top-level helper names", ""]
    if not clusters:
        lines.append("No top-level fun name is declared in more than one file.")
    else:
        lines += ["| Helper | Declarations | Files | Distinct bodies | Identical? |",
                  "|---|---:|---:|---:|---|"]
        for cluster in clusters:
            lines.append(f'| `{cluster["name"]}` | {cluster["declarations"]} | '
                         f'{len(cluster["files"])} | {cluster["distinct_bodies"]} | '
                         f'{"yes" if cluster["identical_body"] else "no"} |')
        if details:
            lines += [""]
            for cluster in clusters:
                lines.append(f'`{cluster["name"]}`:')
                for site in cluster["sites"]:
                    lines.append(f'- `{site["path"]}:{site["line"]}` '
                                 f'(body {site["digest"]})')

    lines += ["", "## Controller imports in helper areas", ""]
    if report["evidence"]["controller_imports"]:
        for row in report["evidence"]["controller_imports"]:
            lines.append(f'- `{row["path"]}:{row["line"]}` imports '
                         f'`{row["import"]}`')
    else:
        lines.append("None. `playback/{djmix,loopmix,smart}` do not import "
                     "`CrossfadeController`.")

    lines += ["", "## Import graph", "",
              f'File-level strongly connected components of size>1: '
              f'**{len(report["import_cycles"])}**.']
    for component in report["import_cycles"]:
        lines.append(f'- {len(component)} files: '
                     + ", ".join(f'`{path}`' for path in component[:6])
                     + (" ..." if len(component) > 6 else ""))
    lines += ["", f'Volatility inversions (foundation layer importing a '
                  f'UI/service/settings layer): '
                  f'**{len(report["evidence"]["layer_inversions"])}**.']
    for row in report["evidence"]["layer_inversions"][:10]:
        lines.append(f'- {row["kind"]}: `{row["path"]}:{row["line"]}` '
                     f'{row["from"]} -> {row["to"]} (`{row["import"]}`)')
    lines += ["", f'`djmix-core` imports outside its namespace and the stdlib: '
                  f'**{len(report["evidence"]["core_external_imports"])}**.']
    for row in report["evidence"]["core_external_imports"]:
        lines.append(f'- `{row["path"]}:{row["line"]}` imports `{row["import"]}`')

    lines += ["", "## What these numbers do and do not prove", ""]
    for name in ("swallowed_exceptions", "retry_sleep_timeout",
                 "fallback_entry_points", "plan_identity_checks",
                 "duplicate_helpers", "layer_inversions", "import_cycles"):
        lines.append(f'- **{name}**: {DEFINITIONS[name]}')
    lines += ["", "Counts are lexical and additive; they locate debt, they do not "
                  "prove a failure path exists or that behaviour is preserved. "
                  "Size budgets are physical line counts, not complexity.",
              "",
              "**Comparability caveat.** The committed targets in "
              "`docs/AE3_LEDGER.md` were written against *reasoned* counts (each "
              "silent failure individually justified). The numbers here are "
              "*lexical supersets*: one chained `runCatching{}.getOrNull()` is "
              "two signals, and `.getOrDefault(...)` is counted whether or not "
              "the default is benign. A `FAIL` therefore means the lexical "
              "surface is far larger than the target's definition; it does not "
              "mean the ledger's reasoned count was exceeded by that amount, "
              "and it does not prove 462 independent silent failures exist.",
              "", "Targets: " + "; ".join(
                  note.removeprefix("targets: ")
                  for note in report["target_notes"]) + "."]
    if details:
        lines += ["", "Complete evidence is in the JSON report (`--format json`)."]
    return "\n".join(lines)


def selftest() -> int:
    """Check the scanner primitives against tiny synthetic sources.

    These assertions exist because each one caught a real defect: comments
    leaking into the code stream (false TODO/patch counts), string literals
    being read as code, a declaration counted as a retry call, a null check
    counted as a plan-identity comparison, and a nested fun counted as a
    duplicated top-level helper.
    """
    src = (
        "package p\n"
        "\n"
        "// TODO: real comment djmix12\n"
        'val url = "http://not/a//comment"\n'
        'val raw = """\n'
        "// inside a raw string\n"
        '"""\n'
        "/* block djmix7\n"
        "   second line */\n"
        "fun f() { runCatching { x() }.getOrNull() }\n"
    )
    code, comments = mask_source(src)
    checks: list[tuple[str, bool]] = []
    checks.append(("comments blanked", "TODO" not in code and "djmix12" not in code))
    checks.append(("string literal blanked", "not/a//comment" not in code))
    checks.append(("raw string blanked", "inside a raw string" not in code))
    checks.append(("newlines preserved", code.count("\n") == src.count("\n")))
    texts = [body for _line, body in comments]
    checks.append(("line comment captured", any("TODO" in body for body in texts)))
    checks.append(("block comment captured",
                   any("second line" in body for body in texts)))
    checks.append(("raw string not a comment",
                   not any("inside a raw string" in body for body in texts)))

    item = {"path": "app/src/main/java/com/music/bitchord/playback/djmix/X.kt",
            "code": code, "text": src, "comments": comments, "lines": 1}
    swallowed = lexical_signals(item)["swallowed_exceptions"]
    checks.append(("chained runCatching.getOrNull counts twice",
                   sorted(row["kind"] for row in swallowed) == ["getOrNull", "runCatching"]))

    retry_src = (
        "package p\n"
        "fun withRetry(block: () -> Unit) { block() }\n"
        "class A { fun run() { delay(10); withTimeout(5) { }; PhrasePlanner.Timeout } }\n"
    )
    retry_code, retry_comments = mask_source(retry_src)
    retry = lexical_signals({
        "path": "app/src/main/java/com/music/bitchord/playback/djmix/Y.kt",
        "code": retry_code, "text": retry_src, "comments": retry_comments, "lines": 1,
    })["retry_sleep_timeout"]
    checks.append(("retry declaration and type excluded",
                   sorted(row["kind"] for row in retry) == ["delay", "withTimeout"]))

    plan_src = (
        "package p\n"
        "fun f(plan: Any?, other: Any?) {\n"
        "  if (plan == other) {}\n"
        "  if (plan === other) {}\n"
        "  if (plan != null) {}\n"
        "  if (other == null) {}\n"
        "}\n"
    )
    plan_code, plan_comments = mask_source(plan_src)
    plan = lexical_signals({
        "path": "app/src/main/java/com/music/bitchord/playback/djmix/Z.kt",
        "code": plan_code, "text": plan_src, "comments": plan_comments, "lines": 1,
    })["plan_identity_checks"]
    checks.append(("plan identity excludes null checks",
                   sorted(row["kind"] for row in plan) == ["==", "==="]))

    dup_src = (
        "package p\n"
        "fun shared(a: Int) = a + 1\n"
        "class Holder {\n"
        "    fun shared(a: Int) = a + 1\n"
        "}\n"
    )
    dup_code, _ = mask_source(dup_src)
    depths = sorted(brace_depth(dup_code, start)
                    for _name, start, _end, _body in function_spans(dup_code))
    checks.append(("nested fun has depth 1, top-level depth 0", depths == [0, 1]))

    areas = {
        "app/src/main/java/com/music/bitchord/playback/CrossfadeController.kt":
            "playback/controller",
        "app/src/main/java/com/music/bitchord/playback/loopmix/LoopMixSession.kt":
            "playback/loopmix",
        "shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/LoopMix.kt":
            "shared/smart",
        "djmix-core/src/commonMain/kotlin/x/Y.kt": "djmix-core",
        "app/src/main/java/com/music/bitchord/playback/AudioCache.kt": "playback/other",
    }
    checks.append(("area classification",
                   all(area_of(path) == want for path, want in areas.items())))
    checks.append(("automix scope excludes data layer",
                   in_automix("app/src/main/java/com/music/bitchord/playback/"
                              "CrossfadeController.kt")
                   and not in_automix("shared/src/jvmSharedMain/kotlin/com/music/"
                                      "bitchord/data/innertube/InnertubeParser.kt")
                   and not in_automix("app/src/main/java/com/music/bitchord/"
                                      "playback/AudioCache.kt")))
    checks.append(("cycle detection", components({"a": {"b"}, "b": {"a"}, "c": {"a"}})
                   == [["a", "b"]]))
    checks.append(("acyclic graph has no component",
                   components({"a": {"b"}, "b": set()}) == []))

    failed = [name for name, ok in checks if not ok]
    for name, ok in checks:
        print(("ok    " if ok else "FAIL  ") + name)
    print(f"{len(checks) - len(failed)}/{len(checks)} D4 scanner self-tests passed")
    return 0 if not failed else 1


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read-only Part D4 signal measurement (stdlib only).")
    parser.add_argument("--root", type=Path,
                        default=Path(__file__).resolve().parents[2],
                        help="repository root (default: two levels above tools/)")
    parser.add_argument("--controller-max", type=int, default=DEFAULT_CONTROLLER_MAX,
                        help=f"CrossfadeController.kt line budget "
                             f"(default {DEFAULT_CONTROLLER_MAX})")
    parser.add_argument("--file-max", type=int, default=DEFAULT_FILE_MAX,
                        help=f"per-file line budget for helpers "
                             f"(default {DEFAULT_FILE_MAX})")
    parser.add_argument("--format", choices=("markdown", "json", "both"),
                        default="both")
    parser.add_argument("--out", type=Path, default=None,
                        help="write the report here instead of stdout")
    parser.add_argument("--details", action="store_true",
                        help="include complete evidence in the report")
    parser.add_argument("--strict", action="store_true",
                        help="exit 1 when a measured target misses")
    parser.add_argument("--self-test", action="store_true",
                        help="check the scanner primitives and exit")
    args = parser.parse_args(argv)
    if args.self_test:
        return selftest()
    if args.controller_max <= 0 or args.file_max <= 0:
        parser.error("--controller-max and --file-max must be positive")
    if not args.root.is_dir():
        parser.error("--root must be an existing directory")

    try:
        report = measure(args.root, args.controller_max, args.file_max)
    except (OSError, ValueError) as exc:
        print("D4 measurement failed: " + str(exc), file=sys.stderr)
        return 2
    if not report["files"]:
        print("D4 measurement failed: no production Kotlin file found under "
              + str(args.root), file=sys.stderr)
        return 2

    chunks: list[str] = []
    if args.format in {"markdown", "both"}:
        chunks.append(markdown(report, args.details))
    if args.format == "both":
        chunks.append("````json")
    if args.format in {"json", "both"}:
        if not args.details:
            report = dict(report)
            report["evidence"] = {name: rows[:5]
                                 for name, rows in report["evidence"].items()}
            report["evidence_truncated"] = True
            report["duplicate_helpers"] = [
                dict(cluster, sites=cluster["sites"][:5])
                for cluster in report["duplicate_helpers"]]
        chunks.append(json.dumps(report, indent=2, sort_keys=True))
    if args.format == "both":
        chunks.append("````")
    output = "\n".join(chunks) + "\n"
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(output, encoding="utf-8")
        print(f"D4 report written to {args.out} "
              f"({report['files']} files, {len(report['overages'])} over budget)")
    else:
        sys.stdout.write(output)
    return 1 if args.strict and not report["ok"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
