package com.daksh.anytimejournal

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class BubbleInputService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private lateinit var collabSync: CollabSyncManager
    private var bubbleView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var bubblePollJob: Job? = null
    private var lastBubbleCloudMillis = (System.currentTimeMillis() - BUBBLE_INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
    private var lastBubbleTranscriptMillis = (System.currentTimeMillis() - BUBBLE_INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
    private var lastBubbleCallSignalMillis = (System.currentTimeMillis() - BUBBLE_INITIAL_LOOKBACK_MS).coerceAtLeast(0L)
    private var lastBubblePopupMillis = 0L
    private val notifiedCallIds = mutableSetOf<String>()
    private var activeCallPopupAuthor: String? = null
    private var activeCallPopupTranscriptText: TextView? = null
    private var expanded = false
    private var movingMiniWindow = false
    private var activeBubbleKind = JournalEntryInput.KIND_JOURNAL
    private var bubbleClosedByUser = false
    private var screenPromptReceiver: BroadcastReceiver? = null
    private var lastScreenPromptAt = 0L

    override fun onCreate() {
        super.onCreate()
        startForeground(FOREGROUND_NOTIFICATION_ID, NotificationHelper.foregroundBubbleNotification(this))
        windowManager = getSystemService(WindowManager::class.java)
        collabSync = CollabSyncManager(
            context = this,
            scope = serviceScope,
            localProfile = { readLocalProfile() },
            relayUrls = { emptyList() },
            cloudConfig = { readCloudConfig() },
            isCollabActive = { true },
            onRemoteEntry = { packet -> handleRemoteCollab(packet) },
            onPresence = {},
            onProfiles = {},
            onTyping = {},
        )
        collabSync.start()
        startBubbleCloudPoll()
        registerScreenPromptReceiver()
        if (canDrawOverlays() && !isAppCollabForeground()) showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, NotificationHelper.foregroundBubbleNotification(this))
        if (intent?.action == ACTION_HIDE_BUBBLE) {
            hideBubbleForForeground()
            return START_STICKY
        }
        if (isAppCollabForeground() && intent?.action != ACTION_SHOW_MESSAGE && intent?.action != ACTION_SHOW_CALL) {
            hideBubbleForForeground()
        } else if (bubbleView == null && canDrawOverlays() && !bubbleClosedByUser) {
            showBubble()
        }
        if (intent?.action == ACTION_SHOW_MESSAGE && canDrawOverlays()) {
            showChatPopup(
                author = intent.getStringExtra(EXTRA_AUTHOR).orEmpty(),
                body = intent.getStringExtra(EXTRA_BODY).orEmpty(),
                targetProfile = intent.getStringExtra(EXTRA_TARGET_PROFILE).orEmpty(),
                createdAtMillis = intent.getLongExtra(EXTRA_CREATED_AT_MILLIS, System.currentTimeMillis()),
            )
        }
        if (intent?.action == ACTION_SHOW_CALL && canDrawOverlays()) {
            showCallPopup(
                author = intent.getStringExtra(EXTRA_CALL_AUTHOR).orEmpty(),
                targetProfile = intent.getStringExtra(EXTRA_CALL_TARGET).orEmpty(),
                transcript = intent.getStringExtra(EXTRA_CALL_TRANSCRIPT).orEmpty(),
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = null
        bubblePollJob?.cancel()
        screenPromptReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        screenPromptReceiver = null
        if (::collabSync.isInitialized) collabSync.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showBubble() {
        if (bubbleClosedByUser) return
        val bubble = buildCollapsedBubble()
        val params = bubbleParams(width = dp(64), height = dp(64)).apply {
            x = dp(18)
            y = dp(220)
        }
        currentParams = params
        attachDragAndClick(bubble, params)
        bubbleView = bubble
        windowManager.addView(bubble, params)
        pulse(bubble)
    }

    private fun registerScreenPromptReceiver() {
        if (screenPromptReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_ON) return
                val now = System.currentTimeMillis()
                if (now - lastScreenPromptAt < SCREEN_PROMPT_THROTTLE_MS) return
                lastScreenPromptAt = now
                FocusSessionScheduler.showUnlockPrompt(context.applicationContext)
            }
        }
        screenPromptReceiver = receiver
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    private fun expand(params: WindowManager.LayoutParams) {
        if (bubbleClosedByUser) return
        if (expanded) return
        expanded = true
        val input = buildTagPicker(params)
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = input
        params.width = dp(336)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(input, params)
        animateOpen(input)
    }

    private fun collapse(params: WindowManager.LayoutParams) {
        if (bubbleClosedByUser) return
        if (!expanded) return
        activeCallPopupAuthor = null
        activeCallPopupTranscriptText = null
        expanded = false
        val bubble = buildCollapsedBubble()
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = bubble
        params.width = dp(64)
        params.height = dp(64)
        windowManager.addView(bubble, params)
        attachDragAndClick(bubble, params)
        currentParams = params
        pulse(bubble)
    }

    private fun showChatPopup(author: String, body: String, targetProfile: String, createdAtMillis: Long) {
        bubbleClosedByUser = false
        val params = currentParams ?: bubbleParams(width = dp(342), height = WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dp(14)
            y = dp(160)
        }
        currentParams = params
        expanded = true
        val popup = buildChatPopup(params, author, body, targetProfile, createdAtMillis)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = popup
        params.width = dp(342)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(popup, params)
        animateOpen(popup)
        popup.postDelayed({
            if (bubbleView === popup && expanded && !movingMiniWindow) {
                collapse(params)
            }
        }, MESSAGE_POPUP_AUTO_COLLAPSE_MS)
    }

    private fun showCallPopup(author: String, targetProfile: String, transcript: String = "") {
        bubbleClosedByUser = false
        val existingTranscript = activeCallPopupTranscriptText
        if (activeCallPopupAuthor.equals(author, ignoreCase = true) &&
            existingTranscript != null &&
            existingTranscript.isAttachedToWindow
        ) {
            if (transcript.isNotBlank()) {
                existingTranscript.text = transcript
                existingTranscript.textSize = 16f
            }
            return
        }
        val params = currentParams ?: bubbleParams(width = dp(342), height = WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = dp(14)
            y = dp(160)
        }
        currentParams = params
        expanded = true
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(11))
            background = incomingMessageGlass()
            elevation = dp(16).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(7))
        }
        val heading = widgetHeading("${author.removePrefix("@")} calling")
        titleRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(collapseChip(params))
        panel.addView(titleRow)
        panel.addView(TextView(this).apply {
            text = transcript.ifBlank { "Listening before accept..." }
            textSize = if (transcript.isBlank()) 14f else 16f
            setTextColor(Color.rgb(28, 34, 36))
            setLineSpacing(0f, 1.05f)
            setPadding(dp(2), 0, dp(2), dp(9))
            maxLines = 5
            activeCallPopupTranscriptText = this
        })
        panel.addView(TextView(this).apply {
            text = "Accept starts your mic"
            textSize = 12f
            setTextColor(Color.argb(190, 64, 70, 72))
            setPadding(dp(2), 0, dp(2), dp(8))
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(hoverChip("Reply", selected = false).apply {
            setOnClickListener {
                pressBounce(this)
                postDelayed({ showReplyInput(params, author) }, 80)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        actions.addView(hoverChip("Accept", selected = true).apply {
            setOnClickListener {
                pressBounce(this)
                postDelayed({
                    startActivity(Intent(this@BubbleInputService, MainActivity::class.java).apply {
                        action = MainActivity.ACTION_OPEN_COLLAB
                        putExtra(MainActivity.EXTRA_OPEN_COLLAB, true)
                        putExtra(MainActivity.EXTRA_PROFILE, targetProfile)
                        putExtra(MainActivity.EXTRA_ACCEPT_CALL, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                    collapse(params)
                }, 80)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(actions)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = panel
        activeCallPopupAuthor = author
        params.width = dp(342)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.gravity = Gravity.TOP or Gravity.START
        windowManager.addView(panel, params)
        attachHeadingMove(heading, panel, params)
        animateOpen(panel)
    }

    private fun buildChatPopup(
        params: WindowManager.LayoutParams,
        author: String,
        body: String,
        targetProfile: String,
        createdAtMillis: Long,
    ): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(10), dp(13), dp(11))
            background = incomingMessageGlass()
            elevation = dp(16).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(7))
        }
        val heading = widgetHeading("${author.removePrefix("@")}  ${DateFormat.format("h:mm a", createdAtMillis)}")
        titleRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(collapseChip(params))
        panel.addView(titleRow)
        panel.addView(TextView(this).apply {
            text = body.ifBlank { "New message" }
            textSize = 16f
            setTextColor(Color.rgb(28, 34, 36))
            setLineSpacing(0f, 1.06f)
            setPadding(dp(2), 0, dp(2), dp(9))
            maxLines = 3
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(hoverChip("Reply", selected = true).apply {
            setOnClickListener {
                pressBounce(this)
                postDelayed({ showReplyInput(params, author) }, 80)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        actions.addView(hoverChip("Later", selected = false).apply {
            setOnClickListener {
                pressBounce(this)
                postDelayed({ collapse(params) }, 80)
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(actions)
        attachHeadingMove(heading, panel, params)
        startMiniPulse(actions.getChildAt(0), 1.035f)
        return panel
    }

    private fun showReplyInput(params: WindowManager.LayoutParams, peerProfile: String) {
        activeBubbleKind = JournalEntryInput.KIND_COLLAB
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = incomingMessageGlass()
            elevation = dp(16).toFloat()
        }
        val localProfile = readLocalProfile()
        val normalizedPeer = normalizeProfile(peerProfile) ?: peerProfile.trim().lowercase()
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(7))
        }
        val heading = widgetHeading("Reply ${normalizedPeer.removePrefix("@")}")
        titleRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(collapseChip(params))
        panel.addView(titleRow)

        val input = EditText(this).apply {
            hint = "Message ${normalizedPeer.removePrefix("@")}..."
            textSize = 17f
            minLines = 1
            maxLines = 2
            minHeight = dp(48)
            setTextColor(Color.rgb(34, 38, 40))
            setHintTextColor(Color.argb(170, 104, 112, 114))
            setPadding(0, dp(6), 0, dp(4))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        actionRow.addView(hoverChip("To ${normalizedPeer.removePrefix("@")}", selected = false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(8)
        })
        actionRow.addView(TextView(this).apply {
            text = "Send"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(78)
            minHeight = dp(42)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = ctaGradient()
            setOnClickListener {
                pressBounce(this)
                sendBubbleReply(params, input, localProfile, normalizedPeer)
            }
        })
        panel.addView(actionRow)
        panel.addView(input)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = panel
        params.width = dp(356)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        windowManager.addView(panel, params)
        attachHeadingMove(heading, panel, params, input)
        animateOpen(panel)
        input.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) {
                sendBubbleReply(params, input, localProfile, normalizedPeer)
                true
            } else {
                false
            }
        }
        input.post {
            input.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendBubbleReply(
        params: WindowManager.LayoutParams,
        input: EditText,
        localProfile: String,
        peerProfile: String,
    ) {
        val draft = input.text?.toString().orEmpty()
        if (draft.isBlank()) {
            input.animate().translationX(dp(5).toFloat()).setDuration(70).withEndAction {
                input.animate().translationX(0f).setDuration(90).start()
            }.start()
            return
        }
        val createdAtMillis = System.currentTimeMillis()
        val preparedText = CollabMessage.prepareDraft(
            draft,
            localProfile,
            peerProfile,
            listOf(localProfile, peerProfile),
        )
        serviceScope.launch(Dispatchers.IO) {
            val savedEntryId = EntryRepository.from(this@BubbleInputService).saveReply(
                preparedText,
                nowMillis = createdAtMillis,
                kind = JournalEntryInput.KIND_COLLAB,
            )
            if (savedEntryId != null) {
                collabSync.broadcast(preparedText, createdAtMillis)
            }
            withContext(Dispatchers.Main) {
                input.text.clear()
                NotificationHelper.showPersistentInput(this@BubbleInputService)
                collapse(params)
            }
        }
    }

    private fun handleRemoteCollab(packet: CollabPacket) {
        Log.d(LOG_TAG, "remote collab packet ${packet.createdAtMillis}: ${packet.text.take(80)}")
        serviceScope.launch(Dispatchers.IO) {
            val savedId = EntryRepository.from(this@BubbleInputService)
                .saveRemoteCollab(packet.text, packet.createdAtMillis)
            val localProfile = readLocalProfile()
            val parsed = CollabMessage.parse(packet.text, localProfile)
            Log.d(LOG_TAG, "remote saved=$savedId local=$localProfile author=${parsed.author} mentions=${parsed.mentions(localProfile)}")
            val shouldPopup = !parsed.author.equals(localProfile, ignoreCase = true) &&
                parsed.mentions(localProfile) &&
                packet.createdAtMillis > lastBubblePopupMillis
            if (shouldPopup) {
                lastBubblePopupMillis = packet.createdAtMillis
                val body = parsed.visibleBody(localProfile, parsed.author)
                withContext(Dispatchers.Main) {
                    NotificationHelper.showMention(
                        context = this@BubbleInputService,
                        author = parsed.author,
                        body = body,
                        createdAtMillis = packet.createdAtMillis,
                        targetProfile = localProfile,
                    )
                    if (canDrawOverlays()) {
                        showChatPopup(
                            author = parsed.author,
                            body = body,
                            targetProfile = localProfile,
                            createdAtMillis = packet.createdAtMillis,
                        )
                    }
                }
            }
        }
    }

    private fun startBubbleCloudPoll() {
        if (bubblePollJob?.isActive == true) return
        bubblePollJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                pollBubbleCloud()
                pollBubbleTranscripts()
                pollBubbleCallSignals()
                delay(BUBBLE_CLOUD_POLL_MS)
            }
        }
    }

    private fun pollBubbleCloud() {
        val config = readCloudConfig() ?: return
        val since = (lastBubbleCloudMillis - 1L).coerceAtLeast(0L)
        val query = "select=source_id,text,kind,created_at_millis&kind=eq.collab&created_at_millis=gte.$since&order=created_at_millis.asc&limit=40"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_entries?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
                setRequestProperty("apikey", config.anonKey)
                setRequestProperty("Authorization", "Bearer ${config.anonKey}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrElse {
            Log.w(LOG_TAG, "bubble cloud poll failed", it)
            return
        }
        val entries = runCatching { JSONArray(response) }.getOrNull() ?: return
        for (index in 0 until entries.length()) {
            val item = entries.optJSONObject(index) ?: continue
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            if (createdAtMillis <= lastBubbleCloudMillis || createdAtMillis <= 0L) continue
            lastBubbleCloudMillis = maxOf(lastBubbleCloudMillis, createdAtMillis)
            val text = item.optString("text").trim()
            if (text.isBlank()) continue
            handleRemoteCollab(CollabPacket(text = text, createdAtMillis = createdAtMillis))
        }
    }

    private fun pollBubbleCallSignals() {
        val config = readCloudConfig() ?: return
        val localProfile = readLocalProfile()
        val since = (lastBubbleCallSignalMillis - 1L).coerceAtLeast(0L)
        val query = "select=call_id,author,target,type,created_at_millis" +
            "&target=eq.${encodeFilter(localProfile)}&type=eq.offer&created_at_millis=gte.$since" +
            "&order=created_at_millis.asc&limit=20"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_call_signals?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
                setRequestProperty("apikey", config.anonKey)
                setRequestProperty("Authorization", "Bearer ${config.anonKey}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrElse {
            Log.w(LOG_TAG, "bubble call signal poll failed", it)
            return
        }
        val rows = runCatching { JSONArray(response) }.getOrNull() ?: return
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            if (createdAtMillis <= lastBubbleCallSignalMillis || createdAtMillis <= 0L) continue
            lastBubbleCallSignalMillis = maxOf(lastBubbleCallSignalMillis, createdAtMillis)
            val callId = item.optString("call_id").trim()
            val author = item.optString("author").trim().lowercase()
            if (callId.isBlank() || author.isBlank() || author.equals(localProfile, ignoreCase = true)) continue
            if (!notifiedCallIds.add(callId)) continue
            if (isAppHandlingCollabPeer(author)) {
                NotificationHelper.cancelIncomingCall(this)
                continue
            }
            withMain {
                NotificationHelper.showIncomingCall(this@BubbleInputService, author, localProfile)
                if (canDrawOverlays()) showCallPopup(author, localProfile)
            }
        }
    }

    private fun pollBubbleTranscripts() {
        val config = readCloudConfig() ?: return
        val localProfile = readLocalProfile()
        val since = (lastBubbleTranscriptMillis - 1L).coerceAtLeast(0L)
        val query = "select=author,target,transcript,created_at_millis" +
            "&target=eq.${encodeFilter(localProfile)}&created_at_millis=gte.$since" +
            "&order=created_at_millis.asc&limit=20"
        val response = runCatching {
            val connection = (URL("${config.restUrl}/collab_call_transcripts?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
                setRequestProperty("apikey", config.anonKey)
                setRequestProperty("Authorization", "Bearer ${config.anonKey}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            body
        }.getOrElse {
            Log.w(LOG_TAG, "bubble transcript poll failed", it)
            return
        }
        val rows = runCatching { JSONArray(response) }.getOrNull() ?: return
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val createdAtMillis = item.optLong("created_at_millis", 0L)
            val author = item.optString("author").trim().lowercase()
            val transcript = item.optString("transcript").trim()
            if (createdAtMillis <= lastBubbleTranscriptMillis || createdAtMillis <= 0L) continue
            lastBubbleTranscriptMillis = maxOf(lastBubbleTranscriptMillis, createdAtMillis)
            if (author.equals(localProfile, ignoreCase = true) || transcript.isBlank()) continue
            if (isAppHandlingCollabPeer(author)) continue
            withMain {
                if (canDrawOverlays()) showCallPopup(author, localProfile, transcript)
            }
        }
    }

    private fun withMain(block: () -> Unit) {
        serviceScope.launch(Dispatchers.Main) { block() }
    }

    private fun buildCollapsedBubble(): TextView {
        return TextView(this).apply {
            text = "+"
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = glassGradient(radius = dp(32), strong = true)
            elevation = dp(12).toFloat()
        }
    }

    private fun buildTagPicker(params: WindowManager.LayoutParams): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = hoverInputGlass()
            elevation = dp(14).toFloat()
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val heading = widgetHeading("Choose tag")
        titleRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(collapseChip(params))
        panel.addView(titleRow)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf(
            JournalEntryInput.KIND_JOURNAL to "#journal",
            JournalEntryInput.KIND_IDEA to "#idea",
            JournalEntryInput.KIND_TASK to "#task",
        ).forEach { (kind, label) ->
            row.addView(hoverChip(label, selected = kind == JournalEntryInput.KIND_JOURNAL).apply {
                setOnClickListener {
                    pressBounce(this)
                    it.postDelayed({ showInputForKind(params, kind) }, 90)
                }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(7)
            })
        }
        panel.addView(row)
        attachHeadingMove(heading, panel, params)
        return panel
    }

    private fun showInputForKind(params: WindowManager.LayoutParams, kind: String) {
        activeBubbleKind = kind
        val inputPanel = buildExpandedBubble(params, kind)
        bubbleView?.let { windowManager.removeView(it) }
        bubbleView = inputPanel
        params.width = dp(356)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        windowManager.addView(inputPanel, params)
        animateOpen(inputPanel)
    }

    private fun buildExpandedBubble(params: WindowManager.LayoutParams, kind: String): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(12), dp(10))
            background = hoverInputGlass()
            elevation = dp(14).toFloat()
        }
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(7))
        }
        val heading = widgetHeading("Anytime capture")
        titleRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(collapseChip(params))
        val input = EditText(this).apply {
            hint = "${hintForKind(kind)} ${EntryUiFormatter.kindPrefix(kind)}"
            textSize = 17f
            minLines = 1
            maxLines = 1
            minHeight = dp(48)
            setTextColor(Color.rgb(34, 38, 40))
            setHintTextColor(Color.argb(170, 104, 112, 114))
            setPadding(0, dp(6), 0, dp(3))
            background = null
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val kindChip = hoverChip(EntryUiFormatter.kindPrefix(kind), selected = true)
        val timeChip = hoverChip("Now ${DateFormat.format("h:mm a", System.currentTimeMillis())}", selected = false)
        chipRow.addView(kindChip)
        chipRow.addView(timeChip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chipRow.addView(TextView(this).apply {
            text = "Post"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(74)
            minHeight = dp(42)
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = ctaGradient()
            setOnClickListener {
                pressBounce(this)
                saveBubbleInput(params, input, kind)
            }
        })
        panel.addView(titleRow)
        panel.addView(chipRow)
        panel.addView(input)
        attachHeadingMove(heading, panel, params, input)
        startMiniPulse(kindChip, 1.04f)
        startMiniPulse(chipRow.getChildAt(2), 1.035f)
        input.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_SEND || enterPressed) {
                saveBubbleInput(params, input, kind)
                true
            } else {
                false
            }
        }
        input.setOnFocusChangeListener { _, hasFocus ->
            panel.animate()
                .scaleX(if (hasFocus) 1.006f else 1f)
                .scaleY(if (hasFocus) 1.006f else 1f)
                .translationY(if (hasFocus) -dp(2).toFloat() else 0f)
                .setDuration(140)
                .setInterpolator(DecelerateInterpolator())
                .start()
            if (!hasFocus) {
                input.postDelayed({
                    if (!input.hasFocus() && !movingMiniWindow && expanded) {
                        collapse(params)
                    }
                }, FOCUS_LOSS_COLLAPSE_MS)
            }
        }
        input.post {
            input.requestFocus()
                getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
        return panel
    }

    private fun saveBubbleInput(params: WindowManager.LayoutParams, input: EditText, kind: String) {
        val textValue = input.text?.toString().orEmpty()
        if (textValue.isBlank()) {
            input.animate().translationX(dp(5).toFloat()).setDuration(70).withEndAction {
                input.animate().translationX(0f).setDuration(90).start()
            }.start()
            return
        }
        val createdAtMillis = System.currentTimeMillis()
        serviceScope.launch(Dispatchers.IO) {
            val savedEntryId = EntryRepository.from(this@BubbleInputService).saveReply(
                textValue,
                nowMillis = createdAtMillis,
                kind = kind,
            )
            if (savedEntryId != null && kind == JournalEntryInput.KIND_TASK) {
                TaskReminderScheduler.schedule(
                    this@BubbleInputService,
                    savedEntryId,
                    textValue,
                    createdAtMillis + JournalEntryInput.DEFAULT_TASK_REMINDER_MS,
                )
            }
            withContext(Dispatchers.Main) {
                NotificationHelper.showPersistentInput(this@BubbleInputService)
                collapse(params)
            }
        }
    }

    private fun animateOpen(view: View) {
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.rotation = 0f
        view.translationY = dp(14).toFloat()
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(210)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hintForKind(kind: String): String {
        return when (kind) {
            JournalEntryInput.KIND_IDEA -> "Drop an idea..."
            JournalEntryInput.KIND_TASK -> "Task to remember..."
            else -> "Log the moment..."
        }
    }

    private fun widgetHeading(label: String): TextView {
        return TextView(this).apply {
            text = "$label  |  drag to move"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(34, 38, 40))
            setPadding(dp(2), 0, dp(8), 0)
        }
    }

    private fun attachHeadingMove(
        handle: View,
        windowView: View,
        params: WindowManager.LayoutParams,
        focusTarget: EditText? = null,
    ) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moving = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moving = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (!moving && (kotlin.math.abs(dx) > dp(8) || kotlin.math.abs(dy) > dp(8))) {
                        moving = true
                        movingMiniWindow = true
                        windowView.pivotX = dp(30).toFloat()
                        windowView.pivotY = dp(30).toFloat()
                        windowView.animate()
                            .scaleX(0.86f)
                            .scaleY(0.86f)
                            .alpha(0.88f)
                            .setDuration(70)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    }
                    if (moving) {
                        params.x = startX + dx
                        params.y = startY + dy
                        windowView.rotation = (dx / 70f).coerceIn(-3f, 3f)
                        windowManager.updateViewLayout(windowView, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving) {
                        if (isInCloseZone(params, windowView)) {
                            closeBubbleView()
                            true
                        } else {
                        windowView.animate()
                            .rotation(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(115)
                            .setInterpolator(DecelerateInterpolator())
                            .withEndAction {
                                focusTarget?.post {
                                    focusTarget.requestFocus()
                                    getSystemService(InputMethodManager::class.java)
                                        .showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT)
                                }
                                movingMiniWindow = false
                            }
                            .start()
                        }
                    } else {
                        focusTarget?.post {
                            focusTarget.requestFocus()
                            getSystemService(InputMethodManager::class.java)
                                .showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT)
                            movingMiniWindow = false
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragged = false
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragged = false
                    touched.animate()
                        .scaleX(0.93f)
                        .scaleY(0.93f)
                        .alpha(0.86f)
                        .setDuration(90)
                        .start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > dp(5) || kotlin.math.abs(dy) > dp(5)) dragged = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(touched, params)
                    if (dragged) {
                        touched.rotation = (dx / 18f).coerceIn(-8f, 8f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged && isInCloseZone(params, touched)) {
                        closeBubbleView()
                        return@setOnTouchListener true
                    }
                    touched.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .rotation(0f)
                        .setDuration(160)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                    if (!dragged) {
                        pressBounce(touched)
                        touched.postDelayed({ expand(params) }, 85)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun closeBubbleView() {
        bubbleClosedByUser = true
        activeCallPopupAuthor = null
        activeCallPopupTranscriptText = null
        expanded = false
        movingMiniWindow = false
        bubbleView?.let { view ->
            runCatching {
                view.animate()
                    .scaleX(0.55f)
                    .scaleY(0.55f)
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction {
                        runCatching { windowManager.removeView(view) }
                    }
                    .start()
            }.onFailure {
                runCatching { windowManager.removeView(view) }
            }
        }
        bubbleView = null
    }

    private fun hideBubbleForForeground() {
        activeCallPopupAuthor = null
        activeCallPopupTranscriptText = null
        expanded = false
        movingMiniWindow = false
        bubbleView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        bubbleView = null
    }

    private fun isInCloseZone(params: WindowManager.LayoutParams, view: View): Boolean {
        val metrics = resources.displayMetrics
        val width = if (view.width > 0) view.width else params.width.coerceAtLeast(dp(64))
        val height = if (view.height > 0) view.height else params.height.coerceAtLeast(dp(64))
        val centerX = params.x + width / 2
        val centerY = params.y + height / 2
        val closeCenterX = metrics.widthPixels / 2
        val closeCenterY = metrics.heightPixels - dp(42)
        return abs(centerX - closeCenterX) <= dp(92) && centerY >= closeCenterY - dp(98)
    }

    private fun bubbleParams(width: Int, height: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            width,
            height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun canDrawOverlays(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun readLocalProfile(): String {
        return AppPrefs.readLocalProfile(this)
    }

    private fun readCloudConfig(): CloudCollabConfig? {
        return AppPrefs.readCloudConfig(this)
    }

    private fun isAppHandlingCollabPeer(author: String): Boolean {
        val activePeer = AppPrefs.activeChatPeer(this)
        return isAppCollabForeground() &&
            activePeer.equals(author, ignoreCase = true)
    }

    private fun isAppCollabForeground(): Boolean {
        return AppPrefs.isCollabForeground(this)
    }

    private fun normalizeProfile(profile: String): String? {
        return AppPrefs.normalizeProfile(profile)
    }

    private fun encodeFilter(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun glassGradient(radius: Int, strong: Boolean): GradientDrawable {
        val alpha = if (strong) 218 else 188
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(alpha, 47, 111, 94),
                Color.argb(alpha, 72, 87, 166),
                Color.argb(alpha, 172, 106, 37),
            ),
        ).apply {
            cornerRadius = radius.toFloat()
            setStroke(dp(1), Color.argb(180, 255, 255, 255))
        }
    }

    private fun hoverInputGlass(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(198, 248, 253, 250),
                Color.argb(186, 228, 243, 237),
                Color.argb(178, 246, 250, 247),
            ),
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.argb(170, 255, 255, 255))
        }
    }

    private fun incomingMessageGlass(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.argb(224, 245, 240, 255),
                Color.argb(210, 235, 247, 244),
                Color.argb(204, 255, 250, 242),
            ),
        ).apply {
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.argb(190, 255, 255, 255))
        }
    }

    private fun hoverChip(label: String, selected: Boolean): TextView {
        val fill = if (selected) Color.argb(224, 47, 111, 94) else Color.argb(116, 255, 255, 255)
        val textColor = if (selected) Color.WHITE else Color.rgb(90, 74, 138)
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = dp(34)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = dp(9).toFloat()
                setStroke(dp(1), if (selected) Color.rgb(47, 111, 94) else Color.argb(160, 255, 255, 255))
            }
        }
    }

    private fun collapseChip(params: WindowManager.LayoutParams): TextView {
        return hoverChip("-", selected = false).apply {
            minWidth = dp(34)
            setOnClickListener {
                pressBounce(this)
                postDelayed({ collapse(params) }, 70)
            }
        }
    }

    private fun ctaGradient(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(47, 111, 94), Color.rgb(98, 149, 133)),
        ).apply {
            cornerRadius = dp(14).toFloat()
        }
    }

    private fun pulse(view: View) {
        view.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .alpha(0.9f)
            .setDuration(850)
            .withEndAction {
                if (!view.isAttachedToWindow) return@withEndAction
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(850)
                    .withEndAction { if (view.isAttachedToWindow) pulse(view) }
                    .start()
            }
            .start()
    }

    private fun startMiniPulse(view: View, scale: Float) {
        view.postDelayed({
            if (!view.isAttachedToWindow) return@postDelayed
            view.animate()
                .scaleX(scale)
                .scaleY(scale)
                .alpha(0.9f)
                .setDuration(680)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    if (!view.isAttachedToWindow) return@withEndAction
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(680)
                        .withEndAction { if (view.isAttachedToWindow) startMiniPulse(view, scale) }
                        .start()
                }
                .start()
        }, 360)
    }

    private fun pressBounce(view: View) {
        view.animate()
            .scaleX(0.94f)
            .scaleY(0.94f)
            .setDuration(70)
            .withEndAction {
                if (!view.isAttachedToWindow) return@withEndAction
                view.animate()
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setDuration(95)
                    .withEndAction {
                        if (view.isAttachedToWindow) {
                            view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val ACTION_SHOW_MESSAGE = "com.daksh.anytimejournal.SHOW_BUBBLE_MESSAGE"
        const val ACTION_SHOW_CALL = "com.daksh.anytimejournal.SHOW_BUBBLE_CALL"
        const val ACTION_HIDE_BUBBLE = "com.daksh.anytimejournal.HIDE_BUBBLE"
        const val EXTRA_AUTHOR = "author"
        const val EXTRA_BODY = "body"
        const val EXTRA_TARGET_PROFILE = "target_profile"
        const val EXTRA_CREATED_AT_MILLIS = "created_at_millis"
        const val EXTRA_CALL_AUTHOR = "call_author"
        const val EXTRA_CALL_TARGET = "call_target"
        const val EXTRA_CALL_TRANSCRIPT = "call_transcript"
        private const val FOREGROUND_NOTIFICATION_ID = 3101
        private const val LONG_PRESS_TO_MOVE_MS = 110L
        private const val FOCUS_LOSS_COLLAPSE_MS = 140L
        private const val MESSAGE_POPUP_AUTO_COLLAPSE_MS = 30_000L
        private const val BUBBLE_CLOUD_POLL_MS = 1_000L
        private const val BUBBLE_INITIAL_LOOKBACK_MS = 2 * 60 * 1000L
        private const val SCREEN_PROMPT_THROTTLE_MS = 60_000L
        private const val LOG_TAG = "AnytimeBubble"
    }
}
