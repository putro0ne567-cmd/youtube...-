package com.stereodsp

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stereodsp.audio.AudioEngine
import com.stereodsp.audio.SystemEffect
import com.stereodsp.audio.SystemEffectService
import com.stereodsp.data.SettingsStore
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DspViewModel(app: Application) : AndroidViewModel(app) {

    val engine = AudioEngine(app)
    private val store = SettingsStore(app)

    private val _left = MutableStateFlow(store.loadCurrent(Channel.LEFT) ?: ChannelSettings())
    private val _right = MutableStateFlow(store.loadCurrent(Channel.RIGHT) ?: ChannelSettings())
    private val _linked = MutableStateFlow(false)
    private val _muteL = MutableStateFlow(store.isMuted(Channel.LEFT))
    private val _muteR = MutableStateFlow(store.isMuted(Channel.RIGHT))

    val left: StateFlow<ChannelSettings> = _left.asStateFlow()
    val right: StateFlow<ChannelSettings> = _right.asStateFlow()
    val linked: StateFlow<Boolean> = _linked.asStateFlow()
    val muteL: StateFlow<Boolean> = _muteL.asStateFlow()
    val muteR: StateFlow<Boolean> = _muteR.asStateFlow()

    /** True bila efek berlaku untuk seluruh aplikasi (YouTube dll.). */
    val systemOn: StateFlow<Boolean> = SystemEffect.active
    val systemError: StateFlow<String?> = SystemEffect.error

    init {
        pushToEngine()
        // Saat efek global menyala/mati, pemutar internal berganti mode agar tidak diproses dua kali.
        viewModelScope.launch { SystemEffect.active.collect { pushToEngine() } }
    }

    private fun flow(ch: Channel) = if (ch == Channel.LEFT) _left else _right
    private fun muteFlow(ch: Channel) = if (ch == Channel.LEFT) _muteL else _muteR

    private fun pushToEngine() {
        store.saveCurrent(Channel.LEFT, _left.value)
        store.saveCurrent(Channel.RIGHT, _right.value)
        if (SystemEffect.active.value) {
            // Efek global sudah memproses semua suara, termasuk pemutar ini.
            engine.setSettings(ChannelSettings(), ChannelSettings(), false, false)
            SystemEffect.update(_left.value, _right.value, _muteL.value, _muteR.value)
        } else {
            engine.setSettings(_left.value, _right.value, _muteL.value, _muteR.value)
        }
    }

    /** Ubah pengaturan satu kanal; bila Link aktif, hasilnya disalin ke kanal lain. */
    fun update(ch: Channel, transform: (ChannelSettings) -> ChannelSettings) {
        val new = transform(flow(ch).value)
        flow(ch).value = new
        if (_linked.value) flow(ch.other).value = new
        pushToEngine()
    }

    fun setLinked(on: Boolean) {
        _linked.value = on
    }

    fun setMute(ch: Channel, on: Boolean) {
        muteFlow(ch).value = on
        store.setMuted(ch, on)
        pushToEngine()
    }

    fun setSystem(on: Boolean) {
        val ctx = getApplication<Application>()
        store.setSystemEnabled(on)
        val intent = Intent(ctx, SystemEffectService::class.java)
        if (on) ctx.startForegroundService(intent) else ctx.stopService(intent)
    }

    fun copyToOther(from: Channel) {
        flow(from.other).value = flow(from).value
        pushToEngine()
    }

    fun open(uri: Uri) {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment ?: "audio"
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0) ?: name
            }
        } catch (_: Exception) {
        }
        engine.load(uri, name)
    }

    fun togglePause() = engine.togglePause()
    fun stop() = engine.stop()
    fun seek(ms: Long) = engine.seekTo(ms)

    override fun onCleared() {
        engine.release()
    }
}
