package com.selfmessenger.app.net

import android.content.Context
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

/**
 * WebRTC-Direktverbindung: DataChannel (Text) + optional Audio/Video-Anruf.
 * "Perfect Negotiation" wie im Web-Client. Anrufe sind über WebRTC Pflicht-verschlüsselt (DTLS-SRTP).
 */
class WebRtcClient(
    appContext: Context,
    private val eglBase: EglBase,
    private val polite: Boolean,
    private val iceServers: List<PeerConnection.IceServer>,
    private val onLocalDesc: (JSONObject) -> Unit,
    private val onIce: (JSONObject) -> Unit,
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit
) {
    private val appCtx = appContext.applicationContext
    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var dc: DataChannel? = null
    private var makingOffer = false
    private var ignoreOffer = false

    private var audioSource: AudioSource? = null
    private var videoSource: VideoSource? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    var localVideoTrack: VideoTrack? = null; private set
    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var audioSender: org.webrtc.RtpSender? = null
    private var videoSender: org.webrtc.RtpSender? = null

    var onRemoteVideo: ((VideoTrack) -> Unit)? = null
    var onLocalVideo: ((VideoTrack) -> Unit)? = null
    var onRemoteTrackKind: ((String) -> Unit)? = null   // "audio"/"video" — für eingehende Anrufe (auch Sprache)

    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    init {
        ensureFactoryInit(appCtx)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    fun start(initiator: Boolean) {
        val config = PeerConnection.RTCConfiguration(iceServers).apply {   // ICE wird vorab (im Hintergrund) geholt, blockiert nicht mehr das Signaling
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        pc = factory.createPeerConnection(config, pcObserver)
        if (initiator) {
            dc = pc?.createDataChannel("chat", DataChannel.Init())
            dc?.registerObserver(dcObserver)
        }
    }

    fun sendText(json: String) {
        dc?.send(DataChannel.Buffer(ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8)), false))
    }

    /** Anruf starten: fügt Audio (+ Video) hinzu -> löst Nachverhandlung aus. */
    fun startCall(video: Boolean) {
        val peer = pc ?: return
        val aSource = factory.createAudioSource(MediaConstraints())
        audioSource = aSource
        val aTrack = factory.createAudioTrack("audio0", aSource); localAudioTrack = aTrack
        audioSender = peer.addTrack(aTrack, listOf("stream0"))
        if (video) {
            val enumerator = Camera2Enumerator(appCtx)
            val device = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull() ?: return
            val cap = enumerator.createCapturer(device, null) ?: return
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            val vSource = factory.createVideoSource(cap.isScreencast)
            cap.initialize(helper, appCtx, vSource.capturerObserver)
            cap.startCapture(1280, 720, 30)
            val vTrack = factory.createVideoTrack("video0", vSource)
            videoSender = peer.addTrack(vTrack, listOf("stream0"))
            capturer = cap; videoSource = vSource; surfaceHelper = helper; localVideoTrack = vTrack
            onLocalVideo?.invoke(vTrack)
        }
    }

    fun setMicEnabled(on: Boolean) { localAudioTrack?.setEnabled(on) }
    fun setCamEnabled(on: Boolean) { localVideoTrack?.setEnabled(on) }

    fun hangupCall() {
        try { capturer?.stopCapture() } catch (_: Exception) {}
        try { audioSender?.let { pc?.removeTrack(it) } } catch (_: Exception) {}   // Sender entfernen, sonst bleiben sie im nächsten Anruf hängen
        try { videoSender?.let { pc?.removeTrack(it) } } catch (_: Exception) {}
        audioSender = null; videoSender = null
        capturer?.dispose(); capturer = null
        surfaceHelper?.dispose(); surfaceHelper = null
        videoSource?.dispose(); videoSource = null                                // native Quellen freigeben (Leck-Fix)
        audioSource?.dispose(); audioSource = null
        localVideoTrack = null; localAudioTrack = null
    }

    fun onRemoteDesc(desc: JSONObject) {
        val type = desc.getString("type")
        val sdp = desc.getString("sdp")
        val collision = type == "offer" &&
                (makingOffer || pc?.signalingState() != PeerConnection.SignalingState.STABLE)
        ignoreOffer = !polite && collision
        if (ignoreOffer) return
        val sdpType = if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        pc?.setRemoteDescription(object : SdpAdapter() {
            override fun onSetSuccess() { if (type == "offer") createAnswerAndSend() }
        }, SessionDescription(sdpType, sdp))
    }

    fun onRemoteIce(cand: JSONObject) {
        try {
            pc?.addIceCandidate(
                IceCandidate(cand.getString("sdpMid"), cand.getInt("sdpMLineIndex"), cand.getString("candidate"))
            )
        } catch (_: Exception) { }
    }

    fun close() {
        hangupCall(); dc?.close(); pc?.close(); pc = null
        try { factory.dispose() } catch (_: Exception) {}   // native PeerConnectionFactory freigeben (sonst Leck pro Chat)
    }

    private fun createOfferAndSend() {
        makingOffer = true
        pc?.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) = setLocalAndSend(sdp)
            override fun onCreateFailure(error: String?) { makingOffer = false }
        }, MediaConstraints())
    }
    private fun createAnswerAndSend() {
        pc?.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) = setLocalAndSend(sdp)
        }, MediaConstraints())
    }
    private fun setLocalAndSend(sdp: SessionDescription) {
        pc?.setLocalDescription(object : SdpAdapter() {
            override fun onSetSuccess() {
                makingOffer = false
                onLocalDesc(JSONObject().put("type", sdp.type.canonicalForm()).put("sdp", sdp.description))
            }
            override fun onSetFailure(error: String?) { makingOffer = false }
        }, sdp)
    }

    private val dcObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}
        override fun onStateChange() { if (dc?.state() == DataChannel.State.OPEN) onOpen() }
        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()); buffer.data.get(bytes)
            onText(String(bytes, Charsets.UTF_8))
        }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            onIce(JSONObject().put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid).put("sdpMLineIndex", candidate.sdpMLineIndex))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) { dc = channel; dc?.registerObserver(dcObserver) }
        override fun onRenegotiationNeeded() { createOfferAndSend() }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val track = receiver?.track()
            track?.kind()?.let { onRemoteTrackKind?.invoke(it) }
            if (track is VideoTrack) onRemoteVideo?.invoke(track)
        }
    }

    companion object {
        @Volatile private var factoryInitialized = false
        private fun ensureFactoryInit(ctx: Context) {
            if (factoryInitialized) return
            synchronized(this) {
                if (factoryInitialized) return
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                        .createInitializationOptions()
                )
                factoryInitialized = true
            }
        }
    }
}

open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
