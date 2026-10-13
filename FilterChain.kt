package com.stereodsp.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

/** Membangun rangkaian biquad dari [ChannelSettings] dan menghitung respons frekuensinya. */
object FilterChain {

    /** Butterworth orde [order]: kaskade seksi orde-2, ditambah satu seksi orde-1 bila orde ganjil. */
    private fun butterworth(high: Boolean, f: Double, order: Int, fs: Double): List<Coeffs> {
        val out = ArrayList<Coeffs>()
        for (k in 1..order / 2) {
            val q = 1.0 / (2.0 * sin((2 * k - 1) * PI / (2.0 * order)))
            out += if (high) BiquadDesign.highPass(f, q, fs) else BiquadDesign.lowPass(f, q, fs)
        }
        if (order % 2 == 1) {
            out += if (high) BiquadDesign.highPass1(f, fs) else BiquadDesign.lowPass1(f, fs)
        }
        return out
    }

    /** Urutan: HPF -> LPF -> EQ. Gain dan polaritas ditangani terpisah. */
    fun build(s: ChannelSettings, fs: Double): List<Coeffs> {
        val out = ArrayList<Coeffs>()
        if (s.hpf.enabled) out += butterworth(true, s.hpf.freq.toDouble(), s.hpf.slope.order, fs)
        if (s.lpf.enabled) out += butterworth(false, s.lpf.freq.toDouble(), s.lpf.slope.order, fs)
        if (s.eqEnabled) {
            for (b in s.bands) {
                if (!b.enabled) continue
                val f = b.freq.toDouble()
                val g = b.gainDb.toDouble()
                val q = b.q.toDouble()
                out += when (b.type) {
                    BandType.PEAK -> BiquadDesign.peaking(f, g, q, fs)
                    BandType.LOW_SHELF -> BiquadDesign.lowShelf(f, g, q, fs)
                    BandType.HIGH_SHELF -> BiquadDesign.highShelf(f, g, q, fs)
                }
            }
        }
        return out
    }

    /** Respons magnitudo total (dB) termasuk gain, pada setiap frekuensi di [freqs]. */
    fun responseCurve(s: ChannelSettings, freqs: DoubleArray, fs: Double): DoubleArray {
        val chain = build(s, fs)
        return DoubleArray(freqs.size) { i ->
            var db = s.gainDb.toDouble()
            for (c in chain) {
                db += 20.0 * log10(c.magnitude(freqs[i], fs).coerceAtLeast(1e-9))
            }
            db
        }
    }
}
