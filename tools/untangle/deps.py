#!/usr/bin/env python3
"""Dependency graph for the Automix (Part D2) scope.

Parses, across the Part D2 automix scope (same scope as skeleton.py):
  * Kotlin `import`s (incl. `as` aliases and `.*` wildcards)
  * package-internal symbol references -- files in the SAME package call
    each other with NO import (e.g. com.music.bitchord.playback.smart
    spanning app/ / shared/ commonMain / shared/ jvmSharedMain);
    detected by same-package simple-name scan over comment/string-stripped
    code, plus fully-qualified `com.music.bitchord...` usages
  * C++ `#include "..."` (quoted, resolved by path suffix then basename)
    and `#include <...>` (recorded as external)
  * JNI bindings: `Java_com_music_bitchord_playback_loopmix_...` symbols
    in C++ -> the Kotlin class file declaring that FQ class
    (single directed edge cpp -> kt: the implementation binding).

Groups nodes by architectural layer (11 layers, rank low->high =
stable engine -> volatile):
  app/ui, app/service, app/controller, app/djmix, app/loopmix,
  app/analyzer, app/settings_telemetry, shared/smart, native/loopmix,
  native/analyzer, app/cpp
Layer assignment (path-based, first match wins, see layer_of()):
  shared/.../playback/smart/**                     -> shared/smart
    (+ shared playback/djmix/**, loopmix/** if present -> shared/smart)
  native/loopmix/** -> native/loopmix ; native/analyzer/** -> native/analyzer
  app/src/main/cpp/**                              -> app/cpp
  app/.../playback/djmix/**  -> app/djmix
  app/.../playback/loopmix/**                     -> app/loopmix
  app/.../playback/smart/**   -> app/analyzer (incl. smart/TrackAnalyzer.kt,
                                 smart/Npu*.kt, choreo/)
  app/.../playback/CrossfadeController.kt,
    AutoMixVisualFeed.kt, Bounded*.kt              -> app/controller
  app/.../playback/PlaybackService.kt              -> app/service
  app/.../playback/AutomixTelemetry.kt,
    data/settings/AppSettings.kt                   -> app/settings_telemetry
  MixViewPopup.kt, PlayerControls.kt (sharedUi/ui/player),
    MiniPlayer.kt (ui/components), SettingsSheet.kt (ui/screens) -> app/ui

Detects import/reference cycles (Tarjan SCCs, layer-level and file-level)
and volatility inversions:
  HARD: shared/smart | native/*  ->  any app/* layer
        (shared is multiplatform, native is pure DSP; neither may depend
         on app code)
  SOFT: {shared/smart, native/*, app/cpp, app/djmix, app/loopmix,
         app/analyzer}  ->  {app/ui, app/service, app/settings_telemetry}
        (engine coupled to volatile UI / global settings / service glue)
  app/controller and app/service are glue and are NOT counted as sources.

Emits, deterministically (stdlib only, no `dot` needed):
  docs/untangle/deps.svg       layered SVG (columns = layers low->high,
                               aggregated arrows with counts; red dashed =
                               cycle, orange = volatility inversion)
  docs/untangle/deps_report.md layer edge table, file fan-in/out top 20,
                               cycles, volatility inversions, externals,
                               full file-edge appendix

Usage:
  python3 tools/untangle/deps.py [--repo DIR] [--out-dir DIR]
Defaults: --repo inferred from script location, --out-dir = <repo>/docs/untangle
"""

import argparse
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path
from xml.etree import ElementTree
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
import skeleton  # same scope + literal blanking (single source of truth)

# --------------------------------------------------------------------------
# Layers
# --------------------------------------------------------------------------

LAYER_ORDER = [
    "native/loopmix", "native/analyzer", "app/cpp", "shared/smart",
    "app/djmix", "app/loopmix", "app/analyzer", "app/controller",
    "app/service", "app/settings_telemetry", "app/ui",
]
RANK = {
    "native/loopmix": 0, "native/analyzer": 0, "app/cpp": 1,
    "shared/smart": 1, "app/djmix": 2, "app/loopmix": 2,
    "app/analyzer": 2, "app/controller": 3, "app/service": 4,
    "app/settings_telemetry": 4, "app/ui": 5,
}
HARD_SRC = {"shared/smart", "native/loopmix", "native/analyzer"}
SOFT_SRC = HARD_SRC | {"app/cpp", "app/djmix", "app/loopmix", "app/analyzer"}
VOLATILE_DST = {"app/ui", "app/service", "app/settings_telemetry"}


def layer_of(rel: str) -> str:
    if rel.startswith("shared/") and "/playback/smart/" in rel:
        return "shared/smart"
    if rel.startswith("shared/") and (
            "/playback/djmix/" in rel or "/playback/loopmix/" in rel):
        return "shared/smart"
    if rel.startswith("native/loopmix/"):
        return "native/loopmix"
    if rel.startswith("native/analyzer/"):
        return "native/analyzer"
    if rel.startswith("app/src/main/cpp/"):
        return "app/cpp"
    if rel.startswith("app/src/main/java/com/music/bitchord/playback/djmix/"):
        return "app/djmix"
    if rel.startswith("app/src/main/java/com/music/bitchord/playback/loopmix/"):
        return "app/loopmix"
    if rel.startswith("app/src/main/java/com/music/bitchord/playback/smart/"):
        return "app/analyzer"
    if rel.endswith("/playback/CrossfadeController.kt") \
            or rel.endswith("/playback/AutoMixVisualFeed.kt") \
            or re.search(r"/playback/Bounded[^/]*\.kt$", rel):
        return "app/controller"
    if rel.endswith("/playback/PlaybackService.kt"):
        return "app/service"
    if rel.endswith("/playback/AutomixTelemetry.kt") \
            or rel.endswith("/data/settings/AppSettings.kt"):
        return "app/settings_telemetry"
    if rel.endswith("MixViewPopup.kt") or rel.endswith("PlayerControls.kt") \
            or rel.endswith("MiniPlayer.kt") or rel.endswith("SettingsSheet.kt"):
        return "app/ui"
    return "UNKNOWN:" + rel


# --------------------------------------------------------------------------
# Kotlin parsing
# --------------------------------------------------------------------------

KT_PKG_RE = re.compile(r"^\s*package\s+([\w.]+)\s*$")
KT_IMP_RE = re.compile(r"^\s*import\s+(.+?)\s*$")
KT_TYPE_RE = re.compile(r"\b(class|interface|object)\s+(\w+)")
KT_FUN_RE = re.compile(r"\bfun\s+(?:<[^>\n]*>\s*)?(?:[\w.<>?,\s]+\.)?(\w+)\s*\(")
KT_TYALIAS_RE = re.compile(r"^\s*typealias\s+(\w+)")
KT_EXTFUN_RE = re.compile(r"\bexternal\s+fun\s+(?:<[^>\n]*>\s*)?"
                          r"(?:[\w.<>?,\s]+\.)?(\w+)\s*\(")
KT_FQ_USE_RE = re.compile(r"\b(com\.music\.bitchord(?:\.[\w]+)+)")


def kt_parse(lines, clean_lines):
    package = ""
    imports = []  # (lineno, raw after 'import')
    import_lines = set()
    for i, raw in enumerate(lines, 1):
        m = KT_PKG_RE.match(raw)
        if m and not package:
            package = m.group(1).strip()
        mi = KT_IMP_RE.match(raw)
        if mi:
            imports.append((i, mi.group(1).strip()))
            import_lines.add(i)
    # top-level declarations via brace depth
    depth = 0
    depth_before = []
    for cl in clean_lines:
        depth_before.append(depth)
        depth += cl.count("{") - cl.count("}")
    declared = []  # (name, kind)
    extern_funs = []  # (name, depth_before)
    for i, cl in enumerate(clean_lines):
        if depth_before[i] != 0:
            continue
        m = KT_TYALIAS_RE.match(cl)
        if m:
            declared.append((m.group(1), "typealias"))
            continue
        if re.search(r"\bcompanion\s+object\b", cl):
            continue  # not top-level-named for FQ purposes
        m = KT_TYPE_RE.search(cl)
        if m:
            lead = cl[: m.start()]
            kw = m.group(1)
            if kw == "object" and ("=" in lead or "(" in lead or "," in lead):
                pass
            else:
                declared.append((m.group(2), kw))
                continue
        mf = KT_FUN_RE.search(cl)
        if mf:
            declared.append((mf.group(1), "fun"))
    for cl in clean_lines:
        m = KT_EXTFUN_RE.search(cl)
        if m:
            extern_funs.append(m.group(1))
    return package, imports, import_lines, declared, extern_funs


def resolve_fq(index, dotted):
    """Longest prefix of `dotted` present as key in index (segment-wise)."""
    parts = dotted.split(".")
    for k in range(len(parts), 0, -1):
        cand = ".".join(parts[:k])
        if cand in index:
            return cand
    return None


def ext_root(fq: str) -> str:
    parts = fq.split(".")
    if len(parts) >= 3:
        return ".".join(parts[:3])
    return fq


# --------------------------------------------------------------------------
# Tarjan SCC (iterative)
# --------------------------------------------------------------------------

def tarjan_scc(nodes, adj):
    index = {}
    low = {}
    onstack = set()
    stack = []
    result = []
    counter = [0]
    for root in sorted(nodes):
        if root in index:
            continue
        work = [(root, iter(sorted(adj.get(root, ()))))]
        index[root] = low[root] = counter[0]
        counter[0] += 1
        stack.append(root)
        onstack.add(root)
        while work:
            node, it = work[-1]
            advanced = False
            for nxt in it:
                if nxt not in index:
                    index[nxt] = low[nxt] = counter[0]
                    counter[0] += 1
                    stack.append(nxt)
                    onstack.add(nxt)
                    work.append((nxt, iter(sorted(adj.get(nxt, ())))))
                    advanced = True
                    break
                elif nxt in onstack:
                    low[node] = min(low[node], index[nxt])
            if advanced:
                continue
            work.pop()
            if work:
                parent = work[-1][0]
                low[parent] = min(low[parent], low[node])
            if low[node] == index[node]:
                comp = []
                while True:
                    w = stack.pop()
                    onstack.discard(w)
                    comp.append(w)
                    if w == node:
                        break
                result.append(sorted(comp))
    return result


# --------------------------------------------------------------------------
# Main analysis
# --------------------------------------------------------------------------

def analyze(repo: Path):
    scope = skeleton.find_scope(repo)
    layers = {rel: layer_of(rel) for rel in scope}
    unknown = [r for r, l in layers.items() if l.startswith("UNKNOWN")]
    if unknown:
        print(f"WARN unmapped scope files: {unknown}", file=sys.stderr)
    texts = {}
    for rel in scope:
        texts[rel] = (repo / rel).read_text(encoding="utf-8", errors="replace")

    # ---- Kotlin indexes ----
    kt_info = {}  # rel -> dict
    fq_to_file = {}  # FQ symbol -> file
    simple_by_pkg = defaultdict(lambda: defaultdict(set))  # pkg -> name -> files
    pkg_to_files = defaultdict(set)
    for rel in scope:
        if not rel.endswith(".kt"):
            continue
        lines = texts[rel].splitlines()
        clean = skeleton.blank_literals(texts[rel], kotlin=True)
        clean_lines = clean.splitlines()
        while len(clean_lines) < len(lines):
            clean_lines.append("")
        pkg, imports, ilines, declared, extfuns = kt_parse(lines, clean_lines)
        kt_info[rel] = {
            "package": pkg, "imports": imports, "import_lines": ilines,
            "declared": declared, "extern_funs": extfuns,
            "clean_lines": clean_lines, "lines": lines,
        }
        pkg_to_files[pkg].add(rel)
        for name, kind in declared:
            fq = f"{pkg}.{name}" if pkg else name
            # first file wins for FQ (duplicate top-level names across
            # modules are possible; record set to handle later if needed)
            fq_to_file.setdefault(fq, rel)
            simple_by_pkg[pkg][name].add(rel)

    def add_edge(edges, evid, src, dst, kind, detail):
        if src == dst:
            return
        edges.setdefault((src, dst), set()).add(kind)
        evid.setdefault((src, dst), set()).add(detail)

    edges = {}
    evid = defaultdict(set)
    ext_kt = Counter()       # external import roots
    ext_kt_detail = defaultdict(set)
    ext_cpp = Counter()      # angle includes
    unresolved_inc = []      # (src, include)
    kt_import_edges = 0

    # ---- Kotlin import + same-package + FQ-usage edges ----
    for rel in scope:
        if rel not in kt_info:
            continue
        info = kt_info[rel]
        pkg = info["package"]
        for ln, rawimp in info["imports"]:
            alias = None
            fq = rawimp
            m = re.match(r"^([\w.]+)\s+as\s+(\w+)$", rawimp)
            if m:
                fq, alias = m.group(1), m.group(2)
            if fq.endswith(".*"):
                want = fq[:-2]
                targets = sorted(pkg_to_files.get(want, ()))
                if targets:
                    for t in targets:
                        add_edge(edges, evid, rel, t, "wildcard", fq)
                        kt_import_edges += 1
                else:
                    ext_kt[ext_root(fq)] += 1
                    ext_kt_detail[ext_root(fq)].add(fq)
                continue
            hit = resolve_fq(fq_to_file, fq)
            if hit is not None:
                add_edge(edges, evid, rel, fq_to_file[hit], "import", fq)
                kt_import_edges += 1
            elif fq.startswith("com.music.bitchord"):
                # in-house but outside Part D2 scope
                ext_kt[ext_root(fq)] += 1
                ext_kt_detail[ext_root(fq)].add(fq)
            else:
                ext_kt[ext_root(fq)] += 1
                ext_kt_detail[ext_root(fq)].add(fq)
        # same-package simple-name scan (skip package/import lines)
        names = set()
        for n, files in simple_by_pkg.get(pkg, {}).items():
            if any(f != rel for f in files):
                names.add(n)
        # also consider names declared by this file's own package that are
        # imported explicitly -- covered by import edges; skip to reduce noise
        if names:
            pat = re.compile(r"\b(" + "|".join(
                re.escape(n) for n in sorted(names, key=len, reverse=True)) +
                r")\b")
            for i, cl in enumerate(info["clean_lines"], 1):
                if i in info["import_lines"]:
                    continue
                if KT_PKG_RE.match(info["lines"][i - 1]):
                    continue
                for mm in pat.finditer(cl):
                    nm = mm.group(1)
                    for t in sorted(simple_by_pkg[pkg][nm]):
                        if t != rel:
                            add_edge(edges, evid, rel, t, "same-package", nm)
        # FQ usages in body
        for i, cl in enumerate(info["clean_lines"], 1):
            if i in info["import_lines"]:
                continue
            for mm in KT_FQ_USE_RE.finditer(cl):
                hit = resolve_fq(fq_to_file, mm.group(1))
                if hit:
                    add_edge(edges, evid, rel, fq_to_file[hit], "fq-usage",
                             mm.group(1))

    # ---- C++ includes + JNI ----
    # include resolution maps
    by_basename = defaultdict(list)
    for rel in scope:
        by_basename[Path(rel).name].append(rel)
    # JNI prefixes: top-level Kotlin classes / typealiases
    jni_checks = []  # (prefix, kt_file)
    for rel in scope:
        if rel not in kt_info:
            continue
        info = kt_info[rel]
        base = Path(rel).stem + "Kt"
        prefixes = []
        for name, kind in info["declared"]:
            if kind in ("class", "interface", "object"):
                fq = f"{info['package']}.{name}" if info["package"] else name
                prefixes.append("Java_" + fq.replace(".", "_") + "_")
        # top-level `external fun` (facade class)
        if info["extern_funs"]:
            pkg_fq = (info["package"] + "." if info["package"] else "") + base
            prefixes.append("Java_" + pkg_fq.replace(".", "_") + "_")
        for p in prefixes:
            jni_checks.append((p, rel))

    for rel in scope:
        if rel.endswith(".kt"):
            continue
        text = texts[rel]
        clean = skeleton.blank_literals(text, kotlin=False)
        # #includes from RAW text (the include line itself must be read)
        for m in re.finditer(r'^\s*#\s*include\s*(<[^>]+>|"[^"]+")',
                             text, re.M):
            inc = m.group(1)
            if inc.startswith("<"):
                ext_cpp[inc] += 1
                continue
            name = inc.strip('"')
            norm = name
            while norm.startswith("../") or norm.startswith("./"):
                norm = norm[3:] if norm.startswith("../") else norm[2:]
            cands = [f for f in scope
                     if f == norm or f.endswith("/" + norm)]
            if not cands:
                cands = sorted(by_basename.get(Path(norm).name, []))
            if cands:
                for t in sorted(set(cands)):
                    add_edge(edges, evid, rel, t, "include", name)
            else:
                unresolved_inc.append((rel, name))
        # JNI symbols live in code, not comments: scan clean text
        for prefix, kt_file in jni_checks:
            if prefix in clean:
                add_edge(edges, evid, rel, kt_file, "jni",
                         prefix.rstrip("_"))

    # ---- graphs ----
    nodes = scope
    adj = defaultdict(set)
    radj = defaultdict(set)
    for (s, d) in edges:
        adj[s].add(d)
        radj[d].add(s)
    fan_in = {n: len(radj.get(n, ())) for n in nodes}
    fan_out = {n: len(adj.get(n, ())) for n in nodes}

    file_sccs = tarjan_scc(nodes, adj)
    file_cycles = [c for c in file_sccs if len(c) > 1]
    self_loops = sorted({s for (s, d) in edges if s == d})

    layer_adj = defaultdict(set)
    layer_pair = Counter()
    layer_pair_kinds = defaultdict(set)
    for (s, d), kinds in edges.items():
        ls, ld = layers[s], layers[d]
        layer_pair[(ls, ld)] += 1
        for k in kinds:
            layer_pair_kinds[(ls, ld)].add(k)
        layer_adj[ls].add(ld)
    layer_sccs = tarjan_scc(LAYER_ORDER, layer_adj)
    layer_cycles = [c for c in layer_sccs if len(c) > 1]

    # ---- volatility inversions ----
    inversions = []  # (kind, src, dst, kinds, sample evidence)
    for (s, d), kinds in sorted(edges.items()):
        ls, ld = layers[s], layers[d]
        if ls in HARD_SRC and ld.startswith("app/"):
            inversions.append(("HARD", s, d, sorted(kinds),
                               sorted(evid[(s, d)])[:6]))
        elif ls in SOFT_SRC and ld in VOLATILE_DST:
            tag = ("SOFT-settings" if ld == "app/settings_telemetry"
                   else "SOFT-ui/service")
            inversions.append((tag, s, d, sorted(kinds),
                               sorted(evid[(s, d)])[:6]))
    inv_counter = Counter(t for t, _, _, _, _ in inversions)
    inv_layer_pairs = Counter()
    for t, s, d, _, _ in inversions:
        inv_layer_pairs[(t, layers[s], layers[d])] += 1

    by_layer_files = defaultdict(list)
    for rel in scope:
        by_layer_files[layers[rel]].append(rel)
    for k in by_layer_files:
        by_layer_files[k].sort(key=lambda r: (r.rsplit("/", 1)[-1], r))

    try:
        head = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "--short", "HEAD"],
            capture_output=True, text=True, timeout=10).stdout.strip()
    except Exception:
        head = "unknown"
    if not head:
        head = "unknown"

    return {
        "scope": scope, "layers": layers, "by_layer": dict(by_layer_files),
        "edges": edges, "evid": dict(evid), "fan_in": fan_in,
        "fan_out": fan_out, "adj": dict(adj), "radj": dict(radj),
        "file_cycles": file_cycles, "self_loops": self_loops,
        "layer_pair": layer_pair, "layer_pair_kinds": dict(layer_pair_kinds),
        "layer_cycles": layer_cycles, "inversions": inversions,
        "inv_counter": inv_counter, "inv_layer_pairs": inv_layer_pairs,
        "ext_kt": ext_kt, "ext_kt_detail": dict(ext_kt_detail),
        "ext_cpp": ext_cpp, "unresolved": unresolved_inc,
        "kt_import_edges": kt_import_edges, "head": head,
    }


# --------------------------------------------------------------------------
# SVG (layered, pure Python, deterministic)
# --------------------------------------------------------------------------

def _esc(s: str) -> str:
    return escape(str(s), {'"': "&quot;"})


def render_svg(res) -> str:
    layers = res["layers"]
    scope = res["scope"]
    by_layer = res["by_layer"]
    pair = res["layer_pair"]
    kinds_map = res["layer_pair_kinds"]
    fan = Counter(res["fan_in"]) + Counter(res["fan_out"])
    # cycle layers = layers in any multi-node layer SCC
    cycle_layers = set()
    for c in res["layer_cycles"]:
        cycle_layers.update(c)
    inv_pairs = set((ls, ld) for (_, ls, ld) in
                    [(a, b, c) for (a, b, c) in
                     ((t, layers[s], layers[d]) for t, s, d, _, _
                      in res["inversions"]) for ls in [b] for ld in [c]])

    col_w = 148
    gap = 10
    margin = 24
    total_w = margin * 2 + len(LAYER_ORDER) * col_w + (len(LAYER_ORDER) - 1) * gap
    header_y = 96
    header_h = 46
    list_y = header_y + header_h + 8
    chip_h = 20
    max_chips = 20
    # column heights (capped)
    col_files = {}
    for lyr in LAYER_ORDER:
        files = by_layer.get(lyr, [])
        # rank chips by total fan (in+out), then name
        ranked = sorted(files, key=lambda r: (-fan[r], r))
        col_files[lyr] = ranked[:max_chips]
    max_h = max((len(col_files[l]) for l in LAYER_ORDER), default=0)
    content_h = max_h * chip_h + 24
    total_h = list_y + content_h + 64
    mid_y = list_y + content_h // 2

    g = []
    g.append(f'<svg xmlns="http://www.w3.org/2000/svg" width="{total_w}" '
             f'height="{total_h}" viewBox="0 0 {total_w} {total_h}">')
    g.append('<defs>'
             '<marker id="arr" viewBox="0 0 10 10" refX="9" refY="5" '
             'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
             '<path d="M0,0 L10,5 L0,10 z" fill="#5b636e"/></marker>'
             '<marker id="arrR" viewBox="0 0 10 10" refX="9" refY="5" '
             'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
             '<path d="M0,0 L10,5 L0,10 z" fill="#c62828"/></marker>'
             '<marker id="arrO" viewBox="0 0 10 10" refX="9" refY="5" '
             'markerWidth="7" markerHeight="7" orient="auto-start-reverse">'
             '<path d="M0,0 L10,5 L0,10 z" fill="#e65100"/></marker>'
             '</defs>')
    g.append('<rect x="0" y="0" width="100%" height="100%" fill="#ffffff"/>')
    g.append(f'<text x="{margin}" y="30" font-family="monospace" font-size="16" '
             f'font-weight="bold">Automix (Part D2) dependency layers '
             f'-- {_esc(res["head"])}</text>')
    g.append(f'<text x="{margin}" y="50" font-family="monospace" '
             f'font-size="11" fill="#455a64">Columns left(low/stable) to '
             f'right(high/volatile). Arrows = aggregated file dependencies '
             f'between layers (label = file-pair count). Red dashed = in a '
             f'cycle. Orange = volatility inversion.</text>')
    g.append(f'<text x="{margin}" y="68" font-family="monospace" '
             f'font-size="11" fill="#455a64">Generated by '
             f'tools/untangle/deps.py (stdlib) .. files={len(scope)} .. '
             f'file-edges={len(res["edges"])} .. See deps_report.md for '
             f'full tables.</text>')
    # legend
    lx = margin
    ly = 82
    g.append(f'<line x1="{lx}" y1="{ly}" x2="{lx + 34}" y2="{ly}" '
             f'stroke="#5b636e" stroke-width="2" marker-end="url(#arr)"/>')
    g.append(f'<text x="{lx + 40}" y="{ly + 4}" font-family="monospace" '
             f'font-size="10" fill="#455a64">normal</text>')
    g.append(f'<line x1="{lx + 110}" y1="{ly}" x2="{lx + 144}" y2="{ly}" '
             f'stroke="#c62828" stroke-width="2" stroke-dasharray="5,3" '
             f'marker-end="url(#arrR)"/>')
    g.append(f'<text x="{lx + 150}" y="{ly + 4}" font-family="monospace" '
             f'font-size="10" fill="#455a64">cycle layer</text>')
    g.append(f'<line x1="{lx + 240}" y1="{ly}" x2="{lx + 274}" y2="{ly}" '
             f'stroke="#e65100" stroke-width="2" marker-end="url(#arrO)"/>')
    g.append(f'<text x="{lx + 280}" y="{ly + 4}" font-family="monospace" '
             f'font-size="10" fill="#455a64">volatility inversion</text>')

    xs = {}
    for i, lyr in enumerate(LAYER_ORDER):
        x = margin + i * (col_w + gap)
        xs[lyr] = x
        n = len(by_layer.get(lyr, []))
        stroke = "#c62828" if lyr in cycle_layers else "#5b636e"
        dash = ' stroke-dasharray="6,3"' if lyr in cycle_layers else ""
        g.append(f'<rect x="{x}" y="{header_y}" width="{col_w}" '
                 f'height="{header_h}" rx="6" fill="#eceff1" '
                 f'stroke="{stroke}" stroke-width="1.5"{dash}/>')
        g.append(f'<text x="{x + col_w // 2}" y="{header_y + 19}" '
                 f'font-family="monospace" font-size="11" font-weight="bold" '
                 f'text-anchor="middle">{_esc(lyr)}</text>')
        g.append(f'<text x="{x + col_w // 2}" y="{header_y + 36}" '
                 f'font-family="monospace" font-size="10" '
                 f'text-anchor="middle">{n} files</text>')
        # file chips
        yy = list_y
        for f in col_files[lyr]:
            base = f.rsplit("/", 1)[-1]
            g.append(f'<rect x="{x}" y="{yy}" width="{col_w}" height="{chip_h - 2}" '
                     f'rx="3" fill="#ffffff" stroke="#b0bec5" stroke-width="0.7"/>')
            g.append(f'<text x="{x + 4}" y="{yy + chip_h - 7}" '
                     f'font-family="monospace" font-size="8.5" '
                     f'fill="#263238">{_esc(base[:31])}</text>')
            yy += chip_h
        if n > max_chips:
            g.append(f'<text x="{x + 4}" y="{yy + 10}" font-family="monospace" '
                     f'font-size="9" fill="#78909c">+{n - max_chips} more .. '
                     f'see report</text>')

    # edges (aggregated, skip self-layer; self counts shown in report)
    agg = [(s, d, c) for (s, d), c in sorted(pair.items()) if s != d]
    # order lanes: group by (mid x) to stagger vertically
    for idx, (s, d, c) in enumerate(agg):
        sx = xs[s] + col_w
        dx = xs[d]
        # direction: rightwards normal anchor lines; leftwards (to lower
        # layer) route from left edge of src to right edge of dst
        if RANK[d] >= RANK[s]:
            x1, x2 = xs[s] + col_w, xs[d]
        else:
            x1, x2 = xs[s], xs[d] + col_w
        y1 = mid_y + ((idx % 7) - 3) * 5
        y2 = mid_y + ((idx % 5) - 2) * 5
        is_cycle = (s in cycle_layers and d in cycle_layers)
        is_inv = ((s, d) in inv_pairs)
        if is_cycle and is_inv:
            col, mark, dash = "#c62828", "url(#arrR)", ' stroke-dasharray="6,3"'
        elif is_cycle:
            col, mark, dash = "#c62828", "url(#arrR)", ' stroke-dasharray="6,3"'
        elif is_inv:
            col, mark, dash = "#e65100", "url(#arrO)", ""
        else:
            col, mark, dash = "#5b636e", "url(#arr)", ""
        op = "0.85" if c >= 5 else "0.55"
        w = min(4.0, 1.2 + (c ** 0.5) * 0.45)
        cx1 = x1 + (x2 - x1) * 0.35
        cx2 = x1 + (x2 - x1) * 0.65
        g.append(
            f'<path d="M{x1},{y1} C{cx1:.0f},{y1} {cx2:.0f},{y2} {x2},{y2}" '
            f'fill="none" stroke="{col}" stroke-width="{w:.1f}" '
            f'stroke-opacity="{op}"{dash} marker-end="{mark}"/>')
        mx, my = (x1 + x2) // 2, (y1 + y2) // 2 - 4
        g.append(f'<text x="{mx}" y="{my}" font-family="monospace" '
                 f'font-size="10" fill="{col}" text-anchor="middle">{c}</text>')
    g.append(f'<text x="{margin}" y="{total_h - 14}" font-family="monospace" '
             f'font-size="10" fill="#78909c">Chips = top files by total fan '
             f'(in+out). Up to {max_chips} shown per layer. Kinds: import / '
             f'same-package / fq-usage / include / jni / wildcard.</text>')
    g.append('</svg>')
    return "\n".join(g) + "\n"


# --------------------------------------------------------------------------
# Markdown report
# --------------------------------------------------------------------------

def render_report(res) -> str:
    scope, layers = res["scope"], res["layers"]
    by_layer = res["by_layer"]
    fan_in, fan_out = res["fan_in"], res["fan_out"]
    L = []
    L.append("# Automix (Part D2) dependency report")
    L.append("")
    L.append(f"Scope files: **{len(scope)}** (HEAD `{res['head']}`). "
             f"Tool: `tools/untangle/deps.py` (Python 3 stdlib; same scope "
             f"as `skeleton.py`). File-level edges: **{len(res['edges'])}** "
             f"(Kotlin imports resolved: {res['kt_import_edges']}).")
    L.append("")
    L.append("## 1. Layers and file inventory")
    L.append("")
    L.append("| Layer (low -> high) | Rank | Files |")
    L.append("|---|---|---|")
    for lyr in LAYER_ORDER:
        L.append(f"| `{lyr}` | {RANK[lyr]} | {len(by_layer.get(lyr, []))} |")
    L.append("")
    for lyr in LAYER_ORDER:
        files = by_layer.get(lyr, [])
        L.append(f"### `{lyr}` ({len(files)} files)")
        for f in files:
            L.append(f"- `{f}`")
        L.append("")
    L.append("## 2. Layer-to-layer edge table")
    L.append("")
    L.append("File-pair counts aggregated per layer pair. Self-edges "
             "(within-layer) are listed but not drawn as arrows in SVG.")
    L.append("")
    L.append("| From | To | File pairs | Kinds |")
    L.append("|---|---|---|---|")
    for (s, d), c in sorted(res["layer_pair"].items(),
                            key=lambda kv: (-kv[1], kv[0])):
        ks = ", ".join(sorted(res["layer_pair_kinds"].get((s, d), ())))
        L.append(f"| `{s}` | `{d}` | {c} | {ks} |")
    L.append("")
    L.append("## 3. File fan-in / fan-out (top 20 each)")
    L.append("")
    L.append("Fan = number of DISTINCT neighbor files (not edge multiplicity).")
    L.append("")
    L.append("### Fan-in (most depended-upon)")
    L.append("")
    L.append("| Rank | File | Layer | Fan-in |")
    L.append("|---|---|---|---|")
    for i, (f, v) in enumerate(sorted(fan_in.items(),
                                      key=lambda kv: (-kv[1], kv[0]))[:20], 1):
        L.append(f"| {i} | `{f}` | `{layers[f]}` | {v} |")
    L.append("")
    L.append("### Fan-out (depends on most)")
    L.append("")
    L.append("| Rank | File | Layer | Fan-out |")
    L.append("|---|---|---|---|")
    for i, (f, v) in enumerate(sorted(fan_out.items(),
                                      key=lambda kv: (-kv[1], kv[0]))[:20], 1):
        L.append(f"| {i} | `{f}` | `{layers[f]}` | {v} |")
    L.append("")
    L.append("## 4. Cycles (Tarjan SCCs)")
    L.append("")
    L.append("### Layer-level SCCs (size > 1)")
    L.append("")
    if res["layer_cycles"]:
        for c in res["layer_cycles"]:
            L.append(f"- **{len(c)} layers**: " +
                     ", ".join(f"`{x}`" for x in c))
    else:
        L.append("- None (layer graph is acyclic).")
    L.append("")
    L.append("### File-level SCCs (size > 1)")
    L.append("")
    L.append(f"Count: {len(res['file_cycles'])} SCCs; "
             f"self-loops: {len(res['self_loops'])} (none expected; "
             f"self-edges are dropped by construction).")
    L.append("")
    for c in sorted(res["file_cycles"], key=lambda x: (-len(x), x)):
        L.append(f"- **{len(c)} files** (all `{layers[c[0]]}` if uniform, "
                 f"else mixed):")
        for f in c:
            L.append(f"  - `{f}` (`{layers[f]}`)")
    L.append("")
    L.append("## 5. Volatility inversions")
    L.append("")
    L.append("HARD = `shared/smart|native/* -> app/*`. "
             "SOFT-settings = `engine -> app/settings_telemetry` "
             "(global-settings reads). "
             "SOFT-ui/service = `engine -> app/ui|app/service`.")
    L.append("")
    L.append(f"Counts: HARD={res['inv_counter'].get('HARD', 0)}, "
             f"SOFT-settings={res['inv_counter'].get('SOFT-settings', 0)}, "
             f"SOFT-ui/service={res['inv_counter'].get('SOFT-ui/service', 0)}.")
    L.append("")
    L.append("| Type | From layer | To layer | File pairs |")
    L.append("|---|---|---|---|")
    for (t, s, d), c in sorted(res["inv_layer_pairs"].items(),
                               key=lambda kv: (-kv[1], kv[0])):
        L.append(f"| {t} | `{s}` | `{d}` | {c} |")
    L.append("")
    L.append("### Inversion file edges")
    L.append("")
    for t, s, d, kinds, ev in res["inversions"]:
        L.append(f"- [{t}] `{s}` -> `{d}` kinds={','.join(kinds)} "
                 f"via={','.join(ev)}")
    L.append("")
    L.append("## 6. External dependencies")
    L.append("")
    L.append("### Kotlin external imports (by 3-segment root; in-house "
             "`com.music.bitchord.*` outside the Part D2 scope is listed "
             "first and is NOT third-party)")
    L.append("")
    L.append("| Root | Files importing | Sample full imports |")
    L.append("|---|---|---|")
    for root, cnt in res["ext_kt"].most_common(40):
        sample = ", ".join(sorted(res["ext_kt_detail"][root])[:4])
        L.append(f"| `{root}` | {cnt} | {sample} |")
    L.append("")
    L.append("### C++ angle includes (system)")
    L.append("")
    if res["ext_cpp"]:
        for inc, cnt in res["ext_cpp"].most_common(40):
            L.append(f"- `{inc}` x{cnt}")
    else:
        L.append("- None.")
    L.append("")
    L.append("### Unresolved quoted includes (not in Part D2 scope)")
    L.append("")
    if res["unresolved"]:
        for s, inc in sorted(set(res["unresolved"])):
            L.append(f"- `{s}` includes `{inc}` (not found in scope)")
    else:
        L.append("- None.")
    L.append("")
    L.append("## 7. Method and limitations")
    L.append("")
    L.append("- Kotlin imports resolved against an FQ symbol index built "
             "from top-level `class|interface|object|typealias|fun` "
             "declarations per package; longest-prefix match handles "
             "`Outer.Inner` imports. Aliases resolve via their FQ target.")
    L.append("- Same-package references scan comment/string-stripped code "
             "for simple names declared in the same package by OTHER "
             "files (one heuristic edge per file pair; a shared identifier "
             "could in principle be a local variable -- spot-check the "
             "evidence column). This is required because `app/`, `shared/ "
             "commonMain` and `shared/ jvmSharedMain` declare the SAME "
             "package `com.music.bitchord.playback.smart` in separate "
             "modules.")
    L.append("- C++ quoted includes resolve by path suffix, then unique "
             "basename; duplicates add edges to every candidate (flagged "
             "by basename ambiguity in manual review). JNI edges match "
             "`Java_<FQ>_ ` prefixes of top-level Kotlin classes; "
             "top-level `external fun` uses the `<File>Kt` facade. "
             "Inner-class JNI (`$`) is not covered.")
    L.append("- File-level SCCs therefore OVER-approximate true compile "
             "cycles: same-package name matches are heuristic. Layer-level "
             "cycles inherit that approximation. Treat cycles as coupling "
             "hotspots to inspect with `skeleton.txt`, not as build errors.")
    L.append("")
    L.append("## Appendix A. Full file-edge list")
    L.append("")
    L.append("| From | To | Kinds | Evidence (sample) |")
    L.append("|---|---|---|---|")
    for (s, d), kinds in sorted(res["edges"].items()):
        ev = ", ".join(sorted(res["evid"].get((s, d), ()))[:5])
        L.append(f"| `{s}` | `{d}` | {','.join(sorted(kinds))} | {ev} |")
    L.append("")
    return "\n".join(L)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Automix dependency graph")
    here = Path(__file__).resolve()
    default_repo = here.parent.parent.parent
    ap.add_argument("--repo", default=str(default_repo))
    ap.add_argument("--out-dir", default=None,
                    help="default: <repo>/docs/untangle")
    args = ap.parse_args(argv)
    repo = Path(args.repo).resolve()
    out_dir = Path(args.out_dir).resolve() if args.out_dir else \
        (repo / "docs" / "untangle")
    out_dir.mkdir(parents=True, exist_ok=True)
    res = analyze(repo)

    svg_path = out_dir / "deps.svg"
    md_path = out_dir / "deps_report.md"
    svg = render_svg(res)
    # self-check: must parse as XML
    ElementTree.fromstring(svg)
    svg_path.write_text(svg, encoding="utf-8")
    # re-parse from disk (proof of validity)
    ElementTree.parse(str(svg_path))
    md_path.write_text(render_report(res), encoding="utf-8")
    print(f"deps.py: {len(res['scope'])} files, "
          f"{len(res['edges'])} file-edges, "
          f"layer-cycles={len(res['layer_cycles'])}, "
          f"file-cycles={len(res['file_cycles'])}, "
          f"inversions HARD={res['inv_counter'].get('HARD', 0)} "
          f"SOFT={sum(v for k, v in res['inv_counter'].items() if k != 'HARD')}")
    print(f"deps.py: wrote {svg_path} and {md_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
