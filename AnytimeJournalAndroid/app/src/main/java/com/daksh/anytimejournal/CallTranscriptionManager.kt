package com.daksh.anytimejournal

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CallTranscriptionManager(
    context: Context,
    private val scope: CoroutineScope,
    private val cloudConfig: () -> CloudCollabConfig?,
    private val localProfile: () -> String,
    private val peerProfile: () -> String,
    private val onTranscriptChanged: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null
    private var activeCallId: String? = null
    private var sequence = 0L
    private var lastPostedText = ""
    private var consecutiveErrors = 0
    private var audioPipeRead: ParcelFileDescriptor? = null
    private var audioPipeOutput: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private val audioPipeLock = Any()
    private val finalSegments = mutableListOf<String>()
    private var latestPartial = ""

    fun start(callId: String) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) return
        if (activeCallId == callId && recognizer != null) return
        stopListeningOnly()
        activeCallId = callId
        sequence = 0L
        lastPostedText = ""
        consecutiveErrors = 0
        finalSegments.clear()
        latestPartial = ""
        scope.launch(Dispatchers.Main) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                setRecognitionListener(listener)
                startListening(recognizerIntent())
            }
        }
    }

    fun stop(): String {
        val transcript = fullTranscript()
        activeCallId = null
        stopListeningOnly()
        return transcript
    }

    fun fullTranscript(): String {
        return (finalSegments + latestPartial)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
    }

    fun feedWebRtcAudio(data: ByteArray) {
        if (activeCallId == null || data.isEmpty()) return
        synchronized(audioPipeLock) {
            try {
                audioPipeOutput?.write(data)
            } catch (_: IOException) {
                closeAudioPipeLocked()
            }
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() {
            consecutiveErrors = 0
        }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = bestText(partialResults) ?: return
            consecutiveErrors = 0
            if (latestPartial.isNotBlank() && text.length + PARTIAL_SHRINK_TOLERANCE < latestPartial.length) return
            latestPartial = text
            publishTranscript(isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0
            val text = bestText(results)
            if (!text.isNullOrBlank()) {
                finalSegments.add(text.trim())
                latestPartial = ""
                publishTranscript(isFinal = true)
            }
            scheduleRestart()
        }

        override fun onError(error: Int) {
            consecutiveErrors += 1
            Log.d(LOG_TAG, "recognizer error=$error consecutive=$consecutiveErrors")
            if (activeCallId != null) scheduleRestart()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun publishTranscript(isFinal: Boolean) {
        val callId = activeCallId ?: return
        val transcript = fullTranscript()
        if (transcript.isBlank()) return
        Log.d(LOG_TAG, "transcript update final=$isFinal text=${transcript.take(80)}")
        onTranscriptChanged(transcript)
        if (!isFinal && transcript == lastPostedText) return
        lastPostedText = transcript
        postTranscript(callId, transcript, isFinal)
    }

    private fun postTranscript(callId: String, transcript: String, isFinal: Boolean) {
        val config = cloudConfig() ?: return
        val local = normalize(localProfile()) ?: return
        val peer = normalize(peerProfile()) ?: return
        val payload = JSONObject()
            .put("call_id", callId)
            .put("source_id", android.provider.Settings.Secure.getString(appContext.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown-device")
            .put("author", local)
            .put("target", peer)
            .put("transcript", transcript)
            .put("is_final", isFinal)
            .put("sequence", ++sequence)
            .put("created_at_millis", System.currentTimeMillis())
            .toString()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = (URL("${config.restUrl}/collab_call_transcripts").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CLOUD_TIMEOUT_MS
                    readTimeout = CLOUD_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("apikey", config.anonKey)
                    setRequestProperty("Authorization", "Bearer ${config.anonKey}")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=minimal")
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    Log.w(LOG_TAG, "transcript post rejected code=$code body=$error")
                } else {
                    connection.inputStream.use { it.readBytes() }
                    Log.d(LOG_TAG, "transcript post ok final=$isFinal seq=$sequence")
                }
                connection.disconnect()
            }.onFailure {
                Log.w(LOG_TAG, "transcript post failed", it)
            }
        }
    }

    private fun scheduleRestart() {
        val callId = activeCallId ?: return
        restartJob?.cancel()
        restartJob = scope.launch(Dispatchers.Main) {
            val delayMs = (RESTART_DELAY_MS * consecutiveErrors.coerceAtLeast(1)).coerceAtMost(MAX_RESTART_DELAY_MS)
            delay(delayMs)
            if (activeCallId == callId) {
                runCatching {
                    recognizer?.startListening(recognizerIntent())
                }.onFailure {
                    Log.w(LOG_TAG, "recognizer restart failed; recreating", it)
                    runCatching { recognizer?.destroy() }
                    recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                        setRecognitionListener(listener)
                        startListening(recognizerIntent())
                    }
                }
            }
        }
    }

    private fun stopListeningOnly() {
        restartJob?.cancel()
        restartJob = null
        val current = recognizer
        recognizer = null
        synchronized(audioPipeLock) {
            closeAudioPipeLocked()
        }
        scope.launch(Dispatchers.Main) {
            runCatching { current?.stopListening() }
            runCatching { current?.destroy() }
        }
    }

    private fun recognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                prepareAudioPipe()?.let { readFd ->
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readFd)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, WEBRTC_SAMPLE_RATE)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, WEBRTC_CHANNEL_COUNT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
            }
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 60_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2_500L)
        }
    }

    private fun prepareAudioPipe(): ParcelFileDescriptor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return synchronized(audioPipeLock) {
            closeAudioPipeLocked()
            runCatching {
                val pipe = ParcelFileDescriptor.createPipe()
                audioPipeRead = pipe[0]
                audioPipeOutput = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                audioPipeRead
            }.onFailure {
                Log.w(LOG_TAG, "audio pipe unavailable; falling back to recognizer mic", it)
                closeAudioPipeLocked()
            }.getOrNull()
        }
    }

    private fun closeAudioPipeLocked() {
        runCatching { audioPipeOutput?.close() }
        runCatching { audioPipeRead?.close() }
        audioPipeOutput = null
        audioPipeRead = null
    }

    private fun bestText(bundle: Bundle?): String? {
        return bundle
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun normalize(profile: String): String? {
        val raw = profile.trim().lowercase()
        val normalized = if (raw.startsWith("@")) raw else "@$raw"
        if (!Regex("^@[a-z0-9_-]{2,32}$").matches(normalized)) return null
        return normalized
    }

    companion object {
        private const val CLOUD_TIMEOUT_MS = 1200
        private const val WEBRTC_SAMPLE_RATE = 48_000
        private const val WEBRTC_CHANNEL_COUNT = 1
        private const val RESTART_DELAY_MS = 260L
        private const val MAX_RESTART_DELAY_MS = 2_500L
        private const val PARTIAL_SHRINK_TOLERANCE = 8
        private const val LOG_TAG = "AnytimeCallTranscript"
    }
}
