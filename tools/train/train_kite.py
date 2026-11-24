#!/usr/bin/env python3
"""Track N / Kite -- beat & downbeat student: dilated TCN, distillation, QAT.

100% original PyTorch written from published TCN / knowledge-distillation
methods and official ``torch.ao.quantization`` documentation.  No code from a
PolyForm-Shield-licensed project is used.

Input:  float32 [B, 1500, 128] log-mel (30 s @ 50 fps, matching BitChord).
Output: float32 [B, 1500, 2] beat / downbeat logits before sigmoid.

Architecture: causal-free (centred padding) dilated temporal conv net, width
384, residual separable-style blocks; two 1x1 heads.  Fully convolutional --
no recurrence, no dynamic control flow -- so the exported graph is static.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Dict, Optional, Sequence, Tuple

import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.ao.quantization import DeQuantStub, QuantStub, get_default_qat_qconfig

WINDOW_FRAMES = 1500
MELS = 128


@dataclass
class KiteConfig:
    in_ch: int = MELS
    width: int = 384
    n_blocks: int = 12
    kernel: int = 3
    stem_kernel: int = 5
    dilations: Tuple[int, ...] = (1, 2, 4, 8, 16, 32, 64, 128, 1, 2, 4, 8)

    def __post_init__(self) -> None:
        if len(self.dilations) != self.n_blocks:
            raise ValueError("dilations length must equal n_blocks")
        if self.width < 256:
            raise ValueError("Kite width should be ~384 channels")


# ---------------------------------------------------------------------------
# Model
# ---------------------------------------------------------------------------
class CausalFreeDilatedBlock(nn.Module):
    """Residual dilated Conv1d block with symmetric ('causal-free') padding."""

    def __init__(self, width: int, dilation: int, kernel: int = 3) -> None:
        super().__init__()
        pad = ((kernel - 1) // 2) * dilation
        self.dilated = nn.Conv1d(
            width, width, kernel, padding=pad, dilation=dilation, bias=False
        )
        self.relu1 = nn.ReLU()
        self.mix = nn.Conv1d(width, width, 1, bias=False)
        self.relu2 = nn.ReLU()

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        y = self.relu1(self.dilated(x))
        y = self.mix(y)
        return self.relu2(x + y)


class KiteTCN(nn.Module):
    """Fully-convolutional beat/downbeat student with QAT stubs."""

    def __init__(self, cfg: Optional[KiteConfig] = None) -> None:
        super().__init__()
        self.cfg = cfg or KiteConfig()
        self.quant = QuantStub()
        self.dequant = DeQuantStub()
        self.stem = nn.Sequential(
            nn.Conv1d(self.cfg.in_ch, self.cfg.width, self.cfg.stem_kernel,
                      padding=self.cfg.stem_kernel // 2, bias=False),
            nn.ReLU(),
        )
        self.blocks = nn.ModuleList(
            CausalFreeDilatedBlock(self.cfg.width, d, self.cfg.kernel)
            for d in self.cfg.dilations
        )
        self.head = nn.Conv1d(self.cfg.width, 2, 1)

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        # [B,1500,128] -> [B,128,1500]
        x = self.quant(mel.transpose(1, 2))
        x = self.stem(x)
        for block in self.blocks:
            x = block(x)
        out = self.head(x).transpose(1, 2)  # [B,1500,2]
        return self.dequant(out)


def count_params(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())


def build_qat_model(cfg: Optional[KiteConfig] = None) -> KiteTCN:
    """Create a model with observers/fake-quant ready for QAT fine-tuning."""
    model = KiteTCN(cfg)
    model.qconfig = get_default_qat_qconfig("x86")
    from torch.ao.quantization import prepare_qat

    prepare_qat(model, inplace=True)
    return model


# ---------------------------------------------------------------------------
# Loss: soft distillation from Beat This! final0 + hard annotation BCE
# ---------------------------------------------------------------------------
def distillation_loss(
    student_logits: torch.Tensor,
    teacher_prob: torch.Tensor,
    hard_labels: torch.Tensor,
    alpha: float = 0.6,
    temperature: float = 2.0,
) -> torch.Tensor:
    """Binary KD (sigmoid + temperature) blended with annotation BCE."""
    teacher_prob = teacher_prob.clamp(1e-4, 1.0 - 1e-4)
    teacher_logits = torch.logit(teacher_prob)
    s = torch.sigmoid(student_logits / temperature)
    t = torch.sigmoid(teacher_logits / temperature)
    kd = F.binary_cross_entropy(s, t) * (temperature * temperature)
    hard = F.binary_cross_entropy_with_logits(student_logits, hard_labels)
    return alpha * kd + (1.0 - alpha) * hard


# ---------------------------------------------------------------------------
# Data
# ---------------------------------------------------------------------------
class KiteDataset(torch.utils.data.Dataset):
    def __init__(self, data_dir: Path) -> None:
        d = np_load(str(Path(data_dir) / "windows.npz"))
        t = np_load(str(Path(data_dir) / "teacher_logits.npz"))
        self.windows = d["windows"]
        self.labels = d["labels"]
        self.teacher = t["teacher_logits"]
        if not (len(self.windows) == len(self.labels) == len(self.teacher)):
            raise ValueError("windows / labels / teacher_logits lengths differ")

    def __len__(self) -> int:
        return int(self.windows.shape[0])

    def __getitem__(self, i: int):
        return (
            torch.from_numpy(self.windows[i]),
            torch.from_numpy(self.labels[i]),
            torch.from_numpy(self.teacher[i]),
        )


def np_load(path: str):  # tiny indirection keeps import light in --self-test
    import numpy as np

    return np.load(path)


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------
def train(
    data_dir: Path,
    out_dir: Path,
    epochs: int,
    batch_size: int,
    lr: float,
    alpha: float,
    temperature: float,
    seed: int,
) -> Path:
    torch.manual_seed(seed)
    dataset = KiteDataset(data_dir)
    if len(dataset) < 2:
        raise RuntimeError("need at least 2 windows to train")
    val_n = max(1, int(0.1 * len(dataset)))
    train_n = len(dataset) - val_n
    train_set, val_set = torch.utils.data.random_split(
        dataset, [train_n, val_n], generator=torch.Generator().manual_seed(seed)
    )
    loader = torch.utils.data.DataLoader(train_set, batch_size=batch_size, shuffle=True)
    val_loader = torch.utils.data.DataLoader(val_set, batch_size=batch_size)

    cfg = KiteConfig()
    model = build_qat_model(cfg)
    optimizer = torch.optim.AdamW(model.parameters(), lr=lr, weight_decay=1e-4)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs)
    history = []

    out_dir.mkdir(parents=True, exist_ok=True)
    best = math.inf
    best_path = out_dir / "kite_qat_best.pt"
    for epoch in range(1, epochs + 1):
        model.train()
        running = 0.0
        for mel, labels, teacher in loader:
            optimizer.zero_grad(set_to_none=True)
            logits = model(mel)
            loss = distillation_loss(logits, teacher, labels, alpha, temperature)
            loss.backward()
            optimizer.step()
            running += float(loss.detach()) * mel.shape[0]
        scheduler.step()

        model.eval()
        vloss = 0.0
        with torch.no_grad():
            for mel, labels, teacher in val_loader:
                logits = model(mel)
                vloss += float(distillation_loss(logits, teacher, labels, alpha, temperature)) * mel.shape[0]
        vloss /= max(1, len(val_set))
        history.append({"epoch": epoch, "train_loss": running / max(1, train_n),
                        "val_loss": vloss})
        print(f"[kite] epoch {epoch:03d}/{epochs} train={running / max(1, train_n):.4f} "
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
    print(f"[kite] best checkpoint: {best_path} (val={best:.4f})")
    return best_path


def self_test() -> int:
    torch.manual_seed(0)
    cfg = KiteConfig()
    float_model = KiteTCN(cfg)
    x = torch.randn(2, WINDOW_FRAMES, MELS)
    logits = float_model(x)
    loss = distillation_loss(
        logits,
        torch.rand(2, WINDOW_FRAMES, 2),
        torch.rand(2, WINDOW_FRAMES, 2),
    )
    loss.backward()
    qat_model = build_qat_model(cfg)
    ql = qat_model(torch.randn(1, WINDOW_FRAMES, MELS))
    print(f"[kite self-test] params={count_params(float_model):,} "
          f"out={tuple(logits.shape)} loss={float(loss):.4f} qat_out={tuple(ql.shape)}")
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--data-dir", type=Path, help="output of data_prep_kite.py")
    ap.add_argument("--out-dir", type=Path, default=Path("kite_ckpt"))
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--alpha", type=float, default=0.6, help="KD weight")
    ap.add_argument("--temperature", type=float, default=2.0)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    if args.data_dir is None:
        ap.error("--data-dir is required unless --self-test is given")
    train(args.data_dir, args.out_dir, args.epochs, args.batch_size,
          args.lr, args.alpha, args.temperature, args.seed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
