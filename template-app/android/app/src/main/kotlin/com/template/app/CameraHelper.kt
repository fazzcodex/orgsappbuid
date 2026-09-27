package com.template.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraHelper(private val context: Context, private val lifecycleOwner: LifecycleOwner) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var currentFacing = CameraSelector.LENS_FACING_BACK

    fun init(onReady: () -> Unit, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                cameraProvider = future.get()
                onReady()
            } catch (e: Exception) {
                Log.e("CameraHelper", "Init error: ${e.message}", e)
                onError(e.message ?: "init failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Capture photo → return Base64 JPEG.
     */
    fun takePhoto(
        facing: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        val provider = cameraProvider
        if (provider == null) {
            onError("Camera provider belum siap")
            return
        }

        try {
            currentFacing = if (facing == "front")
                CameraSelector.LENS_FACING_FRONT
            else
                CameraSelector.LENS_FACING_BACK

            val selector = CameraSelector.Builder()
                .requireLensFacing(currentFacing)
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(
                    (context as android.app.Activity).windowManager.defaultDisplay.rotation
                )
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

            imageCapture?.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val bitmap = imageProxyToBitmap(image)
                            image.close()

                            if (bitmap == null) {
                                onError("Bitmap null")
                                return
                            }

                            // Rotate sesuai orientasi
                            val rotated = rotateBitmap(bitmap, image.imageInfo.rotationDegrees)

                            val stream = ByteArrayOutputStream()
                            rotated.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                            val bytes = stream.toByteArray()
                            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

                            Log.d("CameraHelper", "Photo captured: ${bytes.size} bytes")
                            onSuccess(base64)
                        } catch (e: Exception) {
                            Log.e("CameraHelper", "Process error: ${e.message}", e)
                            onError(e.message ?: "process error")
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraHelper", "Capture error: ${exception.message}", exception)
                        onError(exception.message ?: "capture failed")
                    }
                }
            )
        } catch (e: Exception) {
            Log.e("CameraHelper", "takePhoto error: ${e.message}", e)
            onError(e.message ?: "takePhoto failed")
        }
    }

    /**
     * Start camera stream — capture frame tiap X ms, kirim ke callback.
     */
    fun startStream(
        facing: String,
        intervalMs: Long,
        onFrame: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val provider = cameraProvider
        if (provider == null) {
            onError("Camera provider belum siap")
            return false
        }

        return try {
            currentFacing = if (facing == "front")
                CameraSelector.LENS_FACING_FRONT
            else
                CameraSelector.LENS_FACING_BACK

            val selector = CameraSelector.Builder()
                .requireLensFacing(currentFacing)
                .build()

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, imageCapture)

            // Loop capture frame
            streamActive = true
            streamHandler.post(object : Runnable {
                override fun run() {
                    if (!streamActive) return
                    captureFrameForStream(onFrame)
                    streamHandler.postDelayed(this, intervalMs)
                }
            })

            Log.d("CameraHelper", "Stream started (interval=${intervalMs}ms)")
            true
        } catch (e: Exception) {
            Log.e("CameraHelper", "startStream error: ${e.message}", e)
            onError(e.message ?: "startStream failed")
            false
        }
    }

    private var streamActive = false
    private val streamHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun captureFrameForStream(onFrame: (String) -> Unit) {
        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(image)
                        image.close()
                        if (bitmap != null) {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                            val base64 = Base64.encodeToString(
                                stream.toByteArray(), Base64.NO_WRAP,
                            )
                            onFrame(base64)
                        }
                    } catch (e: Exception) {
                        Log.e("CameraHelper", "Frame error: ${e.message}", e)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e("CameraHelper", "Frame capture error: ${exception.message}", exception)
                }
            }
        )
    }

    fun stopStream() {
        streamActive = false
        streamHandler.removeCallbacksAndMessages(null)
        cameraProvider?.unbindAll()
        Log.d("CameraHelper", "Stream stopped")
    }

    // ==========================================
    // ===== HELPERS =====
    // ==========================================
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val buffer: ByteBuffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e("CameraHelper", "imageProxyToBitmap error: ${e.message}", e)
            null
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
