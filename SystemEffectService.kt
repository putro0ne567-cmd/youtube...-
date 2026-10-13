package com.stereodsp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import com.stereodsp.MainActivity
import com.stereodsp.R
import com.stereodsp.data.SettingsStore
import com.stereodsp.dsp.Channel
import com.stereodsp.dsp.ChannelSettings

/** Menjaga efek global tetap hidup saat pengguna berpindah ke YouTube / aplikasi lain. */
class SystemEffectService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Efek audio", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = SettingsStore(this)
        if (intent?.action == ACTION_STOP) {
            store.setSystemEnabled(false)
            SystemEffect.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!store.isSystemEnabled() && intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = SystemEffect.start(
            store.loadCurrent(Channel.LEFT) ?: ChannelSettings(),
            store.loadCurrent(Channel.RIGHT) ?: ChannelSettings(),
            store.isMuted(Channel.LEFT),
            store.isMuted(Channel.RIGHT),
        )
        if (!ok) {
            store.setSystemEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        SystemEffect.stop()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SystemEffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_nj)
            .setContentTitle("NJ Audio aktif")
            .setContentText("Efek berlaku untuk semua aplikasi")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_stat_nj), "Matikan", stop,
                ).build(),
            )
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.stereodsp.action.STOP_SYSTEM_EFFECT"
        private const val CHANNEL_ID = "system_effect"
        private const val NOTIF_ID = 1
    }
}
