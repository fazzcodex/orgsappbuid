package com.template.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationSet
import android.view.animation.BounceInterpolator
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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

    // ==========================================
    // ===== COLOR PALETTE (NEO-BRUTALISM) =====
    // ==========================================
    private val colorInk = Color.parseColor("#1A1A1A")
    private val colorBackground = Color.parseColor("#F5F0E8")
    private val colorSurface = Color.parseColor("#FFFFFF")
    private val colorAccent = Color.parseColor("#A8D5A8")
    private val colorPrimary = Color.parseColor("#7C9EF5")
    private val colorSecondary = Color.parseColor("#E8D4B8")
    private val colorDanger = Color.parseColor("#E8A5A5")
    private val colorShadow = Color.parseColor("#1A1A1A")

    private var correctPin = "1234"
    private var isLocked = true
    private var wrongAttempts = 0
    private lateinit var pinInput: EditText
    private lateinit var unlockBtn: Button
    private lateinit var errorText: TextView
    private lateinit var mainContainer: LinearLayout
    private var isKioskMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "🔒 Lock overlay created")

        LockOverlayManager.register(this)

        // ==========================================
        // ===== WINDOW FLAGS = FULL SCREEN KIOSK =====
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

        applyImmersiveMode()

        // ==========================================
        // ===== TRY KIOSK MODE (LOCK TASK) =====
        // ==========================================
        tryStartKioskMode()

        // ==========================================
        // ===== BUILD NEO-BRUTALISM UI =====
        // ==========================================
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: "YOUR PHONE IS LOCKED"
        correctPin = intent.getStringExtra(EXTRA_PIN) ?: "1234"

        buildNeoBrutalismUI(message)
        // ===== PLAY SOUND ALERT =====
val audioUrl = intent.getStringExtra("lock_audio_url") ?: ""
val audioVolume = intent.getFloatExtra("lock_audio_volume", 1.0f)
if (audioUrl.isNotEmpty()) {
    playLockSound(audioUrl, audioVolume)
}
    }

    // ==========================================
    // ===== NEO-BRUTALISM UI BUILDER =====
    // ==========================================
    private fun buildNeoBrutalismUI(message: String) {
        // ===== ROOT FRAME =====
        val root = FrameLayout(this).apply {
            setBackgroundColor(colorBackground)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        // ===== DECORATIVE BACKGROUND SHAPES =====
        addDecorativeShapes(root)

        // ===== MAIN CONTAINER =====
        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                setMargins(48, 48, 48, 48)
            }
            setPadding(32, 32, 32, 32)
        }

        // ==========================================
        // ===== LOCK ICON CARD =====
        // ==========================================
        val iconCard = createNeoCard(
            backgroundColor = colorPrimary,
            paddingDp = 24,
            shadowOffsetDp = 6,
        ).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(140),
                dpToPx(140),
            ).apply {
                gravity = Gravity.CENTER
            }
        }

        val lockIcon = TextView(this).apply {
            text = "🔒"
            textSize = 64f
            gravity = Gravity.CENTER
        }
        iconCard.addView(lockIcon)

        // ==========================================
        // ===== MESSAGE CARD =====
        // ==========================================
        val messageCard = createNeoCard(
            backgroundColor = colorSurface,
            paddingDp = 20,
            shadowOffsetDp = 5,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 32
            }
        }

        val messageText = TextView(this).apply {
            text = message
            setTextColor(colorInk)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        }
        messageCard.addView(messageText)

        // ==========================================
        // ===== SUB MESSAGE =====
        // ==========================================
        val subText = TextView(this).apply {
            text = "Masukkan PIN untuk membuka"
            setTextColor(colorInk)
            textSize = 13f
            alpha = 0.6f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 16
            }
        }

        // ==========================================
        // ===== PIN INPUT CARD =====
        // ==========================================
        val pinCard = createNeoCard(
            backgroundColor = colorSecondary,
            paddingDp = 8,
            shadowOffsetDp = 4,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 48
            }
        }

        pinInput = EditText(this).apply {
            hint = "• • • •"
            setHintTextColor(colorInk)
            setTextColor(colorInk)
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            inputType = InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            letterSpacing = 0.5f
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            // Enter key untuk submit
            setOnEditorActionListener { _, _, _ ->
                attemptUnlock()
                true
            }
        }
        pinCard.addView(pinInput)

        // ==========================================
        // ===== ERROR TEXT =====
        // ==========================================
        errorText = TextView(this).apply {
            text = ""
            setTextColor(colorDanger)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 12
            }
            visibility = View.GONE
        }

        // ==========================================
        // ===== UNLOCK BUTTON CARD =====
        // ==========================================
        val unlockCard = createNeoCard(
            backgroundColor = colorAccent,
            paddingDp = 4,
            shadowOffsetDp = 4,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = 24
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { attemptUnlock() }
        }

        unlockBtn = Button(this).apply {
            text = "UNLOCK"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(colorInk)
            letterSpacing = 0.1f
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setPadding(32, 24, 32, 24)
            // Button tidak handle klik — parent card yang handle
            isClickable = false
        }
        unlockCard.addView(unlockBtn)

        // ==========================================
        // ===== FOOTER INFO =====
        // ==========================================
        val footerCard = createNeoCard(
            backgroundColor = colorSurface,
            paddingDp = 12,
            shadowOffsetDp = 3,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                topMargin = 32
            }
        }

        val footerText = TextView(this).apply {
            text = if (isKioskMode) "🔐 KIOSK MODE — HUBUNGI ADMIN" else "🔒 DEVICE TERKUNCI"
            setTextColor(colorInk)
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
        }
        footerCard.addView(footerText)

        // ==========================================
        // ===== ASSEMBLE =====
        // ==========================================
        mainContainer.addView(iconCard)
        mainContainer.addView(messageCard)
        mainContainer.addView(subText)
        mainContainer.addView(pinCard)
        mainContainer.addView(errorText)
        mainContainer.addView(unlockCard)
        mainContainer.addView(footerCard)

        root.addView(mainContainer)
        setContentView(root)

        // ==========================================
        // ===== START ANIMATIONS =====
        // ==========================================
        animateEntrance()

        // Auto focus PIN input
        Handler(Looper.getMainLooper()).postDelayed({
            pinInput.requestFocus()
        }, 600)
    }

    // ==========================================
    // ===== NEO-BRUTALISM CARD HELPER =====
    // ==========================================
    private fun createNeoCard(
        backgroundColor: Int,
        paddingDp: Int = 12,
        shadowOffsetDp: Int = 4,
        cornerRadiusDp: Int = 16,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                dpToPx(paddingDp),
                dpToPx(paddingDp),
                dpToPx(paddingDp),
                dpToPx(paddingDp),
            )
        }

        // Create shadow layer (background black box offset)
        val background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = dpToPx(cornerRadiusDp).toFloat()
            setStroke(dpToPx(2), colorInk)
        }

        card.background = background

        // Add shadow effect via elevation
        card.elevation = dpToPx(shadowOffsetDp).toFloat()

        return card
    }

    // ==========================================
    // ===== DECORATIVE BACKGROUND SHAPES =====
    // ==========================================
    private fun addDecorativeShapes(root: FrameLayout) {
        // Top-left circle
        val circle1 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(120),
                dpToPx(120),
            ).apply {
                setMargins(-30, -30, 0, 0)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorPrimary)
                setStroke(dpToPx(2), colorInk)
            }
            alpha = 0.3f
        }

        // Bottom-right circle
        val circle2 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(160),
                dpToPx(160),
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, -40, -40)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorAccent)
                setStroke(dpToPx(2), colorInk)
            }
            alpha = 0.3f
        }

        // Top-right square
        val square1 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(80),
                dpToPx(80),
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(0, 40, -20, 0)
            }
            background = GradientDrawable().apply {
                setColor(colorSecondary)
                cornerRadius = dpToPx(12).toFloat()
                setStroke(dpToPx(2), colorInk)
            }
            alpha = 0.4f
            rotation = 15f
        }

        root.addView(circle1)
        root.addView(circle2)
        root.addView(square1)
    }

    // ==========================================
    // ===== ANIMATIONS =====
    // ==========================================
    private fun animateEntrance() {
        // Fade in + slide up main container
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 400
        }

        val slideUp = TranslateAnimation(
            0f, 0f,
            dpToPx(50).toFloat(), 0f,
        ).apply {
            duration = 500
            interpolator = BounceInterpolator()
        }

        val scaleIn = ScaleAnimation(
            0.8f, 1f, 0.8f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 500
            interpolator = BounceInterpolator()
        }

        val set = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(slideUp)
        }

        mainContainer.startAnimation(set)
    }

    // ==========================================
    // ===== ATTEMPT UNLOCK =====
    // ==========================================
    private fun attemptUnlock() {
        val entered = pinInput.text.toString()

        if (entered.isEmpty()) {
            showError("PIN tidak boleh kosong")
            shakeView(pinInput)
            return
        }

        if (entered == correctPin) {
            Log.d(TAG, "🔓 PIN correct — unlocking")
            hideError()
            animateUnlockSuccess()
        } else {
            wrongAttempts++
            Log.d(TAG, "❌ Wrong PIN attempt #$wrongAttempts")

            when {
                wrongAttempts >= 5 -> {
                    showError("Terlalu banyak percobaan. Tunggu 30 detik.")
                    pinInput.isEnabled = false
                    Handler(Looper.getMainLooper()).postDelayed({
                        wrongAttempts = 0
                        pinInput.isEnabled = true
                        hideError()
                        pinInput.setText("")
                    }, 30000)
                }
                else -> {
                    showError("PIN salah. Sisa: ${5 - wrongAttempts} percobaan")
                }
            }

            shakeView(pinInput)
            pinInput.setText("")
        }
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE

        // Fade in
        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 200
        }
        errorText.startAnimation(fadeIn)
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun shakeView(view: View) {
        val shake = TranslateAnimation(
            0f, dpToPx(10).toFloat(),
            0f, 0f,
        ).apply {
            duration = 50
            repeatCount = 6
            repeatMode = Animation.REVERSE
        }
        view.startAnimation(shake)
    }

    private fun animateUnlockSuccess() {
        // Scale down + fade out
        val scaleDown = ScaleAnimation(
            1f, 0.8f, 1f, 0.8f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 300
        }

        val fadeOut = AlphaAnimation(1f, 0f).apply {
            duration = 300
        }

        val set = AnimationSet(true).apply {
            addAnimation(scaleDown)
            addAnimation(fadeOut)
            fillAfter = true
        }

        set.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) {}
            override fun onAnimationEnd(animation: Animation?) {
                unlockAndFinish()
            }
            override fun onAnimationRepeat(animation: Animation?) {}
        })

        mainContainer.startAnimation(set)
    }

    // ==========================================
    // ===== KIOSK MODE (LOCK TASK) =====
    // ==========================================
    private fun tryStartKioskMode() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

            if (dpm.isDeviceOwnerApp(packageName)) {
                Log.d(TAG, "🎯 Device Owner detected — enabling kiosk mode")

                // Set lock task packages — hanya app ini yang boleh
                dpm.setLockTaskPackages(admin, arrayOf(packageName))

                // Enable lock task mode
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    startLockTask()
                    isKioskMode = true
                    Log.d(TAG, "✅ Kiosk mode enabled")
                }
            } else {
                Log.d(TAG, "⚠️ Not device owner — lock task mode unavailable")
                Log.d(TAG, "   For full kiosk, run: adb shell dpm set-device-owner " +
                        "$packageName/.MyDeviceAdminReceiver")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kiosk mode error", e)
        }
    }
// ==========================================
// ===== SOUND ALERT =====
// ==========================================
private var soundPlayer: android.media.MediaPlayer? = null
private var soundVolume = 1.0f

private fun playLockSound(audioUrl: String?, volume: Float) {
    if (audioUrl.isNullOrEmpty()) {
        Log.d(TAG, "🔇 No audio URL — silent")
        return
    }

    soundVolume = volume.coerceIn(0f, 1f)
    Log.d(TAG, "🔊 Playing lock sound: $audioUrl (vol=$soundVolume)")

    try {
        soundPlayer?.release()
        soundPlayer = android.media.MediaPlayer().apply {
            setDataSource(audioUrl)
            setVolume(soundVolume, soundVolume)
            isLooping = false
            setOnPreparedListener { start() }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Sound error: what=$what extra=$extra")
                true
            }
            prepareAsync()
        }
    } catch (e: Exception) {
        Log.e(TAG, "playLockSound error", e)
    }
}

private fun stopLockSound() {
    try {
        soundPlayer?.stop()
        soundPlayer?.release()
        soundPlayer = null
    } catch (e: Exception) {}
}
    private fun stopKioskMode() {
        if (isKioskMode) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    stopLockTask()
                    isKioskMode = false
                    Log.d(TAG, "🔓 Kiosk mode disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stop kiosk error", e)
            }
        }
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
            stopKioskMode()
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
        shakeView(mainContainer)
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
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH -> {
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
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_APP_SWITCH -> true
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
    // ===== ON RESUME =====
    // ==========================================
    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ==========================================
    // ===== ON WINDOW FOCUS =====
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
        stopLockSound()
        super.onDestroy()
        Log.d(TAG, "🔓 Lock overlay destroyed")
        stopKioskMode()
        LockOverlayManager.unregister()
        isLocked = false
       
    }

    // ==========================================
    // ===== UTILS =====
    // ==========================================
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }
}
