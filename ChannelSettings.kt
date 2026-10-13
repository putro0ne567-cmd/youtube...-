package com.stereodsp.dsp

import org.json.JSONArray
import org.json.JSONObject

enum class BandType(val label: String) {
    PEAK("Peak"),
    LOW_SHELF("Low shelf"),
    HIGH_SHELF("High shelf"),
}

/** Slope filter Butterworth. [order] adalah orde filter (6 dB/oktaf per orde). */
enum class Slope(val db: Int, val order: Int) {
    DB6(6, 1),
    DB12(12, 2),
    DB18(18, 3),
    DB24(24, 4),
    DB36(36, 6),
    DB48(48, 8),
}

data class EqBand(
    val enabled: Boolean = true,
    val type: BandType = BandType.PEAK,
    val freq: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1.0f,
)

data class PassFilter(
    val enabled: Boolean = false,
    val freq: Float = 100f,
    val slope: Slope = Slope.DB12,
)

/** Seluruh pengaturan satu kanal (L atau R). */
data class ChannelSettings(
    val gainDb: Float = 0f,
    val polarityInverted: Boolean = false,
    val hpf: PassFilter = PassFilter(enabled = false, freq = 20f, slope = Slope.DB12),
    val lpf: PassFilter = PassFilter(enabled = false, freq = 20000f, slope = Slope.DB12),
    val eqEnabled: Boolean = true,
    val bands: List<EqBand> = defaultBands(),
) {
    fun withBand(index: Int, f: (EqBand) -> EqBand): ChannelSettings =
        copy(bands = bands.mapIndexed { i, b -> if (i == index) f(b) else b })

    companion object {
        const val BAND_COUNT = 10

        fun defaultBands(): List<EqBand> {
            val freqs = floatArrayOf(40f, 80f, 160f, 320f, 640f, 1250f, 2500f, 5000f, 10000f, 16000f)
            return freqs.mapIndexed { i, f ->
                when (i) {
                    0 -> EqBand(type = BandType.LOW_SHELF, freq = f, q = 0.71f)
                    BAND_COUNT - 1 -> EqBand(type = BandType.HIGH_SHELF, freq = f, q = 0.71f)
                    else -> EqBand(type = BandType.PEAK, freq = f, q = 1.0f)
                }
            }
        }
    }
}

enum class Channel(val label: String) {
    LEFT("L"),
    RIGHT("R");

    val other: Channel get() = if (this == LEFT) RIGHT else LEFT
}

// ---- Serialisasi JSON untuk preset pengguna dan pengaturan terakhir ----

fun EqBand.toJson(): JSONObject = JSONObject().apply {
    put("on", enabled)
    put("type", type.name)
    put("f", freq.toDouble())
    put("g", gainDb.toDouble())
    put("q", q.toDouble())
}

fun eqBandFromJson(o: JSONObject): EqBand = EqBand(
    enabled = o.optBoolean("on", true),
    type = BandType.entries.firstOrNull { it.name == o.optString("type") } ?: BandType.PEAK,
    freq = o.optDouble("f", 1000.0).toFloat(),
    gainDb = o.optDouble("g", 0.0).toFloat(),
    q = o.optDouble("q", 1.0).toFloat(),
)

fun PassFilter.toJson(): JSONObject = JSONObject().apply {
    put("on", enabled)
    put("f", freq.toDouble())
    put("slope", slope.db)
}

fun passFilterFromJson(o: JSONObject?, default: PassFilter): PassFilter {
    if (o == null) return default
    return PassFilter(
        enabled = o.optBoolean("on", default.enabled),
        freq = o.optDouble("f", default.freq.toDouble()).toFloat(),
        slope = Slope.entries.firstOrNull { it.db == o.optInt("slope", -1) } ?: default.slope,
    )
}

fun ChannelSettings.toJson(): JSONObject = JSONObject().apply {
    put("gain", gainDb.toDouble())
    put("pol", polarityInverted)
    put("eq", eqEnabled)
    put("hpf", hpf.toJson())
    put("lpf", lpf.toJson())
    put("bands", JSONArray().also { arr -> bands.forEach { arr.put(it.toJson()) } })
}

fun channelSettingsFromJson(o: JSONObject): ChannelSettings {
    val d = ChannelSettings()
    val arr = o.optJSONArray("bands")
    val bands = if (arr != null && arr.length() == ChannelSettings.BAND_COUNT) {
        List(arr.length()) { eqBandFromJson(arr.getJSONObject(it)) }
    } else {
        d.bands
    }
    return ChannelSettings(
        gainDb = o.optDouble("gain", 0.0).toFloat(),
        polarityInverted = o.optBoolean("pol", false),
        eqEnabled = o.optBoolean("eq", true),
        hpf = passFilterFromJson(o.optJSONObject("hpf"), d.hpf),
        lpf = passFilterFromJson(o.optJSONObject("lpf"), d.lpf),
        bands = bands,
    )
}
