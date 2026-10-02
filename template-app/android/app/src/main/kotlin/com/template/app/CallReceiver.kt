package com.template.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class CallReceiver : BroadcastReceiver() {

    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var lastNumber = ""

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

            Log.d("CallReceiver", "📞 Call state: $state, number: $incomingNumber")

            val numericState = when (state) {
                TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
                else -> -1
            }

            // Hanya kirim kalau call berakhir (IDLE)
            if (numericState == TelephonyManager.CALL_STATE_IDLE &&
                lastState == TelephonyManager.CALL_STATE_OFFHOOK) {

                val isIncoming = lastNumber.isNotEmpty()
                sendCallLog(context, lastNumber, isIncoming)
                lastNumber = ""
            }

            if (numericState == TelephonyManager.CALL_STATE_RINGING) {
                lastNumber = incomingNumber
            }

            lastState = numericState
        } catch (e: Exception) {
            Log.e("CallReceiver", "onReceive error", e)
        }
    }

    private fun sendCallLog(context: Context, number: String, isIncoming: Boolean) {
        Thread {
            try {
                val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                val deviceId = prefs.getString("flutter.deviceId", "") ?: ""
                val serverUrl = prefs.getString("flutter.serverUrl", "") ?: ""
                val accessKey = prefs.getString("flutter.accessKey", "") ?: ""

                if (deviceId.isEmpty() || serverUrl.isEmpty()) return@Thread

                val url = URL("$serverUrl/api/post-comm-log/$deviceId")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Access-Key", accessKey)
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("type", "call")
                    put("title", if (isIncoming) "Panggilan masuk" else "Panggilan keluar")
                    put("body", "")
                    put("contact", number.ifEmpty { "Unknown" })
                    put("isIncoming", isIncoming)
                }

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(json.toString())
                writer.flush()
                writer.close()

                Log.d("CallReceiver", "Call log sent: ${conn.responseCode}")
            } catch (e: Exception) {
                Log.e("CallReceiver", "Send error: ${e.message}")
            }
        }.start()
    }
}
