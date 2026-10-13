package com.stereodsp.data

import android.content.Context
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings
import com.stereodsp.dsp.channelSettingsFromJson
import com.stereodsp.dsp.toJson
import org.json.JSONObject

/** Menyimpan pengaturan terakhir, status mute L/R, dan status efek sistem. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("stereo_dsp", Context.MODE_PRIVATE)

    fun loadCurrent(ch: Channel): ChannelSettings? {
        val raw = prefs.getString("current_${ch.label}", null) ?: return null
        return try {
            channelSettingsFromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }
    }

    fun saveCurrent(ch: Channel, s: ChannelSettings) {
        prefs.edit().putString("current_${ch.label}", s.toJson().toString()).apply()
    }

    fun isMuted(ch: Channel): Boolean = prefs.getBoolean("mute_${ch.label}", false)

    fun setMuted(ch: Channel, on: Boolean) {
        prefs.edit().putBoolean("mute_${ch.label}", on).apply()
    }

    /** Apakah pengguna menyalakan efek untuk seluruh aplikasi (YouTube dll.). */
    fun isSystemEnabled(): Boolean = prefs.getBoolean("system_enabled", false)

    fun setSystemEnabled(on: Boolean) {
        prefs.edit().putBoolean("system_enabled", on).apply()
    }
}
