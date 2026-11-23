# tools/models - stem separation model tooling (Stream B)

Scripts that turn upstream checkpoints into the ONNX graphs BitChord runs, with the STFT/iSTFT
**outside** the graph, static shapes, and numerically verified against the original PyTorch.
Nothing here runs in the app build. See `MODELS.md` for every file, hash and licence.

```
pip install -r tools/models/requirements.txt
python tools/models/stft_reference.py            # front-end self-test (vs torch.stft if installed)

# SCNet (primary)
python tools/models/export_scnet.py --msst <Music-Source-Separation-Training> \
    --ckpt scnet_checkpoint_musdb18.ckpt --out build/stems           # -> scnet_fp32.onnx
python tools/models/quantize.py --model build/stems/scnet_fp32.onnx \
    --out build/stems/scnet_dyn_int8.onnx --layout scnet             # CPU dynamic int8 + SNR gate

# Open-Unmix UMX-HQ (fallback; vocals already ships)
for t in drums bass other; do
  python tools/models/export_umx.py --target $t --out build/stems
  python tools/models/quantize.py --model build/stems/${t}_umxhq_fp32.onnx \
      --out build/stems/${t}_umxhq_int8.onnx --layout umx
done
```

Every script exits non-zero when a gate fails (channel ordering / DFT patch / front end /
ORT parity / int8 SNR >= 30 dB). Pin the printed SHA-256 and size into `StemModels` and
`MODELS.md` only from a passing run.

## Graph contracts (what native/analyzer/stem_dsp.cpp feeds and reads)

| Layout | Input | Output | STFT | Chunk |
|---|---|---|---|---|
| SCNet `SCNET_CAC` | `mix_spec` f32 `[1,4,2049,476]` planes L.re, L.im, R.re, R.im | `stems_spec` f32 `[1,16,2049,476]` plane `(source*2+ch)*2+part`, sources drums, bass, other, vocals | n_fft 4096, hop 1024, **rectangular** window, x 1/sqrt(4096), centred reflect pad | 485100 samples (11 s @ 44.1 kHz) + 1300 zeros = 486400; audio normalized by region mono mean/std (MSST `inference.normalize: true`) |
| UMX `UMX_MAGNITUDE` | `mix_magnitude` f32 `[1,2,2049,960]` | `target_magnitude` f32 `[1,2,2049,960]` (one graph per target) | n_fft 4096, hop 1024, periodic Hann, unnormalized, centred reflect pad | 982016 samples (22.3 s), not normalized |

Plane layout is bin-major (`plane, bin, frame`), i.e. the row-major tensor. Chunks overlap by
>= `AutomixV2Stems.minChunkOverlap` (12 %) with raised-cosine crossfades; OTHER is
`dry - vocals - drums - bass`, so stems always sum to the input.

## Hand-off to Stream A (`tools/npu`, `*_qdq.onnx`)

* Source graph: `scnet_fp32.onnx` (or `<target>_umxhq_fp32.onnx`). Static shapes above; no
  STFT, no complex, no DFT ops (SCNet's time-axis rFFT is exported as constant matmuls).
* Calibration: `python tools/models/make_calibration.py --audio <music dir> --layout scnet
  --out build/calib/scnet` writes `calib_XXXX.npy` tensors exactly as the phone builds them,
  plus `manifest.json` (input name, shape). 64-128 chunks across genres / loudness.
* Recommended: A16W8 (16-bit activations). audiojs measured that int8 on SCNet's last
  decoder layers alone moves the output 27-39 dB under its power, all-int8 weights 23 dB.
* Blocker to solve first: SCNet's dual-path blocks and Open-Unmix are **LSTMs**, which the HTP
  backend does not run; the graph would partition back to CPU. Either keep stems on CPU
  (default: `AutomixV2Stems.allowNpu = false`) or unroll the LSTMs for the fixed T in the export.
* Deliver as `scnet_qdq.onnx`; Stream B adds a `StemModelSpec` (cacheTag `scnet1q`, same
  layout/frames) ahead of `SCNET_INT8` once it is pinned and measured on the 8s Gen 3.
