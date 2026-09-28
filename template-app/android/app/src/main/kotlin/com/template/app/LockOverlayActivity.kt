package com.template.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class LockOverlayActivity : Activity() {

    companion object {
        private const val TAG = "LockOverlayActivity"
        const val EXTRA_MESSAGE = "lock_message"
        const val EXTRA_PIN = "lock_pin"

        fun launch(context: Context, message: String, pin: String) {
            try {
                Log.d(TAG, "🚀 Launch lock overlay: msg=$message, pin=${pin.take(2)}***")
                val intent = Intent(context, LockOverlayActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                    putExtra(EXTRA_MESSAGE, message)
                    putExtra(EXTRA_PIN, pin)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "launch error", e)
            }
        }
    }

    private var correctPin = "1234"
    private var isLocked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "🔒 Lock overlay created")

        // Register ke manager biar bisa di-unlock dari luar
        LockOverlayManager.register(this)

        // ==========================================
        // ===== WINDOW FLAGS - FULL SCREEN KIOSK =====
        // ==========================================
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // Hide status bar + nav bar
        applyImmersiveMode()

        // ==========================================
        // ===== BUILD UI =====
        // ==========================================
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "YOUR PHONE IS LOCKED"
        correctPin = intent.getStringExtra(EXTRA_PIN) ?: "1234"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(64, 64, 64, 64)
        }

        // Icon lock
        val icon = TextView(this).apply {
            text = "🔒"
            textSize = 64f
            gravity = Gravity.CENTER
        }

        // Message
        val messageText = TextView(this).apply {
            text = message
            setTextColor(android.graphics.Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 32
            }
        }

        // Sub message
        val subText = TextView(this).apply {
            text = "Masukkan PIN untuk membuka"
            setTextColor(android.graphics.Color.GRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 16
            }
        }

        // PIN input
        val pinInput = EditText(this).apply {
            hint = "PIN"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 48
                leftMargin = 32
                rightMargin = 32
            }
            setPadding(32, 24, 32, 24)
        }

        // Unlock button
        val unlockBtn = Button(this).apply {
            text = "UNLOCK"
            textSize = 18f
            setBackgroundColor(android.graphics.Color.WHITE)
            setTextColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 24
                leftMargin = 32
                rightMargin = 32
            }
            setPadding(32, 32, 32, 32)

            setOnClickListener {
                val entered = pinInput.text.toString()
                if (entered == correctPin) {
                    Log.d(TAG, "🔓 PIN correct — unlocking")
                    unlockAndFinish()
                } else {
                    pinInput.error = "PIN salah"
                    pinInput.setText("")
                }
            }
        }

        // Build hierarchy
        root.addView(icon)
        root.addView(messageText)
        root.addView(subText)
        root.addView(pinInput)
        root.addView(unlockBtn)

        setContentView(root)
    }

    // ==========================================
    // ===== IMMERSIVE MODE =====
    // ==========================================
    private fun applyImmersiveMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.apply {
                    hide(android.view.WindowInsets.Type.statusBars())
                    hide(android.view.WindowInsets.Type.navigationBars())
                    systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "immersive error", e)
        }
    }

    // ==========================================
    // ===== UNLOCK =====
    // ==========================================
    private fun unlockAndFinish() {
        isLocked = false
        try {
            LockOverlayManager.unregister()
            finish()
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            Log.e(TAG, "finish error", e)
        }
    }

    // ==========================================
    // ===== BLOCK BACK BUTTON =====
    // ==========================================
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "🚫 Back button blocked")
        // Do nothing — user cannot escape
    }

    // ==========================================
    // ===== BLOCK VOLUME + POWER BUTTON =====
    // ==========================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU -> {
                Log.d(TAG, "🚫 Key blocked: $keyCode")
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MENU -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // ==========================================
    // ===== BLOCK USER LEAVE (Home Button) =====
    // ==========================================
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLocked) {
            Log.d(TAG, "⚠️ User tried to leave — returning to lock")
            // Re-launch lock overlay
            val intent = Intent(this, LockOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_MESSAGE, intent.getStringExtra(EXTRA_MESSAGE))
                putExtra(EXTRA_PIN, correctPin)
            }
            startActivity(intent)
        }
    }

    // ==========================================
    // ===== ON RESUME — re-apply immersive =====
    // ==========================================
    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ==========================================
    // ===== ON WINDOW FOCUS — re-apply =====
    // ==========================================
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    // ==========================================
    // ===== ON DESTROY =====
    // ==========================================
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🔓 Lock overlay destroyed")
        LockOverlayManager.unregister()
        isLocked = false
    }
}
