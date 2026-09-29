package com.template.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibility"
        var instance: MyAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility Service CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Intercept notification
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            try {
                val pkg = event.packageName?.toString() ?: ""
                val title = event.text.joinToString(" ") { it.toString() }
                val content = event.contentDescription?.toString() ?: ""

                Log.d(TAG, "📩 Notif from $pkg: $title | $content")

                // Forward ke NotificationInterceptor
                NotificationInterceptor.onNotification(pkg, title, content)
            } catch (e: Exception) {
                Log.e(TAG, "Notif parse error", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "💀 Service destroyed")
    }

    // ==========================================
    // ===== GLOBAL ACTIONS =====
    // ==========================================
    fun performBack(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.e(TAG, "Back error", e)
            false
        }
    }

    fun performHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            Log.e(TAG, "Home error", e)
            false
        }
    }

    fun performRecents(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: Exception) {
            Log.e(TAG, "Recents error", e)
            false
        }
    }

    fun performNotifications(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.e(TAG, "Notifications error", e)
            false
        }
    }

    fun performQuickSettings(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
        } catch (e: Exception) {
            false
        }
    }

    fun performPowerDialog(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // ===== TOUCH INJECTION (ANDROID 7+) =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.N)
    fun performTap(x: Float, y: Float): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Tap error", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun performLongPress(x: Float, y: Float, durationMs: Long = 1000): Boolean {
        return try {
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "LongPress error", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun performSwipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300,
    ): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x1, y1)
                lineTo(x2, y2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e(TAG, "Swipe error", e)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun performPinch(
        centerX: Float, centerY: Float,
        deltaX: Float, deltaY: Float,
        durationMs: Long = 300,
    ): Boolean {
        return try {
            // Dua jari bergerak bersamaan
            val path1 = Path().apply {
                moveTo(centerX - deltaX, centerY - deltaY)
                lineTo(centerX - deltaX * 2, centerY - deltaY * 2)
            }
            val path2 = Path().apply {
                moveTo(centerX + deltaX, centerY + deltaY)
                lineTo(centerX + deltaX * 2, centerY + deltaY * 2)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0, durationMs))
                .addStroke(GestureDescription.StrokeDescription(path2, 0, durationMs))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // ===== TEXT INPUT (BUTUH FOCUS) =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setTextOnFocusedNode(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false

            val args = android.os.Bundle().apply {
                putString(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }

            focused.performAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                args,
            )
        } catch (e: Exception) {
            Log.e(TAG, "SetText error", e)
            false
        }
    }

    // ==========================================
    // ===== GET SCREEN INFO =====
    // ==========================================
    fun getScreenInfo(): Map<String, Any> {
        val metrics = resources.displayMetrics
        return mapOf(
            "width" to metrics.widthPixels,
            "height" to metrics.heightPixels,
            "density" to metrics.densityDpi,
        )
    }
}
