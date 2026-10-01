package com.template.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import org.json.JSONObject
import org.webrtc.*

object WebRTCService {

    private const val TAG = "WebRTCService"

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    // ✅ Tipe diubah ke VideoCapturer agar bisa menampung CameraVideoCapturer & ScreenCapturerAndroid
    private var videoCapturer: VideoCapturer? = null
    private var screenCapturer: ScreenCapturerAndroid? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private var isStreaming = false
    private var useFrontCamera = false

    // Callback ICE ke server
    var onIceCandidate: ((JSONObject) -> Unit)? = null

    // ==========================================
    // ===== INIT =====
    // ==========================================
    fun init(context: Context) {
        if (peerConnectionFactory != null) return

        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            eglBase = EglBase.create()

            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase!!.eglBaseContext,
                true,
                true,
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options())
                .createPeerConnectionFactory()

            Log.d(TAG, "✅ WebRTC initialized")
        } catch (e: Exception) {
            Log.e(TAG, "init error", e)
        }
    }

    // ==========================================
    // ===== START CAMERA STREAM =====
    // ==========================================
    fun startCameraStream(
        context: Context,
        facing: String = "back",
        withAudio: Boolean = true,
    ): Boolean {
        if (isStreaming) {
            Log.w(TAG, "Already streaming")
            return true
        }

        try {
            init(context)

            // ===== VIDEO CAPTURER =====
            val camCapturer = createCameraCapturer(context, facing == "front")
            if (camCapturer == null) {
                Log.e(TAG, "No camera capturer")
                return false
            }
            videoCapturer = camCapturer
            screenCapturer = null

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread",
                eglBase!!.eglBaseContext,
            )

            videoSource = peerConnectionFactory!!.createVideoSource(false)
            camCapturer.initialize(
                surfaceTextureHelper,
                context,
                videoSource!!.capturerObserver,
            )

            videoTrack = peerConnectionFactory!!.createVideoTrack("video0", videoSource)
            videoTrack!!.setEnabled(true)

            // ===== AUDIO =====
            if (withAudio) {
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                }

                audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
                audioTrack = peerConnectionFactory!!.createAudioTrack("audio0", audioSource)
                audioTrack!!.setEnabled(true)
            }

            // ===== PEER CONNECTION =====
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = peerConnectionFactory!!.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        try {
                            val json = JSONObject().apply {
                                put("candidate", candidate?.sdp)
                                put("sdpMid", candidate?.sdpMid)
                                put("sdpMLineIndex", candidate?.sdpMLineIndex)
                            }
                            onIceCandidate?.invoke(json)
                            Log.d(TAG, "📡 ICE candidate: ${candidate?.sdp?.take(50)}")
                        } catch (e: Exception) {
                            Log.e(TAG, "ICE error", e)
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                        Log.d(TAG, "Signaling: $state")
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "ICE: $state")
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        Log.d(TAG, "ICE Gather: $state")
                    }

                    override fun onAddStream(stream: MediaStream?) {
                        Log.d(TAG, "Add stream: ${stream?.id}")
                    }

                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {
                        Log.d(TAG, "Renegotiation needed")
                    }

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        streams: Array<out MediaStream>?,
                    ) {
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        Log.d(TAG, "Track added")
                    }
                },
            )

            // Add tracks
            val streamIds = listOf("stream0")
            peerConnection!!.addTrack(videoTrack, streamIds)
            if (audioTrack != null) {
                peerConnection!!.addTrack(audioTrack, streamIds)
            }

            // ===== START CAPTURE =====
            camCapturer.startCapture(1280, 720, 30)

            isStreaming = true
            Log.d(TAG, "✅ Camera stream started (${facing}, audio=$withAudio)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startCameraStream error", e)
            return false
        }
    }

    // ==========================================
    // ===== START SCREEN STREAM (SCREEN MIRRORING) =====
    // mediaProjectionData = Intent dari onActivityResult
    // ==========================================
    fun startScreenStream(
        context: Context,
        mediaProjectionData: Intent,
        withAudio: Boolean = true,
    ): Boolean {
        if (isStreaming) {
            stop()
        }

        try {
            init(context)

            val metrics = context.resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels

            // ===== SCREEN CAPTURER =====
            val sc = ScreenCapturerAndroid(
                mediaProjectionData,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.d(TAG, "Screen capture stopped")
                    }
                },
            )

            surfaceTextureHelper = SurfaceTextureHelper.create(
                "ScreenCaptureThread",
                eglBase!!.eglBaseContext,
            )

            videoSource = peerConnectionFactory!!.createVideoSource(false)
            sc.initialize(
                surfaceTextureHelper,
                context,
                videoSource!!.capturerObserver,
            )

            videoTrack = peerConnectionFactory!!.createVideoTrack("screen0", videoSource)
            videoTrack!!.setEnabled(true)

            // ✅ Simpan referensi
            screenCapturer = sc
            videoCapturer = sc

            // ===== AUDIO =====
            if (withAudio) {
                val audioConstraints = MediaConstraints()
                audioSource = peerConnectionFactory!!.createAudioSource(audioConstraints)
                audioTrack = peerConnectionFactory!!.createAudioTrack("audio0", audioSource)
                audioTrack!!.setEnabled(true)
            }

            // ===== PEER CONNECTION =====
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory!!.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        try {
                            val json = JSONObject().apply {
                                put("candidate", candidate?.sdp)
                                put("sdpMid", candidate?.sdpMid)
                                put("sdpMLineIndex", candidate?.sdpMLineIndex)
                            }
                            onIceCandidate?.invoke(json)
                        } catch (_: Exception) {}
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                    override fun onAddStream(stream: MediaStream?) {}
                    override fun onRemoveStream(stream: MediaStream?) {}
                    override fun onDataChannel(channel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        streams: Array<out MediaStream>?,
                    ) {
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {}
                },
            )

            val streamIds = listOf("screen-stream")
            peerConnection!!.addTrack(videoTrack, streamIds)
            if (audioTrack != null) {
                peerConnection!!.addTrack(audioTrack, streamIds)
            }

            sc.startCapture(screenWidth, screenHeight, 30)

            isStreaming = true
            Log.d(TAG, "✅ Screen mirroring started")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "startScreenStream error", e)
            return false
        }
    }

    // ==========================================
    // ===== SIGNALING =====
    // ==========================================
    fun createOffer(callback: (JSONObject) -> Unit) {
        try {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            }

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    try {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                val json = JSONObject().apply {
                                    put("type", "offer")
                                    put("sdp", sdp?.description)
                                }
                                callback(json)
                                Log.d(TAG, "✅ Offer created")
                            }

                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, sdp)
                    } catch (e: Exception) {
                        Log.e(TAG, "onCreateSuccess error", e)
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "Create offer failed: $error")
                }

                override fun onSetFailure(error: String?) {}
            }, constraints)
        } catch (e: Exception) {
            Log.e(TAG, "createOffer error", e)
        }
    }

    fun setRemoteAnswer(sdp: String) {
        try {
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onSetSuccess() {
                    Log.d(TAG, "✅ Remote answer set")
                }

                override fun onCreateFailure(p0: String?) {}
                override fun onSetFailure(p0: String?) {}
            }, answer)
        } catch (e: Exception) {
            Log.e(TAG, "setRemoteAnswer error", e)
        }
    }

    fun addIceCandidate(sdp: String, sdpMid: String?, sdpMLineIndex: Int) {
        try {
            val candidate = IceCandidate(sdpMid, sdpMLineIndex, sdp)
            peerConnection?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "addIceCandidate error", e)
        }
    }

    // ==========================================
    // ===== SWITCH CAMERA =====
    // ==========================================
    fun switchCamera() {
        try {
            val capturer = videoCapturer as? CameraVideoCapturer
            if (capturer == null) {
                Log.w(TAG, "switchCamera: current capturer bukan kamera (mungkin screen)")
                return
            }

            useFrontCamera = !useFrontCamera
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    useFrontCamera = isFrontCamera
                    Log.d(TAG, "Camera switched: front=$isFrontCamera")
                }

                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.e(TAG, "Camera switch error: $errorDescription")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "switchCamera error", e)
        }
    }

    // ==========================================
    // ===== STOP =====
    // ==========================================
    fun stop() {
        try {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            screenCapturer = null

            videoCapturer?.stopCapture()
            videoCapturer?.dispose()
            videoCapturer = null

            videoTrack?.dispose()
            videoTrack = null
            videoSource?.dispose()
            videoSource = null

            audioTrack?.dispose()
            audioTrack = null
            audioSource?.dispose()
            audioSource = null

            peerConnection?.close()
            peerConnection = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            isStreaming = false
            Log.d(TAG, "✅ WebRTC stopped")
        } catch (e: Exception) {
            Log.e(TAG, "stop error", e)
        }
    }

    // ==========================================
    // ===== HELPER: CREATE CAMERA CAPTURER =====
    // ==========================================
    private fun createCameraCapturer(context: Context, front: Boolean): CameraVideoCapturer? {
        try {
            val enumerator = Camera2Enumerator(context)
            val deviceNames = enumerator.deviceNames

            for (name in deviceNames) {
                if (front && enumerator.isFrontFacing(name)) {
                    return enumerator.createCapturer(name, null)
                } else if (!front && enumerator.isBackFacing(name)) {
                    return enumerator.createCapturer(name, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "createCameraCapturer error", e)
        }
        return null
    }

    val isActive: Boolean
        get() = isStreaming
}
