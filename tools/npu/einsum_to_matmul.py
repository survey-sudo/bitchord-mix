"""Rewrite two-operand batched-matmul Einsum nodes into Transpose + MatMul (+ Transpose).

QNN HTP has no Einsum op. Handles equations like "bhid,bhjd->bhij" or "bij,bjk->bik":
exactly one contracted letter, one free letter per operand, shared batch letters.
Anything else is reported and left alone (the strict QNN check will then flag it).
"""
from __future__ import annotations

import onnx
from onnx import helper


def _perm(src: str, dst: str):
    return [src.index(c) for c in dst]


def rewrite(model: onnx.ModelProto) -> tuple[onnx.ModelProto, int, list[str]]:
    graph = model.graph
    out_nodes, rewritten, skipped = [], 0, []
    for node in graph.node:
        if node.op_type != "Einsum" or len(node.input) != 2:
            if node.op_type == "Einsum":
                skipped.append(node.name)
            out_nodes.append(node)
            continue
        eq = next(a.s.decode() for a in node.attribute if a.name == "equation").replace(" ", "")
        if "->" not in eq or "." in eq:
            skipped.append(f"{node.name}:{eq}")
            out_nodes.append(node)
            continue
        lhs, out = eq.split("->")
        a, b = lhs.split(",")
        k = [c for c in a if c in b and c not in out]
        batch = [c for c in out if c in a and c in b]
        i = [c for c in a if c not in b and c in out]
        j = [c for c in b if c not in a and c in out]
        ok = (len(k) == 1 and len(i) == 1 and len(j) == 1 and
              sorted(a) == sorted(batch + i + k) and sorted(b) == sorted(batch + k + j) and
              sorted(out) == sorted(batch + i + j))
        if not ok:
            skipped.append(f"{node.name}:{eq}")
            out_nodes.append(node)
            continue
        bt = "".join(batch)
        a_dst, b_dst, mm = bt + i[0] + k[0], bt + k[0] + j[0], bt + i[0] + j[0]
        name = node.name or f"einsum_{rewritten}"
        ina, inb = node.input[0], node.input[1]
        if a != a_dst:
            out_nodes.append(helper.make_node("Transpose", [ina], [name + "_ta"], name=name + "_ta", perm=_perm(a, a_dst)))
            ina = name + "_ta"
        if b != b_dst:
            out_nodes.append(helper.make_node("Transpose", [inb], [name + "_tb"], name=name + "_tb", perm=_perm(b, b_dst)))
            inb = name + "_tb"
        if mm == out:
            out_nodes.append(helper.make_node("MatMul", [ina, inb], [node.output[0]], name=name + "_mm"))
        else:
            out_nodes.append(helper.make_node("MatMul", [ina, inb], [name + "_mm"], name=name + "_mm"))
            out_nodes.append(helper.make_node("Transpose", [name + "_mm"], [node.output[0]], name=name + "_to", perm=_perm(mm, out)))
        rewritten += 1
    del graph.node[:]
    graph.node.extend(out_nodes)
    return model, rewritten, skipped
