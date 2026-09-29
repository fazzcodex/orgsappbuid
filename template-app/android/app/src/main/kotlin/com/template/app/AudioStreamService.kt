package com.template.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlin.concurrent.thread

object AudioStreamService {
    private const val TAG = "AudioStream"
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun start(onChunk: (String) -> Unit): Boolean {
        if (isRecording) return true

        return try {
            val sampleRate = 16000
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2,
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true

            thread {
                val buffer = ByteArray(bufferSize)
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val chunk = buffer.copyOf(read)
                        val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
                        onChunk(base64)
                    }
                }
            }

            Log.d(TAG, "🎤 Audio recording started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "start error", e)
            false
        }
    }

    fun stop() {
        try {
            isRecording = false
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.d(TAG, "🎤 Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        }
    }

    val isActive: Boolean
        get() = isRecording
}
