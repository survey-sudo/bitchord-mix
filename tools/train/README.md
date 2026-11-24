# Track N — Kite & Lark training pipeline

Clean training scaffold for the two on-device students used by BitChord's
`bitchord-unified/tools/train/` pipeline. Copy this whole directory into that
location in Step 7.

**License compliance (§4.5 / §8.2).** Every `.py` file here is 100% original
Python written from published methods (dilated TCNs, knowledge distillation,
fake-quant QAT) and the public ONNX / PyTorch quantization specifications.
Nothing is copied, translated, or derived from any Orchard PolyForm Shield
source. Teacher models are accessed only through user-supplied adapters; no
teacher code or weights are vendored. **MUSDB18-HQ and every research-only
corpus are explicitly refused in code**, not merely discouraged.

## Architecture

Common front end (byte-for-byte the recipe in BitChord's `mel.py` / native
analyzer): 22 050 Hz mono, `n_fft=1024`, `hop=441` → 50 fps, 128 Slaney
triangles 30–11 000 Hz, `log1p(1000·max(E,1e-10))`, `float32`. Every model
consumes a fixed **`[1, 1500, 128]`** window = 30 s.

| Student | Task | Model | Output | Quantization |
|---|---|---|---|---|
| **Kite** | beat + downbeat | causal-free (centred-pad) dilated TCN, width 384, 12 residual blocks, dilations `1…128` repeated, two `1×1` heads | `[1,1500,2]` logits | 8-bit QAT → static QDQ ONNX `kite_qnn.onnx`, **uint16 at input & stem, uint8 interior** |
| **Lark** | vocal presence | compact dilated conv/TCN, width 96, 6 blocks (~0.28 M params) | `[1,1500]` vocal energy share | 8-bit QAT → static QDQ ONNX `lark_qnn.onnx`, all uint8 |

Both graphs are fully convolutional: no LSTM/GRU, no control flow (`Loop`,
`If`, `Scan`), no dynamic dimensions — checked by `export_kite_qdq.py`
(`validate_static`).

### Distillation

- Kite: `0.6 · KD(beat/downbeat sigmoid, T=2) + 0.4 · BCE(annotation labels)`.
  The teacher is **Beat This! final0** (MIT); its per-window probabilities are
  cached by `data_prep_kite.py` through a `module.path:callable` adapter or a
  directory of precomputed `.npz` logits.
- Lark: `0.8 · MSE + 0.2 · BCE` against frame-level UMX-HQ (MIT) vocal-share
  targets computed on 50 fps energy windows with a 5-frame smoother.

## Datasets & license compliance

| Source | License | Allowed use here | Enforcement |
|---|---|---|---|
| Beat This! spectrograms, [Zenodo 13922116](https://zenodo.org/records/13922116) | **CC BY 4.0** | Kite training mel inputs | downloaded via public Zenodo API; recorded in `manifest.json` |
| [CPJKU/beat_this_annotations](https://huggingface.co/datasets/CPJKU/beat_this_annotations) | **MIT** | Kite beat/downbeat labels | HF loader or local JSON |
| GTZAN | research corpus, per-track rights vary | **evaluation only** | `split_tracks()` routes every GTZAN id to holdout; `is_gtzan()` guard blocks it from training |
| Beat This! final0 | **MIT** | Kite teacher (logits only) | adapter hook; nothing vendored |
| [FMA-full](https://freemusicarchive.org/) metadata + audio | **CC-BY / CC0 subset only** | Lark training | `filter_fma_tracks()` strict allowlist; NC/ND/SA/research terms rejected |
| UMX-HQ | **MIT** | Lark vocal-share teacher | adapter hook; nothing vendored |
| **MUSDB18-HQ** | **research-only / non-commercial** | **REFUSED** | `assert_not_refused()` raises `PermissionError`; listed in `REFUSED_DATASETS` |

Lark's license filter is deliberately allowlist-based: only normalized license
strings in `ALLOWED_LICENSES` (`CC BY`, `CC BY 4.0`, `CC0`, …) survive. Any
string containing `NC`, `ND`, `SA`, `RESEARCH` or `MUSDB` is dropped before a
single waveform is read.

## Running it (Colab or rented GPU)

```bash
pip install -r requirements.txt

# ---- Kite ------------------------------------------------------------------
# (a) data: CC BY 4.0 spectrograms + MIT labels, GTZAN excluded from training
python data_prep_kite.py --out data/kite \
  --teacher my_adapter:beat_this_final0          # or --teacher-npz-dir DATA/

# (b) QAT fine-tuning (~384 channels, distill + BCE)
python train_kite.py --data-dir data/kite --out-dir ckpt/kite --epochs 20

# (c) static QDQ export + validation -> kite_qnn.onnx
python export_kite_qdq.py --checkpoint ckpt/kite/kite_qat_best.pt --out kite_qnn.onnx
python export_kite_qdq.py --check-only kite_qnn.onnx

# (d) GTZAN holdout gate (70 ms F1 vs final0)
python eval_kite_gtzan.py --eval-cache data/kite_gtzan_eval.npz \
  --student kite_qnn.onnx --teacher-onnx final0.onnx \
  --htp-compile-seconds 42 --out kite_eval_report.json

# ---- Lark ------------------------------------------------------------------
python data_prep_lark.py --out data/lark \
  --tracks-csv /path/to/fma_metadata/tracks.csv --fma-root /path/to/fma_full \
  --teacher my_adapter:umx_hq_vocal               # or --stems-dir DATA/
python train_lark.py --data-dir data/lark --out-dir ckpt/lark --out lark_qnn.onnx
python train_lark.py --checkpoint ckpt/lark/lark_qat_best.pt --out lark_qnn.onnx
```

On Colab, prepend `!` to each line and enable a GPU runtime. No network is
needed at training time once the cached NPZs exist. The `--self-test` flag on
each Python file runs a dependency-light smoke test (NumPy only for the two
data-prep files; a forward/backward + QAT pass for the two trainers).

### Teacher adapter contract

```python
# my_adapter.py — install Beat This! (MIT) or UMX-HQ (MIT) separately.
def beat_this_final0(mel_window):        # [1500,128] float32
    ...                                  # returns (beat[1500], downbeat[1500])
def umx_hq_vocal(audio_22050_mono):      # float32 waveform
    ...                                  # returns vocal stem, same length
```

## Acceptance gates

| Gate | Threshold | Checked by |
|---|---|---|
| Kite beat **and** downbeat F1 delta vs Beat This! final0, GTZAN holdout, 70 ms tolerance | `abs(student − teacher) ≤ 0.01` | `eval_kite_gtzan.py` (`mir_eval.beat.f_measure`) |
| Kite HTP (Qualcomm QNN) compile time | `≤ 60 s` | measured with `qnn-onnx-converter` / `qnn-model-lib-generator`; pass `--htp-compile-seconds` |
| Kite inference, 30 s window | `≤ 100 ms` | `eval_kite_gtzan.py` ORT timing |
| Kite ONNX static / QDQ shape | `[1,1500,128]`, uint16 input+stem, uint8 interior, zero LSTM/dynamic ops | `export_kite_qdq.py --check-only` |
| Lark parameter count | `< 1 000 000` | asserted in `train_lark.py` |
| Lark data provenance | FMA-full **CC-BY / CC0 only**; MUSDB18-HQ refused | `data_prep_lark.py` filter + manifest |

`eval_kite_gtzan.py` exits non-zero on any failed gate and prints a JSON
report with per-clip F1 rows.

## File map

- `data_prep_kite.py` — Zenodo 13922116 + MIT annotations, GTZAN holdout split, teacher-logit caching, mel windows.
- `train_kite.py` — 384-channel causal-free dilated TCN, KD+BCE loss, QAT.
- `export_kite_qdq.py` — static `[1,1500,128]` ONNX, uint16 input/stem QDQ rewrite, uint8 interior, static-graph validation.
- `eval_kite_gtzan.py` — 70 ms beat/downbeat F1 vs final0 + latency gate.
- `data_prep_lark.py` — strict FMA license filter, UMX-HQ vocal-share targets, mel windows.
- `train_lark.py` — `<1M` param conv/TCN, QAT, `lark_qnn.onnx` static QDQ export.
- `requirements.txt` — pinned lower bounds for reproducibility.
