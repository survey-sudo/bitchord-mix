#!/usr/bin/env python3
"""Track N / Lark -- small vocal-presence student with QAT + QDQ export.

100% original PyTorch written from published TCN methods and the official
``torch.ao.quantization`` documentation.  Training audio is restricted by
``data_prep_lark.py`` to FMA-full CC-BY / CC0 tracks only; MUSDB18-HQ and all
other research-only corpora are explicitly refused.

Input:  float32 [B,1500,128] log-mel (30 s @ 50 fps, BitChord front end).
Output: float32 [B,1500] vocal energy share in [0,1] (UMX-HQ, MIT, teacher).
Model:  < 1M parameters.  Fully convolutional -- static ONNX, no recurrence.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Optional, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.ao.quantization import DeQuantStub, QuantStub, get_default_qat_qconfig

WINDOW_FRAMES = 1500
MELS = 128
PARAM_LIMIT = 1_000_000


@dataclass
class LarkConfig:
    in_ch: int = MELS
    width: int = 96
    n_blocks: int = 6
    kernel: int = 3
    stem_kernel: int = 5
    dilations: Tuple[int, ...] = (1, 2, 4, 8, 16, 32)

    def __post_init__(self) -> None:
        if len(self.dilations) != self.n_blocks:
            raise ValueError("dilations length must equal n_blocks")


class LightDilatedBlock(nn.Module):
    def __init__(self, width: int, dilation: int, kernel: int = 3) -> None:
        super().__init__()
        pad = ((kernel - 1) // 2) * dilation
        self.dilated = nn.Conv1d(
            width, width, kernel, padding=pad, dilation=dilation, bias=False
        )
        self.relu1 = nn.ReLU()
        self.point = nn.Conv1d(width, width, 1, bias=False)
        self.relu2 = nn.ReLU()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.relu2(x + self.point(self.relu1(self.dilated(x))))


class LarkTCN(nn.Module):
    def __init__(self, cfg: Optional[LarkConfig] = None) -> None:
        super().__init__()
        self.cfg = cfg or LarkConfig()
        self.quant = QuantStub()
        self.dequant = DeQuantStub()
        self.stem = nn.Sequential(
            nn.Conv1d(self.cfg.in_ch, self.cfg.width, self.cfg.stem_kernel,
                      padding=self.cfg.stem_kernel // 2, bias=False),
            nn.ReLU(),
        )
        self.blocks = nn.ModuleList(
            LightDilatedBlock(self.cfg.width, d, self.cfg.kernel)
            for d in self.cfg.dilations
        )
        self.head = nn.Conv1d(self.cfg.width, 1, 1)

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        x = self.quant(mel.transpose(1, 2))
        x = self.stem(x)
        for block in self.blocks:
            x = block(x)
        return self.dequant(self.head(x).transpose(1, 2).squeeze(-1))


def count_params(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())


def build_qat_model(cfg: Optional[LarkConfig] = None) -> LarkTCN:
    torch.backends.quantized.engine = "x86"
    model = LarkTCN(cfg)
    model.qconfig = get_default_qat_qconfig("x86")
    from torch.ao.quantization import prepare_qat

    prepare_qat(model, inplace=True)
    return model


def lark_loss(logits: torch.Tensor, target: torch.Tensor,
              mse_weight: float = 0.8) -> torch.Tensor:
    p = torch.sigmoid(logits)
    t = target.clamp(1e-4, 1.0 - 1e-4)
    mse = F.mse_loss(p, t)
    bce = F.binary_cross_entropy(p.clamp(1e-4, 1.0 - 1e-4), t)
    return mse_weight * mse + (1.0 - mse_weight) * bce


class LarkDataset(torch.utils.data.Dataset):
    def __init__(self, data_dir: Path) -> None:
        import numpy as np

        z = np.load(str(Path(data_dir) / "windows.npz"))
        self.windows = z["windows"]
        self.targets = z["targets"]
        if len(self.windows) != len(self.targets):
            raise ValueError("windows / targets lengths differ")

    def __len__(self) -> int:
        return int(self.windows.shape[0])

    def __getitem__(self, i: int):
        return torch.from_numpy(self.windows[i]), torch.from_numpy(self.targets[i])


def train(data_dir: Path, out_dir: Path, epochs: int, batch_size: int,
          lr: float, seed: int) -> Path:
    torch.manual_seed(seed)
    dataset = LarkDataset(data_dir)
    if len(dataset) < 2:
        raise RuntimeError("need at least 2 windows to train")
    val_n = max(1, int(0.1 * len(dataset)))
    train_n = len(dataset) - val_n
    train_set, val_set = torch.utils.data.random_split(
        dataset, [train_n, val_n], generator=torch.Generator().manual_seed(seed)
    )
    loader = torch.utils.data.DataLoader(train_set, batch_size=batch_size, shuffle=True)
    val_loader = torch.utils.data.DataLoader(val_set, batch_size=batch_size)

    cfg = LarkConfig()
    model = build_qat_model(cfg)
    n_params = count_params(model)
    if n_params >= PARAM_LIMIT:
        raise RuntimeError(f"Lark has {n_params:,} params; limit is {PARAM_LIMIT:,}")
    print(f"[lark] params={n_params:,} (< {PARAM_LIMIT:,})")
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)

    out_dir.mkdir(parents=True, exist_ok=True)
    best, best_path = math.inf, out_dir / "lark_qat_best.pt"
    history = []
    for epoch in range(1, epochs + 1):
        model.train()
        running = 0.0
        for mel, target in loader:
            optimizer.zero_grad(set_to_none=True)
            loss = lark_loss(model(mel), target)
            loss.backward()
            optimizer.step()
            running += float(loss.detach()) * mel.shape[0]
        scheduler.step()
        model.eval()
        vloss = 0.0
        with torch.no_grad():
            for mel, target in val_loader:
                vloss += float(lark_loss(model(mel), target)) * mel.shape[0]
        vloss /= max(1, len(val_set))
        history.append({"epoch": epoch, "train_loss": running / max(1, train_n),
                        "val_loss": vloss})
        print(f"[lark] epoch {epoch:03d}/{epochs} train={running / max(1, train_n):.4f} "
              f"val={vloss:.4f}")
        if vloss < best:
            best = vloss
            torch.save(
                {"state_dict": model.state_dict(), "config": asdict(cfg),
                 "framework": "torch.ao.quantization QAT", "epoch": epoch,
                 "val_loss": vloss},
                best_path,
            )
    (out_dir / "train_history.json").write_text(json.dumps(history, indent=2) + "\n")
    print(f"[lark] best checkpoint: {best_path} (val={best:.4f})")
    return best_path


# ---------------------------------------------------------------------------
# Static QDQ export (all uint8; fixed [1,1500,128] input)
# ---------------------------------------------------------------------------
def export_lark(checkpoint: Path, out: Path) -> dict:
    from export_kite_qdq import convert_and_export, validate_static

    torch.backends.quantized.engine = "x86"
    payload = torch.load(str(checkpoint), map_location="cpu", weights_only=False)
    cfg = LarkConfig(**payload.get("config", {}))
    model = build_qat_model(cfg)
    model.load_state_dict(payload["state_dict"], strict=True)
    export_path = convert_and_export(model, out)
    report = validate_static(export_path, expect_uint16=False)
    print(json.dumps(report, indent=2))
    return report


def self_test() -> int:
    torch.manual_seed(0)
    cfg = LarkConfig()
    model = LarkTCN(cfg)
    n = count_params(model)
    assert n < PARAM_LIMIT, f"{n} params exceeds limit"
    x = torch.randn(2, WINDOW_FRAMES, MELS)
    logits = model(x)
    loss = lark_loss(logits, torch.rand(2, WINDOW_FRAMES))
    loss.backward()
    qat = build_qat_model(cfg)
    qout = qat(torch.randn(1, WINDOW_FRAMES, MELS))
    print(f"[lark self-test] params={n:,} out={tuple(logits.shape)} "
          f"loss={float(loss):.4f} qat_out={tuple(qout.shape)}")
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data-dir", type=Path)
    ap.add_argument("--out-dir", type=Path, default=Path("lark_ckpt"))
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--checkpoint", type=Path, help="export-only: QAT .pt")
    ap.add_argument("--out", type=Path, default=Path("lark_qnn.onnx"))
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if args.checkpoint is not None:
        export_lark(args.checkpoint, args.out)
        return 0
    if args.data_dir is None:
        ap.error("--data-dir or --checkpoint is required unless --self-test is given")
    ckpt = train(args.data_dir, args.out_dir, args.epochs, args.batch_size, args.lr, args.seed)
    export_lark(ckpt, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
