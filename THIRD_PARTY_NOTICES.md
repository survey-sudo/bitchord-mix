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

## Qualcomm AI (QAIRT) HTP runtime

The Snapdragon NPU stack is the QAIRT 2.51.0.260929 HTP runtime, bundled
in the APK under lib/arm64-v8a (libQnnHtp*, plus one Skel per Hexagon
generation), built against the ONNX Runtime 1.31.0 source build in
npu-addon/. Qualcomm AI Runtime is the property of Qualcomm
Technologies, Inc. and/or its subsidiaries and is governed by the
license in app/src/main/assets/licenses/Qualcomm-AI-Runtime-LICENSE.pdf
and the notices in Qualcomm-AI-Runtime-QNN-NOTICE.txt. All rights
reserved; no rights are granted except as stated in that license.

## Automix v2 models (bundled / downloaded)

SCNet: Sparse Compression Network for Music Source Separation, W. Tong et al., ICASSP 2024.
Code and pretrained weights (c) 2024 starrytong, MIT License. ONNX export by audiojs.
Bundled as `assets/scnet.int8.onnx` (SHA-256 98228931494151762a1c4ab1ec7899a894b1f81fd4a509921a9d2f9bacc50845).

Open-Unmix (UMX-HQ), F.-R. Stöter, S. Uhlich, A. Liutkus, Y. Mitsufuji, JOSS 2019,
(c) Inria / Sony, MIT License.

Both models were trained on MUSDB18-HQ, which its authors license for research and educational use;
whether that reaches trained weights is unsettled and is recorded here for legal review.

## Automix v2 NPU runtime

ONNX Runtime with the QNN execution provider (`com.microsoft.onnxruntime:onnxruntime-android-qnn`), MIT License.
Qualcomm AI Engine Direct / QAIRT runtime libraries (`com.qualcomm.qti:qnn-runtime`: libQnnHtp*, libQnnSystem),
proprietary, (c) Qualcomm Technologies, Inc., redistributed under Qualcomm's licence terms shipped with that
artifact. Review those terms before any public release.
