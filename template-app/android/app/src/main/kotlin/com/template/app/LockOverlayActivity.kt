package com.template.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.util.Log

class LockOverlayActivity : Activity() {

    companion object {
        const val EXTRA_MESSAGE = "lock_message"
        const val EXTRA_PIN = "lock_pin"

        fun launch(context: Context, message: String, pin: String) {
            try {
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
                Log.e("LockOverlay", "launch error", e)
            }
        }
    }

    private var pin = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("LockOverlay", "🔒 Lock overlay opened")

        // Full screen, keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        // Hide status bar & nav bar
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }

        // Build UI programmatically
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.BLACK)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(48, 48, 48, 48)
        }

        val messageText = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_MESSAGE) ?: "YOUR PHONE IS LOCKED"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
        }

        val pinInput = EditText(this).apply {
            hint = "Enter PIN to unlock"
            setHintTextColor(android.graphics.Color.GRAY)
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 48
            }
        }

        val unlockBtn = Button(this).apply {
            text = "UNLOCK"
            setBackgroundColor(android.graphics.Color.WHITE)
            setTextColor(android.graphics.Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 24
            }
            setOnClickListener {
                val entered = pinInput.text.toString()
                if (entered == pin) {
                    Log.d("LockOverlay", "🔓 PIN correct")
                    finish()
                    overridePendingTransition(0, 0)
                } else {
                    pinInput.error = "Wrong PIN"
                    pinInput.setText("")
                }
            }
        }

        root.addView(messageText)
        root.addView(pinInput)
        root.addView(unlockBtn)

        setContentView(root)

        pin = intent.getStringExtra(EXTRA_PIN) ?: "1234"
    }

    // Blokir tombol back
    override fun onBackPressed() {
        // Do nothing — user tidak bisa keluar
        Log.d("LockOverlay", "🚫 Back button blocked")
    }

    // Blokir tombol volume
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_POWER -> true  // block
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // Blokir home button (butuh Device Owner)
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Kalau user coba keluar (home), kembali ke lock
        if (isLocked) {
            val intent = Intent(this, LockOverlayActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(intent)
        }
    }

    private var isLocked = true

    override fun onDestroy() {
        super.onDestroy()
        isLocked = false
    }
}
