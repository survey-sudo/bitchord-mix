#!/usr/bin/env python3
"""OPTIONAL: precompile the QDQ model to an HTP v73 EP-context model offline.

Needs the onnxruntime-qnn wheel (Windows x64/arm64) whose bundled QAIRT matches the app's
pinned com.qualcomm.qti:qnn-runtime (2.42.0 for onnxruntime-android-qnn 1.29.0): a context
built with another QAIRT version fails to load on the phone (then the app recompiles the QDQ
on device, or stays on CPU). Graph-I/O quantization is kept inside QNN
(offload_graph_io_quantization=0) so the strict self-test can also verify the ctx model.

  pip install onnxruntime-qnn==1.29.0
  python make_context.py --qdq ../../app/src/main/assets/beat_this_final0_qdq.onnx \
      --out ../../app/src/main/assets/beat_this_final0_qdq_ctx.onnx
"""
from __future__ import annotations

import argparse
import os

import onnxruntime as ort


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--qdq", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--backend", default="QnnHtp.dll")
    args = ap.parse_args()
    if os.path.exists(args.out):
        os.remove(args.out)  # ORT refuses to overwrite an EP-context file
    so = ort.SessionOptions()
    so.add_session_config_entry("ep.context_enable", "1")
    so.add_session_config_entry("ep.context_embed_mode", "1")
    so.add_session_config_entry("ep.context_file_path", args.out)
    so.add_session_config_entry("session.disable_cpu_ep_fallback", "1")
    options = {
        "backend_path": args.backend,
        "htp_arch": "73",
        "soc_model": "68",
        "htp_graph_finalization_optimization_mode": "3",
        "enable_htp_fp16_precision": "1",
        "offload_graph_io_quantization": "0",
    }
    ort.InferenceSession(args.qdq, so, providers=["QNNExecutionProvider"], provider_options=[options])
    print(f"EP context: {args.out} ({os.path.getsize(args.out)/1e6:.1f} MB)")


if __name__ == "__main__":
    main()
