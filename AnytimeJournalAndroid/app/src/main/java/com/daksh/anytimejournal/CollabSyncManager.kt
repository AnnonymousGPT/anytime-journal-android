package com.daksh.anytimejournal

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class CollabSyncManager(
    context: Context,
    private val scope: CoroutineScope,
    private val localProfile: () -> String,
    private val relayUrls: () -> List<String>,
    private val cloudConfig: () -> CloudCollabConfig?,
    private val isCollabActive: () -> Boolean,
    private val onRemoteEntry: (CollabPacket) -> Unit,
    private val onPresence: (List<CollabPresence>) -> Unit,
    private val onProfiles: (List<String>) -> Unit,
    private val onTyping: (CollabTyping) -> Unit,
) {
    private val appContext = context.applicationContext
    private val deviceId = Settings.Secure.getString(
        appContext.contentResolver,
        Settings.Secure.ANDROID_ID,
    ) ?: "unknown-device"
    private var listenJob: Job? = null
    private var relayJob: Job? = null
    private var cloudJob: Job? = null
    private var cloudPresenceJob: Job? = null
    private var presenceJob: Job? = null
    private var lastRelayCreatedAtMillis = 0L
    private var lastCloudCreatedAtMillis = (System.currentTimeMillis() - CLOUD_INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
    private var lastCloudTypingMillis = (System.currentTimeMillis() - CLOUD_INITIAL_LOOKBACK_MS).coerceAtLeast(0L)

    fun start() {
        if (listenJob?.isActive != true) {
            listenJob = scope.launch(Dispatchers.IO) {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.soTimeout = RECEIVE_TIMEOUT_MS
                    socket.bind(InetSocketAddress(COLLAB_PORT))
                    val buffer = ByteArray(MAX_PACKET_BYTES)
                    while (isActive) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                            parsePacket(packet)?.let(onRemoteEntry)
                        } catch (_: SocketTimeoutException) {
                            // Keep the loop cancellable without blocking forever in receive().
                        }
                    }
                }
            }
        }
        if (relayJob?.isActive != true) {
            relayJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (shouldUseRelay()) {
                        pollRelay()
                        onPresence(pollPresence())
                    }
                    delay(RELAY_POLL_MS)
                }
            }
        }
        if (cloudJob?.isActive != true) {
            cloudJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (cloudConfig() != null) {
                        pollCloud()
                        if (isCollabActive()) pollCloudTyping()
                        delay(if (isCollabActive()) CLOUD_MESSAGE_POLL_ACTIVE_MS else CLOUD_MESSAGE_POLL_IDLE_MS)
                    } else {
                        delay(RELAY_POLL_MS)
                    }
                }
            }
        }
        if (cloudPresenceJob?.isActive != true) {
            cloudPresenceJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (cloudConfig() != null) {
                        registerCloudProfile()
                        onPresence(pollCloudPresence())
                        onProfiles(pollCloudProfiles())
                    }
                    delay(CLOUD_PRESENCE_POLL_MS)
                }
            }
        }
        if (presenceJob?.isActive != true) {
            presenceJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (shouldUseRelay()) postPresence()
                    postCloudPresence()
                    delay(PRESENCE_POST_MS)
                }
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        relayJob?.cancel()
        relayJob = null
        cloudJob?.cancel()
        cloudJob = null
        cloudPresenceJob?.cancel()
        cloudPresenceJob = null
        presenceJob?.cancel()
        presenceJob = null
    }

    fun broadcast(
        text: String,
        createdAtMillis: Long,
        kind: String = JournalEntryInput.KIND_COLLAB,
    ) {
        val normalizedKind = sharedKind(kind)
        val payload = JSONObject()
            .put("app", APP_MARKER)
            .put("sourceId", deviceId)
            .put("kind", normalizedKind)
            .put("createdAtMillis", createdAtMillis)
            .put("text", text)
            .toString()
            .toByteArray(Charsets.UTF_8)

        scope.launch(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    val packet = DatagramPacket(
                        payload,
                        payload.size,
                        InetAddress.getByName(BROADCAST_ADDRESS),
                        COLLAB_PORT,
                    )
                    socket.send(packet)
                }
            }
            if (shouldUseRelay()) postRelay(payload)
            postCloud(text, createdAtMillis, normalizedKind)
        }
    }

    fun publishTyping(targetProfile: String, typing: Boolean) {
        val config = cloudConfig() ?: return
        val author = localProfile().trim().lowercase()
        val target = targetProfile.trim().lowercase()
        if (!author.startsWith("@") || !target.startsWith("@") || author == target) return
        val payload = JSONObject()
            .put("source_id", deviceId)
            .put("author", author)
            .put("target", target)
            .put("typing", typing)
            .put("created_at_millis", System.currentTimeMillis())
            .toString()
        scope.launch(Dispatchers.IO) {
            runCatching {
                val connection = (URL("${config.restUrl}/collab_typing_events").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CLOUD_TIMEOUT_MS
                    readTimeout = CLOUD_TIMEOUT_MS
                    doOutput = true
                    setCloudHeaders(config)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Prefer", "return=minimal")
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                connection.readCloudResponse("post typing")
                connection.disconnect()
            }
                .onFailure { error -> Log.w(TAG, "Cloud post typing failed: ${error.message}") }
        }
    }

    private fun parsePacket(packet: DatagramPacket): CollabPacket? {
        val json = runCatching {
            JSONObject(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (json.optString("app") != APP_MARKER) return null
        if (json.optString("sourceId") == deviceId) return null
        val kind = sharedKind(json.optString("kind"))
        val text = json.optString("text").trim()
        val createdAtMillis = json.optLong("createdAtMillis", 0L)
        if (text.isBlank() || createdAtMillis <= 0L) return null
        return CollabPacket(text = text, createdAtMillis = createdAtMillis, kind = kind)
    }

    private fun postRelay(payload: ByteArray) {
        for (baseUrl in activeRelayUrls()) {
            val posted = runCatching {
                val connection = (URL("$baseUrl/entries").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = RELAY_TIMEOUT_MS
                    readTimeout = RELAY_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(String(payload, Charsets.UTF_8))
                }
                connection.inputStream.use { it.readBytes() }
                connection.disconnect()
                true
            }.getOrDefault(false)
            if (posted) {
                return
            }
        }
    }

    private fun pollRelay() {
        for (baseUrl in activeRelayUrls()) {
            val response = runCatching {
                val connection = (URL("$baseUrl/entries?since=$lastRelayCreatedAtMillis").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = RELAY_TIMEOUT_MS
                    readTimeout = RELAY_TIMEOUT_MS
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                body
            }.getOrNull() ?: continue

            val entries = runCatching { JSONArray(response) }.getOrNull() ?: continue
            for (index in 0 until entries.length()) {
                val packet = parseRelayJson(entries.optJSONObject(index) ?: continue) ?: continue
                lastRelayCreatedAtMillis = maxOf(lastRelayCreatedAtMillis, packet.createdAtMillis)
                onRemoteEntry(packet)
            }
        }
    }

    private fun postPresence() {
        val profile = localProfile().trim().lowercase()
        if (!profile.startsWith("@")) return
        val payload = JSONObject()
            .put("app", APP_MARKER)
            .put("sourceId", deviceId)
            .put("profile", profile)
            .put("lastSeenMillis", System.currentTimeMillis())
            .toString()
        for (baseUrl in activeRelayUrls()) {
            runCatching {
                val connection = (URL("$baseUrl/presence").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = RELAY_TIMEOUT_MS
                    readTimeout = RELAY_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(payload)
                }
                connection.inputStream.use { it.readBytes() }
                connection.disconnect()
            }
        }
    }

    private fun pollPresence(): List<CollabPresence> {
        val online = mutableListOf<CollabPresence>()
        for (baseUrl in activeRelayUrls()) {
            val response = runCatching {
                val connection = (URL("$baseUrl/presence").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = RELAY_TIMEOUT_MS
                    readTimeout = RELAY_TIMEOUT_MS
                }
                val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                connection.disconnect()
                body
            }.getOrNull() ?: continue
            val presences = runCatching { JSONArray(response) }.getOrNull() ?: continue
            for (index in 0 until presences.length()) {
                val item = presences.optJSONObject(index) ?: continue
                if (item.optString("app") != APP_MARKER) continue
                val profile = item.optString("profile").trim().lowercase()
                if (!profile.startsWith("@")) continue
                online.add(
                    CollabPresence(
                        profile = profile,
                        sourceId = item.optString("sourceId"),
                        lastSeenMillis = item.optLong("lastSeenMillis", 0L),
                    ),
                )
            }
        }
        return online.distinctBy { it.sourceId }
    }

    private fun postCloud(text: String, createdAtMillis: Long, kind: String) {
        val config = cloudConfig() ?: run {
            Log.w(TAG, "Cloud post entry skipped: no cloud config")
            return
        }
        val normalizedKind = sharedKind(kind)
        val parsed = if (normalizedKind == JournalEntryInput.KIND_COLLAB) {
            parseCollabText(text)
        } else {
            CollabMessage(
                author = localProfile().trim().lowercase().ifBlank { "@unknown" },
                body = text,
            )
        }
        val payload = JSONObject()
            .put("source_id", deviceId)
            .put("author", parsed.author)
            .put("body", parsed.body)
            .put("text", text)
            .put("kind", normalizedKind)
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
                writer.write(payload)
            }
            connection.readCloudResponse("post entry")
            Log.i(TAG, "Cloud post entry ok")
            connection.disconnect()
        }
            .onFailure { error -> Log.w(TAG, "Cloud post entry failed: ${error.message}") }
    }

    private fun pollCloud() {
        val config = cloudConfig() ?: return
        val sinceMillis = (lastCloudCreatedAtMillis - CLOUD_POLL_OVERLAP_MS).coerceAtLeast(0L)
        val query = "select=source_id,text,kind,created_at_millis&created_at_millis=gte.$sinceMillis&order=created_at_millis.asc&limit=100"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_entries?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                setCloudHeaders(config)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrNull() ?: return
        val entries = runCatching { JSONArray(response) }.getOrNull() ?: return
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            if (item.optString("source_id") == deviceId) continue
            val kind = sharedKind(item.optString("kind"))
            val text = item.optString("text").trim()
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            if (text.isBlank() || createdAtMillis <= 0L) continue
            lastCloudCreatedAtMillis = maxOf(lastCloudCreatedAtMillis, createdAtMillis)
            onRemoteEntry(CollabPacket(text = text, createdAtMillis = createdAtMillis, kind = kind))
        }
    }

    private fun pollCloudTyping() {
        val config = cloudConfig() ?: return
        val local = localProfile().trim().lowercase()
        if (!local.startsWith("@")) return
        val sinceMillis = (lastCloudTypingMillis - CLOUD_POLL_OVERLAP_MS).coerceAtLeast(0L)
        val query = "select=source_id,author,target,typing,created_at_millis" +
            "&target=eq.${encodeFilter(local)}&created_at_millis=gte.$sinceMillis&order=created_at_millis.asc&limit=40"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_typing_events?$query").openConnection() as HttpURLConnection).apply {
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
            if (item.optString("source_id") == deviceId) continue
            val author = item.optString("author").trim().lowercase()
            val target = item.optString("target").trim().lowercase()
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            if (!author.startsWith("@") || target != local || createdAtMillis <= 0L) continue
            lastCloudTypingMillis = maxOf(lastCloudTypingMillis, createdAtMillis)
            onTyping(
                CollabTyping(
                    author = author,
                    target = target,
                    typing = item.optBoolean("typing"),
                    createdAtMillis = createdAtMillis,
                ),
            )
        }
    }

    private fun postCloudPresence() {
        val config = cloudConfig() ?: return
        val profile = localProfile().trim().lowercase()
        if (!profile.startsWith("@")) return
        val payload = JSONObject()
            .put("source_id", deviceId)
            .put("profile", profile)
            .put("last_seen_millis", System.currentTimeMillis())
            .toString()
        runCatching {
            val connection = (URL("${config.restUrl}/collab_presence?on_conflict=source_id").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                doOutput = true
                setCloudHeaders(config)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
            connection.readCloudResponse("post presence")
            connection.disconnect()
        }
            .onFailure { error -> Log.w(TAG, "Cloud post presence failed: ${error.message}") }
    }

    private fun registerCloudProfile() {
        val config = cloudConfig() ?: return
        val profile = localProfile().trim().lowercase()
        if (!profile.startsWith("@")) return
        val displayName = profile.removePrefix("@").replaceFirstChar { it.titlecase() }
        val payload = JSONObject()
            .put("profile", profile)
            .put("display_name", displayName)
            .put("last_source_id", deviceId)
            .toString()
        runCatching {
            val connection = (URL("${config.restUrl}/collab_profiles?on_conflict=profile").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                doOutput = true
                setCloudHeaders(config)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
            connection.readCloudResponse("register profile")
            connection.disconnect()
        }
            .onFailure { error -> Log.w(TAG, "Cloud register profile failed: ${error.message}") }
    }

    private fun pollCloudPresence(): List<CollabPresence> {
        val config = cloudConfig() ?: return emptyList()
        val cutoff = System.currentTimeMillis() - PRESENCE_RECENT_WINDOW_MS
        val query = "select=source_id,profile,last_seen_millis&last_seen_millis=gt.$cutoff&order=last_seen_millis.desc&limit=50"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_presence?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                setCloudHeaders(config)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrNull() ?: return emptyList()
        val presences = runCatching { JSONArray(response) }.getOrNull() ?: return emptyList()
        val online = mutableListOf<CollabPresence>()
        for (index in 0 until presences.length()) {
            val item = presences.optJSONObject(index) ?: continue
            val profile = item.optString("profile").trim().lowercase()
            if (!profile.startsWith("@")) continue
            online.add(
                CollabPresence(
                    profile = profile,
                    sourceId = item.optString("source_id"),
                    lastSeenMillis = item.optLong("last_seen_millis", 0L),
                ),
            )
        }
        return online.distinctBy { it.sourceId }
    }

    private fun pollCloudProfiles(): List<String> {
        val config = cloudConfig() ?: return emptyList()
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_profiles?select=profile&order=updated_at.desc&limit=50").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CLOUD_TIMEOUT_MS
                readTimeout = CLOUD_TIMEOUT_MS
                setCloudHeaders(config)
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrNull() ?: return emptyList()
        val profiles = runCatching { JSONArray(response) }.getOrNull() ?: return emptyList()
        val mentions = mutableListOf<String>()
        for (index in 0 until profiles.length()) {
            val profile = profiles.optJSONObject(index)?.optString("profile")?.trim()?.lowercase().orEmpty()
            if (profile.startsWith("@")) mentions.add(profile)
        }
        return mentions.distinct()
    }

    private fun parseRelayJson(json: JSONObject): CollabPacket? {
        if (json.optString("app") != APP_MARKER) return null
        if (json.optString("sourceId") == deviceId) return null
        val kind = sharedKind(json.optString("kind"))
        val text = json.optString("text").trim()
        val createdAtMillis = json.optLong("createdAtMillis", 0L)
        if (text.isBlank() || createdAtMillis <= 0L) return null
        return CollabPacket(text = text, createdAtMillis = createdAtMillis, kind = kind)
    }

    private fun activeRelayUrls(): List<String> {
        return (relayUrls() + FALLBACK_RELAY_BASE_URLS)
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    private fun shouldUseRelay(): Boolean {
        return cloudConfig() == null || relayUrls().isNotEmpty()
    }

    private fun HttpURLConnection.setCloudHeaders(config: CloudCollabConfig) {
        setRequestProperty("apikey", config.anonKey)
        setRequestProperty("Authorization", "Bearer ${config.anonKey}")
    }

    private fun HttpURLConnection.readCloudResponse(operation: String): ByteArray {
        val code = responseCode
        val body = (if (code in 200..299) inputStream else errorStream)
            ?.use { it.readBytes() }
            ?: ByteArray(0)
        if (code !in 200..299) {
            Log.w(TAG, "Cloud $operation HTTP $code: ${body.toString(Charsets.UTF_8).take(240)}")
            throw IllegalStateException("HTTP $code")
        }
        return body
    }

    private fun parseCollabText(text: String): CollabMessage = CollabMessage.parse(text, localProfile())

    private fun encodeFilter(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun sharedKind(kind: String): String {
        return when (EntryKindNormalizer.normalize(kind)) {
            JournalEntryInput.KIND_JOURNAL -> JournalEntryInput.KIND_JOURNAL
            JournalEntryInput.KIND_IDEA -> JournalEntryInput.KIND_IDEA
            JournalEntryInput.KIND_TASK -> JournalEntryInput.KIND_TASK
            JournalEntryInput.KIND_COLLAB -> JournalEntryInput.KIND_COLLAB
            else -> JournalEntryInput.KIND_COLLAB
        }
    }

    companion object {
        private const val APP_MARKER = "anytime-journal-collab-v1"
        private const val TAG = "CollabSyncManager"
        private const val BROADCAST_ADDRESS = "255.255.255.255"
        private const val COLLAB_PORT = 49373
        private const val MAX_PACKET_BYTES = 8 * 1024
        private const val RECEIVE_TIMEOUT_MS = 1500
        private val FALLBACK_RELAY_BASE_URLS = listOf(
            "http://127.0.0.1:49374",
            "http://10.0.2.2:49374",
            "http://192.168.137.1:49374",
        )
        private const val RELAY_POLL_MS = 1200L
        private const val CLOUD_MESSAGE_POLL_ACTIVE_MS = 750L
        private const val CLOUD_MESSAGE_POLL_IDLE_MS = 2_500L
        private const val CLOUD_INITIAL_LOOKBACK_MS = 2 * 60 * 1000L
        private const val CLOUD_POLL_OVERLAP_MS = 1L
        private const val CLOUD_PRESENCE_POLL_MS = 10_000L
        private const val PRESENCE_POST_MS = 15_000L
        private const val RELAY_TIMEOUT_MS = 900
        private const val CLOUD_TIMEOUT_MS = 1500
        private const val PRESENCE_RECENT_WINDOW_MS = 24 * 60 * 60 * 1000L
    }
}

data class CollabPacket(
    val text: String,
    val createdAtMillis: Long,
    val kind: String = JournalEntryInput.KIND_COLLAB,
)

data class CollabPresence(
    val profile: String,
    val sourceId: String,
    val lastSeenMillis: Long,
)

data class CollabTyping(
    val author: String,
    val target: String,
    val typing: Boolean,
    val createdAtMillis: Long,
)

data class CloudCollabConfig(
    val projectUrl: String,
    val anonKey: String,
) {
    val restUrl: String = "${projectUrl.trim().trimEnd('/')}/rest/v1"
}
