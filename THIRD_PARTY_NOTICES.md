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
