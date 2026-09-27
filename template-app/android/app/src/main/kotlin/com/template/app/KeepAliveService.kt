package com.template.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {
    companion object {
        private const val CHANNEL_ID = "keepalive_channel"
        private const val NOTIF_ID = 1001
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Buat notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Background Service",
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(chan)
        }

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sync aktif")
            .setContentText("Menjaga koneksi tetap berjalan")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notif)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
