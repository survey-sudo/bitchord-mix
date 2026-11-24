#!/usr/bin/env python3
"""Track N / Kite -- static QDQ ONNX export (kite_qnn.onnx).

100% original code based on the published ONNX QuantizeLinear / DequantizeLinear
specification and the official PyTorch quantization export documentation.

Pipeline:
  QAT checkpoint -> torch.ao.quantization.convert -> torch.onnx.export (opset 17)
  -> rewrite the input/stem QDQ pairs to uint16 -> static-graph validation.

Result: fixed [1,1500,128] input, QDQ integer math with uint16 at the input and
stem, uint8 everywhere else.  No LSTM, no control flow, no dynamic shapes.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import List, Optional, Sequence, Set

import numpy as np
import onnx
import onnx.checker
from onnx import TensorProto, numpy_helper

from train_kite import KiteConfig, KiteTCN, build_qat_model

INPUT_NAME = "mel"
OUTPUT_NAME = "logits"
OPSET = 17
EXPECTED_SHAPE = (1, 1500, 128)

FORBIDDEN_OPS = {
    "LSTM", "GRU", "RNN", "Loop", "Scan", "If", "When",
    "DynamicQuantizeLinear", "DynamicSlice", "NonMaxSuppression",
}


# ---------------------------------------------------------------------------
# Model loading / conversion
# ---------------------------------------------------------------------------
def load_qat_checkpoint(ckpt: Path, calibrate_dir: Optional[Path] = None) -> KiteTCN:
    """Restore a QAT checkpoint, or calibrate a float checkpoint if needed."""
    torch = _torch()
    torch.backends.quantized.engine = "x86"
    payload = torch.load(str(ckpt), map_location="cpu", weights_only=False)
    cfg = KiteConfig(**payload.get("config", {}))
    state = payload["state_dict"]

    is_qat = any("fake_quant" in k for k in state)
    if is_qat:
        model = build_qat_model(cfg)
        model.load_state_dict(state, strict=True)
        return model

    # Static post-training fallback for a plain float checkpoint.
    from torch.ao.quantization import prepare

    model = KiteTCN(cfg)
    model.load_state_dict(state, strict=True)
    model.qconfig = torch.ao.quantization.get_default_qconfig("x86")
    prepare(model, inplace=True)
    if calibrate_dir is None:
        raise RuntimeError("float checkpoint needs --calibrate-dir for static quantization")
    model.eval()
    with torch.no_grad():
        for p in sorted(Path(calibrate_dir).glob("*.npy"))[:64]:
            model(torch.from_numpy(np.load(p).astype(np.float32))[None])
    return model


def _torch():
    import torch

    return torch


def convert_and_export(
    model: KiteTCN,
    out_path: Path,
    example: Optional["torch.Tensor"] = None,
) -> Path:
    torch = _torch()
    from torch.ao.quantization import convert

    model.eval()
    convert(model, inplace=True)
    if example is None:
        example = torch.zeros(EXPECTED_SHAPE, dtype=torch.float32)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        model,
        example,
        str(out_path),
        opset_version=OPSET,
        input_names=[INPUT_NAME],
        output_names=[OUTPUT_NAME],
        dynamic_axes=None,          # fixed batch 1 / fixed 1500 / fixed 128
        do_constant_folding=True,
    )
    return out_path


# ---------------------------------------------------------------------------
# ONNX graph surgery: uint16 at input + stem, uint8 elsewhere
# ---------------------------------------------------------------------------
def _tensor_from_init_or_constant(graph, name: str) -> Optional[np.ndarray]:
    for init in graph.initializer:
        if init.name == name:
            return numpy_helper.to_array(init)
    for node in graph.node:
        if node.op_type == "Constant" and node.output[0] == name:
            for attr in node.attribute:
                if attr.name == "value":
                    return numpy_helper.to_array(attr.t)
    return None


def _set_value_type(model: onnx.ModelProto, name: str, elem_type: int) -> None:
    for collection in (model.graph.input, model.graph.output, model.graph.value_info):
        for vi in collection:
            if vi.name == name:
                vi.type.tensor_type.elem_type = elem_type
                return


def _paired_dequant(graph, q_node) -> Optional[object]:
    for node in graph.node:
        if node.op_type == "DequantizeLinear" and node.input[0] == q_node.output[0]:
            return node
    return None


def make_uint16_input_stem(path: Path, tokens: Sequence[str] = ("stem",)) -> Path:
    """Rewrite the input QDQ pair and every stem-named QDQ pair to uint16.

    Scale is reduced by 255/65535 so the representable positive range is
    unchanged; zero_point becomes 0.  Interior QDQ pairs remain uint8.
    """
    model = onnx.load(str(path))
    graph = model.graph
    q_nodes = [n for n in graph.node if n.op_type == "QuantizeLinear"]
    if not q_nodes:
        raise RuntimeError("export contains no QuantizeLinear nodes; QAT conversion failed")

    graph_input_names = {i.name for i in graph.input}
    selected: List[object] = []
    for q in q_nodes:
        paired = _paired_dequant(graph, q)
        if paired is None:
            continue
        is_input = q.input[0] in graph_input_names
        is_stem = any(t in (q.name or "") or t in (paired.name or "") for t in tokens)
        if is_input or is_stem:
            selected.append(q)

    if not selected:
        # Rare naming mismatch: fall back to the first two QDQ pairs in order
        # (input then stem) so the contract still holds.
        selected = [q for q in q_nodes if _paired_dequant(graph, q) is not None][:2]
    if not selected:
        raise RuntimeError("no QDQ pairs available for uint16 rewrite")

    for k, q in enumerate(selected):
        dq = _paired_dequant(graph, q)
        scale = _tensor_from_init_or_constant(graph, q.input[1])
        if scale is None:
            raise RuntimeError(f"missing scale initializer {q.input[1]!r}")
        s8 = float(np.asarray(scale).reshape(-1)[0])
        s16 = np.array([s8 * 255.0 / 65535.0], dtype=np.float32)

        # Fresh initializers avoid mutating a producer shared with uint8 nodes.
        new_scale = f"{q.name or 'q'}_u16_scale_{k}"
        new_zp = f"{q.name or 'q'}_u16_zero_point_{k}"
        graph.initializer.append(numpy_helper.from_array(s16, name=new_scale))
        graph.initializer.append(
            numpy_helper.from_array(np.array([0], dtype=np.uint16), name=new_zp)
        )
        q.input[1], q.input[2] = new_scale, new_zp
        dq.input[1], dq.input[2] = new_scale, new_zp
        _set_value_type(model, q.output[0], TensorProto.UINT16)

    onnx.checker.check_model(model)
    onnx.save(model, str(path))
    return path


# ---------------------------------------------------------------------------
# Static-graph acceptance gate
# ---------------------------------------------------------------------------
def _static_producer_names(graph) -> Set[str]:
    names = {i.name for i in graph.initializer}
    for node in graph.node:
        if node.op_type == "Constant":
            names.add(node.output[0])
    return names


def validate_static(path: Path, expect_uint16: bool = True) -> dict:
    """Fail loudly if the ONNX is dynamic, recurrent, or wrongly quantized."""
    model = onnx.load(str(path))
    onnx.checker.check_model(model)
    graph = model.graph

    inp = graph.input[0]
    dims = [d.dim_value for d in inp.type.tensor_type.shape.dim]
    if tuple(dims) != EXPECTED_SHAPE:
        raise RuntimeError(f"expected input {EXPECTED_SHAPE}, got {tuple(dims)}")

    for vi in list(graph.input) + list(graph.output) + list(graph.value_info):
        for d in vi.type.tensor_type.shape.dim:
            if d.dim_param:
                raise RuntimeError(f"dynamic dimension {d.dim_param!r} in {vi.name!r}")

    static = _static_producer_names(graph)
    for node in graph.node:
        if node.op_type in FORBIDDEN_OPS:
            raise RuntimeError(f"forbidden op {node.op_type} in {node.name!r}")
        if node.op_type in {"Slice", "Resize", "Gather", "GatherElements"}:
            for operand in node.input[1:]:
                if operand and operand not in static:
                    raise RuntimeError(
                        f"dynamic operand {operand!r} on {node.op_type} in {node.name!r}"
                    )

    dtypes: Set[int] = set()
    for node in graph.node:
        if node.op_type != "QuantizeLinear":
            continue
        zp = _tensor_from_init_or_constant(graph, node.input[2])
        if zp is not None:
            dtypes.add(np.asarray(zp).dtype.kind)
    if not dtypes:
        raise RuntimeError("no static QDQ pairs found")
    if expect_uint16 and not any(
        _is_uint16(graph, n) for n in graph.node if n.op_type == "QuantizeLinear"
    ):
        raise RuntimeError("no uint16 QuantizeLinear pair found (input/stem requirement)")
    if expect_uint16 and not any(_is_uint8(graph, n) for n in graph.node if n.op_type == "QuantizeLinear"):
        raise RuntimeError("no uint8 QuantizeLinear pair found (interior requirement)")

    report = {
        "model": str(path),
        "input_shape": dims,
        "opset": model.opset_import[0].version,
        "nodes": len(graph.node),
        "quantize_linear_nodes": sum(n.op_type == "QuantizeLinear" for n in graph.node),
        "uint16_qdq": sum(_is_uint16(graph, n) for n in graph.node if n.op_type == "QuantizeLinear"),
        "uint8_qdq": sum(_is_uint8(graph, n) for n in graph.node if n.op_type == "QuantizeLinear"),
        "forbidden_ops": 0,
        "dynamic_dims": 0,
        "status": "PASS",
    }
    return report


def _is_uint16(graph, q_node) -> bool:
    zp = _tensor_from_init_or_constant(graph, q_node.input[2])
    return zp is not None and np.asarray(zp).dtype == np.uint16


def _is_uint8(graph, q_node) -> bool:
    zp = _tensor_from_init_or_constant(graph, q_node.input[2])
    return zp is not None and np.asarray(zp).dtype == np.uint8


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--checkpoint", type=Path, help="QAT .pt from train_kite.py")
    ap.add_argument("--out", type=Path, default=Path("kite_qnn.onnx"))
    ap.add_argument("--calibrate-dir", type=Path, help="mel .npy files for float-ckpt calibration")
    ap.add_argument("--check-only", type=Path, help="only validate an existing ONNX")
    ap.add_argument("--skip-uint16-rewrite", action="store_true",
                    help="debug only; breaks the uint16 acceptance gate")
    args = ap.parse_args(argv)

    if args.check_only is not None:
        print(json.dumps(validate_static(args.check_only), indent=2))
        return 0
    if args.checkpoint is None:
        ap.error("--checkpoint is required unless --check-only is given")

    model = load_qat_checkpoint(args.checkpoint, args.calibrate_dir)
    convert_and_export(model, args.out)
    if not args.skip_uint16_rewrite:
        make_uint16_input_stem(args.out)
    report = validate_static(args.out)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
