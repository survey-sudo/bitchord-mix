#!/usr/bin/env python3
"""CUE-only ops union from ORT 1.31 optimized Fixed/Runtime graphs, never raw graph scans."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys

import onnxruntime as ort


def merge_config(path, operators):
    for line in path.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        domain, version, names = line.split(";", 2)
        operators.setdefault((domain, int(version)), set()).update(name.strip() for name in names.split(","))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="Evidence directory (outside the repo)")
    parser.add_argument("--config", type=Path, required=True)
    args = parser.parse_args()
    if socket.gethostname() != "2887726280f4" or ort.__version__ != "1.31.0":
        parser.error("Run on CUE with the pinned ORT 1.31.0 venv; this does not build ORT")
    source_dir, optimized_dir = args.output / "model-inputs", args.output / "optimized"
    source_dir.mkdir(parents=True, exist_ok=True)
    optimized_dir.mkdir(parents=True, exist_ok=True)
    models = ("beat_this_final0_q8.onnx", "nirovi_qnn.onnx", "vocals_umxhq_int8.onnx")
    manifest = {"ort_version": ort.__version__, "host": socket.gethostname(), "models": {}}
    for name in models:
        source = args.assets / name
        shutil.copyfile(source, source_dir / name)
        manifest["models"][name] = {"bytes": source.stat().st_size, "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}
    command = [sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", str(source_dir),
               "--output_dir", str(optimized_dir), "--optimization_style", "Fixed", "Runtime",
               "--target_platform", "arm", "--save_optimized_onnx_model"]
    manifest["command"] = command
    # No allow_conversion_failures: a missing model's kernels must never be silently omitted.
    subprocess.run(command, check=True)
    configs = sorted(optimized_dir.glob("*.config"))
    assert len(configs) >= 2, "Both optimized Fixed and Runtime operator inventories are required"
    operators = {}
    # Keep vc83's CPU compatibility inventory; add new kernels ONLY from optimized
    # exports plus explicit quant/fusion guards. Do not regenerate from raw ONNX.
    merge_config(args.config, operators)
    for config in configs:
        merge_config(config, operators)
    operators.setdefault(("ai.onnx", 17), set()).update(
        ("MatMulInteger", "DynamicQuantizeLinear", "DequantizeLinear", "QuantizeLinear"))
    operators.setdefault(("com.microsoft", 1), set()).update(
        ("DynamicQuantizeLSTM", "DynamicQuantizeMatMul", "MatMulIntegerToFloat"))
    lines = ["# ORT 1.31.0 CUE: union of optimized Fixed + Runtime ARM-target graphs for both beat models and CPU vocals.",
             "# Regenerate with npu-addon/regenerate-ops.py; retain vc83 CPU compatibility + QDQ/dynamic-quant/fusion guards."]
    lines += [f"{domain};{version};{','.join(sorted(names))}" for (domain, version), names in sorted(operators.items())]
    args.config.write_text("\n".join(lines) + "\n")
    manifest["optimized_configs"] = [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()} for path in configs]
    manifest["optimized_graphs"] = [{"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
                                     for path in sorted(optimized_dir.glob("*.onnx"))]
    manifest["union_sha256"] = hashlib.sha256(args.config.read_bytes()).hexdigest()
    (args.output / "ops-union-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(args.config.read_text())


if __name__ == "__main__":
    main()
