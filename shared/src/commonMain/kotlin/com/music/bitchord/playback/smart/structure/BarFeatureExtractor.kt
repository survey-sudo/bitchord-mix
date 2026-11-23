package com.music.bitchord.playback.smart.structure

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AUTOMIX-V2(D): PCM to per-bar [V2Bar] features, pure Kotlin (commonMain).
 *
 * Works at the rate it is given (the phone passes TrackFeatures' low analysis
 * rate, ~11 kHz); above 24 kHz it box-decimates first. Frame = ~93 ms Hann,
 * hop = half a frame. Per frame: power, 30-150 Hz power, 2.5-8 kHz power,
 * 24 log-mel bands, MFCC c1..c12, 12-bin chroma (60-2100 Hz), mel flux and
 * low-band flux. Onsets are adaptive-threshold peaks of the mel flux.
 */
object BarFeatureExtractor {

    class Frames internal constructor(
        val sampleRate: Int,
        val frameSize: Int,
        val hop: Int,
        val count: Int,
        internal val total: DoubleArray,
        internal val low: DoubleArray,
        internal val high: DoubleArray,
        internal val mfcc: FloatArray,
        internal val chroma: FloatArray,
        internal val flux: DoubleArray,
        internal val lowFlux: DoubleArray,
        internal val onset: BooleanArray,
    ) {
        val durationSec: Double get() = if (count == 0) 0.0 else ((count - 1) * hop + frameSize).toDouble() / sampleRate
    }

    /** A bar grid. [shifted] = the phase differs from the vc80 grid that starts at 0. */
    class Grid(val downbeats: DoubleArray, val firstDownbeatSec: Double, val confidence: Float, val shifted: Boolean)

    private const val MEL = 24
    private const val MFCC = 12

    fun frames(pcm: FloatArray, sampleRate: Int): Frames {
        var rate = sampleRate.coerceAtLeast(1)
        var mono = pcm
        if (rate > 24_000) {
            val factor = rate / 22_050
            if (factor >= 2) {
                val outN = mono.size / factor
                val dec = FloatArray(outN)
                for (i in 0 until outN) {
                    var acc = 0f
                    for (k in 0 until factor) acc += mono[i * factor + k]
                    dec[i] = acc / factor
                }
                mono = dec
                rate /= factor
            }
        }
        val n = if (rate <= 16_000) 1024 else 2048
        val hop = n / 2
        val bins = n / 2
        val count = if (mono.size < n) 0 else 1 + (mono.size - n) / hop
        val total = DoubleArray(count)
        val low = DoubleArray(count)
        val high = DoubleArray(count)
        val mfcc = FloatArray(count * MFCC)
        val chroma = FloatArray(count * 12)
        val flux = DoubleArray(count)
        val lowFlux = DoubleArray(count)
        if (count == 0) {
            return Frames(rate, n, hop, 0, total, low, high, mfcc, chroma, flux, lowFlux, BooleanArray(0))
        }
        val binHz = rate.toDouble() / n
        val lowA = max(1, ceil(30.0 / binHz).toInt())
        val lowB = max(lowA + 1, (150.0 / binHz).toInt())
        val hiA = min(bins - 1, (2500.0 / binHz).toInt())
        val hiB = min(bins, (min(8000.0, 0.47 * rate) / binHz).toInt()).coerceAtLeast(hiA + 1)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
        // sparse triangular mel bank
        val melLo = hzToMel(40.0)
        val melHi = hzToMel(min(7000.0, 0.45 * rate))
        val pts = DoubleArray(MEL + 2) { melToHz(melLo + (melHi - melLo) * it / (MEL + 1)) / binHz }
        val melStart = IntArray(MEL)
        val melW = Array(MEL) { m ->
            val left = pts[m]
            val centre = pts[m + 1]
            val right = pts[m + 2]
            val a = max(0, ceil(left).toInt())
            val b = min(bins - 1, right.toInt())
            melStart[m] = a
            DoubleArray((b - a + 1).coerceAtLeast(0)) { off ->
                val k = (a + off).toDouble()
                val w = if (k < centre) (k - left) / (centre - left).coerceAtLeast(1e-9) else (right - k) / (right - centre).coerceAtLeast(1e-9)
                w.coerceAtLeast(0.0)
            }
        }
        val pitchClass = IntArray(bins) { -1 }
        for (k in 1 until bins) {
            val f = k * binHz
            if (f in 60.0..2100.0) {
                val p = (12.0 * ln(f / 440.0) / ln(2.0) + 69.0).roundToInt()
                pitchClass[k] = ((p % 12) + 12) % 12
            }
        }
        val dct = Array(MFCC) { q -> DoubleArray(MEL) { j -> cos(PI * (q + 1) * (j + 0.5) / MEL) } }
        val fft = Fft(n)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val power = DoubleArray(bins)
        val mel = DoubleArray(MEL)
        val prevMel = DoubleArray(MEL)
        var prevLow = 0.0
        for (t in 0 until count) {
            val at = t * hop
            for (i in 0 until n) {
                re[i] = mono[at + i] * window[i]
                im[i] = 0.0
            }
            fft.forward(re, im)
            var tot = 0.0
            var lo = 0.0
            var hi = 0.0
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                power[k] = p
                tot += p
                if (k in lowA until lowB) lo += p
                if (k in hiA until hiB) hi += p
            }
            total[t] = tot
            low[t] = lo
            high[t] = hi
            var fl = 0.0
            for (m in 0 until MEL) {
                val w = melW[m]
                val a = melStart[m]
                var acc = 0.0
                for (o in w.indices) acc += w[o] * power[a + o]
                mel[m] = ln(1e-9 + acc)
                if (t > 0) {
                    val d = mel[m] - prevMel[m]
                    if (d > 0) fl += d
                }
            }
            flux[t] = fl
            for (q in 0 until MFCC) {
                var acc = 0.0
                val row = dct[q]
                for (j in 0 until MEL) acc += row[j] * mel[j]
                mfcc[t * MFCC + q] = acc.toFloat()
            }
            for (k in 1 until bins) {
                val pc = pitchClass[k]
                if (pc >= 0) chroma[t * 12 + pc] += sqrt(power[k]).toFloat()
            }
            val ll = ln(1e-9 + lo)
            lowFlux[t] = if (t > 0) max(0.0, ll - prevLow) else 0.0
            prevLow = ll
            for (m in 0 until MEL) prevMel[m] = mel[m]
        }
        val onset = pickOnsets(flux, rate.toDouble() / hop)
        return Frames(rate, n, hop, count, total, low, high, mfcc, chroma, flux, lowFlux, onset)
    }

    private fun pickOnsets(env: DoubleArray, fps: Double): BooleanArray {
        val n = env.size
        val out = BooleanArray(n)
        if (n == 0) return out
        val w = max(2, (0.25 * fps).roundToInt())
        var g = 0.0
        for (v in env) g += v
        g /= n
        // running window sum for the local mean
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + env[i]
        var last = -10
        for (t in 0 until n) {
            val a = max(0, t - w)
            val b = min(n, t + w + 1)
            val local = (prefix[b] - prefix[a]) / (b - a)
            val v = env[t]
            if (v <= local * 1.25 + 0.1 * g) continue
            if (t > 0 && v < env[t - 1]) continue
            if (t + 1 < n && v < env[t + 1]) continue
            if (t - last < 2) continue
            out[t] = true
            last = t
        }
        return out
    }

    private fun frameIndex(f: Frames, sec: Double): Double = (sec * f.sampleRate - f.frameSize / 2.0) / f.hop

    private fun interp(v: DoubleArray, x: Double): Double {
        if (x < 0.0 || x > v.size - 1) return 0.0
        val i = x.toInt()
        val fr = x - i
        if (i + 1 >= v.size) return v[i]
        return v[i] * (1 - fr) + v[i + 1] * fr
    }

    /** Sum of frame-normalised chroma over [t0, t1) seconds, into [out]. */
    private fun chromaSum(f: Frames, t0: Double, t1: Double, out: DoubleArray) {
        for (k in 0 until 12) out[k] = 0.0
        val a = max(0, frameIndex(f, t0).toInt())
        val b = min(f.count, frameIndex(f, t1).toInt())
        for (t in a until b) {
            var nv = 0.0
            for (k in 0 until 12) {
                val c = f.chroma[t * 12 + k].toDouble()
                nv += c * c
            }
            nv = sqrt(nv)
            if (nv <= 1e-9) continue
            for (k in 0 until 12) out[k] += f.chroma[t * 12 + k] / nv
        }
    }

    private fun cos12(a: DoubleArray, b: DoubleArray): Double {
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (k in 0 until 12) {
            dot += a[k] * b[k]
            na += a[k] * a[k]
            nb += b[k] * b[k]
        }
        val d = sqrt(na * nb)
        return if (d < 1e-12) 0.0 else dot / d
    }

    /**
     * Beat phase (16 sub-beat candidates, onset strength at the beats) and
     * bar phase (chord change + kick/bass onset on the one). Keeps the vc80
     * phase 0 unless another phase wins by [StructureTuning.GRID_MARGIN].
     * [pcmDurationSec] bounds the grid exactly like the vc80 front end.
     */
    fun estimateGrid(f: Frames, bpm: Double, pcmDurationSec: Double, beatsPerBar: Int = 4): Grid {
        val beat = 60.0 / bpm.coerceAtLeast(1.0)
        val bar = beat * beatsPerBar.coerceAtLeast(1)
        fun uniform(first: Double): DoubleArray {
            val count = if (first <= 0.0) max(1, (pcmDurationSec / bar).toInt()) else max(1, ((pcmDurationSec - first) / bar).toInt())
            return DoubleArray(count) { first + it * bar }
        }
        var envSum = 0.0
        for (v in f.flux) envSum += v
        if (f.count < 16 || envSum < 1e-6 || pcmDurationSec < 4 * bar) return Grid(uniform(0.0), 0.0, 0f, false)
        val k = 32
        val beats = (pcmDurationSec / beat).toInt()
        val scores = DoubleArray(k)
        for (ph in 0 until k) {
            val off = ph * beat / k
            var acc = 0.0
            for (j in 0 until beats) acc += interp(f.flux, frameIndex(f, off + j * beat))
            scores[ph] = acc
        }
        var best = 0
        for (ph in 1 until k) if (scores[ph] > scores[best]) best = ph
        val offset = if (best != 0 && scores[best] > scores[0] * StructureTuning.GRID_MARGIN) best * beat / k else 0.0
        var meanScore = 0.0
        for (v in scores) meanScore += v
        meanScore /= k
        val conf = ((scores[best] - meanScore) / (scores[best] + 1e-9)).toFloat().coerceIn(0f, 1f)
        val a = DoubleArray(12)
        val b = DoubleArray(12)
        val phase = DoubleArray(beatsPerBar)
        for (m in 0 until beatsPerBar) {
            var acc = 0.0
            var j = 0
            while (true) {
                val t = offset + (j * beatsPerBar + m) * beat
                if (t + beat > pcmDurationSec) break
                if (t - beat >= 0.0) {
                    chromaSum(f, t - beat, t, a)
                    chromaSum(f, t, t + beat, b)
                    acc += 1.0 - cos12(a, b)
                }
                acc += 0.5 * min(1.0, interp(f.lowFlux, frameIndex(f, t)))
                j++
            }
            phase[m] = acc
        }
        var bestM = 0
        for (m in 1 until beatsPerBar) if (phase[m] > phase[bestM]) bestM = m
        if (!(bestM != 0 && phase[bestM] > phase[0] * StructureTuning.GRID_MARGIN)) bestM = 0
        val first = offset + bestM * beat
        return Grid(uniform(first), first, conf, first > 1e-9)
    }

    /** Beat-synchronous bar features on [downbeats]; the last bar is [barSec] long. */
    fun bars(f: Frames, downbeats: DoubleArray, beatsPerBar: Int, barSec: Double): List<V2Bar> {
        val out = ArrayList<V2Bar>(downbeats.size)
        val c = DoubleArray(12)
        for (i in downbeats.indices) {
            val t0 = downbeats[i]
            val t1 = if (i + 1 < downbeats.size) downbeats[i + 1] else t0 + barSec
            val a = max(0, ceil(frameIndex(f, t0)).toInt())
            val b = min(f.count, ceil(frameIndex(f, t1)).toInt())
            if (b <= a) {
                out.add(V2Bar(FloatArray(12), FloatArray(MFCC), -100f, -100f, -100f, 0f, 0f))
                continue
            }
            var tot = 0.0
            var lo = 0.0
            var hi = 0.0
            var ons = 0
            val mf = FloatArray(MFCC)
            for (t in a until b) {
                tot += f.total[t]
                lo += f.low[t]
                hi += f.high[t]
                if (f.onset[t]) ons++
                for (q in 0 until MFCC) mf[q] += f.mfcc[t * MFCC + q]
            }
            val cnt = (b - a).toDouble()
            for (q in 0 until MFCC) mf[q] = (mf[q] / cnt).toFloat()
            chromaSum(f, t0, t1, c)
            var mx = 0.0
            var mean = 0.0
            var nv = 0.0
            for (k in 0 until 12) {
                mx = max(mx, c[k])
                mean += c[k]
                nv += c[k] * c[k]
            }
            mean /= 12.0
            nv = sqrt(nv)
            val chroma = FloatArray(12) { if (nv > 1e-9) (c[it] / nv).toFloat() else 0f }
            out.add(
                V2Bar(
                    chroma = chroma,
                    timbre = mf,
                    rmsDb = db(tot / cnt / f.frameSize),
                    lowDb = db(lo / cnt / f.frameSize),
                    highDb = db(hi / cnt / f.frameSize),
                    onsetRate = ons.toFloat() / beatsPerBar.coerceAtLeast(1),
                    clarity = if (mean > 1e-9) (mx / mean).toFloat() else 0f,
                ),
            )
        }
        return out
    }

    /**
     * Mean of a proportional vocal mask (index = t / duration * size, the vc80
     * convention) over each bar; null when there is no mask.
     */
    fun vocalPerBar(mask: FloatArray?, downbeats: DoubleArray, barSec: Double, durationSec: Double): FloatArray? {
        if (mask == null || mask.isEmpty() || durationSec <= 0.0) return null
        return FloatArray(downbeats.size) { b ->
            val t0 = downbeats[b]
            val t1 = if (b + 1 < downbeats.size) downbeats[b + 1] else t0 + barSec
            val a = (t0 / durationSec * mask.size).toInt().coerceIn(0, mask.size)
            val z = (t1 / durationSec * mask.size).toInt().coerceIn(a, mask.size)
            if (z <= a) {
                mask[a.coerceAtMost(mask.size - 1)]
            } else {
                var acc = 0f
                for (i in a until z) acc += mask[i]
                acc / (z - a)
            }
        }
    }

    private fun db(p: Double): Float = (10.0 * ln(p + 1e-12) / ln(10.0)).toFloat()

    private fun hzToMel(hz: Double) = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)

    private fun melToHz(mel: Double) = 700.0 * (kotlin.math.exp(mel * ln(10.0) / 2595.0) - 1.0)

    /** Iterative radix-2 complex FFT with precomputed twiddles. */
    private class Fft(private val n: Int) {
        private val rev = IntArray(n)
        private val cosT = DoubleArray(n / 2) { cos(-2.0 * PI * it / n) }
        private val sinT = DoubleArray(n / 2) { sin(-2.0 * PI * it / n) }

        init {
            var bits = 0
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) {
                var x = i
                var y = 0
                for (b in 0 until bits) {
                    y = (y shl 1) or (x and 1)
                    x = x shr 1
                }
                rev[i] = y
            }
        }

        fun forward(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until n) {
                val j = rev[i]
                if (j > i) {
                    val tr = re[i]
                    re[i] = re[j]
                    re[j] = tr
                    val ti = im[i]
                    im[i] = im[j]
                    im[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val half = len / 2
                val stride = n / len
                var i = 0
                while (i < n) {
                    for (j in 0 until half) {
                        val wr = cosT[j * stride]
                        val wi = sinT[j * stride]
                        val u = i + j
                        val v = u + half
                        val xr = wr * re[v] - wi * im[v]
                        val xi = wr * im[v] + wi * re[v]
                        re[v] = re[u] - xr
                        im[v] = im[u] - xi
                        re[u] += xr
                        im[u] += xi
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }
}
