# Stem models: catalogue, licences, delivery

Every weight file BitChord may load for stem separation, where it comes from, and the licence
it is used under. `StemModels` in
`shared/src/jvmSharedMain/kotlin/com/music/bitchord/playback/smart/StemModelStore.kt` is the
machine-readable copy of this table: a file is only ever opened after its SHA-256 matches the
pin there. An empty pin means "not released yet" and is refused unless
`AutomixV2Stems.allowUnpinnedModels` (debug only).

## Files

| File | Network | Bytes | SHA-256 | Where from | Licence | Role |
|---|---|---|---|---|---|---|
| `scnet.int8.onnx` | SCNet (10.1 M params), MUSDB18-HQ checkpoint; int8 weights (5 decoder layers fp16), computes fp32 | 12,900,277 | `98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845` | https://huggingface.co/audiojs/scnet/resolve/main/scnet.int8.onnx (mirror it, see below) | MIT. Code (c) 2024 starrytong; the weights' author confirmed MIT for the released checkpoints and converted/ONNX redistributions (starrytong/SCNet#35). Checkpoint as distributed by ZFTurbo/Music-Source-Separation-Training v1.0.6 (MIT); ONNX export/compaction by audiojs | **Primary** (`StemModels.SCNET_INT8`) |
| `scnet_fp32.onnx` | same, our export (`export_scnet.py`) | ~42.8 M | pin on export | build output | MIT (as above) | Reference; input to QDQ (Stream A) |
| `scnet_dyn_int8.onnx` | same, ORT dynamic int8 (`quantize.py`) | ~13 M | pin after the SNR gate | build output, mirror | MIT (as above) | Faster-CPU candidate (`SCNET_DYN_INT8`) |
| `vocals_umxhq_int8.onnx` | Open-Unmix UMX-HQ vocals, `[1,2,2049,960]` magnitude in/out, dynamic int8 | 9,054,206 | `a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb` | already bundled in the APK (`app/src/main/assets/`) | MIT (sigsep/open-unmix-pytorch, UMX-HQ weights, Zenodo 3370489) | Legacy 2-stem path; vocals of the UMX-4 fallback |
| `drums_umxhq_int8.onnx`, `bass_umxhq_int8.onnx`, `other_umxhq_int8.onnx` | UMX-HQ targets (`export_umx.py` + `quantize.py --layout umx`) | ~9 M each | pin on export | build output, mirror | MIT (as above) | 4-stem fallback (`UMXHQ4_INT8`) |

Attribution text for `THIRD_PARTY_NOTICES.md` (orchestrator owns that file):

> SCNet: Sparse Compression Network for Music Source Separation, W. Tong et al., ICASSP 2024.
> Code and pretrained weights (c) 2024 starrytong, MIT License. ONNX export by audiojs.
> Open-Unmix (UMX-HQ), F.-R. Stöter, S. Uhlich, A. Liutkus, Y. Mitsufuji, JOSS 2019,
> (c) Inria / Sony, MIT License.

## Rejected on licence or fit

| Candidate | Why not |
|---|---|
| Open-Unmix UMX-L | Weights are CC BY-NC-SA 4.0 (non-commercial); `export_umx.py` refuses `--model umxl`. |
| HT-Demucs / htdemucs_ft | Code is MIT, but redistributors disagree on the weights (some mirrors label them CC-BY-NC 4.0, others MIT); ~42 M params, time+spectral hybrid with a transformer: the STFT cannot leave the graph without leaving the time branch inside it. Not shippable until Meta's licence for the weights is confirmed in writing. |
| BS-RoFormer / Mel-RoFormer community checkpoints | Mixed or unstated weight licences, tens to hundreds of M params, long attention (needs static shapes + 16-bit activations on HTP). |
| Moises-Light (2025) | Very efficient band-split U-Net, but no public weights found. |

## Training-data caveat (record, do not ignore)

Both SCNet and UMX-HQ were trained on MUSDB18-HQ, which its authors license for research and
educational use. No project has settled whether that restriction reaches weights trained on it
(audiojs states this explicitly on the SCNet card). The weights' own licences (MIT) are
GPL-3.0-compatible; the dataset question is a legal-review item, the same one that already
applies to the UMX-HQ vocals model the app ships today.

## Delivery decision: on-demand download, SHA-256 verified

The APK is already ~67 MB and only users who enable stem automix on a capable device (the
existing `canUseWindowedStems` gate: >= 6 GB RAM, not low-RAM) need 13 MB more. So:

* Files live in `filesDir/stems/` and are fetched by `StemModelStore.install(spec)` through an
  orchestrator-provided `StemModelDownloader` (the app's HTTP stack, Wi-Fi / opt-in policy),
  written to `<name>.part`, hashed, then renamed. A file failing its hash is deleted.
* Host the files on BitChord's own release assets (e.g. `stems-v1/scnet.int8.onnx` on the
  GitHub release of the version that introduces them) and change `url` in `StemModels`; the
  Hugging Face URL is the upstream origin and fine for development.
* A build that prefers bundling can drop a file into `app/src/main/assets/stems/` and set
  `bundledAsset = "stems/<name>"` for it; `StemModelStore` honours bundled assets first.
