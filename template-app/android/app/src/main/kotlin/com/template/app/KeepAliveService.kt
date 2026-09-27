package com.template.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keepalive_channel"
        private const val CHANNEL_NAME = "Background Service"
        private const val NOTIF_ID = 1001

        /**
         * Start service dengan aman untuk semua versi Android.
         * Android 8+ wajib pakai startForegroundService().
         */
        fun start(context: Context) {
            try {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val notif = buildNotification()

            // ===== FIX ANDROID 14 (API 34) =====
            // WAJIB pakai ServiceCompat + FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // JANGAN pakai startForeground(NOTIF_ID, notif) biasa
            // karena di Android 14, FGS tanpa type akan dianggap default
            // dan bisa crash kalau manifest declare type microphone/camera.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIF_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            // Jangan crash kalau startForeground gagal (mis. permission belum granted)
            e.printStackTrace()
            stopSelf()
        }

        // START_STICKY = restart otomatis kalau service dibunuh system
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Menjaga koneksi tetap berjalan"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        // Tap notif → buka app
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sync aktif")
            .setContentText("Menjaga koneksi tetap berjalan")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Restart service kalau app di-swipe dari recents
        try {
            val restartIntent = Intent(applicationContext, KeepAliveService::class.java)
            val restartPending = PendingIntent.getService(
                this,
                1,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.set(
                android.app.AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + 2000,
                restartPending
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
