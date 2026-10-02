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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import android.view.animation.TranslateAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class LockOverlayActivity : Activity() {

    companion object {
        private const val TAG = "LockOverlayActivity"
        const val EXTRA_MESSAGE = "lock_message"
        const val EXTRA_PIN = "lock_pin"
        const val EXTRA_AUDIO_URL = "lock_audio_url"
        const val EXTRA_AUDIO_VOLUME = "lock_audio_volume"

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
    private val maxAttempts = 5
    private val pinLength = 4

    private val currentPin = StringBuilder()

    // ==========================================
    // ===== FIX: SIMPAN MESSAGE DI FIELD =====
    // ===== biar tidak error saat onUserLeaveHint =====
    // ==========================================
    private var currentMessage: String = "PERANGKAT TERKUNCI"
    private var currentAudioUrl: String = ""
    private var currentAudioVolume: Float = 1.0f

    // UI refs
    private lateinit var dotsContainer: LinearLayout
    private lateinit var errorText: TextView
    private lateinit var attemptsText: TextView
    private lateinit var mainContainer: LinearLayout
    private lateinit var numpadContainer: LinearLayout
    private val dotViews = mutableListOf<View>()

    private var isKioskMode = false
    private var isInputLocked = false

    // Sound
    private var soundPlayer: android.media.MediaPlayer? = null
    private var soundVolume = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "🔒 Lock overlay created")

        LockOverlayManager.register(this)

        // ==========================================
        // ===== WINDOW FLAGS — FULL SCREEN KIOSK =====
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
        tryStartKioskMode()

        // ==========================================
        // ===== FIX: SIMPAN DI FIELD =====
        // ==========================================
        currentMessage = intent.getStringExtra(EXTRA_MESSAGE) ?: "PERANGKAT TERKUNCI"
        correctPin = intent.getStringExtra(EXTRA_PIN) ?: "1234"
        currentAudioUrl = intent.getStringExtra(EXTRA_AUDIO_URL) ?: ""
        currentAudioVolume = intent.getFloatExtra(EXTRA_AUDIO_VOLUME, 1.0f)

        buildNeoBrutalismUI(currentMessage)

        // ===== PLAY SOUND ALERT =====
        if (currentAudioUrl.isNotEmpty()) {
            playLockSound(currentAudioUrl, currentAudioVolume)
        }
    }

    // ==========================================
    // ===== MAIN UI BUILDER =====
    // ==========================================
    private fun buildNeoBrutalismUI(message: String) {
        val root = FrameLayout(this).apply {
            setBackgroundColor(colorBackground)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        addDecorativeShapes(root)

        mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                setMargins(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
            }
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(16))
        }

        // ==========================================
        // ===== ICON CARD =====
        // ==========================================
        val iconCard = createNeoCard(
            backgroundColor = colorPrimary,
            paddingDp = 16,
            shadowOffsetDp = 5,
            cornerRadiusDp = 20,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(110),
                dpToPx(110),
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val lockIcon = TextView(this).apply {
            text = "🔒"
            textSize = 48f
            gravity = Gravity.CENTER
        }
        iconCard.addView(lockIcon)

        // ==========================================
        // ===== MESSAGE CARD =====
        // ==========================================
        val messageCard = createNeoCard(
            backgroundColor = colorSurface,
            paddingDp = 16,
            shadowOffsetDp = 4,
            cornerRadiusDp = 14,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(20)
            }
        }

        val messageText = TextView(this).apply {
            text = message
            setTextColor(colorInk)
            textSize = 18f
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
            textSize = 12f
            alpha = 0.6f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(14)
            }
        }

        // ==========================================
        // ===== PIN DOTS =====
        // ==========================================
        dotsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(24)
            }
        }

        for (i in 0 until pinLength) {
            val dotWrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(48),
                    dpToPx(56),
                ).apply {
                    marginStart = dpToPx(6)
                    marginEnd = dpToPx(6)
                }
            }

            val dot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    dpToPx(38),
                    dpToPx(38),
                ).apply {
                    gravity = Gravity.CENTER
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorSurface)
                    setStroke(dpToPx(3), colorInk)
                }
                elevation = dpToPx(3).toFloat()
            }

            dotWrapper.addView(dot)
            dotsContainer.addView(dotWrapper)
            dotViews.add(dot)
        }

        // ==========================================
        // ===== ERROR TEXT =====
        // ==========================================
        errorText = TextView(this).apply {
            text = ""
            setTextColor(colorInk)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(12)
            }
            visibility = View.GONE
            setBackgroundColor(colorDanger)
            setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
        }

        // ==========================================
        // ===== ATTEMPTS TEXT =====
        // ==========================================
        attemptsText = TextView(this).apply {
            text = "Percobaan tersisa: $maxAttempts"
            setTextColor(colorInk)
            textSize = 11f
            alpha = 0.7f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(8)
            }
        }

        // ==========================================
        // ===== NUMPAD =====
        // ==========================================
        numpadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dpToPx(24)
            }
        }

        val row1 = createNumpadRow("1", "2", "3")
        val row2 = createNumpadRow("4", "5", "6")
        val row3 = createNumpadRow("7", "8", "9")
        val row4 = createNumpadRow("⌫", "0", "✓", isBackspace = true, isConfirm = true)

        numpadContainer.addView(row1)
        numpadContainer.addView(row2)
        numpadContainer.addView(row3)
        numpadContainer.addView(row4)

        // ==========================================
        // ===== FOOTER INFO =====
        // ==========================================
        val footerCard = createNeoCard(
            backgroundColor = colorSurface,
            paddingDp = 10,
            shadowOffsetDp = 3,
            cornerRadiusDp = 10,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dpToPx(20)
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
        mainContainer.addView(dotsContainer)
        mainContainer.addView(errorText)
        mainContainer.addView(attemptsText)
        mainContainer.addView(numpadContainer)
        mainContainer.addView(footerCard)

        root.addView(mainContainer)
        setContentView(root)

        animateEntrance()
    }

    // ==========================================
    // ===== NUMPAD ROW BUILDER =====
    // ==========================================
    private fun createNumpadRow(
        left: String,
        mid: String,
        right: String,
        isBackspace: Boolean = false,
        isConfirm: Boolean = false,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(createNumpadButton(left, isBackspace = isBackspace))
        row.addView(createNumpadButton(mid))
        row.addView(createNumpadButton(right, isConfirm = isConfirm))

        return row
    }

    private fun createNumpadButton(
        label: String,
        isBackspace: Boolean = false,
        isConfirm: Boolean = false,
    ): View {
        val bgColor = when {
            isConfirm -> colorAccent
            isBackspace -> colorSecondary
            else -> colorSurface
        }
        val textSize = if (isConfirm || isBackspace) 22f else 26f

        val wrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply {
                marginStart = dpToPx(5)
                marginEnd = dpToPx(5)
                topMargin = dpToPx(5)
                bottomMargin = dpToPx(5)
            }
        }

        val shadowView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ).apply {
                topMargin = dpToPx(4)
                leftMargin = dpToPx(4)
            }
            background = GradientDrawable().apply {
                setColor(colorInk)
                cornerRadius = dpToPx(14).toFloat()
            }
        }

        val button = TextView(this).apply {
            text = label
            setTextColor(colorInk)
            textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(18), 0, dpToPx(18))
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(2), colorInk)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dpToPx(4)
                rightMargin = dpToPx(4)
            }
            isClickable = true
            isFocusable = true
        }

        button.setOnClickListener {
            if (isInputLocked) return@setOnClickListener

            performHaptic()
            animateButtonPress(button)

            when {
                isConfirm -> attemptUnlock()
                isBackspace -> {
                    if (currentPin.isNotEmpty()) {
                        currentPin.deleteCharAt(currentPin.length - 1)
                        updateDots()
                    }
                }
                else -> {
                    if (currentPin.length < pinLength) {
                        currentPin.append(label)
                        updateDots()

                        if (currentPin.length == pinLength) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (currentPin.length == pinLength) {
                                    attemptUnlock()
                                }
                            }, 200)
                        }
                    }
                }
            }
        }

        wrapper.addView(shadowView)
        wrapper.addView(button)

        return wrapper
    }

    // ==========================================
    // ===== ANIMASI TOMBOL =====
    // ==========================================
    private fun animateButtonPress(view: View) {
        val scaleDown = ScaleAnimation(
            1f, 0.92f, 1f, 0.92f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 80
        }

        val scaleUp = ScaleAnimation(
            0.92f, 1f, 0.92f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f,
        ).apply {
            duration = 120
            interpolator = OvershootInterpolator()
        }

        val set = AnimationSet(true).apply {
            addAnimation(scaleDown)
            addAnimation(scaleUp)
        }

        view.startAnimation(set)
    }

    private fun performHaptic() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                        as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        15,
                        VibrationEffect.DEFAULT_AMPLITUDE,
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(15)
            }
        } catch (_: Exception) {}
    }

    // ==========================================
    // ===== UPDATE DOTS =====
    // ==========================================
    private fun updateDots() {
        for (i in 0 until pinLength) {
            val dot = dotViews[i]
            val isFilled = i < currentPin.length

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isFilled) colorPrimary else colorSurface)
                setStroke(dpToPx(3), colorInk)
            }
            dot.background = bg

            if (isFilled) {
                val pop = ScaleAnimation(
                    0.6f, 1.15f, 0.6f, 1.15f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                ).apply {
                    duration = 180
                    interpolator = OvershootInterpolator()
                }
                val popBack = ScaleAnimation(
                    1.15f, 1f, 1.15f, 1f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                    Animation.RELATIVE_TO_SELF, 0.5f,
                ).apply {
                    duration = 100
                    startOffset = 180
                }
                val set = AnimationSet(true).apply {
                    addAnimation(pop)
                    addAnimation(popBack)
                }
                dot.startAnimation(set)
            }
        }
    }

    // ==========================================
    // ===== ATTEMPT UNLOCK =====
    // ==========================================
    private fun attemptUnlock() {
        val entered = currentPin.toString()

        if (entered.isEmpty()) {
            showError("PIN tidak boleh kosong")
            shakeDots()
            return
        }

        if (entered.length < pinLength) {
            showError("PIN harus $pinLength digit")
            shakeDots()
            return
        }

        if (entered == correctPin) {
            Log.d(TAG, "🔓 PIN correct — unlocking")
            hideError()
            performHaptic()
            animateUnlockSuccess()
        } else {
            wrongAttempts++
            Log.d(TAG, "❌ Wrong PIN attempt #$wrongAttempts")
            performHaptic()

            val remaining = maxAttempts - wrongAttempts

            when {
                remaining <= 0 -> {
                    isInputLocked = true
                    showError("⛔ Terlalu banyak percobaan. Tunggu 30 detik.")
                    attemptsText.text = "Terkunci sementara"

                    Handler(Looper.getMainLooper()).postDelayed({
                        wrongAttempts = 0
                        isInputLocked = false
                        currentPin.clear()
                        updateDots()
                        hideError()
                        updateAttemptsText()
                    }, 30000)
                }
                else -> {
                    showError("❌ PIN salah. Sisa: $remaining percobaan")
                    updateAttemptsText()
                }
            }

            shakeDots()
            currentPin.clear()
            updateDots()
        }
    }

    private fun updateAttemptsText() {
        val remaining = maxAttempts - wrongAttempts
        attemptsText.text = "Percobaan tersisa: $remaining"
        attemptsText.setTextColor(if (remaining <= 2) colorDanger else colorInk)
    }

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE

        val fadeIn = AlphaAnimation(0f, 1f).apply {
            duration = 200
        }
        errorText.startAnimation(fadeIn)
    }

    private fun hideError() {
        errorText.visibility = View.GONE
    }

    private fun shakeDots() {
        for (dot in dotViews) {
            val shake = TranslateAnimation(
                -8f, 8f, 0f, 0f,
            ).apply {
                duration = 50
                repeatCount = 5
                repeatMode = Animation.REVERSE
            }
            dot.startAnimation(shake)
        }
    }

    private fun shakeView(view: View) {
        val shake = TranslateAnimation(
            -8f, 8f, 0f, 0f,
        ).apply {
            duration = 50
            repeatCount = 5
            repeatMode = Animation.REVERSE
        }
        view.startAnimation(shake)
    }

    private fun animateUnlockSuccess() {
        val scaleDown = ScaleAnimation(
            1f, 0.85f, 1f, 0.85f,
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
    // ===== NEO CARD HELPER =====
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

        card.background = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = dpToPx(cornerRadiusDp).toFloat()
            setStroke(dpToPx(2), colorInk)
        }

        card.elevation = dpToPx(shadowOffsetDp).toFloat()

        return card
    }

    // ==========================================
    // ===== DECORATIVE SHAPES =====
    // ==========================================
    private fun addDecorativeShapes(root: FrameLayout) {
        val circle1 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(120), dpToPx(120),
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

        val circle2 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(160), dpToPx(160),
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

        val square1 = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                dpToPx(80), dpToPx(80),
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
    // ===== ENTRANCE ANIMATION =====
    // ==========================================
    private fun animateEntrance() {
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

        val set = AnimationSet(true).apply {
            addAnimation(fadeIn)
            addAnimation(slideUp)
        }

        mainContainer.startAnimation(set)
    }

    // ==========================================
    // ===== SOUND =====
    // ==========================================
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
        } catch (_: Exception) {}
    }

    // ==========================================
    // ===== KIOSK MODE =====
    // ==========================================
    private fun tryStartKioskMode() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

            if (dpm.isDeviceOwnerApp(packageName)) {
                Log.d(TAG, "🎯 Device Owner — enabling kiosk mode")

                dpm.setLockTaskPackages(admin, arrayOf(packageName))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    startLockTask()
                    isKioskMode = true
                    Log.d(TAG, "✅ Kiosk mode enabled")
                }
            } else {
                Log.d(TAG, "⚠️ Not device owner — lock task unavailable")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kiosk mode error", e)
        }
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
        shakeView(mainContainer)
    }

    // ==========================================
    // ===== BLOCK HARDWARE KEYS =====
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
    // ===== FIX: onUserLeaveHint (baris 460) =====
    // ===== Sebelumnya error "Val cannot be reassigned" =====
    // ===== karena `intent` di-reassign dalam apply{} =====
    // ==========================================
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (isLocked) {
            Log.d(TAG, "⚠️ User tried to leave — returning to lock")
            // ✅ Pakai `currentMessage` field, bukan `intent.getStringExtra()`
            val newIntent = Intent(this, LockOverlayActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                putExtra(EXTRA_MESSAGE, currentMessage)
                putExtra(EXTRA_PIN, correctPin)
                putExtra(EXTRA_AUDIO_URL, currentAudioUrl)
                putExtra(EXTRA_AUDIO_VOLUME, currentAudioVolume)
            }
            startActivity(newIntent)
        }
    }

    // ==========================================
    // ===== LIFECYCLE =====
    // ==========================================
    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

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
