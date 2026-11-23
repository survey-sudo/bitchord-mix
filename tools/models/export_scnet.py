#!/usr/bin/env python3
"""Export SCNet (starrytong/SCNet, MIT) to a static-shape ONNX graph with the STFT and iSTFT
OUTSIDE the graph, matching native/analyzer/stem_dsp.cpp:

    input  mix_spec   float32 [1, 4, 2049, 476]  CaC planes L.re, L.im, R.re, R.im
                                                 (rect window, x 1/sqrt(4096), centred reflect pad,
                                                  11 s = 485100 samples + 1300 zeros, normalized audio)
    output stems_spec float32 [1, 16, 2049, 476] plane (source*2 + channel)*2 + part,
                                                 sources drums, bass, other, vocals

The network's rFFT/irFFT over time (separation.FeatureConversion) is rewritten as matmuls with
constant DFT matrices, so the graph has no complex or DFT ops (CPU, QNN and WebGPU friendly).

Prerequisites (pin these; record them in MODELS.md when you export):
  * ZFTurbo/Music-Source-Separation-Training checkout (models/scnet at
    84b1eac0887756b4f1a9d7a1ff49105939749ed2 was verified upstream by audiojs/scnet)
  * scnet_checkpoint_musdb18.ckpt  (MSST release v.1.0.6,
    sha256 1bc0d1abb20bfdf966dcd07637bafd03e4bc13653d09ef18bc9b3e342eafe2aa)
  * configs/config_musdb18_scnet.yaml from the same checkout
  * pip install -r tools/models/requirements.txt

    python tools/models/export_scnet.py --msst /path/to/Music-Source-Separation-Training \
        --ckpt scnet_checkpoint_musdb18.ckpt --out build/stems
    python tools/models/quantize.py --model build/stems/scnet_fp32.onnx \
        --out build/stems/scnet_dyn_int8.onnx --layout scnet

Exit code 0 only when every verification gate passed.
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import stft_reference as ref  # noqa: E402

SOURCES = ["drums", "bass", "other", "vocals"]


def snr_db(reference: np.ndarray, estimate: np.ndarray) -> float:
    noise = np.sum((reference - estimate) ** 2)
    signal = np.sum(reference ** 2)
    return float("inf") if noise == 0 else 10.0 * np.log10(signal / max(noise, 1e-30))


def load_model(msst: str, ckpt: str, config: str):
    import torch
    import yaml

    sys.path.insert(0, msst)
    from models.scnet.scnet import SCNet  # type: ignore

    with open(config) as f:
        cfg = yaml.load(f, Loader=yaml.FullLoader)
    model_cfg = dict(cfg["model"])
    assert list(model_cfg.get("sources", SOURCES)) == SOURCES, model_cfg.get("sources")
    model = SCNet(**model_cfg)
    state = torch.load(ckpt, map_location="cpu")
    if isinstance(state, dict) and "state_dict" in state:
        state = state["state_dict"]
    model.load_state_dict(state)
    model.eval()
    return model, cfg


def patch_feature_conversion():
    """Replace torch.fft.rfft/irfft (norm='ortho', dim=3) by exact DFT matmuls."""
    import torch
    from models.scnet import separation  # type: ignore

    cls = separation.FeatureConversion
    original = cls.forward
    cache: dict = {}

    def mats(n: int):
        if n not in cache:
            k = np.arange(n // 2 + 1)
            t = np.arange(n)
            angle = 2.0 * np.pi * np.outer(t, k) / n  # [n, K]
            scale = 1.0 / np.sqrt(n)
            fwd_c = np.cos(angle) * scale
            fwd_s = np.sin(angle) * scale
            a = np.full(n // 2 + 1, 2.0)
            a[0] = 1.0
            a[-1] = 1.0
            inv_c = (np.cos(angle) * scale * a[None, :]).T  # [K, n]
            inv_s = (np.sin(angle) * scale * a[None, :]).T
            cache[n] = [torch.tensor(m, dtype=torch.float32) for m in (fwd_c, fwd_s, inv_c, inv_s)]
        return cache[n]

    def forward(self, x):
        x = x.float()
        if not self.inverse:
            n = x.shape[3]
            c, s, _, _ = mats(n)
            real = torch.matmul(x, c)
            imag = -torch.matmul(x, s)
            return torch.cat([real, imag], dim=1)
        half = self.channels // 2
        xr = x[:, :half]
        xi = x[:, half:]
        n = 2 * (x.shape[3] - 1)
        _, _, ic, is_ = mats(n)
        return torch.matmul(xr, ic) - torch.matmul(xi, is_)

    cls.forward = forward
    return original


class Core:
    """SCNet.forward between its STFT and its iSTFT (encoder -> separation -> decoder)."""

    @staticmethod
    def build(model, permutation):
        import torch

        class ScnetCore(torch.nn.Module):
            def __init__(self, m):
                super().__init__()
                self.m = m

            def forward(self, x):
                m = self.m
                b, _, fr, t = x.shape
                skips, lengths, originals = [], [], []
                for sd_layer in m.encoder:
                    x, skip, length, original = sd_layer(x)
                    skips.append(skip)
                    lengths.append(length)
                    originals.append(original)
                x = m.separation_net(x)
                for fusion_layer, su_layer in m.decoder:
                    x = fusion_layer(x, skips.pop())
                    x = su_layer(x, lengths.pop(), originals.pop())
                if permutation is not None:
                    # internal axes (a0, a1, a2) of the 16 channels -> (source, channel, part)
                    dims, perm = permutation
                    x = x.reshape(b, *dims, fr, t).permute(0, *[p + 1 for p in perm], 4, 5)
                    x = x.reshape(b, 16, fr, t)
                return x

        return ScnetCore(model)


def candidate_orderings():
    """Ways the 16 output channels could factor into (source=4, channel=2, part=2)."""
    axes = {"s": 4, "c": 2, "p": 2}
    out = []
    for order in itertools.permutations("scp"):
        dims = tuple(axes[a] for a in order)
        perm = tuple(order.index(a) for a in "scp")
        out.append(("".join(order), (dims, perm)))
    return out


def torch_pipeline(core, audio):
    """SCNet's own padding + torch.stft -> core -> torch.istft, for an [2, L] float32 clip."""
    import torch
    import torch.nn.functional as F

    hop = ref.HOP
    length = audio.shape[-1]
    pad = hop - length % hop
    if (length + pad) // hop % 2 == 0:
        pad += hop
    x = F.pad(audio, (0, pad))
    spec = torch.stft(x, ref.N_FFT, hop, ref.N_FFT, center=True, normalized=True, return_complex=True)
    cac = torch.view_as_real(spec).permute(0, 3, 1, 2).reshape(1, 4, spec.shape[-2], spec.shape[-1])
    with torch.no_grad():
        y = core(cac)
    fr, t = y.shape[-2], y.shape[-1]
    y = y.reshape(4, 2, 2, fr, t)
    z = torch.complex(y[:, :, 0], y[:, :, 1]).reshape(8, fr, t)
    out = torch.istft(z, ref.N_FFT, hop, ref.N_FFT, center=True, normalized=True)
    return out.reshape(4, 2, -1)[..., :length], cac


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--msst", required=True, help="Music-Source-Separation-Training checkout")
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--config", default=None, help="default: <msst>/configs/config_musdb18_scnet.yaml")
    ap.add_argument("--out", required=True, help="output directory")
    ap.add_argument("--opset", type=int, default=17)
    args = ap.parse_args()

    import torch

    config = args.config or os.path.join(args.msst, "configs", "config_musdb18_scnet.yaml")
    model, cfg = load_model(args.msst, args.ckpt, config)
    audio_cfg = cfg.get("audio", {})
    assert int(audio_cfg.get("chunk_size", ref.SCNET_SEGMENT)) == ref.SCNET_SEGMENT, audio_cfg
    mcfg = cfg["model"]
    assert mcfg["nfft"] == ref.N_FFT and mcfg["hop_size"] == ref.HOP and mcfg["normalized"], mcfg

    rng = np.random.default_rng(1234)
    t = np.arange(ref.SCNET_SEGMENT) / ref.SAMPLE_RATE
    clip = np.stack([
        0.3 * np.sin(2 * np.pi * 110 * t) + 0.1 * rng.standard_normal(t.size),
        0.3 * np.sin(2 * np.pi * 165 * t + 0.4) + 0.1 * rng.standard_normal(t.size),
    ]).astype(np.float32)
    left, right, _, _ = ref.normalize(clip[0].astype(np.float64), clip[1].astype(np.float64))
    audio = torch.from_numpy(np.stack([left, right]).astype(np.float32))

    with torch.no_grad():
        reference = model(audio[None])[0].numpy()  # [4 sources, 2, L], unpatched

    # 1. Find how the decoder's 16 channels factor, using the *unpatched* network.
    best = None
    for name, ordering in candidate_orderings():
        core = Core.build(model, ordering)
        est, _ = torch_pipeline(core, audio)
        score = min(snr_db(reference[s], est[s].numpy()) for s in range(4))
        print(f"ordering {name}: worst-source SNR vs SCNet.forward {score:.1f} dB")
        if best is None or score > best[0]:
            best = (score, name, ordering)
    if best[0] < 60.0:
        print("FAIL: no channel ordering reproduces SCNet.forward; the forward pass differs from "
              "the encoder/separation/decoder structure this exporter assumes.")
        return 1
    _, order_name, ordering = best
    print(f"decoder channel order = {order_name} (exported as source, channel, part)")

    # 2. Swap the time-axis rFFT for DFT matmuls and prove nothing moved.
    patch_feature_conversion()
    core = Core.build(model, ordering).eval()
    est, cac = torch_pipeline(core, audio)
    patched = min(snr_db(reference[s], est[s].numpy()) for s in range(4))
    print(f"DFT-matmul FeatureConversion: worst-source SNR {patched:.1f} dB")
    if patched < 60.0:
        print("FAIL: patched FeatureConversion differs from torch.fft (check models/scnet/separation.py)")
        return 1

    # 3. Our numpy front end must be the tensor torch builds (and the phone builds).
    ours = ref.scnet_input(left, right, ref.SCNET_SEGMENT)
    front = np.abs(ours - cac.numpy()).max() / max(np.abs(cac.numpy()).max(), 1e-12)
    print(f"front end vs torch.stft: max relative diff {front:.2e}")
    if front > 1e-5:
        print("FAIL: reference STFT does not match SCNet's torch.stft")
        return 1

    # 4. Export, static shapes.
    os.makedirs(args.out, exist_ok=True)
    out_path = os.path.join(args.out, "scnet_fp32.onnx")
    dummy = torch.from_numpy(ours)
    kwargs = dict(input_names=["mix_spec"], output_names=["stems_spec"], opset_version=args.opset,
                  do_constant_folding=True)
    try:
        torch.onnx.export(core, (dummy,), out_path, dynamo=False, **kwargs)
    except TypeError:  # torch < 2.5 has no `dynamo` argument
        torch.onnx.export(core, (dummy,), out_path, **kwargs)

    # 5. ONNX Runtime agrees with torch on the same tensor.
    import onnxruntime as ort

    session = ort.InferenceSession(out_path, providers=["CPUExecutionProvider"])
    got = session.run(None, {"mix_spec": ours})[0]
    with torch.no_grad():
        want = core(dummy).numpy()
    parity = snr_db(want, got)
    print(f"onnxruntime vs torch: SNR {parity:.1f} dB, shape {got.shape}")
    ops = sorted({node.op_type for node in __import__("onnx").load(out_path).graph.node})
    print("ops:", ", ".join(ops))
    if got.shape != (1, 16, ref.BINS, ref.SCNET_FRAMES) or parity < 80.0:
        print("FAIL: exported graph does not reproduce the network")
        return 1
    digest = hashlib.sha256(open(out_path, "rb").read()).hexdigest()
    print(f"OK {out_path} {os.path.getsize(out_path)} bytes sha256 {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
