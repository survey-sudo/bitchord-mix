#!/usr/bin/env python3
"""AE3 release-gate invariants: engine version, FX constant parity, recipe IDs,
committed secrets, and sleep/delay inside @AudioThread regions.

Python 3 standard library only. Read-only: this tool never edits a file, never
runs Gradle, and never touches the network. Diagnostics carry locations and
rule names, never a secret value.

Checks (``--only NAME``, repeatable or comma separated; no ``--only`` runs all):

    engine-version     every engine-version declaration equals "3.0.0"
    fx-constant-parity mirrored Kotlin <-> C++ FX constants agree
    recipe-id-parity   recipe JSON IDs match the Kotlin catalog and mirror
    secret-scan        no committed credentials or developer home paths
    no-timeout-sleep   no Thread.sleep/delay inside @AudioThread regions

Exit code: 0 when every selected check is clean, 1 when any finding is
reported, 2 on usage/IO errors or when a check cannot find its inputs. A check
that cannot find its inputs fails loudly rather than passing vacuously.

Scope: the current working tree (Git index plus non-ignored untracked files),
not Git history and not ignored local signing material.
"""

from __future__ import annotations

import argparse
import ast
import importlib.util
import json
import math
import operator
from pathlib import Path
import re
import subprocess
import sys

sys.dont_write_bytecode = True

# --------------------------------------------------------------------------- #
# Shared plumbing
# --------------------------------------------------------------------------- #

EXCLUDED_DIRS = {
    ".git", ".gradle", ".cxx", ".kotlin", ".idea", "build", "out", "dist",
    "node_modules", "__pycache__", ".venv", "venv",
}
BINARY_SUFFIXES = {
    ".onnx", ".ort", ".tflite", ".pt", ".pth", ".bin", ".weights", ".npy", ".npz",
    ".wav", ".mp3", ".flac", ".aac", ".ogg", ".opus", ".m4a", ".mp4", ".webm", ".mov",
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".ico", ".pdf",
    ".woff", ".woff2", ".ttf", ".otf", ".jar", ".class", ".so", ".a", ".o", ".dex",
    ".apk", ".aab", ".zip", ".gz", ".xz", ".7z", ".bundle",
}
TEST_PATH_PARTS = {"test", "tests", "commonTest", "jvmTest", "androidTest",
                   "testFixtures", "integrationTest"}
CHECK_NAMES = ("engine-version", "fx-constant-parity", "recipe-id-parity",
               "secret-scan", "no-timeout-sleep")


class Finding:
    """One ``file:line: message`` diagnostic plus the rule that produced it."""

    __slots__ = ("check", "path", "line", "message")

    def __init__(self, check: str, path: str, line: int, message: str) -> None:
        self.check = check
        self.path = path
        self.line = line
        self.message = message

    def __str__(self) -> str:
        where = f"{self.path}:{self.line}" if self.line else self.path
        return f"{where}: {self.message}"

    def as_dict(self) -> dict:
        return {"check": self.check, "path": self.path, "line": self.line,
                "message": self.message}


def line_of(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def is_test_path(relative: str) -> bool:
    """True for test source sets and test tooling (never a declaration site)."""
    parts = Path(relative).parts
    if set(parts) & TEST_PATH_PARTS:
        return True
    name = parts[-1] if parts else ""
    return (name.endswith("Test.kt") or name.endswith("Test.java")
            or name.startswith("test_") or name.endswith("_test.py")
            or "/src/test/" in "/" + relative or "/src/commonTest/" in "/" + relative)


def strip_comments(text: str) -> str:
    """Blank ``//`` and ``/* */`` comments, keeping offsets and newlines.

    Kotlin/C++/Gradle mixed dialect: handles nested block comments and leaves
    string literals visible so numeric literals survive. This is a lexical aid,
    not a compiler.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            for k in range(i, end):
                out[k] = " "
            i = end
        elif text.startswith("/*", i):
            j, depth = i + 2, 1
            while j < n and depth:
                if text.startswith("/*", j):
                    depth += 1
                    j += 2
                elif text.startswith("*/", j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            for k in range(i, j):
                if text[k] not in "\r\n":
                    out[k] = " "
            i = j
        elif text[i] == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                elif text[i] == '"' or text[i] == "\n":
                    i += 1
                    break
                else:
                    i += 1
        elif text[i] == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                elif text[i] == "'" or text[i] == "\n":
                    i += 1
                    break
                else:
                    i += 1
        else:
            i += 1
    return "".join(out)


def collect_files(root: Path) -> list[str]:
    """Relative POSIX paths of Git's index plus non-ignored worktree files.

    Falls back to an os.walk for non-Git fixture trees. Ignored files such as
    ``local.properties`` are absent by construction, which is the intent: this
    gate audits what can be committed.
    """
    if (root / ".git").exists():
        completed = subprocess.run(
            ["git", "-C", str(root), "ls-files", "--cached", "--others",
             "--exclude-standard", "-z"],
            capture_output=True, check=False,
        )
        if completed.returncode != 0:
            raise OSError("git ls-files failed in " + str(root))
        names = {Path(raw.decode("utf-8", errors="surrogateescape")).as_posix()
                 for raw in completed.stdout.split(b"\0") if raw}
        return sorted(names)
    names = []
    for folder, dirs, files in __import__("os").walk(root, followlinks=False):
        dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_DIRS)
        for name in sorted(files):
            names.append((Path(folder) / name).relative_to(root).as_posix())
    return names


def skipped(relative: str) -> bool:
    parts = relative.split("/")
    return bool(set(parts[:-1]) & EXCLUDED_DIRS)


def read_text(root: Path, relative: str) -> str | None:
    """UTF-8 text of a scannable file, or None for binary/symlink/missing."""
    path = root / relative
    try:
        if path.is_symlink() or not path.is_file():
            return None
        if path.suffix.lower() in BINARY_SUFFIXES:
            return None
        raw = path.read_bytes()
    except OSError:
        return None
    if b"\0" in raw:
        return None
    return raw.decode("utf-8", errors="replace")


class Tree:
    """Cached view of the scannable working tree."""

    def __init__(self, root: Path) -> None:
        self.root = root
        self.paths = [p for p in collect_files(root) if not skipped(p)]
        self._text: dict[str, str | None] = {}

    def text(self, relative: str) -> str | None:
        if relative not in self._text:
            self._text[relative] = read_text(self.root, relative)
        return self._text[relative]

    def with_suffix(self, *suffixes: str) -> list[str]:
        return [p for p in self.paths if Path(p).suffix.lower() in suffixes]


# --------------------------------------------------------------------------- #
# 1. engine-version
# --------------------------------------------------------------------------- #

ENGINE = "3.0.0"
# (label, pattern, value group, symbol group or None, diagnostic subject).
# The label selects the anchor rule; the subject is what a reader needs to
# recognise the drifting declaration in their own file.
ENGINE_DECLARATIONS = (
    ("kotlin const", re.compile(
        r"\bconst\s+val\s+(ENGINE_VERSION|ENGINE_EXPECTED|EXPECTED_ENGINE"
        r"|ENGINE_VERSION_STRING)\b[^=\n]*=\s*\"([0-9][0-9A-Za-z.+-]*)\""), 2, 1, None),
    ("python constant", re.compile(
        r"^\s*(ENGINE_VERSION|ENGINE_EXPECTED|EXPECTED_ENGINE)\s*[:=]\s*"
        r"['\"]([0-9][0-9A-Za-z.+-]*)['\"]", re.MULTILINE), 2, 1, None),
    ("gradle buildConfigField", re.compile(
        r"\bbuildConfigField\s*\(\s*\"String\"\s*,\s*\"ENGINE_VERSION\"\s*,\s*"
        r"\"(?:\\\")?([0-9][0-9A-Za-z.+-]*)(?:\\\")?\"\s*\)"), 1, None,
     "ENGINE_VERSION in buildConfigField"),
    ("telemetry json literal", re.compile(
        r"\"engine\"\s*:\s*\"([0-9][0-9A-Za-z.+-]*)\""), 1, None, '"engine"'),
    ("model manifest minEngine", re.compile(
        r"\"minEngine\"\s*:\s*\"([0-9][0-9A-Za-z.+-]*)\""), 1, None,
     '"minEngine"'),
)
# Anchor forms that must exist, or the gate has lost the invariant entirely.
ENGINE_ANCHORS = {
    "kotlin const": lambda relative, symbol: (
        relative.endswith("EngineVersion.kt") and symbol == "ENGINE_VERSION"),
    "gradle buildConfigField": lambda relative, symbol: (
        relative.endswith(".gradle.kts") and symbol == "ENGINE_VERSION"),
}


def check_engine_version(tree: Tree) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    notes: list[str] = []
    declarations: list[tuple[str, int, str, str, str, str]] = []
    for relative in tree.paths:
        suffix = Path(relative).suffix.lower()
        if suffix not in {".kt", ".kts", ".java", ".gradle", ".py", ".h", ".hpp",
                          ".cpp", ".cc", ".c", ".json", ".toml", ".properties"}:
            continue
        if is_test_path(relative):
            continue
        text = tree.text(relative)
        if not text:
            continue
        for label, pattern, value_group, symbol_group, fixed in ENGINE_DECLARATIONS:
            for match in pattern.finditer(text):
                symbol = (match.group(symbol_group) if symbol_group
                          else "ENGINE_VERSION")
                subject = match.group(symbol_group) if symbol_group else fixed
                declarations.append((relative, line_of(text, match.start()),
                                     label, symbol, subject,
                                     match.group(value_group)))

    anchors = {name: False for name in ENGINE_ANCHORS}
    for relative, line, label, symbol, subject, value in declarations:
        for name, matches in ENGINE_ANCHORS.items():
            if name == label and matches(relative, symbol):
                anchors[name] = True
        if value != ENGINE:
            findings.append(Finding(
                "engine-version", relative, line,
                f'{subject} is "{value}", expected "{ENGINE}"'))

    for name, present in sorted(anchors.items()):
        if not present:
            findings.append(Finding(
                "engine-version", "repo", 0,
                f'cannot find the required {name} engine-version declaration; '
                f'refusing to pass vacuously'))

    if not findings:
        notes.append(f"engine-version: {len(declarations)} declarations, "
                     f'all "{ENGINE}"')

    # Component constants, when present, must compose the declared string.
    components: dict[str, tuple[str, int, int]] = {}
    for relative in tree.paths:
        if Path(relative).suffix != ".kt" or is_test_path(relative):
            continue
        text = tree.text(relative)
        if not text:
            continue
        for name in ("ENGINE_MAJOR", "ENGINE_MINOR", "ENGINE_PATCH"):
            for match in re.finditer(
                    r"\bconst\s+val\s+" + name + r"\s*(?::\s*Int)?\s*=\s*(\d+)", text):
                components[name] = (relative, line_of(text, match.start()),
                                    int(match.group(1)))
    if len(components) == 3:
        composed = ".".join(str(components[n][2])
                            for n in ("ENGINE_MAJOR", "ENGINE_MINOR", "ENGINE_PATCH"))
        if composed != ENGINE:
            relative, line, _ = components["ENGINE_MAJOR"]
            findings.append(Finding(
                "engine-version", relative, line,
                f'ENGINE_MAJOR/MINOR/PATCH compose "{composed}", expected "{ENGINE}"'))
        else:
            notes.append("engine-version: ENGINE_MAJOR/MINOR/PATCH compose "
                         + f'"{composed}"')
    elif components:
        notes.append("engine-version: partial MAJOR/MINOR/PATCH components, "
                     "composition not asserted")
    return findings, notes


# --------------------------------------------------------------------------- #
# 2. fx-constant-parity
# --------------------------------------------------------------------------- #

CPP_ROOTS = ("app/src/main/cpp", "native")
KOTLIN_ROOTS = (
    "app/src/main/java/com/music/bitchord/playback",
    "djmix-core/src",
)
CPP_SUFFIXES = (".h", ".hpp", ".cpp", ".cc")
CPP_NUMBER = (r"[-+]?(?:0[xX][0-9a-fA-F]+|(?:\d+(?:\.\d*)?|\.\d+)"
              r"(?:[eE][-+]?\d+)?)[fFlLuU]*")
CPP_CONSTEXPR = re.compile(
    r"\b(?:static\s+)?constexpr\s+(?:static\s+)?(?:const\s+)?"
    r"(?:float|double|int|int64_t|int32_t|size_t|uint32_t|uint64_t|unsigned|long|bool|auto)"
    r"\s+([A-Za-z_]\w*)\s*=\s*([^;]+);")
CPP_STATIC_CONST = re.compile(
    r"\bstatic\s+const\s+(?:float|double|int|int64_t|uint32_t|uint64_t|unsigned|long|bool)"
    r"\s+([A-Za-z_]\w*)\s*=\s*([^;]+);")
CPP_DEFINE = re.compile(
    r"^[ \t]*#[ \t]*define[ \t]+([A-Za-z_]\w*)[ \t]+([^/\n]+)$", re.MULTILINE)
CPP_ENUM = re.compile(
    r"\benum(?:\s+class)?\s+(\w+)?\s*(?::\s*[\w:]+(?:\s*,\s*[\w:]+)*\s*)?\{([^}]*)\}")
CPP_ENUM_ENTRY = re.compile(r"(?:^|,)\s*([A-Za-z_]\w*)\s*=\s*([^,}\n]+)")
KOTLIN_CONST = re.compile(
    r"\bconst\s+val\s+([A-Za-z_]\w*)\s*(?::\s*(?:Int|Float|Double|Long|Short))?"
    r"\s*=\s*([^\n]+)")
MIRROR_MARKER = re.compile(
    r"MIRROR\(\s*(kotlin|cpp)\s*\)\s*:?\s*([A-Za-z0-9_]+(?:\s*,\s*[A-Za-z0-9_]+)*)")
# A marker names the declaration directly below it, not an arbitrary symbol
# elsewhere in the repository. This window bounds "directly below".
MIRROR_WINDOW = 400
# Mirrors whose names differ between the languages. Every entry is verified to
# exist on both sides, so a renamed constant becomes a finding rather than a
# silently weakened gate.
ALIASED_MIRRORS = (
    ("cpp:kCapabilities", "kotlin:V2_CAPABILITIES"),
)
# Coverage floor: the C++/Kotlin FX surface has 11 envelope kinds and 12
# capability bits. Fewer discovered pairs means the scanners, not the code,
# regressed.
MIN_PARITY_PAIRS = 20


def cpp_declarations(text: str) -> list[tuple[str, int, str, str]]:
    """(name, offset, raw value, origin) for every C++ numeric declaration."""
    found: list[tuple[str, int, str, str]] = []
    for pattern, origin in ((CPP_CONSTEXPR, "constexpr"),
                            (CPP_STATIC_CONST, "static const"),
                            (CPP_DEFINE, "#define")):
        for match in pattern.finditer(text):
            found.append((match.group(1), match.start(), match.group(2), origin))
    # Enumerators with an explicit numeric value are mirrored constants too;
    # the FX kind/capability tables live in enums, not constexpr variables.
    # The entry pattern starts at the preceding comma, so the recorded offset
    # is the *name* position: a MIRROR marker above an entry must bind to that
    # entry, not to whatever follows it.
    for enum in CPP_ENUM.finditer(text):
        for entry in CPP_ENUM_ENTRY.finditer(enum.group(2)):
            found.append((entry.group(1), enum.start(2) + entry.start(1),
                          entry.group(2), "enum " + (enum.group(1) or "")))
    return found


def kotlin_declarations(text: str) -> list[tuple[str, int, str, str]]:
    """(name, offset, raw value, origin) for every Kotlin const val."""
    return [(match.group(1), match.start(), match.group(2), "const val")
            for match in KOTLIN_CONST.finditer(text)]



MAX_SHIFT = 63


def numeric(expression: str) -> float | int:
    """Evaluate a numeric literal expression, or raise ValueError.

    Accepts Kotlin ``shl``/``shr``/``ushr``/``and``/``or``/``xor`` and C++
    shifts/masks plus decimal, hex, underscores, float suffixes, and C casts.
    Rejects anything containing an identifier, so an unevaluable mirror is
    reported as uncovered instead of guessed.

    A malformed expression, a division by zero, a non-finite result and an
    absurd shift count all raise ValueError: returning a number the gate would
    then compare against its counterpart is worse than reporting no coverage.
    """
    text = expression.strip()
    if not text:
        raise ValueError("empty numeric expression")
    text = re.sub(r"\bushr\b", ">>", text)
    text = re.sub(r"\bshr\b", ">>", text)
    text = re.sub(r"\bshl\b", "<<", text)
    text = re.sub(r"\bxor\b", "^", text)
    text = re.sub(r"\bor\b", "|", text)
    text = re.sub(r"\band\b", "&", text)
    text = re.sub(r"(?<=\d|\.)[fFlLuU]\b", "", text)
    text = text.replace("_", "")
    text = re.sub(r"\(\s*(?:int|float|double|unsigned|long)\s*\)", " ", text)
    if not re.fullmatch(r"[\s0-9a-fA-FxX.+\-*/%()<>&|^~]*", text):
        raise ValueError("non-numeric expression")
    operations = {ast.Add: operator.add, ast.Sub: operator.sub,
                  ast.Mult: operator.mul, ast.Div: operator.truediv,
                  ast.Mod: operator.mod, ast.LShift: operator.lshift,
                  ast.RShift: operator.rshift, ast.BitOr: operator.or_,
                  ast.BitAnd: operator.and_, ast.BitXor: operator.xor}

    def visit(node):
        if isinstance(node, ast.Constant):
            if type(node.value) not in (int, float):
                raise ValueError("non-numeric literal")
            value = node.value
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError("non-finite literal")
            return value
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, (ast.USub, ast.UAdd,
                                                                 ast.Invert)):
            value = visit(node.operand)
            if isinstance(node.op, ast.USub):
                return -value
            if isinstance(node.op, ast.Invert):
                if not isinstance(value, int):
                    raise ValueError("bitwise invert of a non-integer")
                return ~value
            return value
        if isinstance(node, ast.BinOp) and type(node.op) in operations:
            left = visit(node.left)
            right = visit(node.right)
            if isinstance(node.op, (ast.LShift, ast.RShift)):
                if not isinstance(right, int) or not 0 <= right <= MAX_SHIFT:
                    raise ValueError("shift count out of range")
                if not isinstance(left, int):
                    raise ValueError("shift of a non-integer")
            if isinstance(node.op, (ast.Div, ast.Mod)) and right == 0:
                raise ValueError("division by zero")
            value = operations[type(node.op)](left, right)
            if isinstance(value, float) and not math.isfinite(value):
                raise ValueError("non-finite result")
            return value
        raise ValueError("unsupported numeric expression")

    try:
        value = visit(ast.parse(text.strip(), mode="eval").body)
    except (SyntaxError, TypeError, OverflowError, ZeroDivisionError) as exc:
        raise ValueError("unparseable numeric expression") from exc
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError("non-finite result")
    return value


def _add_constant(table: dict, name: str, value: str, path: str,
                  text: str, offset: int, origin: str) -> None:
    try:
        evaluated = numeric(value)
    except ValueError:
        return
    table.setdefault(name, []).append(
        {"path": path, "line": line_of(text, offset), "value": evaluated,
         "origin": origin})


def cpp_constants(tree: Tree) -> dict[str, list[dict]]:
    table: dict[str, list[dict]] = {}
    for relative in tree.paths:
        if not relative.startswith(CPP_ROOTS) or Path(relative).suffix.lower() not in CPP_SUFFIXES:
            continue
        raw = tree.text(relative)
        if raw is None:
            continue
        for name, offset, value, origin in cpp_declarations(strip_comments(raw)):
            _add_constant(table, name, value, relative, raw, offset, origin)
    return table


def kotlin_constants(tree: Tree) -> dict[str, list[dict]]:
    table: dict[str, list[dict]] = {}
    for relative in tree.paths:
        if not relative.startswith(KOTLIN_ROOTS) or Path(relative).suffix != ".kt":
            continue
        if is_test_path(relative):
            continue
        raw = tree.text(relative)
        if raw is None:
            continue
        for name, offset, value, origin in kotlin_declarations(strip_comments(raw)):
            _add_constant(table, name, value, relative, raw, offset, origin)
    return table


def mirror_claims(tree: Tree) -> list[dict]:
    """Resolve ``MIRROR(kotlin|cpp)`` comments to the declaration they annotate.

    A marker is read from the raw text (comments intact) and binds to exactly
    one declaration: the closest declaration starting on the marker's own line
    before it (a trailing comment), otherwise the next declaration below it
    within ``MIRROR_WINDOW`` characters. The claim names the declaration on the
    *other* side, so the pair is explicit rather than inferred from a shared
    name. Every claim is returned, resolved or not; the caller reports the
    unresolved ones instead of dropping them.
    """
    claims: list[dict] = []
    for relative in tree.paths:
        suffix = Path(relative).suffix.lower()
        if suffix not in (".h", ".hpp", ".cpp", ".cc", ".kt"):
            continue
        if not (relative.startswith(CPP_ROOTS) or relative.startswith(KOTLIN_ROOTS)):
            continue
        if is_test_path(relative):
            continue
        raw = tree.text(relative)
        if raw is None:
            continue
        cpp_side = suffix in CPP_SUFFIXES
        declarations = (cpp_declarations(strip_comments(raw)) if cpp_side
                        else kotlin_declarations(strip_comments(raw)))
        for marker in MIRROR_MARKER.finditer(raw):
            side = "cpp" if cpp_side else "kotlin"
            if marker.group(1) != ("kotlin" if cpp_side else "cpp"):
                continue          # a marker for the other direction
            line = line_of(raw, marker.start())
            same_line = [item for item in declarations
                         if line_of(raw, item[1]) == line and item[1] < marker.start()]
            if same_line:
                owner = max(same_line, key=lambda item: item[1])
            else:
                # Declarations are collected by rule, not by file order, so the
                # nearest one below the marker is the minimum offset, not the
                # first list entry.
                below = sorted((item for item in declarations if item[1] > marker.end()),
                               key=lambda item: item[1])
                owner = (below[0] if below
                         and below[0][1] - marker.end() <= MIRROR_WINDOW else None)
            for name in re.split(r"\s*,\s*", marker.group(2).strip()):
                if not name:
                    continue
                claims.append({
                    "side": side, "counterpart_language": marker.group(1),
                    "counterpart": name, "path": relative, "line": line,
                    "owner": ({"path": relative, "line": line_of(raw, owner[1]),
                               "name": owner[0], "raw": owner[2]} if owner else None),
                })
    return claims


def _unique_site(table: dict[str, list[dict]], name: str) -> dict | None:
    """The single declaration site for ``name``, or None when absent/ambiguous."""
    sites = table.get(name) or []
    if len({(item["path"]) for item in sites}) != 1:
        return None
    return sites[0]


def check_fx_parity(tree: Tree) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    notes: list[str] = []
    cpp, kotlin = cpp_constants(tree), kotlin_constants(tree)
    if not cpp:
        findings.append(Finding("fx-constant-parity", ",".join(CPP_ROOTS), 0,
                                "no C++ numeric constants discovered; "
                                "refusing to pass vacuously"))
    if not kotlin:
        findings.append(Finding("fx-constant-parity", ",".join(KOTLIN_ROOTS), 0,
                                "no Kotlin const val declarations discovered; "
                                "refusing to pass vacuously"))
    if findings:
        return findings, notes

    # Explicitly declared mirrors: MIRROR markers and the alias table. These
    # assert intent; a name match alone does not.
    declared: dict[tuple[str, str], str] = {}   # (cpp, kotlin) -> evidence
    for claim in mirror_claims(tree):
        if claim["owner"] is None:
            findings.append(Finding(
                "fx-constant-parity", claim["path"], claim["line"],
                f"MIRROR({claim['counterpart_language']}) has no adjacent "
                f"declaration within {MIRROR_WINDOW} characters; a marker that "
                f"annotates nothing cannot claim a pair"))
            continue
        owner = claim["owner"]
        counterpart_table = kotlin if claim["side"] == "cpp" else cpp
        if _unique_site(counterpart_table, claim["counterpart"]) is None:
            findings.append(Finding(
                "fx-constant-parity", owner["path"], owner["line"],
                f"MIRROR({claim['counterpart_language']}) claims "
                f"{claim['counterpart']} mirrors {owner['name']}, but "
                f"{claim['counterpart']} has no unique declaration on the "
                f"{claim['counterpart_language']} side"))
            continue
        pair = ((owner["name"], claim["counterpart"]) if claim["side"] == "cpp"
                else (claim["counterpart"], owner["name"]))
        declared[pair] = f"MIRROR at {claim['path']}:{claim['line']}"

    for cpp_symbol, kotlin_symbol in ALIASED_MIRRORS:
        cpp_name, kotlin_name = cpp_symbol.split(":", 1)[1], kotlin_symbol.split(":", 1)[1]
        if _unique_site(cpp, cpp_name) and _unique_site(kotlin, kotlin_name):
            declared[(cpp_name, kotlin_name)] = "ALIASED_MIRRORS table"
        else:
            findings.append(Finding(
                "fx-constant-parity", "tools/check_ae3_invariants.py", 0,
                f"declared mirror alias {cpp_name}/{kotlin_name} no longer "
                f"resolves to a unique declaration on both sides; update the "
                f"alias table or the sources"))

    # Name-matched mirrors: identical names on both sides, one file each. The
    # pair proves the two values agree; it does not by itself prove the two
    # declarations mean the same thing, which is why marker/alias pairs are
    # counted and reported separately.
    name_pairs = sorted(set(cpp) & set(kotlin))
    pairs: list[tuple[str, str]] = sorted(declared)
    for name in name_pairs:
        if (name, name) not in declared:
            pairs.append((name, name))

    compared = 0
    declared_compared = 0
    for cpp_name, kotlin_name in pairs:
        claim = declared.get((cpp_name, kotlin_name))
        cpp_values = {item["value"] for item in cpp[cpp_name]}
        kotlin_values = {item["value"] for item in kotlin[kotlin_name]}
        if len(cpp_values) > 1 or len(kotlin_values) > 1:
            findings.append(Finding(
                "fx-constant-parity", cpp[cpp_name][0]["path"],
                cpp[cpp_name][0]["line"],
                f"{cpp_name}/{kotlin_name} is declared with conflicting values "
                f"(C++ {sorted(cpp_values)}, Kotlin {sorted(kotlin_values)})"
                f"{'; declared by ' + claim if claim else ''}"))
            continue
        compared += 1
        if claim:
            declared_compared += 1
        if cpp_values != kotlin_values:
            first = cpp[cpp_name][0]
            second = kotlin[kotlin_name][0]
            findings.append(Finding(
                "fx-constant-parity", second["path"], second["line"],
                f"{kotlin_name} = {sorted(kotlin_values)[0]} in Kotlin but "
                f"{cpp_name} = {sorted(cpp_values)[0]} in C++ "
                f"({first['path']}:{first['line']}); "
                f"{claim or 'same-named'} mirror must agree"))

    names = ", ".join(f"{c}/{k}" for c, k in pairs)
    notes.append(f"fx-constant-parity: {compared} pairs compared "
                 f"({declared_compared} declared by marker/alias, "
                 f"{compared - declared_compared} matched by name)")
    notes.append("fx-constant-parity: " + (names if len(names) < 900
                                           else f"{len(pairs)} pairs"))
    notes.append("fx-constant-parity: covers Kotlin const val vs C++ "
                 "constexpr/#define/static const/enum enumerators; "
                 "MIRROR(kotlin|cpp) markers bind to the adjacent declaration "
                 "and are reported unresolved when either side is missing. "
                 "Name-matched pairs prove value agreement for identically "
                 "named declarations, not semantic identity; unrelated "
                 "constants are out of scope by design")
    if compared < MIN_PARITY_PAIRS:
        findings.append(Finding(
            "fx-constant-parity", "repo", 0,
            f"only {compared} mirrored pairs compared, below the floor of "
            f"{MIN_PARITY_PAIRS}; the parity surface shrank or the scanners "
            f"stopped matching"))
    return findings, notes


# --------------------------------------------------------------------------- #
# 3. recipe-id-parity
# --------------------------------------------------------------------------- #

RECIPE_RESOURCE_GLOB = "djmix-core/src/commonMain/resources/recipes"
RECIPE_ASSET_GLOB = "app/src/main/assets/recipes"
CATALOG = "djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/planner/BuiltInRecipeCatalog.kt"
BUNDLED = "djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/recipes/BundledRecipesJson.kt"
REGISTRY = "djmix-core/src/commonMain/kotlin/com/music/bitchord/djmix/core/recipes/RecipeRegistry.kt"


def recipe_sources(tree: Tree) -> tuple[dict[str, list[tuple[str, int]]], list[Finding]]:
    """Collect ``label -> [(id, line)]`` for every registry of recipe IDs.

    Labels prefixed with ``__`` are auxiliary views used for duplicate checks;
    the rest are compared against the canonical resources JSON set. Missing
    inputs are returned as findings, never as an empty pass.
    """
    sources: dict[str, list[tuple[str, int]]] = {}
    findings: list[Finding] = []
    declared_files: list[str] = []

    for label, folder in (("resources JSON", RECIPE_RESOURCE_GLOB),
                          ("app assets JSON", RECIPE_ASSET_GLOB)):
        files = [p for p in tree.paths
                 if p.startswith(folder + "/") and p.endswith(".json")]
        if not files:
            findings.append(Finding("recipe-id-parity", folder, 0,
                                    f"no recipe JSON found under {folder}; "
                                    f"refusing to pass vacuously"))
            continue
        ids: list[tuple[str, int]] = []
        for relative in sorted(files):
            text = tree.text(relative) or ""
            try:
                data = json.loads(text)
            except ValueError as exc:
                findings.append(Finding("recipe-id-parity", relative, 1,
                                        f"unparseable recipe JSON: {exc}"))
                continue
            declared = data.get("id") if isinstance(data, dict) else None
            stem = Path(relative).stem
            if declared != stem:
                findings.append(Finding(
                    "recipe-id-parity", relative, 1,
                    f'file name "{stem}" does not match declared id "{declared}"'))
            if not isinstance(declared, str) or not declared:
                findings.append(Finding("recipe-id-parity", relative, 1,
                                        "recipe JSON has no string id"))
                continue
            ids.append((declared, 1))
            if label == "resources JSON":
                declared_files.append(relative)
        sources[label] = ids

    catalog_text = tree.text(CATALOG)
    if catalog_text is None:
        findings.append(Finding("recipe-id-parity", CATALOG, 0,
                                "BuiltInRecipeCatalog.kt is missing; cannot check "
                                "catalog IDs"))
    else:
        entries = [(m.group(1), line_of(catalog_text, m.start()))
                   for m in re.finditer(
                       r"\bval\s+([A-Za-z_]\w*)\s*=\s*RecipeCatalogEntry\s*\(",
                       catalog_text)]
        sources["catalog entries"] = entries
        sources["__catalog id field"] = [
            (m.group(1), line_of(catalog_text, m.start()))
            for m in re.finditer(r"\bid\s*=\s*\"([A-Za-z0-9_.-]+)\"\s*,\s*\n",
                                 catalog_text)]
        if not entries:
            findings.append(Finding("recipe-id-parity", CATALOG, 0,
                                    "no RecipeCatalogEntry declarations found; "
                                    "the catalog shape changed"))

    bundled_text = tree.text(BUNDLED)
    if bundled_text is None:
        findings.append(Finding("recipe-id-parity", BUNDLED, 0,
                                "BundledRecipesJson.kt is missing; cannot check the "
                                "bundled JSON mirror"))
    else:
        ordered: list[tuple[str, int]] = []
        for block in re.finditer(r"\borderedIds\b[^=]*=\s*listOf\s*\(([^)]*)\)",
                                 bundled_text):
            for item in re.finditer(r"\"([^\"]+)\"", block.group(1)):
                ordered.append((item.group(1),
                                line_of(bundled_text, block.start(1) + item.start())))
        sources["bundled orderedIds"] = ordered
        sources["__bundled byId keys"] = [
            (m.group(1), line_of(bundled_text, m.start()))
            for m in re.finditer(r"^\s*\"([A-Za-z0-9_.-]+)\"\s+to\s+\"\"\"",
                                 bundled_text, re.MULTILINE)]
        sources["__bundled payload ids"] = [
            (m.group(1), line_of(bundled_text, m.start()))
            for m in re.finditer(r"\"id\"\s*:\s*\"([^\"]+)\"", bundled_text)]
        if not ordered:
            findings.append(Finding("recipe-id-parity", BUNDLED, 0,
                                    "no orderedIds list found in the bundled JSON "
                                    "mirror"))

    registry_text = tree.text(REGISTRY)
    if registry_text is None:
        findings.append(Finding("recipe-id-parity", REGISTRY, 0,
                                "RecipeRegistry.kt is missing; cannot confirm it "
                                "registers no independent IDs"))
    else:
        literals = [(m.group(1), line_of(registry_text, m.start()))
                    for m in re.finditer(r"\"([SCF][0-9]{1,2})\"", registry_text)]
        if literals:
            sources["__RecipeRegistry literals"] = literals
    sources["__files"] = [(path, 1) for path in declared_files]
    return sources, findings


def check_recipe_ids(tree: Tree) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    notes: list[str] = []
    sources, source_findings = recipe_sources(tree)
    findings.extend(source_findings)
    if "resources JSON" not in sources:
        return findings, notes

    canonical = {value for value, _ in sources["resources JSON"]}
    files = [value for value, _ in sources.pop("__files", [])]
    if not canonical:
        findings.append(Finding("recipe-id-parity", "resources JSON", 0,
                                "canonical recipe ID set is empty"))

    for label in sorted(sources):
        if label.startswith("__"):
            continue
        ids = {value for value, _ in sources[label]}
        missing = sorted(canonical - ids)
        extra = sorted(ids - canonical)
        if missing:
            findings.append(Finding(
                "recipe-id-parity", label, 0,
                "missing recipe IDs present in resources JSON: "
                + ", ".join(missing)))
        if extra:
            findings.append(Finding(
                "recipe-id-parity", label, 0,
                "extra recipe IDs absent from resources JSON: "
                + ", ".join(extra)))

    for label in sorted(sources):
        if not label.startswith("__"):
            continue
        seen: dict[str, int] = {}
        for value, line in sources[label]:
            if value in seen:
                findings.append(Finding(
                    "recipe-id-parity", label[2:], line,
                    f'duplicate recipe ID "{value}" (first at line {seen[value]})'))
            else:
                seen[value] = line
        ids = set(seen)
        missing = sorted(canonical - ids)
        extra = sorted(ids - canonical)
        if missing:
            findings.append(Finding("recipe-id-parity", label[2:], 0,
                                    "missing recipe IDs: " + ", ".join(missing)))
        if extra:
            findings.append(Finding("recipe-id-parity", label[2:], 0,
                                    "extra recipe IDs: " + ", ".join(extra)))

    notes.append(f"recipe-id-parity: {len(canonical)} recipe IDs across "
                 f"{len(files)} resource JSON files")
    notes.append("recipe-id-parity: RecipeRegistry.kt declares its own ID "
                 "literals" if "__RecipeRegistry literals" in sources else
                 "recipe-id-parity: RecipeRegistry.kt declares no independent ID list")
    return findings, notes


# --------------------------------------------------------------------------- #
# 4. secret-scan (B14.1)
# --------------------------------------------------------------------------- #

SECRET_RULES = (
    ("private key", re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----")),
    ("provider token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("provider token", re.compile(r"\bgithub_pat_[A-Za-z0-9_]{20,}\b")),
    ("cloud API key", re.compile(r"\bAIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])")),
    ("cloud API key", re.compile(r"\bAKIA[A-Z0-9]{16}\b")),
    ("cloud API key", re.compile(r"\bASIA[A-Z0-9]{16}\b")),
    ("signing material", re.compile(r"\bsk-(?:proj-|live_)?[A-Za-z0-9_-]{24,}\b")),
    ("signing material", re.compile(r"\b[rs]k_live_[A-Za-z0-9]{20,}\b")),
)
CREDENTIAL_ASSIGNMENT = re.compile(
    r"[\"']?\b(ANDROID_KEYSTORE|ANDROID_KEY_ALIAS|KEYSTORE_BASE64"
    r"|KEYSTORE_PASSWORD|KEY_PASSWORD|STORE_PASSWORD|storePassword|keyPassword)"
    r"\b[\"']?[ \t]*[:=](?![-=])[ \t]*(.*)$",
    re.MULTILINE)
# Exact placeholder values only. Whole-string, case-insensitive compare: a
# substring rule would hide a real password such as "Pr0ductionSecret!".
PLACEHOLDER_WORDS = {
    "", "null", "none", "nil", "changeme", "change-me", "placeholder",
    "example", "dummy", "fixture", "sample", "redacted", "xxx", "xxxx",
    "tbd", "todo", "n/a", "na", "false", "true",
}
# An expression that resolves at build/run time, not a literal secret.
INDIRECTION = re.compile(
    r"^(?:"
    r"\$\{.*\}"                       # Kotlin/Gradle/Node interpolation
    r"|\$[A-Za-z_]\w*"                # shell variable
    r"|\$\([^)]*\)"                   # command substitution
    r"|\{\{.*\}\}"                    # template
    r"|%[A-Za-z_]\w*%"                # Windows env
    r"|@[\w.]+@"                      # Maven-style
    r"|<[^>]*>"                       # prose placeholder
    r"|%[sd]"                         # printf format
    r"|(?:getProperty|System\s*\.\s*getenv|System\s*\.\s*getProperty|env"
    r"|os\s*\.\s*environ|os\s*\.\s*getenv|settings|project|providers"
    r"|findProperty|signing)\b"
    r"|[A-Za-z_][\w.:]*\s*\("         # a call such as signing.getProperty(...)
    r")")
FILLER = re.compile(r"^(.)\1{2,}$")
PASSWORD_FIELD = re.compile(r"^\s*(storePassword|keyPassword)\s*=\s*(.*)$",
                            re.MULTILINE)
HOME_PATH = re.compile(r"(?:/home/([a-z][a-z0-9_.-]*)/"
                       r"|/Users/([A-Za-z][A-Za-z0-9_.-]*)/"
                       r"|[A-Za-z]:\\\\+Users\\\\+([A-Za-z][A-Za-z0-9_.-]*)\\*)")
# Server deployment scripts legitimately address service accounts, not a
# developer home directory; only real user homes are a local.properties leak.
SYSTEM_HOME_USERS = {"deploy", "root", "nobody", "ubuntu", "runner", "app",
                     "www-data", "git", "boxes"}
PATH_RULE_SUFFIXES = {
    ".kt", ".kts", ".java", ".py", ".sh", ".bash", ".yml", ".yaml", ".json",
    ".properties", ".toml", ".gradle", ".cpp", ".cc", ".c", ".h", ".hpp",
    ".rs", ".swift", ".xml", ".cfg", ".ini", ".pro", ".mk",
}
# (path, rule|None) -> documented reason. Narrow on purpose: the release-gate
# test generates a throwaway PKCS#12 and passes throwaway passwords through a
# child process environment; nothing it writes can become a credential. No
# other file is exempt: both AE3 tools are scanned like any other source.
ALLOWLIST = {
    ("tools/release/test-release-gates.py", "credential assignment"):
        "B14.1: generates a throwaway PKCS#12 fixture and passes dummy "
        "passwords only through its child process environment",
}


def _placeholder(value: str) -> bool:
    """True only for an exact placeholder token or an indirection expression.

    Deliberately strict: a real secret must never be classified as a
    placeholder, so substring matching is not used here.
    """
    stripped = value.strip().rstrip(",").strip().strip("\"'`").strip()
    stripped = stripped.rstrip("`").strip()
    if not stripped:
        return True
    if stripped.lower() in PLACEHOLDER_WORDS:
        return True
    if INDIRECTION.match(stripped):
        return True
    return bool(FILLER.match(stripped))


def check_secrets(tree: Tree) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    notes: list[str] = []
    scanned = 0
    allowed: list[str] = []
    for relative in tree.paths:
        suffix = Path(relative).suffix.lower()
        if suffix in {".jks", ".keystore", ".p12", ".pfx"}:
            findings.append(Finding("secret-scan", relative, 0,
                                    "committed signing keystore file"))
            continue
        text = tree.text(relative)
        if text is None:
            continue
        scanned += 1
        for rule, pattern in SECRET_RULES:
            for match in pattern.finditer(text):
                if (relative, rule) in ALLOWLIST:
                    allowed.append(f"{relative}: {ALLOWLIST[(relative, rule)]}")
                    continue
                findings.append(Finding(
                    "secret-scan", relative, line_of(text, match.start()),
                    f"committed {rule} matching /{pattern.pattern}/ "
                    f"({len(match.group(0))} chars; value not printed)"))
        for match in CREDENTIAL_ASSIGNMENT.finditer(text):
            value = match.group(2).strip().strip("\"'").strip()
            if _placeholder(value):
                continue
            if (relative, "credential assignment") in ALLOWLIST:
                allowed.append(
                    f"{relative}: {ALLOWLIST[(relative, 'credential assignment')]}")
                continue
            findings.append(Finding(
                "secret-scan", relative, line_of(text, match.start()),
                f"{match.group(1)} is assigned a literal value; commit a "
                f"placeholder or read it from the environment"))
        if Path(relative).name == "keystore.properties":
            for match in PASSWORD_FIELD.finditer(text):
                if match.group(2).strip():
                    findings.append(Finding(
                        "secret-scan", relative, line_of(text, match.start()),
                        f"{match.group(1)} has a committed value in tracked "
                        f"keystore.properties"))
        if suffix in PATH_RULE_SUFFIXES:
            for match in HOME_PATH.finditer(text):
                user = next(group for group in match.groups() if group)
                if user in SYSTEM_HOME_USERS:
                    continue
                findings.append(Finding(
                    "secret-scan", relative, line_of(text, match.start()),
                    f"absolute home directory path for user '{user}' in "
                    f"tracked source (local.properties-style machine path)"))
    notes.append(f"secret-scan: scanned {scanned} text files "
                 f"({len(tree.paths)} candidates, binaries and build output excluded)")
    for entry in sorted(set(allowed)):
        notes.append("secret-scan: allowlisted " + entry)
    if scanned == 0:
        findings.append(Finding("secret-scan", "repo", 0,
                                "no scannable text file found; refusing to pass "
                                "vacuously"))
    return findings, notes


# --------------------------------------------------------------------------- #
# 5. no-timeout-sleep
# --------------------------------------------------------------------------- #

AUDIOTHREAD_TOOL = "check_audiothread_alloc.py"
SLEEP_CALL = re.compile(r"\bThread\s*\.\s*sleep\s*\(|\bSystemClock\s*\.\s*sleep\s*\("
                        r"|(?<![\w.])delay\s*\(")


def load_audiothread_tool() -> object:
    path = Path(__file__).resolve().with_name(AUDIOTHREAD_TOOL)
    if not path.is_file():
        raise FileNotFoundError(
            f"{AUDIOTHREAD_TOOL} is required: this check reuses its "
            f"@AudioThread region convention")
    spec = importlib.util.spec_from_file_location("_ae3_audiothread_alloc", path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {AUDIOTHREAD_TOOL}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_audiothread_sleep(tree: Tree, module: object) -> tuple[list[Finding], list[str]]:
    findings: list[Finding] = []
    notes: list[str] = []
    regions = 0
    files = 0
    for relative in tree.paths:
        if Path(relative).suffix != ".kt":
            continue
        if not relative.startswith(("app/src/main", "djmix-core", "shared")):
            continue
        raw = tree.text(relative)
        if raw is None:
            continue
        cleaned = module.strip_comments_and_strings(raw)
        spans = module.find_audiothread_regions(cleaned)
        if not spans:
            continue
        files += 1
        lines = cleaned.split("\n")
        for start, end, annotation in spans:
            regions += 1
            for number in range(start, end + 1):
                if not 0 < number <= len(lines):
                    continue
                match = SLEEP_CALL.search(lines[number - 1])
                if match:
                    findings.append(Finding(
                        "no-timeout-sleep", relative, number,
                        f"'{match.group(0).strip()}' inside @AudioThread "
                        f"(annotated at line {annotation}); the audio thread "
                        f"must never sleep or delay"))
    notes.append(f"no-timeout-sleep: {regions} @AudioThread regions in "
                 f"{files} Kotlin files")
    if regions == 0:
        findings.append(Finding(
            "no-timeout-sleep", "repo", 0,
            "no @AudioThread region found; the annotation convention changed "
            "or the annotation was removed, so this check cannot pass"))
    return findings, notes


# --------------------------------------------------------------------------- #
# Driver
# --------------------------------------------------------------------------- #

def run(root: Path, names: list[str]) -> tuple[list[Finding], list[str]]:
    tree = Tree(root)
    findings: list[Finding] = []
    notes: list[str] = []
    audiothread = None
    for name in names:
        if name == "engine-version":
            found, extra = check_engine_version(tree)
        elif name == "fx-constant-parity":
            found, extra = check_fx_parity(tree)
        elif name == "recipe-id-parity":
            found, extra = check_recipe_ids(tree)
        elif name == "secret-scan":
            found, extra = check_secrets(tree)
        elif name == "no-timeout-sleep":
            if audiothread is None:
                audiothread = load_audiothread_tool()
            found, extra = check_audiothread_sleep(tree, audiothread)
        else:
            raise ValueError("unknown check " + name)
        findings.extend(found)
        notes.append(f"[{name}] {len(found)} finding(s)")
        notes.extend(extra)
    return findings, notes


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="AE3 release-gate invariants (read-only).")
    parser.add_argument("--root", type=Path,
                        default=Path(__file__).resolve().parents[1],
                        help="repository root (default: parent of tools/)")
    parser.add_argument("--only", action="append", metavar="NAME",
                        help="run one check; repeatable or comma separated. "
                             "Names: " + ", ".join(CHECK_NAMES))
    parser.add_argument("--list", action="store_true", help="list check names")
    args = parser.parse_args(argv)
    if args.list:
        print("\n".join(CHECK_NAMES))
        return 0
    if not args.root.is_dir():
        parser.error("--root must be an existing directory")

    names: list[str] = []
    for chunk in args.only or []:
        for name in chunk.split(","):
            name = name.strip()
            if not name:
                continue
            if name not in CHECK_NAMES:
                print(f"error: unknown check '{name}'; known: "
                      + ", ".join(CHECK_NAMES), file=sys.stderr)
                return 2
            if name not in names:
                names.append(name)
    if not names:
        names = list(CHECK_NAMES)

    try:
        findings, notes = run(Path(args.root), names)
    except (OSError, ImportError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2

    for note in notes:
        print(f"note  {note}")
    for finding in findings:
        print(f"FAIL  {finding}")
    for name in names:
        count = sum(1 for finding in findings if finding.check == name)
        print(f"      {name}: {count} finding(s)")
    print(f"{len(findings)} finding(s) across {len(names)} check(s)")
    return 0 if not findings else 1


if __name__ == "__main__":
    raise SystemExit(main())
