package com.template.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object CameraStreamService {

    private const val TAG = "CameraStreamService"

    // ==========================================
    // ===== STATE =====
    // ==========================================
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var isStreaming = false
    private var currentFacing = CameraCharacteristics.LENS_FACING_BACK
    private var frameIntervalMs: Long = 200L  // 5 fps default

    // Callback ke ConnectionService / NativeCommandHandler
    var onFrame: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // ==========================================
    // ===== START STREAM =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun start(
        context: Context,
        facing: String = "back",
        intervalMs: Long = 200L,
        frameCallback: (String) -> Unit,
        errorCallback: (String) -> Unit,
    ): Boolean {
        if (isStreaming) {
            Log.d(TAG, "⏸️ Already streaming — stop first")
            stop(context)
        }

        // Cek permission
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Camera permission not granted")
            errorCallback("camera_permission_denied")
            return false
        }

        // Set state
        onFrame = frameCallback
        onError = errorCallback
        frameIntervalMs = intervalMs.coerceIn(100L, 5000L)
        currentFacing = if (facing == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        // Init camera thread
        cameraThread = HandlerThread("CameraStreamThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        return try {
            openCamera(context)
            Log.d(TAG, "📷 Stream starting (facing=$facing, interval=${frameIntervalMs}ms)")
            isStreaming = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
            errorCallback(e.message ?: "start_error")
            isStreaming = false
            false
        }
    }

    // ==========================================
    // ===== STOP STREAM =====
    // ==========================================
    fun stop(context: Context) {
        try {
            Log.d(TAG, "🛑 Stopping camera stream")

            isStreaming = false

            try {
                captureSession?.stopRepeating()
                captureSession?.close()
            } catch (e: Exception) {}
            captureSession = null

            try {
                cameraDevice?.close()
            } catch (e: Exception) {}
            cameraDevice = null

            try {
                imageReader?.close()
            } catch (e: Exception) {}
            imageReader = null

            try {
                cameraThread?.quitSafely()
            } catch (e: Exception) {}
            cameraThread = null
            cameraHandler = null

            onFrame = null
            onError = null

            Log.d(TAG, "✅ Stream stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        }
    }

    val isActive: Boolean
        get() = isStreaming

    // ==========================================
    // ===== TAKE SINGLE PHOTO =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun takePhoto(
        context: Context,
        facing: String = "back",
        callback: (String?, String?) -> Unit,
    ) {
        // Callback: (base64, error)

        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            callback(null, "camera_permission_denied")
            return
        }

        val camFacing = if (facing == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        // Buat thread baru untuk capture
        val thread = HandlerThread("TakePhotoThread").apply { start() }
        val handler = Handler(thread.looper)

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                    as CameraManager

            // Cari camera ID
            var cameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == camFacing) {
                    cameraId = id
                    break
                }
            }

            if (cameraId == null) {
                callback(null, "camera_not_found")
                thread.quitSafely()
                return
            }

            // Setup image reader
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)?.firstOrNull()
                ?: android.util.Size(640, 480)

            val reader = ImageReader.newInstance(
                size.width, size.height, ImageFormat.JPEG, 2,
            )

            reader.setOnImageAvailableListener({ r ->
                try {
                    val image = r.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        Log.d(TAG, "📸 Photo: ${bytes.size} bytes")
                        callback(base64, null)
                    } else {
                        callback(null, "no_image")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "photo process error", e)
                    callback(null, e.message)
                } finally {
                    try { r.close() } catch (e: Exception) {}
                    thread.quitSafely()
                }
            }, handler)

            // Open camera
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                callback(null, "camera_permission_denied")
                thread.quitSafely()
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        val surface = reader.surface
                        val builder = camera.createCaptureRequest(
                            CameraDevice.TEMPLATE_STILL_CAPTURE,
                        )
                        builder.addTarget(surface)

                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(session: CameraCaptureSession) {
                                    try {
                                        session.capture(
                                            builder.build(),
                                            null,
                                            handler,
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "capture error", e)
                                        callback(null, e.message)
                                        camera.close()
                                    }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    callback(null, "session_failed")
                                    camera.close()
                                }
                            },
                            handler,
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "capture setup error", e)
                        callback(null, e.message)
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    callback(null, "camera_disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    callback(null, "camera_error_$error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "takePhoto error", e)
            callback(null, e.message)
            thread.quitSafely()
        }
    }

    // ==========================================
    // ===== OPEN CAMERA =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun openCamera(context: Context) {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                    as CameraManager

            // Cari camera ID yang sesuai facing
            var cameraId: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
                if (lensFacing == currentFacing) {
                    cameraId = id
                    break
                }
            }

            if (cameraId == null) {
                Log.e(TAG, "No camera found for facing $currentFacing")
                onError?.invoke("camera_not_found")
                return
            }

            // Setup image reader (JPEG untuk stream)
            imageReader = ImageReader.newInstance(
                640, 480, ImageFormat.JPEG, 2,
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                try {
                    if (!isStreaming) return@setOnImageAvailableListener

                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()

                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        onFrame?.invoke(base64)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "frame error", e)
                }
            }, cameraHandler)

            // Buka camera
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                onError?.invoke("camera_permission_denied")
                return
            }

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    Log.d(TAG, "✅ Camera opened: $cameraId")
                    cameraDevice = camera
                    startRepeatingCapture()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "⚠️ Camera disconnected")
                    camera.close()
                    cameraDevice = null
                    onError?.invoke("camera_disconnected")
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "❌ Camera error: $error")
                    camera.close()
                    cameraDevice = null
                    onError?.invoke("camera_error_$error")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera error", e)
            onError?.invoke(e.message ?: "open_camera_error")
        }
    }

    // ==========================================
    // ===== START REPEATING CAPTURE =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startRepeatingCapture() {
        try {
            val surface = imageReader?.surface ?: return
            val camera = cameraDevice ?: return

            val builder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW,
            )
            builder.addTarget(surface)
            builder.set(
                CaptureRequest.CONTROL_MODE,
                CameraMetadata.CONTROL_MODE_AUTO,
            )

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "✅ Session configured")
                        captureSession = session

                        try {
                            session.setRepeatingRequest(
                                builder.build(),
                                null,
                                cameraHandler,
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "setRepeatingRequest error", e)
                            onError?.invoke(e.message ?: "repeating_error")
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "❌ Session configure failed")
                        onError?.invoke("session_configure_failed")
                    }
                },
                cameraHandler,
            )
        } catch (e: Exception) {
            Log.e(TAG, "startRepeatingCapture error", e)
            onError?.invoke(e.message ?: "repeating_error")
        }
    }

    // ==========================================
    // ===== SWITCH CAMERA =====
    // ==========================================
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun switchCamera(context: Context): Boolean {
        return try {
            val newFacing = if (currentFacing == CameraCharacteristics.LENS_FACING_BACK) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

            Log.d(TAG, "🔄 Switching camera to $newFacing")

            val callback = onFrame
            val errCallback = onError

            // Stop current stream
            stop(context)

            // Restart dengan facing baru
            if (callback != null && errCallback != null) {
                start(
                    context = context,
                    facing = if (newFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                        "front"
                    } else {
                        "back"
                    },
                    intervalMs = frameIntervalMs,
                    frameCallback = callback,
                    errorCallback = errCallback,
                )
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera error", e)
            false
        }
    }

    // ==========================================
    // ===== INFO =====
    // ==========================================
    fun getInfo(): JSONObject {
        return JSONObject().apply {
            put("streaming", isStreaming)
            put("facing", if (currentFacing == CameraCharacteristics.LENS_FACING_FRONT) {
                "front"
            } else {
                "back"
            })
            put("interval", frameIntervalMs)
        }
    }
}
