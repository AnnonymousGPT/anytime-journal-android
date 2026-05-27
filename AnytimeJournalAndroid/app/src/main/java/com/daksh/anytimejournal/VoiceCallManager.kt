package com.daksh.anytimejournal

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.IceCandidateErrorEvent
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

class VoiceCallManager(
    context: Context,
    private val scope: CoroutineScope,
    private val localProfile: () -> String,
    private val peerProfile: () -> String,
    private val cloudConfig: () -> CloudCollabConfig?,
    private val onStateChanged: (VoiceCallState) -> Unit,
    private val onIncomingCall: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val deviceId = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID,
    ) ?: "unknown-device"
    private val factory: PeerConnectionFactory by lazy { createFactory() }
    private var pollJob: Job? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var currentCallId: String? = null
    private var currentPeer: String? = null
    private var callStartedAtMillis = 0L
    private var lastSignalMillis = (System.currentTimeMillis() - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
    private var lastTranscriptMillis = (System.currentTimeMillis() - INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
    private var incomingAudioWaiting = false
    private var micLive = false
    private var peerMicLive = false
    private var latestTranscript = ""
    private var incomingNotifiedCallId: String? = null
    private val transcriptionManager = CallTranscriptionManager(
        context = appContext,
        scope = scope,
        cloudConfig = cloudConfig,
        localProfile = localProfile,
        peerProfile = peerProfile,
        onTranscriptChanged = { text ->
            latestTranscript = text
            emitState()
        },
    )

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                pollSignals()
                pollTranscripts()
                delay(SIGNAL_POLL_MS)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        closeCall(notifyPeer = false)
    }

    fun startOutgoing() {
        val local = normalize(localProfile()) ?: return
        val peer = normalize(peerProfile()) ?: return
        Log.d(LOG_TAG, "startOutgoing local=$local peer=$peer")
        closeCall(notifyPeer = false)
        val callId = "${local.removePrefix("@")}_${peer.removePrefix("@")}_${System.currentTimeMillis()}"
        currentCallId = callId
        currentPeer = peer
        callStartedAtMillis = System.currentTimeMillis()
        incomingAudioWaiting = false
        peerMicLive = false
        latestTranscript = ""
        createPeerConnection(callId, local, peer)
        enableMic()
        peerConnection?.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    Log.d(LOG_TAG, "offer created callId=$callId")
                    setLocalAndPost(callId, local, peer, SIGNAL_OFFER, description)
                }

                override fun onCreateFailure(error: String?) {
                    Log.w(LOG_TAG, "offer create failed $error")
                }
            },
            mediaConstraints(),
        )
        emitState()
    }

    fun acceptIncoming() {
        val local = normalize(localProfile()) ?: return
        val peer = currentPeer ?: normalize(peerProfile()) ?: return
        val callId = currentCallId ?: return
        Log.d(LOG_TAG, "acceptIncoming local=$local peer=$peer callId=$callId")
        NotificationHelper.cancelIncomingCall(appContext)
        enableMic()
        peerConnection?.createOffer(
            object : SimpleSdpObserver() {
                override fun onCreateSuccess(description: SessionDescription) {
                    Log.d(LOG_TAG, "renegotiate offer created callId=$callId")
                    setLocalAndPost(callId, local, peer, SIGNAL_RENEGOTIATE_OFFER, description)
                }

                override fun onCreateFailure(error: String?) {
                    Log.w(LOG_TAG, "renegotiate offer create failed $error")
                }
            },
            mediaConstraints(),
        )
        emitState()
    }

    fun endCall() {
        closeCall(notifyPeer = true)
    }

    private fun closeCall(notifyPeer: Boolean) {
        val callId = currentCallId
        val local = normalize(localProfile())
        val peer = currentPeer ?: normalize(peerProfile())
        Log.d(LOG_TAG, "closeCall notifyPeer=$notifyPeer callId=$callId local=$local peer=$peer")
        if (notifyPeer && callId != null && local != null && peer != null) {
            postSignal(callId, local, peer, SIGNAL_END, JSONObject())
        }
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        localAudioSource?.dispose()
        localAudioSource = null
        NotificationHelper.cancelIncomingCall(appContext)
        val savedTranscript = transcriptionManager.stop()
        if (savedTranscript.isNotBlank() && local != null && peer != null) {
            saveTranscriptSummary(local, peer, savedTranscript)
        }
        currentCallId = null
        currentPeer = null
        callStartedAtMillis = 0L
        incomingAudioWaiting = false
        micLive = false
        peerMicLive = false
        latestTranscript = ""
        incomingNotifiedCallId = null
        appContext.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
        emitState()
    }

    fun state(): VoiceCallState {
        return VoiceCallState(
            active = currentCallId != null,
            micLive = micLive,
            hasIncomingAudio = incomingAudioWaiting && !micLive,
            peerProfile = currentPeer,
            startedAtMillis = callStartedAtMillis,
            latestTranscript = latestTranscript,
            peerMicLive = peerMicLive,
        )
    }

    private fun createPeerConnection(callId: String, local: String, peer: String) {
        if (peerConnection != null) return
        appContext.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        peerConnection = factory.createPeerConnection(
            PeerConnection.RTCConfiguration(
                listOf(PeerConnection.IceServer.builder(STUN_SERVER).createIceServer()),
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            },
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val payload = JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("sdp", candidate.sdp)
                    postSignal(callId, local, peer, SIGNAL_CANDIDATE, payload)
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    incomingAudioWaiting = !micLive
                    emitState()
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d(LOG_TAG, "iceConnection state=$state")
                    if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                        state == PeerConnection.IceConnectionState.FAILED ||
                        state == PeerConnection.IceConnectionState.CLOSED
                    ) {
                        incomingAudioWaiting = false
                        emitState()
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
                override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
                override fun onAddStream(stream: MediaStream?) = Unit
                override fun onRemoveStream(stream: MediaStream?) = Unit
                override fun onDataChannel(channel: DataChannel?) = Unit
                override fun onRenegotiationNeeded() = Unit
                override fun onIceCandidateError(event: IceCandidateErrorEvent) = Unit
                override fun onTrack(transceiver: RtpTransceiver?) = Unit
            },
        )
    }

    private fun enableMic() {
        if (localAudioTrack != null) {
            localAudioTrack?.setEnabled(true)
            micLive = true
            return
        }
        val constraints = MediaConstraints()
        localAudioSource = factory.createAudioSource(constraints)
        localAudioTrack = factory.createAudioTrack("audio0", localAudioSource).apply {
            setEnabled(true)
        }
        peerConnection?.addTrack(localAudioTrack, listOf("stream0"))
        micLive = true
        currentCallId?.let { transcriptionManager.start(it) }
    }

    private fun pollSignals() {
        val config = cloudConfig() ?: return
        val local = normalize(localProfile()) ?: return
        val peer = normalize(peerProfile()) ?: return
        val target = encodeFilter(local)
        val author = encodeFilter(peer)
        val query = "select=call_id,author,target,type,payload,created_at_millis" +
            "&target=eq.$target&author=eq.$author&created_at_millis=gt.$lastSignalMillis" +
            "&order=created_at_millis.asc&limit=80"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_call_signals?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                setCloudHeaders(config)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrNull() ?: return
        val rows = runCatching { JSONArray(response) }.getOrNull() ?: return
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            if (createdAtMillis <= 0L) continue
            lastSignalMillis = maxOf(lastSignalMillis, createdAtMillis)
            handleSignal(
                callId = item.optString("call_id"),
                local = local,
                peer = peer,
                type = item.optString("type"),
                payload = item.optJSONObject("payload") ?: JSONObject(),
            )
        }
    }

    private fun handleSignal(callId: String, local: String, peer: String, type: String, payload: JSONObject) {
        if (callId.isBlank()) return
        if (currentCallId != null && currentCallId != callId) return
        when (type) {
            SIGNAL_OFFER -> {
                currentCallId = callId
                currentPeer = peer
                if (callStartedAtMillis == 0L) callStartedAtMillis = System.currentTimeMillis()
                incomingAudioWaiting = true
                peerMicLive = true
                if (incomingNotifiedCallId != callId) {
                    incomingNotifiedCallId = callId
                    scope.launch(Dispatchers.Main) {
                        onIncomingCall(peer)
                    }
                }
                createPeerConnection(callId, local, peer)
                val offer = SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                peerConnection?.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            peerConnection?.createAnswer(
                                object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(description: SessionDescription) {
                                        setLocalAndPost(callId, local, peer, SIGNAL_ANSWER, description)
                                    }
                                },
                                mediaConstraints(),
                            )
                        }
                    },
                    offer,
                )
                emitState()
            }
            SIGNAL_ANSWER -> {
                val answer = SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp"))
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
            }
            SIGNAL_RENEGOTIATE_OFFER -> {
                peerMicLive = true
                val offer = SessionDescription(SessionDescription.Type.OFFER, payload.optString("sdp"))
                peerConnection?.setRemoteDescription(
                    object : SimpleSdpObserver() {
                        override fun onSetSuccess() {
                            peerConnection?.createAnswer(
                                object : SimpleSdpObserver() {
                                    override fun onCreateSuccess(description: SessionDescription) {
                                        setLocalAndPost(callId, local, peer, SIGNAL_RENEGOTIATE_ANSWER, description)
                                    }
                                },
                                mediaConstraints(),
                            )
                        }
                    },
                    offer,
                )
            }
            SIGNAL_RENEGOTIATE_ANSWER -> {
                peerMicLive = true
                val answer = SessionDescription(SessionDescription.Type.ANSWER, payload.optString("sdp"))
                peerConnection?.setRemoteDescription(SimpleSdpObserver(), answer)
            }
            SIGNAL_CANDIDATE -> {
                peerConnection?.addIceCandidate(
                    IceCandidate(
                        payload.optString("sdpMid"),
                        payload.optInt("sdpMLineIndex"),
                        payload.optString("sdp"),
                    ),
                )
            }
            SIGNAL_END -> closeCall(notifyPeer = false)
        }
    }

    private fun pollTranscripts() {
        val config = cloudConfig() ?: return
        val local = normalize(localProfile()) ?: return
        val peer = normalize(peerProfile()) ?: return
        val query = "select=call_id,author,target,transcript,created_at_millis" +
            "&target=eq.${encodeFilter(local)}&author=eq.${encodeFilter(peer)}" +
            "&created_at_millis=gt.$lastTranscriptMillis&order=created_at_millis.asc&limit=40"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_call_transcripts?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                setCloudHeaders(config)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrNull() ?: return
        val rows = runCatching { JSONArray(response) }.getOrNull() ?: return
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            val transcript = item.optString("transcript").trim()
            if (createdAtMillis <= 0L || transcript.isBlank()) continue
            lastTranscriptMillis = maxOf(lastTranscriptMillis, createdAtMillis)
            latestTranscript = transcript
            emitState()
        }
    }

    private fun setLocalAndPost(
        callId: String,
        local: String,
        peer: String,
        type: String,
        description: SessionDescription,
    ) {
        peerConnection?.setLocalDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    Log.d(LOG_TAG, "local description set type=$type callId=$callId")
                    postSignal(
                        callId,
                        local,
                        peer,
                        type,
                        JSONObject().put("sdp", description.description),
                    )
                }

                override fun onSetFailure(error: String?) {
                    Log.w(LOG_TAG, "local description set failed type=$type error=$error")
                }
            },
            description,
        )
    }

    private fun postSignal(callId: String, author: String, target: String, type: String, payload: JSONObject) {
        val config = cloudConfig() ?: return
        val body = JSONObject()
            .put("call_id", callId)
            .put("source_id", deviceId)
            .put("author", author)
            .put("target", target)
            .put("type", type)
            .put("payload", payload)
            .put("created_at_millis", System.currentTimeMillis())
            .toString()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = (URL("${config.restUrl}/collab_call_signals").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CLOUD_TIMEOUT_MS
                    readTimeout = CLOUD_TIMEOUT_MS
                    doOutput = true
                    setCloudHeaders(config)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=minimal")
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(body)
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    Log.w(LOG_TAG, "WebRTC signal rejected code=$code type=$type body=$error")
                } else {
                    connection.inputStream.use { it.readBytes() }
                }
                connection.disconnect()
            }.onFailure {
                Log.w(LOG_TAG, "WebRTC signal post failed", it)
            }
        }
    }

    private fun saveTranscriptSummary(local: String, peer: String, transcript: String) {
        val createdAtMillis = System.currentTimeMillis()
        val prepared = CollabMessage.prepareDraft(
            text = "Call transcript: $transcript",
            author = local,
            peer = peer,
            knownProfiles = listOf(local, peer),
        )
        scope.launch(Dispatchers.IO) {
            EntryRepository.from(appContext).saveReply(
                prepared,
                nowMillis = createdAtMillis,
                kind = JournalEntryInput.KIND_COLLAB,
            )
            postCloudEntry(prepared, local, createdAtMillis)
        }
    }

    private fun postCloudEntry(text: String, author: String, createdAtMillis: Long) {
        val config = cloudConfig() ?: return
        val parsed = CollabMessage.parse(text, author)
        val body = JSONObject()
            .put("source_id", deviceId)
            .put("author", parsed.author)
            .put("body", parsed.body)
            .put("text", text)
            .put("kind", JournalEntryInput.KIND_COLLAB)
            .put("created_at_millis", createdAtMillis)
            .toString()
        runCatching {
            val connection = (URL("${config.restUrl}/collab_entries").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                doOutput = true
                setCloudHeaders(config)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "return=minimal")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                Log.w(LOG_TAG, "transcript summary rejected code=$code body=$error")
            } else {
                connection.inputStream.use { it.readBytes() }
            }
            connection.disconnect()
        }.onFailure {
            Log.w(LOG_TAG, "transcript summary post failed", it)
        }
    }

    private fun createFactory(): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .setFieldTrials("WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/")
                .createInitializationOptions(),
        )
        val audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { samples ->
                transcriptionManager.feedWebRtcAudio(samples.data)
            }
            .createAudioDeviceModule()
        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
    }

    private fun mediaConstraints(): MediaConstraints {
        return MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
    }

    private fun emitState() {
        scope.launch(Dispatchers.Main) {
            onStateChanged(state())
        }
    }

    private fun HttpURLConnection.setCloudHeaders(config: CloudCollabConfig) {
        setRequestProperty("apikey", config.anonKey)
        setRequestProperty("Authorization", "Bearer ${config.anonKey}")
    }

    private fun encodeFilter(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun normalize(profile: String): String? {
        val raw = profile.trim().lowercase()
        val normalized = if (raw.startsWith("@")) raw else "@$raw"
        if (!Regex("^@[a-z0-9_-]{2,32}$").matches(normalized)) return null
        return normalized
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = Unit
    }

    companion object {
        private const val STUN_SERVER = "stun:stun.l.google.com:19302"
        private const val SIGNAL_POLL_MS = 180L
        private const val CLOUD_TIMEOUT_MS = 1200
        private const val INITIAL_LOOKBACK_MS = 60_000L
        private const val SIGNAL_OFFER = "offer"
        private const val SIGNAL_ANSWER = "answer"
        private const val SIGNAL_CANDIDATE = "candidate"
        private const val SIGNAL_RENEGOTIATE_OFFER = "renegotiate_offer"
        private const val SIGNAL_RENEGOTIATE_ANSWER = "renegotiate_answer"
        private const val SIGNAL_END = "end"
        private const val LOG_TAG = "AnytimeVoiceRTC"
    }
}

data class VoiceCallState(
    val active: Boolean,
    val micLive: Boolean,
    val hasIncomingAudio: Boolean,
    val peerProfile: String?,
    val startedAtMillis: Long,
    val latestTranscript: String,
    val peerMicLive: Boolean,
)
