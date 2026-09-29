package com.template.app

import android.app.Notification

object NotificationHolder {
    private val notifMap = mutableMapOf<String, Notification>()

    fun store(key: String, notif: Notification) {
        notifMap[key] = notif
        // Keep 50 terakhir
        if (notifMap.size > 50) {
            val oldest = notifMap.keys.first()
            notifMap.remove(oldest)
        }
    }

    fun get(key: String): Notification? = notifMap[key]
}
