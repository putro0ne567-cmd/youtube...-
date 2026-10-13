package com.stereodsp.audio

import android.media.audiofx.DynamicsProcessing
import com.stereodsp.dsp.ChannelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * Efek global (audio session 0 = seluruh output perangkat) berbasis [DynamicsProcessing],
 * sehingga suara aplikasi lain (YouTube, dll.) ikut diproses.
 *
 * Pemetaan dari pengaturan per kanal:
 *  - Gain / Mute  -> input gain per kanal (mute = -80 dB)
 *  - HPF / LPF    -> crossover 3 band MBC; band bawah/atas dibisukan (slope tetap 24 dB/okt)
 *  - EQ 10 band   -> Pre-EQ 10 band (pendekatan: tipe peak/shelf tidak dibedakan, Q diabaikan)
 *  - Polaritas    -> tidak didukung di mode ini
 *
 * Catatan: sebagian perangkat/versi Android membatasi efek global; bila gagal, [error] terisi.
 */
object SystemEffect {
    private const val MUTE_DB = -80f
    private const val TOP_EDGE = 22000f
    private const val EQ_BANDS = ChannelSettings.BAND_COUNT

    private var dp: DynamicsProcessing? = null

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    @Synchronized
    fun start(l: ChannelSettings, r: ChannelSettings, muteL: Boolean, muteR: Boolean): Boolean {
        if (dp == null) {
            try {
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, EQ_BANDS,
                    true, 3,
                    false, 0,
                    false,
                ).build()
                val d = DynamicsProcessing(0, 0, cfg)
                dp = d
                applyAll(d, l, r, muteL, muteR)
                d.setEnabled(true)
            } catch (e: Throwable) {
                try { dp?.release() } catch (_: Throwable) {}
                dp = null
                _active.value = false
                _error.value = "Perangkat ini tidak mengizinkan efek untuk semua aplikasi (${e.javaClass.simpleName})"
                return false
            }
        } else {
            update(l, r, muteL, muteR)
        }
        _error.value = null
        _active.value = true
        return true
    }

    @Synchronized
    fun update(l: ChannelSettings, r: ChannelSettings, muteL: Boolean, muteR: Boolean) {
        val d = dp ?: return
        try {
            applyAll(d, l, r, muteL, muteR)
        } catch (e: Throwable) {
            _error.value = "Gagal memperbarui efek: ${e.javaClass.simpleName}"
        }
    }

    @Synchronized
    fun stop() {
        try { dp?.setEnabled(false) } catch (_: Throwable) {}
        try { dp?.release() } catch (_: Throwable) {}
        dp = null
        _active.value = false
    }

    private fun applyAll(d: DynamicsProcessing, l: ChannelSettings, r: ChannelSettings, mL: Boolean, mR: Boolean) {
        applyChannel(d, 0, l, mL)
        applyChannel(d, 1, r, mR)
    }

    private fun applyChannel(d: DynamicsProcessing, ch: Int, s: ChannelSettings, muted: Boolean) {
        d.setInputGainbyChannel(ch, if (muted) MUTE_DB else s.gainDb)

        // --- HPF / LPF lewat crossover MBC (3 band) ---
        var hp = if (s.hpf.enabled) s.hpf.freq else 20f
        hp = hp.coerceIn(20f, 20000f)
        var lp = if (s.lpf.enabled) s.lpf.freq else 20000f
        lp = lp.coerceIn(20f, 20000f)
        if (lp < hp * 1.05f) lp = (hp * 1.05f).coerceAtMost(21500f)
        val lowGain = if (s.hpf.enabled) MUTE_DB else 0f
        val highGain = if (s.lpf.enabled) MUTE_DB else 0f
        d.setMbcBandByChannelIndex(ch, 0, mbc(hp, lowGain))
        d.setMbcBandByChannelIndex(ch, 1, mbc(lp, 0f))
        d.setMbcBandByChannelIndex(ch, 2, mbc(TOP_EDGE, highGain))

        // --- EQ 10 band lewat Pre-EQ ---
        val bands = s.bands.sortedBy { it.freq }
        for (i in 0 until EQ_BANDS) {
            val b = bands[i]
            val edge = if (i == EQ_BANDS - 1) TOP_EDGE else sqrt(b.freq * bands[i + 1].freq)
            val gain = if (s.eqEnabled && b.enabled) b.gainDb.coerceIn(-18f, 18f) else 0f
            d.setPreEqBandByChannelIndex(ch, i, DynamicsProcessing.EqBand(true, edge.coerceAtMost(TOP_EDGE), gain))
        }
    }

    /** Band MBC netral (tanpa kompresi) dengan batas atas [cutoff] dan penguatan keluaran [postGain]. */
    private fun mbc(cutoff: Float, postGain: Float) = DynamicsProcessing.MbcBand(
        true, cutoff,
        1f, 50f,      // attack, release (ms)
        1f, 0f, 0f,   // ratio, threshold, knee -> tidak mengompresi
        -90f, 1f,     // noise gate, expander ratio
        0f, postGain, // pre gain, post gain
    )
}
