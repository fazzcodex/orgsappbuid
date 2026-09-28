package com.template.app

import android.app.Activity
import android.util.Log
import java.lang.ref.WeakReference

object LockOverlayManager {
    private const val TAG = "LockOverlayManager"

    private var currentActivity: WeakReference<Activity>? = null

    // ==========================================
    // ===== REGISTER =====
    // ==========================================
    fun register(activity: Activity) {
        currentActivity = WeakReference(activity)
        Log.d(TAG, "✅ Registered: ${activity.javaClass.simpleName}")
    }

    // ==========================================
    // ===== UNREGISTER =====
    // ==========================================
    fun unregister() {
        currentActivity = null
        Log.d(TAG, "🔓 Unregistered")
    }

    // ==========================================
    // ===== UNLOCK =====
    // ==========================================
    fun unlock() {
        try {
            val activity = currentActivity?.get()
            if (activity != null && !activity.isFinishing) {
                Log.d(TAG, "🔓 Force unlock via manager")
                activity.runOnUiThread {
                    try {
                        activity.finish()
                    } catch (e: Exception) {
                        Log.e(TAG, "finish error", e)
                    }
                }
            } else {
                Log.d(TAG, "⚠️ No active lock activity")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unlock error", e)
        }
    }

    // ==========================================
    // ===== IS LOCKED? =====
    // ==========================================
    fun isLocked(): Boolean {
        val activity = currentActivity?.get()
        return activity != null && !activity.isFinishing
    }
}
