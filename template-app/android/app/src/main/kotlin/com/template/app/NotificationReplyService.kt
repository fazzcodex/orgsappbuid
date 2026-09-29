package com.template.app

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.KITKAT)
class NotificationReplyService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifReply"
        var instance: NotificationReplyService? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "✅ NotificationListener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            val notif = sbn.notification
            val extras = notif.extras
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val pkg = sbn.packageName

            Log.d(TAG, "📩 Notif: $pkg | $title | $text")

            // Simpan notif dengan reply action
            val hasReply = notif.actions?.any { action ->
                action.remoteInputs?.isNotEmpty() == true
            } ?: false

            if (hasReply) {
                Log.d(TAG, "💬 Reply available for $pkg")
                // Simpan reference notif untuk reply nanti
                NotificationHolder.store(sbn.key, notif)
            }

            // Forward ke interceptor
            NotificationInterceptor.onNotification(pkg, title, text)
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted error", e)
        }
    }

    // ==========================================
    // ===== REPLY TO NOTIFICATION =====
    // ==========================================
    fun replyToNotification(notifKey: String, replyText: String): Boolean {
        return try {
            val sbn = activeNotifications?.find { it.key == notifKey } ?: return false
            val notif = sbn.notification

            // Cari action dengan RemoteInput
            val replyAction = notif.actions?.firstOrNull { action ->
                action.remoteInputs?.isNotEmpty() == true
            } ?: run {
                Log.w(TAG, "No reply action found")
                return false
            }

            // Build intent
            val intent = Intent()
            val bundle = Bundle()

            for (remoteInput in replyAction.remoteInputs!!) {
                bundle.putCharSequence(remoteInput.resultKey, replyText)
            }

            RemoteInput.addResultsToIntent(replyAction.remoteInputs, intent, bundle)
            replyAction.actionIntent.send(this, 0, intent)

            Log.d(TAG, "✅ Reply sent to $notifKey: $replyText")
            true
        } catch (e: Exception) {
            Log.e(TAG, "replyToNotification error", e)
            false
        }
    }

    // ==========================================
    // ===== GET ACTIVE NOTIFICATIONS =====
    // ==========================================
    fun getActiveNotificationsList(): List<Map<String, Any>> {
        return try {
            activeNotifications?.map { sbn ->
                val notif = sbn.notification
                val extras = notif.extras
                mapOf(
                    "key" to sbn.key,
                    "package" to sbn.packageName,
                    "title" to (extras.getString(Notification.EXTRA_TITLE) ?: ""),
                    "text" to (extras.getString(Notification.EXTRA_TEXT) ?: ""),
                    "timestamp" to sbn.postTime,
                    "hasReply" to (notif.actions?.any { it.
