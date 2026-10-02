package com.template.app

import android.app.Activity
import android.util.Log
import java.lang.ref.WeakReference

object LockOverlayManager {
    private const val TAG = "LockOverlayManager"

    private var currentActivity: WeakReference<Activity>? = null
    private var lockActive: Boolean = false

    // ==========================================
    // ===== REGISTER =====
    // ==========================================
    fun register(activity: Activity) {
        currentActivity = WeakReference(activity)
        lockActive = true
        Log.d(TAG, "✅ Registered: ${activity.javaClass.simpleName}")
    }

    // ==========================================
    // ===== UNREGISTER =====
    // ==========================================
    fun unregister() {
        currentActivity = null
        lockActive = false
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
        } finally {
            currentActivity = null
            lockActive = false
        }
    }

    // ==========================================
    // ===== IS LOCKED? =====
    // ==========================================
    fun isLocked(): Boolean {
        val activity = currentActivity?.get()
        return activity != null && !activity.isFinishing
    }

    // ==========================================
    // ===== HAS ACTIVE LOCK? (untuk MonitorService) =====
    // ==========================================
    fun hasActiveLock(): Boolean {
        val activity = currentActivity?.get()
        val isActive = lockActive && activity != null && !activity.isFinishing
        return isActive
    }

    // ==========================================
    // ===== GET ACTIVE ACTIVITY =====
    // ==========================================
    fun getActiveActivity(): Activity? {
        return currentActivity?.get()
    }
}
