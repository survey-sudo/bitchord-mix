# Third-party notices

## BKmasterplayer

Portions of Automix harmonic matching and beat-grid steadiness
(`shared/src/commonMain/kotlin/com/music/bitchord/playback/smart/HarmonicMix.kt`)
are adapted from [BKmasterplayer](https://github.com/thieggs/bkmasterplayer)
(`app/rust/src/engine/analysis.rs`), which is licensed under the MIT License.

Copyright (C) 2026 thieggs and BKmasterplayer contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Signalsmith Stretch

The loop mix and the beat-matched blend's pitch correction use
[Signalsmith Stretch](https://github.com/Signalsmith-Audio/signalsmith-stretch)
and signalsmith-linear, vendored under `native/third_party`. Both are MIT
licensed. Copyright (c) Signalsmith Audio Ltd. The same permission notice as
above applies. The loop mix time-stretches with it. A beat-matched blend
keeps the player's playback rate for the timeline and uses a second
Signalsmith instance only to cancel the pitch that rate would add.

## open-unmix umxhq

The vocal mask is `vocals_umxhq_int8.onnx`, the MIT-licensed umxhq weights
from [open-unmix](https://github.com/sigsep/open-unmix-pytorch). The
non-commercial umxl weights are not shipped. madmom models and the
proprietary AI_DJ sources are not shipped either.

The section labels, the Schroeder plate and the loop roll are original to
this fork. They are not copied from those projects.

## Qualcomm AI (QAIRT) HTP runtime — not shipped

No Qualcomm runtime, NPU model or vendor binary is part of this release. The
`libQnnHtp*` libraries, the Hexagon skels and the QDQ beat models that earlier
fork versions carried are gone from the build, and `npu-addon/` is off every
`jniLibs` source set. Re-introducing any of them requires a separate delivery
under Qualcomm's own licence terms; see `npu-addon/README.md`.

## Automix v2 models (downloaded at first use, not packaged)

The three ONNX weights are no longer bundled in the APK. The model
coordinator verifies each file against a pinned SHA-256 before it is used, and
fetches what is missing from the fork's pinned GitHub release
(`models-v1`), so nothing is downloaded unless the user asks for it.

SCNet: Sparse Compression Network for Music Source Separation, W. Tong et al., ICASSP 2024.
Code and pretrained weights (c) 2024 starrytong, MIT License. ONNX export by audiojs.
`scnet.int8.onnx`, 12,900,277 B, SHA-256 98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845.

Beat This!: beat and downbeat tracking model, MIT License.
`beat_this_final0_q8.onnx`, 23,138,816 B, SHA-256 7801f9c1adfd5ff193f37ececa3320bf1fda1ddee2bf7de341cea4c25ffd0acd.

Open-Unmix (UMX-HQ), F.-R. Stöter, S. Uhlich, A. Liutkus, Y. Mitsufuji, JOSS 2019,
(c) Inria / Sony, MIT License.
`vocals_umxhq_int8.onnx`, 9,054,206 B, SHA-256 a2be987b55a29bc149d3a6ae99b08175d81f85ee292a8ea21f96c3a473bc94cb.

All three were trained on MUSDB18-HQ, which its authors license for research and educational use;
whether that reaches trained weights is unsettled and is recorded here for legal review.

## ONNX Runtime (CPU execution provider)

`com.microsoft.onnxruntime:onnxruntime-android:1.31.0`, MIT License, is the only
inference runtime in the APK. The QNN execution provider, the `onnxruntime-android-qnn`
artifact and the `com.qualcomm.qti:qnn-runtime` artifact are not dependencies of this
release.
