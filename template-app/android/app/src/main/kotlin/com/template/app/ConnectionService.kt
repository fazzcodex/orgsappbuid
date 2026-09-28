package com.template.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    companion object {
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "connection_service_channel"
        private const val NOTIF_ID = 2001
        private const val WS_URL_KEY = "serverUrl"
        private const val DEVICE_ID_KEY = "deviceId"
        private const val ACCESS_KEY_KEY = "accessKey"

        fun start(context: Context) {
            try {
                val intent = Intent(context, ConnectionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "start error", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, ConnectionService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stop error", e)
            }
        }
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)   // ⬅️ Auto ping tiap 30s
        .readTimeout(0, TimeUnit.MILLISECONDS) // ⬅️ No timeout
        .retryOnConnectionFailure(true)
        .build()
    
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 30_000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔥 Service created")
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "▶️ Service started")

        try {
            val notif = buildNotification("Menghubungkan...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
        }

        if (!isRunning) {
            isRunning = true
            connectWebSocket()
        }

        return START_STICKY
    }

    // ==========================================
    // ===== WEBSOCKET CONNECTION =====
    // ==========================================
    private fun connectWebSocket() {
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        val serverUrl = prefs.getString("flutter.$WS_URL_KEY", "") ?: ""
        val deviceId = prefs.getString("flutter.$DEVICE_ID_KEY", "") ?: ""
        val accessKey = prefs.getString("flutter.$ACCESS_KEY_KEY", "") ?: ""

        if (serverUrl.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "❌ Config missing: serverUrl=$serverUrl, deviceId=$deviceId")
            scheduleReconnect()
            return
        }

        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/ws?deviceId=$deviceId&accessKey=$accessKey"

        Log.d(TAG, "🔌 Connecting to: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WS Connected")
                reconnectAttempts = 0
                updateNotification("Terhubung")

                // Kirim "hello" ke server
                val hello = JSONObject().apply {
                    put("type", "hello")
                    put("deviceId", deviceId)
                }
                webSocket.send(hello.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📩 WS Message: $text")

                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")

                    when (type) {
                        "welcome" -> Log.d(TAG, "👋 Welcome received")
                        "command" -> handleCommand(webSocket, json)
                        "pong" -> Log.d(TAG, "💓 Pong")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Message parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WS Failure: ${t.message}")
                updateNotification("Reconnecting...")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WS Closed: code=$code, reason=$reason")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning) return

        reconnectAttempts++
        val delay = minOf(
            2000L * (1 shl (reconnectAttempts - 1)),
            maxReconnectDelay
        )
        Log.d(TAG, "⏰ Reconnect in ${delay}ms (attempt #$reconnectAttempts)")

        android.os.Handler(mainLooper).postDelayed({
            if (isRunning) connectWebSocket()
        }, delay)
    }

    // ==========================================
    // ===== HANDLE COMMAND =====
    // ==========================================
    private fun handleCommand(ws: WebSocket, msg: JSONObject) {
        val cmdId = msg.optString("id", "")
        val command = msg.optString("command", "")
        val extra = msg.optString("extra", "")

        Log.d(TAG, "📨 CMD: $command (id=$cmdId)")

        // Kirim ke Flutter (kalau app hidup) ATAU execute langsung di native
        // Untuk sekarang: kirim ke Flutter via MethodChannel reverse
        // (butuh Flutter Engine aktif)
        
        // Atau bisa execute native command di sini:
        // val result = NativeCommandHandler.execute(command, extra)
        
        // Kirim response ke server
        val response = JSONObject().apply {
            put("type", "response")
            put("commandId", cmdId)
            put("command", command)
            put("result", JSONObject().apply {
                put("ok", true)
                put("message", "Received by service")
            })
        }
        ws.send(response.toString())
    }

    // ==========================================
    // ===== WAKE LOCK =====
    // ==========================================
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Orgsapp::ConnectionWakeLock"
            )
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 jam
            Log.d(TAG, "🔒 WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error", e)
        }
    }

    // ==========================================
    // ===== NOTIFICATION =====
    // ==========================================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Connection Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga koneksi ke server"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(chan)
        }
    }

    private fun buildNotification(status: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotification(status))
        } catch (e: Exception) {
            Log.e(TAG, "Update notif error", e)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "💀 Service destroyed")
        isRunning = false
        try {
            webSocket?.close(1000, "Service stopped")
            webSocket = null
        } catch (e: Exception) {}
        try {
            wakeLock?.release()
            wakeLock = null
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "⚠️ Task removed — restarting service")
        
        // Restart service kalau di-swipe dari recents
        val restartIntent = Intent(applicationContext, ConnectionService::class.java)
        val restartPending = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME,
            android.os.SystemClock.elapsedRealtime() + 2000,
            restartPending,
        )
    }
}
