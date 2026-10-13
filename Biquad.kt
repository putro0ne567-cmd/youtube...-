package com.stereodsp.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Koefisien biquad ternormalisasi (a0 = 1). */
data class Coeffs(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    /** Magnitudo respons frekuensi (linear) pada [freq] Hz. */
    fun magnitude(freq: Double, fs: Double): Double {
        val w = 2.0 * PI * freq / fs
        val cw = cos(w)
        val sw = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = b0 + b1 * cw + b2 * c2
        val ni = -(b1 * sw + b2 * s2)
        val dr = 1.0 + a1 * cw + a2 * c2
        val di = -(a1 * sw + a2 * s2)
        return sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
    }

    companion object {
        val IDENTITY = Coeffs(1.0, 0.0, 0.0, 0.0, 0.0)
    }
}

/** Biquad bentuk transposed direct form II. */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun set(c: Coeffs) {
        b0 = c.b0; b1 = c.b1; b2 = c.b2; a1 = c.a1; a2 = c.a2
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/** Desain koefisien berdasarkan Audio EQ Cookbook (RBJ). */
object BiquadDesign {

    private fun norm(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
        Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun clampFreq(f: Double, fs: Double) = f.coerceIn(1.0, fs * 0.49)

    fun lowPass(f: Double, q: Double, fs: Double): Coeffs {
        val w0 = 2 * PI * clampFreq(f, fs) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        return norm(
            (1 - cw) / 2, 1 - cw, (1 - cw) / 2,
            1 + alpha, -2 * cw, 1 - alpha,
        )
    }

    fun highPass(f: Double, q: Double, fs: Double): Coeffs {
        val w0 = 2 * PI * clampFreq(f, fs) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        return norm(
            (1 + cw) / 2, -(1 + cw), (1 + cw) / 2,
            1 + alpha, -2 * cw, 1 - alpha,
        )
    }

    /** Low-pass orde pertama (6 dB/oktaf), transformasi bilinear. */
    fun lowPass1(f: Double, fs: Double): Coeffs {
        val k = tan(PI * clampFreq(f, fs) / fs)
        val n = 1 + k
        return Coeffs(k / n, k / n, 0.0, (k - 1) / n, 0.0)
    }

    /** High-pass orde pertama (6 dB/oktaf), transformasi bilinear. */
    fun highPass1(f: Double, fs: Double): Coeffs {
        val k = tan(PI * clampFreq(f, fs) / fs)
        val n = 1 + k
        return Coeffs(1 / n, -1 / n, 0.0, (k - 1) / n, 0.0)
    }

    fun peaking(f: Double, gainDb: Double, q: Double, fs: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * clampFreq(f, fs) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        return norm(
            1 + alpha * a, -2 * cw, 1 - alpha * a,
            1 + alpha / a, -2 * cw, 1 - alpha / a,
        )
    }

    fun lowShelf(f: Double, gainDb: Double, q: Double, fs: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * clampFreq(f, fs) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val tw = 2 * sqrt(a) * alpha
        return norm(
            a * ((a + 1) - (a - 1) * cw + tw),
            2 * a * ((a - 1) - (a + 1) * cw),
            a * ((a + 1) - (a - 1) * cw - tw),
            (a + 1) + (a - 1) * cw + tw,
            -2 * ((a - 1) + (a + 1) * cw),
            (a + 1) + (a - 1) * cw - tw,
        )
    }

    fun highShelf(f: Double, gainDb: Double, q: Double, fs: Double): Coeffs {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * clampFreq(f, fs) / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2 * q)
        val tw = 2 * sqrt(a) * alpha
        return norm(
            a * ((a + 1) + (a - 1) * cw + tw),
            -2 * a * ((a - 1) + (a + 1) * cw),
            a * ((a + 1) + (a - 1) * cw - tw),
            (a + 1) - (a - 1) * cw + tw,
            2 * ((a - 1) - (a + 1) * cw),
            (a + 1) - (a - 1) * cw - tw,
        )
    }
}
