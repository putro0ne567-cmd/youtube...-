package com.stereodsp.dsp

import kotlin.math.exp
import kotlin.math.pow

/**
 * Prosesor satu kanal: polaritas -> gain/mute -> HPF -> LPF -> EQ.
 * [update] aman dipanggil dari thread UI saat [process] berjalan di thread audio.
 * Perubahan gain/mute dihaluskan (~5 ms) agar tidak berbunyi "klik".
 */
class ChannelProcessor(private val fs: Double) {
    private val lock = Any()
    private val sections = ArrayList<Biquad>()
    private var target = 1.0
    private var current = 1.0
    private var primed = false
    private val smooth = 1.0 - exp(-1.0 / (fs * 0.005))

    fun update(s: ChannelSettings, muted: Boolean = false) {
        val coeffs = FilterChain.build(s, fs)
        val linear = 10.0.pow(s.gainDb / 20.0)
        val sign = if (s.polarityInverted) -1.0 else 1.0
        synchronized(lock) {
            while (sections.size < coeffs.size) sections.add(Biquad())
            while (sections.size > coeffs.size) sections.removeAt(sections.lastIndex)
            coeffs.forEachIndexed { i, c -> sections[i].set(c) }
            target = if (muted) 0.0 else linear * sign
            if (!primed) {
                current = target
                primed = true
            }
        }
    }

    fun reset() {
        synchronized(lock) { sections.forEach { it.reset() } }
    }

    /** Memproses [count] sampel pertama dari [buf] di tempat. */
    fun process(buf: FloatArray, count: Int) {
        synchronized(lock) {
            for (i in 0 until count) {
                current += (target - current) * smooth
                var x = buf[i] * current
                for (s in sections) x = s.process(x)
                buf[i] = x.toFloat()
            }
        }
    }
}
