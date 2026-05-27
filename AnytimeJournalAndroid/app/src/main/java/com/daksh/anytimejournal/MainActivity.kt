package com.daksh.anytimejournal

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var entriesContainer: LinearLayout
    private lateinit var kindSelector: LinearLayout
    private lateinit var filterSelector: LinearLayout
    private lateinit var quickInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var statsText: TextView
    private lateinit var pageTitleText: TextView
    private lateinit var pageSubtitleText: TextView
    private lateinit var callActionText: TextView
    private lateinit var collabUserRow: LinearLayout
    private lateinit var collabPeerText: TextView
    private lateinit var collabStatusText: TextView
    private lateinit var captureKindText: TextView
    private lateinit var captureToolsRow: LinearLayout
    private lateinit var timeSelectorText: TextView
    private lateinit var postActionText: TextView
    private lateinit var capturePanel: View
    private lateinit var collabPanel: View
    private var startupSetupOverlay: View? = null
    private lateinit var mainScreenRenderer: MainScreenRenderer
    private lateinit var entryListRenderer: EntryListRenderer
    private lateinit var collabUiController: CollabUiController
    private lateinit var collabSync: CollabSyncManager
    private lateinit var voiceCallManager: VoiceCallManager

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCallState: VoiceCallState? = null
    private val callTimerTick = object : Runnable {
        override fun run() {
            val state = lastCallState ?: return
            if (state.active && ::callActionText.isInitialized) {
                updateCallAction(state)
                mainHandler.postDelayed(this, 1_000L)
            }
        }
    }
    private var selectedKind = JournalEntryInput.KIND_JOURNAL
    private var selectedEntryTimeMillis = System.currentTimeMillis()
    private var customEntryTimeSelected = false
    private var collabAssignTaskMode = false
    private var collabSendLaterMode = false
    private var activeFilter: String? = null
    private var editingEntryId: Long? = null
    private var activeCollabUser = "@daksh"
    private var activeChatPeer = "@sid"
    private val customKinds = mutableListOf<String>()
    private var allEntries: List<EntryEntity> = emptyList()
    private var profiles: List<ProfileEntity> = defaultProfiles()
    private var onlineProfiles: Set<String> = emptySet()
    private var cloudProfiles: Set<String> = emptySet()
    private var lastRenderedCallTranscript = ""
    private var callTranscriptTextView: TextView? = null
    private var callTranscriptOwnerTextView: TextView? = null
    private var pendingRecordAudioAction: String? = null
    private var appResumed = false
    private var lastPersistedAppVisible: Boolean? = null
    private var lastPersistedCollabVisible: Boolean? = null
    private var lastPersistedChatPeer: String? = null
    private var lastHideBubbleRequestAt = 0L
    private var remoteTypingPeer: String? = null
    private var remoteTypingUntilMillis = 0L
    private var lastTypingSentAtMillis = 0L
    private var lastTypingActive = false
    private val typingClearTick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= remoteTypingUntilMillis) {
                remoteTypingPeer = null
                updateCollabHeader()
                renderEntries()
            } else {
                mainHandler.postDelayed(this, 450L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val savedProfile = hasSavedProfile()
        val needsStartupSetup = !savedProfile && requestedProfile(intent) == null
        activeCollabUser = if (savedProfile) readLocalProfile() else requestedProfile(intent) ?: readLocalProfile()
        if (!savedProfile && requestedProfile(intent) != null) {
            saveProfileToPrefs(activeCollabUser)
        }
        mainScreenRenderer = MainScreenRenderer(this)
        entryListRenderer = EntryListRenderer(
            object : EntryListRenderer.Callbacks {
                override fun onEditEntry(view: View, entry: EntryEntity) = openEntryForEdit(view, entry)
                override fun onEntryDeleted() = loadEntries()
            },
        )
        collabUiController = CollabUiController(
            object : CollabUiController.Callbacks {
                override fun onSelectPeer(profile: String) {
                    selectCollabPeer(profile)
                }

                override fun onRenderEntries() = renderEntries()

                override fun onPublishTyping(peer: String, typing: Boolean) {
                    if (::collabSync.isInitialized) collabSync.publishTyping(peer, typing)
                }
            },
        )
        setContentView(buildContent())
        collabSync = CollabSyncManager(
            context = this,
            scope = activityScope,
            localProfile = { activeCollabUser },
            relayUrls = { relayBaseUrls() },
            cloudConfig = { readCloudConfig() },
            isCollabActive = {
                selectedKind == JournalEntryInput.KIND_COLLAB ||
                    activeFilter == JournalEntryInput.KIND_COLLAB
            },
            onRemoteEntry = { packet ->
                activityScope.launch(Dispatchers.IO) {
                    if (!shouldAcceptRemotePacket(packet)) return@launch
                    val savedId = EntryRepository.from(this@MainActivity)
                        .saveRemoteSharedEntry(packet.text, packet.createdAtMillis, packet.kind)
                    if (savedId != null) {
                        withContext(Dispatchers.Main) {
                            notifyIfMentioned(packet)
                            loadEntries()
                        }
                    }
                }
            },
            onPresence = { presences ->
                activityScope.launch(Dispatchers.Main) {
                    onlineProfiles = presences
                        .filter { System.currentTimeMillis() - it.lastSeenMillis <= ONLINE_WINDOW_MS }
                        .map { it.profile }
                        .toSet()
                    syncActivePeerWithPresence()
                    renderCollabUsers()
                    applyCaptureTheme(animate = false)
                    renderEntries()
                }
            },
            onProfiles = { mentions ->
                activityScope.launch(Dispatchers.Main) {
                    cloudProfiles = mentions
                        .map { it.lowercase() }
                        .filter { it.startsWith("@") }
                        .toSet()
                    renderCollabUsers()
                }
            },
            onTyping = { typing ->
                activityScope.launch(Dispatchers.Main) {
                    handleRemoteTyping(typing)
                }
            },
        )
        voiceCallManager = VoiceCallManager(
            context = this,
            scope = activityScope,
            localProfile = { activeCollabUser },
            peerProfile = { activeChatPeer },
            cloudConfig = { readCloudConfig() },
            onStateChanged = { updateCallAction(it) },
            onIncomingCall = { author -> showIncomingCallSurfaces(author) },
        )
        collabSync.start()
        voiceCallManager.start()
        loadProfiles()
        if (!needsStartupSetup) {
            completeInitialSetup()
        }
        loadEntries()
        handleLaunchIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        appResumed = true
        persistAppSurfaceState()
        startBubbleIfAllowed()
    }

    override fun onPause() {
        appResumed = false
        persistAppSurfaceState()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        startBubbleIfAllowed()
    }

    override fun onDestroy() {
        appResumed = false
        persistAppSurfaceState()
        mainHandler.removeCallbacks(callTimerTick)
        collabSync.stop()
        voiceCallManager.stop()
        activityScope.cancel()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val frame = FrameLayout(this).apply {
            background = appBackground()
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(250))
            background = appBackground()
        }

        root.addView(buildHeader())
        collabPanel = buildCollabPanel()
        root.addView(collabPanel)
        root.addView(buildSearchPanel())

        entriesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(entriesContainer)

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            background = appBackground()
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        frame.addView(scrollView)

        val footerShell = FrameLayout(this).apply {
            background = captureFooterBackground()
            setPadding(dp(12), dp(8), dp(12), dp(28))
        }
        capturePanel = buildCapturePanel().apply {
            elevation = dp(8).toFloat()
        }
        footerShell.addView(
            capturePanel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        frame.addView(
            footerShell,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        if (!hasSavedProfile() && requestedProfile(intent) == null) {
            startupSetupOverlay = buildStartupSetupOverlay()
            frame.addView(
                startupSetupOverlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        return frame
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(14))
        }

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        pageTitleText = TextView(this).apply {
            text = "Anytime Journal"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            includeFontPadding = false
        }
        copy.addView(pageTitleText)
        pageSubtitleText = TextView(this).apply {
            text = "Inbox for thoughts, tasks, and small logs"
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(5), 0, 0)
        }
        copy.addView(pageSubtitleText)

        statsText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(COLOR_SURFACE, dp(8), COLOR_LINE, 1)
        }

        header.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        callActionText = actionText("Call", COLOR_OBSIDIAN).apply {
            minWidth = dp(66)
            visibility = View.GONE
            setOnClickListener { handleCallAction() }
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        handleCallAction()
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                        true
                    }
                    else -> true
                }
            }
        }
        header.addView(
            callActionText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = dp(8)
            },
        )
        header.addView(statsText)
        return header
    }

    private fun buildCollabPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = chatShellBackground()
        }
        panel.layoutParams = blockParams(bottom = dp(6))

        val peerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        collabPeerText = TextView(this).apply {
            text = "Online"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_OBSIDIAN)
            includeFontPadding = false
        }
        collabStatusText = TextView(this).apply {
            visibility = View.GONE
        }
        peerRow.addView(collabPeerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(peerRow)

        collabUserRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        renderCollabUsers()
        panel.addView(horizontalScroller(collabUserRow))
        return panel
    }

    private fun renderCollabUsers() {
        if (!::collabUserRow.isInitialized) return
        collabUserRow.removeAllViews()
        updateCollabHeader()
        val onlineUsers = collabUiController.visibleOnlineUsers(activeCollabUser, onlineProfiles)
        if (onlineUsers.isEmpty()) {
            collabUserRow.addView(chip("No one online", false, COLOR_MUTED))
            return
        }
        onlineUsers.forEach { user ->
            if (user.equals(activeCollabUser, ignoreCase = true)) {
                collabUserRow.addView(chip(user.removePrefix("@"), true, COLOR_OBSIDIAN).apply {
                    setOnClickListener { showLockedProfileDialog() }
                })
            } else {
                collabUserRow.addView(chatUserPill(user, online = true, selected = activeChatPeer == user))
            }
        }
    }

    private fun buildCapturePanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = captureBackground(selectedKind)
        }

        quickInput = EditText(this).apply {
            hint = "Write anything... add #tags if useful"
            textSize = 17f
            minLines = 1
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            minHeight = dp(46)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            setPadding(0, dp(6), 0, dp(5))
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!customEntryTimeSelected) updateTimeSelectorLabel()
                    publishTypingFromInput(hasTypedWord(s))
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, actionId, event ->
                val enterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (actionId == EditorInfo.IME_ACTION_SEND || enterUp) {
                    saveQuickEntry()
                    true
                } else {
                    false
                }
            }
        }

        val timeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        captureKindText = chip(EntryUiFormatter.kindPrefix(selectedKind), true, colorForKind(selectedKind))
        timeSelectorText = chip(timeSelectorLabel(), true, COLOR_OBSIDIAN).apply {
            setOnClickListener { showEntryTimePicker() }
        }
        attachCaptureSwipe(captureKindText, panel)
        timeRow.addView(captureKindText)
        timeRow.addView(timeSelectorText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        attachCaptureSwipe(timeSelectorText, panel)
        val nowText = chip("Now", false, COLOR_MUTED).apply {
            setOnClickListener {
                resetSelectedEntryTime()
                updateTimeSelectorLabel()
            }
        }
        attachCaptureSwipe(nowText, panel)
        timeRow.addView(nowText)
        postActionText = actionText("Post", colorForKind(selectedKind)).apply {
            tag = ACTION_POST_TAG
            setOnClickListener { saveQuickEntry() }
        }
        attachCaptureSwipe(postActionText, panel)
        timeRow.addView(postActionText)
        panel.addView(timeRow)
        captureToolsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        val toolsScroller = horizontalScroller(captureToolsRow).apply {
            attachCaptureSwipe(this, panel)
        }
        panel.addView(toolsScroller)
        panel.addView(quickInput)
        attachCaptureSwipe(panel)
        applyCaptureTheme(panel, animate = false)

        kindSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        renderKindSelector()
        panel.addView(horizontalScroller(kindSelector))
        return panel
    }

    private fun buildSearchPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panel.layoutParams = blockParams(bottom = dp(6))

        searchInput = EditText(this).apply {
            hint = "Search text, #tag, or type"
            setSingleLine(true)
            textSize = 14f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(COLOR_SURFACE, dp(8), COLOR_LINE, 1)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderEntries()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        filterSelector = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        panel.addView(horizontalScroller(filterSelector))
        panel.addView(searchInput)
        renderFilterSelector()
        return panel
    }

    private fun buildStartupSetupOverlay(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(232, 246, 248, 245))
            isClickable = true
            isFocusable = true
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
            background = chatShellBackground()
            elevation = dp(18).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "Set chat name"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_OBSIDIAN)
            includeFontPadding = false
        })
        card.addView(TextView(this).apply {
            text = "Register once, then app will setup notifications and bubble."
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(8), 0, dp(16))
        })
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(4))
            background = rounded(COLOR_SURFACE, dp(14), COLOR_LINE, 1)
        }
        inputRow.addView(TextView(this).apply {
            text = "@"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_OBSIDIAN)
            includeFontPadding = false
        })
        val usernameInput = EditText(this).apply {
            hint = "username"
            setSingleLine(true)
            textSize = 20f
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            background = null
            setPadding(dp(4), dp(8), 0, dp(8))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        inputRow.addView(usernameInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(inputRow)
        val errorText = TextView(this).apply {
            text = ""
            textSize = 12f
            setTextColor(Color.rgb(190, 64, 64))
            setPadding(dp(2), dp(7), dp(2), dp(10))
        }
        card.addView(errorText)
        val register = ctaText("Register", CtaTone.PRIMARY, COLOR_OBSIDIAN).apply {
            minHeight = dp(52)
            setOnClickListener {
                val normalized = normalizeProfile(usernameInput.text?.toString())
                if (normalized == null) {
                    errorText.text = "Use 2-32 letters, numbers, _ or -"
                    usernameInput.requestFocus()
                    showKeyboard(usernameInput)
                    return@setOnClickListener
                }
                startupSetupOverlay?.let { parentView ->
                    (parentView.parent as? ViewGroup)?.removeView(parentView)
                }
                startupSetupOverlay = null
                saveProfileChoice(normalized)
                completeInitialSetup()
            }
        }
        card.addView(register, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        overlay.addView(
            card,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ).apply {
                leftMargin = dp(22)
                rightMargin = dp(22)
            },
        )
        usernameInput.setOnEditorActionListener { _, actionId, event ->
            val enterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
            if (actionId == EditorInfo.IME_ACTION_DONE || enterUp) {
                register.performClick()
                true
            } else {
                false
            }
        }
        usernameInput.post {
            usernameInput.requestFocus()
            showKeyboard(usernameInput)
        }
        return overlay
    }

    private fun renderKindSelector() {
        kindSelector.removeAllViews()
        PageRenderer.primaryPageKinds.forEach { kind ->
            addKindChip(kind, EntryUiFormatter.kindLabel(kind))
        }
    }

    private fun addKindChip(kind: String, label: String) {
        kindSelector.addView(chip(label, selectedKind == kind, colorForKind(kind)).apply {
            setOnClickListener {
                selectCaptureKind(kind, updateFilter = false)
            }
        })
    }

    private fun selectCaptureKind(kind: String, updateFilter: Boolean) {
        publishTypingFromInput(false)
        selectedKind = normalizeKind(kind)
        if (updateFilter) activeFilter = selectedKind
        editingEntryId = null
        collabAssignTaskMode = false
        collabSendLaterMode = false
        resetSelectedEntryTime()
        renderKindSelector()
        renderFilterSelector()
        applyCaptureTheme()
        renderEntries()
    }

    private fun renderCaptureTools() {
        if (!::captureToolsRow.isInitialized) return
        captureToolsRow.removeAllViews()
        when (normalizeKind(selectedKind)) {
            JournalEntryInput.KIND_JOURNAL -> {
                addCaptureTool("Tag") { appendQuickToken("#tag ") }
                addCaptureTool("Idea") { appendQuickToken("#idea ") }
                addCaptureTool("Reminder") {
                    appendQuickToken("#task ")
                    if (!customEntryTimeSelected) {
                        selectedEntryTimeMillis = System.currentTimeMillis() + JournalEntryInput.DEFAULT_TASK_REMINDER_MS
                        customEntryTimeSelected = true
                    }
                    updateTimeSelectorLabel()
                }
                addCaptureTool("Mention") { appendQuickToken("${preferredMentionToken()} ") }
            }
            JournalEntryInput.KIND_IDEA -> {
                addCaptureTool("Spark") { appendQuickToken("#spark ") }
                addCaptureTool("Priority") { appendQuickToken("#priority ") }
                addCaptureTool("Link") { appendQuickToken("#link ") }
                addCaptureTool("#task") { appendQuickToken("#task ") }
            }
            JournalEntryInput.KIND_TASK -> {
                addCaptureTool("10 min") {
                    selectedEntryTimeMillis = System.currentTimeMillis() + JournalEntryInput.DEFAULT_TASK_REMINDER_MS
                    customEntryTimeSelected = true
                    updateTimeSelectorLabel()
                }
                addCaptureTool("Checklist") { appendQuickToken("- [ ] ") }
                addCaptureTool("Due") { showEntryTimePicker() }
                addCaptureTool("#idea") { appendQuickToken("#idea ") }
            }
            JournalEntryInput.KIND_COLLAB -> {
                addCaptureTool(if (collabSendLaterMode) "Later on" else chatToolLabel(CaptureController.ChatTool.SCHEDULE)) {
                    collabAssignTaskMode = false
                    collabSendLaterMode = true
                    showEntryTimePicker()
                    updatePostLabel()
                    renderCaptureTools()
                }
                addCaptureTool(if (collabAssignTaskMode) "Task on" else chatToolLabel(CaptureController.ChatTool.ASSIGN_TASK)) {
                    collabAssignTaskMode = !collabAssignTaskMode
                    if (collabAssignTaskMode) collabSendLaterMode = false
                    if (collabAssignTaskMode && !customEntryTimeSelected) {
                        selectedEntryTimeMillis = System.currentTimeMillis() + JournalEntryInput.DEFAULT_TASK_REMINDER_MS
                        customEntryTimeSelected = true
                    }
                    updateTimeSelectorLabel()
                    updatePostLabel()
                    renderCaptureTools()
                }
                addCaptureTool(chatToolLabel(CaptureController.ChatTool.TEN_MIN)) {
                    collabAssignTaskMode = true
                    collabSendLaterMode = false
                    selectedEntryTimeMillis = System.currentTimeMillis() + JournalEntryInput.DEFAULT_TASK_REMINDER_MS
                    customEntryTimeSelected = true
                    updateTimeSelectorLabel()
                    updatePostLabel()
                    renderCaptureTools()
                }
                addCaptureTool(chatToolLabel(CaptureController.ChatTool.DUE)) {
                    collabAssignTaskMode = true
                    collabSendLaterMode = false
                    showEntryTimePicker()
                    updatePostLabel()
                    renderCaptureTools()
                }
            }
            else -> {
                addCaptureTool(EntryUiFormatter.kindPrefix(selectedKind)) {
                    appendQuickToken("${EntryUiFormatter.kindPrefix(selectedKind)} ")
                }
                addCaptureTool("Pin") { appendQuickToken("#pin ") }
                addCaptureTool("Reminder") { showEntryTimePicker() }
            }
        }
    }

    private fun addCaptureTool(label: String, onClick: () -> Unit) {
        captureToolsRow.addView(chip(label, false, colorForKind(selectedKind)).apply {
            setOnClickListener { onClick() }
            attachCaptureSwipe(this)
        })
    }

    private fun appendQuickToken(token: String) {
        val existing = quickInput.text?.toString().orEmpty()
        val separator = if (existing.isBlank() || existing.endsWith(" ")) "" else " "
        val next = existing + separator + token
        quickInput.setText(next)
        quickInput.setSelection(quickInput.text.length)
        quickInput.requestFocus()
        showKeyboard(quickInput)
    }

    private fun preferredMentionToken(): String {
        return chatPeerMentions().firstOrNull()
            ?: profileMentions().firstOrNull { !it.equals(activeCollabUser, ignoreCase = true) }
            ?: activeChatPeer
    }

    private fun showKeyboard(view: View) {
        view.post {
            getSystemService(InputMethodManager::class.java)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun renderFilterSelector() {
        filterSelector.removeAllViews()
        filterSelector.addView(chip("All", activeFilter == null, COLOR_ACCENT_GREEN).apply {
            setOnClickListener {
                publishTypingFromInput(false)
                activeFilter = null
                selectedKind = JournalEntryInput.KIND_JOURNAL
                renderFilterSelector()
                renderKindSelector()
                applyCaptureTheme()
                renderEntries()
            }
        })
        PageRenderer.primaryPageKinds.forEach { kind ->
            filterSelector.addView(chip(EntryUiFormatter.kindLabel(kind), activeFilter == kind, colorForKind(kind)).apply {
                setOnClickListener {
                    selectCaptureKind(kind, updateFilter = true)
                }
            })
        }
        if (activeFilter != null && normalizeKind(activeFilter.orEmpty()) != JournalEntryInput.KIND_COLLAB) {
            EntryUiFormatter.extractTags(
                allEntries
                    .filter { entryListRenderer.matchesPage(it, activeFilter) }
                    .joinToString(" ") { it.text },
            ).let { tags ->
                tags.take(3).forEach { tag ->
                    filterSelector.addView(compactChip(tag, false, colorForKind(selectedKind)).apply {
                        setOnClickListener {
                            searchInput.setText(tag)
                            searchInput.setSelection(searchInput.text.length)
                        }
                    })
                }
                if (tags.size > 3) {
                    filterSelector.addView(compactChip("+${tags.size - 3}", false, COLOR_MUTED))
                }
            }
        }
    }

    private fun compactChip(label: String, selected: Boolean, color: Int): TextView {
        return chip(label, selected, color).apply {
            textSize = 12f
            minHeight = dp(32)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
    }

    private fun metaPill(label: String, color: Int, filled: Boolean = false): TextView {
        val fill = if (filled) color else Color.argb(32, Color.red(color), Color.green(color), Color.blue(color))
        return TextView(this).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (filled) Color.WHITE else color)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(7), dp(3), dp(7), dp(3))
            background = rounded(fill, dp(8), Color.TRANSPARENT, 0)
        }
    }

    private fun addMetaPill(row: LinearLayout, label: String, color: Int, filled: Boolean = false) {
        row.addView(metaPill(label, color, filled), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(5)
        })
    }

    private fun hasReminderAffordance(entry: EntryEntity): Boolean {
        return normalizeKind(entry.kind) == JournalEntryInput.KIND_TASK ||
            EntryUiFormatter.extractTags(entry.text).any { it == "#task" || it == "#due" }
    }

    private fun journalDayLabel(createdAtMillis: Long): String {
        val entryCal = Calendar.getInstance().apply { timeInMillis = createdAtMillis }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            entryCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                entryCal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
            entryCal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                entryCal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
            else -> DateFormat.format("dd MMM", createdAtMillis).toString()
        }
    }

    private fun saveQuickEntry() {
        val text = quickInput.text
        if (text.isBlank()) {
            quickInput.animate().translationX(dp(4).toFloat()).setDuration(70).withEndAction {
                quickInput.animate().translationX(0f).setDuration(90).start()
            }.start()
            return
        }

        val kind = selectedKind
        val createdAtMillis = if (customEntryTimeSelected) selectedEntryTimeMillis else System.currentTimeMillis()
        val isScheduledCollab = normalizeKind(kind) == JournalEntryInput.KIND_COLLAB &&
            collabSendLaterMode &&
            customEntryTimeSelected &&
            selectedEntryTimeMillis > System.currentTimeMillis() + 1_000L
        val textValue = text.toString()
        val preparedTextValue = prepareTextForKind(textValue, kind)
        val hasTaskReminderTag = EntryUiFormatter.extractTags(preparedTextValue)
            .any { it.equals("#task", ignoreCase = true) }
        val reminderAtMillis = if (kind == JournalEntryInput.KIND_TASK || hasTaskReminderTag) {
            if (customEntryTimeSelected) selectedEntryTimeMillis else createdAtMillis + JournalEntryInput.DEFAULT_TASK_REMINDER_MS
        } else if (normalizeKind(kind) == JournalEntryInput.KIND_COLLAB && collabAssignTaskMode) {
            if (customEntryTimeSelected) selectedEntryTimeMillis else System.currentTimeMillis() + JournalEntryInput.DEFAULT_TASK_REMINDER_MS
        } else {
            null
        }
        val editingId = editingEntryId
        val assignTask = normalizeKind(kind) == JournalEntryInput.KIND_COLLAB && collabAssignTaskMode
        activityScope.launch(Dispatchers.IO) {
            val repository = EntryRepository.from(this@MainActivity)
            val savedEntryId = if (editingId == null) {
                repository.saveReply(preparedTextValue, nowMillis = createdAtMillis, kind = kind)
            } else {
                if (repository.updateEntry(editingId, preparedTextValue, kind, createdAtMillis)) editingId else null
            }

            if (savedEntryId != null) {
                if (assignTask && reminderAtMillis != null && reminderAtMillis > System.currentTimeMillis()) {
                    val taskText = "Chat task: ${CollabMessage.parse(preparedTextValue, activeCollabUser).visibleBody(activeCollabUser, activeChatPeer)}"
                    repository.saveReply(
                        taskText,
                        nowMillis = reminderAtMillis,
                        kind = JournalEntryInput.KIND_TASK,
                    )?.let { taskId ->
                        TaskReminderScheduler.schedule(this@MainActivity, taskId, taskText, reminderAtMillis)
                    }
                    TaskReminderScheduler.cancel(this@MainActivity, savedEntryId)
                } else if (reminderAtMillis != null && reminderAtMillis > System.currentTimeMillis()) {
                    TaskReminderScheduler.schedule(this@MainActivity, savedEntryId, preparedTextValue, reminderAtMillis)
                } else {
                    TaskReminderScheduler.cancel(this@MainActivity, savedEntryId)
                }
                if (editingId == null && normalizeKind(kind) == JournalEntryInput.KIND_COLLAB) {
                    if (isScheduledCollab) {
                        ScheduledCollabScheduler.schedule(
                            context = this@MainActivity,
                            entryId = savedEntryId,
                            text = preparedTextValue,
                            sendAtMillis = createdAtMillis,
                            localProfile = activeCollabUser,
                        )
                    } else {
                        ScheduledCollabScheduler.cancel(this@MainActivity, savedEntryId)
                        collabSync.broadcast(preparedTextValue, createdAtMillis)
                    }
                } else if (editingId != null) {
                    ScheduledCollabScheduler.cancel(this@MainActivity, savedEntryId)
                } else if (hasMentionForOtherProfile(preparedTextValue)) {
                    collabSync.broadcast(preparedTextValue, createdAtMillis, normalizeKind(kind))
                }
            }
            withContext(Dispatchers.Main) {
                quickInput.text.clear()
                publishTypingFromInput(false)
                editingEntryId = null
                collabAssignTaskMode = false
                collabSendLaterMode = false
                resetSelectedEntryTime()
                updatePostLabel()
                applyCaptureTheme()
                if (ensureNotificationPermission()) {
                    NotificationHelper.showPersistentInput(this@MainActivity)
                }
                loadEntries()
            }
        }
    }

    private fun prepareTextForKind(text: String, kind: String): String {
        if (normalizeKind(kind) != JournalEntryInput.KIND_COLLAB) return text
        return CollabMessage.prepareDraft(text, activeCollabUser, activeChatPeer, profileMentions())
    }

    private fun ensureNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        return false
    }

    private fun ensureRecordAudioPermission(action: String): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        pendingRecordAudioAction = action
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        return false
    }

    private fun ensureBubblePermission() {
        if (canShowBubble()) return
        AlertDialog.Builder(this)
            .setTitle("Enable hover bubble")
            .setMessage("Allow display over other apps so Anytime Journal can turn into a floating quick input bubble when you go Home.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Allow") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
            .show()
    }

    private fun canShowBubble(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun startBubbleIfAllowed() {
        if (isFinishing || !canShowBubble()) return
        runCatching {
            val intent = Intent(this, BubbleInputService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }.onFailure {
            Log.w(LOG_TAG, "Bubble start skipped", it)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (
            requestCode == REQUEST_NOTIFICATIONS &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationHelper.showPersistentInput(this)
        } else if (
            requestCode == REQUEST_RECORD_AUDIO &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            when (pendingRecordAudioAction) {
                RECORD_AUDIO_ACTION_ACCEPT -> voiceCallManager.acceptIncoming()
                else -> startOutgoingCallIfPeerOnline()
            }
            pendingRecordAudioAction = null
        }
    }

    private fun loadEntries() {
        activityScope.launch(Dispatchers.IO) {
            val entries = EntryRepository.from(this@MainActivity).latestEntries()
            withContext(Dispatchers.Main) {
                allEntries = entries
                renderKindSelector()
                renderFilterSelector()
                renderEntries()
            }
        }
    }

    private fun loadProfiles() {
        activityScope.launch(Dispatchers.IO) {
            val repository = ProfileRepository.from(this@MainActivity)
            repository.seedDefaults()
            val loadedProfiles = repository.profiles()
            withContext(Dispatchers.Main) {
                profiles = loadedProfiles.ifEmpty { defaultProfiles() }
                if (profileMentions().none { it == activeCollabUser }) {
                    setActiveProfile(profileMentions().first())
                }
                activeChatPeer = chatPeerMentions().firstOrNull() ?: activeCollabUser
                renderCollabUsers()
                applyCaptureTheme(animate = false)
                renderEntries()
            }
        }
    }

    private fun renderEntries() {
        persistAppSurfaceState()
        callTranscriptTextView = null
        callTranscriptOwnerTextView = null
        entriesContainer.removeAllViews()
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val visibleEntries = allEntries.filter { entry ->
            val matchesFilter = entryListRenderer.matchesPage(entry, activeFilter)
            val matchesProfile = activeFilter != JournalEntryInput.KIND_COLLAB ||
                normalizeKind(entry.kind) != JournalEntryInput.KIND_COLLAB ||
                collabVisibleInActiveThread(entry)
            val searchable = buildString {
                append(entry.text.lowercase())
                append(' ')
                append(entry.kind.lowercase())
                append(' ')
                append(EntryUiFormatter.extractTags(entry.text).joinToString(" "))
            }
            matchesFilter && matchesProfile && (query.isEmpty() || searchable.contains(query))
        }

        statsText.text = "${visibleEntries.size} / ${allEntries.size}"

        val transcriptPreview = callTranscriptPreview()
        if (transcriptPreview != null) {
            entriesContainer.addView(transcriptPreview)
        }

        if (visibleEntries.isEmpty()) {
            typingPreview()?.let { entriesContainer.addView(it) }
            if (transcriptPreview == null && !isRemoteTypingVisible()) entriesContainer.addView(emptyState())
            return
        }

        typingPreview()?.let { entriesContainer.addView(it) }
        visibleEntries.forEachIndexed { index, entry ->
            entriesContainer.addView(
                if (normalizeKind(entry.kind) == JournalEntryInput.KIND_COLLAB) {
                    collabEntryBlock(entry, index)
                } else {
                    entryBlock(entry, index)
                },
            )
        }
    }

    private fun collabEntryBlock(entry: EntryEntity, index: Int): View {
        val parsed = parseCollabText(entry.text)
        val isMine = parsed.author.equals(activeCollabUser, ignoreCase = true)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isMine) Gravity.END else Gravity.START
            alpha = 0f
            translationX = if (isMine) dp(10).toFloat() else -dp(10).toFloat()
            translationY = dp(6).toFloat()
        }
        row.layoutParams = blockParams(bottom = dp(6))

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
            background = chatBubbleBackground(isMine)
            elevation = dp(1).toFloat()
        }
        bubble.addView(TextView(this).apply {
            text = "${chatSenderLabel(parsed.author)}  ${formatChatTime(entry.createdAtMillis)}"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isMine) COLOR_ACCENT_GREEN else COLOR_OBSIDIAN)
            includeFontPadding = false
        })
        bubble.addView(TextView(this).apply {
            text = displayCollabBody(parsed)
            textSize = 14f
            setTextColor(COLOR_INK)
            setLineSpacing(0f, 1.05f)
            setPadding(0, dp(4), 0, 0)
        })

        row.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = if (isMine) Gravity.END else Gravity.START
                leftMargin = if (isMine) dp(76) else 0
                rightMargin = if (isMine) 0 else dp(76)
            },
        )
        attachSwipeActions(row, entry)
        bubble.setOnClickListener { openEntryForEdit(row, entry) }
        row.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(8) * 18).toLong())
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return row
    }

    private fun updateCollabHeader() {
        if (!::collabPeerText.isInitialized || !::collabStatusText.isInitialized) return
        val peerName = activeChatPeer.removePrefix("@")
        val online = isPeerOnline(activeChatPeer)
        val callState = if (::voiceCallManager.isInitialized) voiceCallManager.state() else lastCallState
        collabPeerText.text = when {
            callState?.micLive == true -> "Voice live"
            callState?.hasIncomingAudio == true -> "Incoming voice"
            callState?.active == true -> "Listening"
            isRemoteTypingVisible() -> "$peerName typing"
            online -> "Online"
            else -> "Offline"
        }
        collabStatusText.visibility = View.GONE
        collabPeerText.setTextColor(if (online || callState?.active == true || isRemoteTypingVisible()) COLOR_ACCENT_GREEN else COLOR_OBSIDIAN)
    }

    private fun activeUserCount(): Int {
        return (onlineProfiles + activeCollabUser)
            .filter { it.startsWith("@") }
            .distinct()
            .size
            .coerceAtLeast(1)
    }

    private fun handleRemoteTyping(typing: CollabTyping) {
        if (!typing.target.equals(activeCollabUser, ignoreCase = true)) return
        if (!typing.author.equals(activeChatPeer, ignoreCase = true)) return
        remoteTypingPeer = if (typing.typing) typing.author else null
        remoteTypingUntilMillis = if (typing.typing) System.currentTimeMillis() + TYPING_VISIBLE_MS else 0L
        mainHandler.removeCallbacks(typingClearTick)
        if (typing.typing) mainHandler.postDelayed(typingClearTick, TYPING_VISIBLE_MS)
        updateCollabHeader()
        renderEntries()
    }

    private fun publishTypingFromInput(hasText: Boolean) {
        if (!::collabSync.isInitialized) return
        if (normalizeKind(selectedKind) != JournalEntryInput.KIND_COLLAB ||
            normalizeKind(activeFilter.orEmpty()) != JournalEntryInput.KIND_COLLAB
        ) {
            if (lastTypingActive) {
                collabSync.publishTyping(activeChatPeer, typing = false)
                lastTypingActive = false
            }
            return
        }
        val now = System.currentTimeMillis()
        if (hasText) {
            if (!lastTypingActive || now - lastTypingSentAtMillis >= TYPING_SEND_THROTTLE_MS) {
                collabSync.publishTyping(activeChatPeer, typing = true)
                lastTypingSentAtMillis = now
                lastTypingActive = true
            }
        } else if (lastTypingActive) {
            collabSync.publishTyping(activeChatPeer, typing = false)
            lastTypingActive = false
            lastTypingSentAtMillis = now
        }
    }

    private fun hasTypedWord(value: CharSequence?): Boolean {
        return value
            ?.toString()
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.any { token -> token.count { it.isLetterOrDigit() } >= MIN_TYPING_WORD_LENGTH }
            ?: false
    }

    private fun callTranscriptPreview(): View? {
        val state = lastCallState ?: return null
        if (normalizeKind(activeFilter.orEmpty()) != JournalEntryInput.KIND_COLLAB) return null
        if (state.latestTranscript.isBlank()) return null
        val isMine = state.micLive && !state.peerMicLive
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isMine) Gravity.END else Gravity.START
            alpha = 0.96f
        }
        row.layoutParams = blockParams(bottom = dp(7))
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(8))
            background = chatBubbleBackground(isMine)
            elevation = dp(2).toFloat()
        }
        bubble.addView(TextView(this).apply {
            text = if (isMine) "Your voice live" else "${state.peerProfile?.removePrefix("@") ?: "Caller"} voice live"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isMine) COLOR_ACCENT_GREEN else COLOR_OBSIDIAN)
            includeFontPadding = false
            callTranscriptOwnerTextView = this
        })
        bubble.addView(TextView(this).apply {
            text = state.latestTranscript
            textSize = 14f
            setTextColor(COLOR_INK)
            setLineSpacing(0f, 1.05f)
            setPadding(0, dp(4), 0, 0)
            callTranscriptTextView = this
        })
        row.addView(
            bubble,
            LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.82f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return row
    }

    private fun typingPreview(): View? {
        if (!isRemoteTypingVisible()) return null
        val peer = remoteTypingPeer ?: activeChatPeer
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            alpha = 0f
            translationX = -dp(10).toFloat()
        }
        row.layoutParams = blockParams(bottom = dp(7))
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = chatBubbleBackground(isMine = false)
            elevation = dp(1).toFloat()
        }
        bubble.addView(TextView(this).apply {
            text = "${peer.removePrefix("@")} typing"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_OBSIDIAN)
            includeFontPadding = false
        })
        bubble.addView(TextView(this).apply {
            text = "  ..."
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_ACCENT_GREEN)
            includeFontPadding = false
        })
        row.addView(
            bubble,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        row.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return row
    }

    private fun isRemoteTypingVisible(): Boolean {
        return normalizeKind(activeFilter.orEmpty()) == JournalEntryInput.KIND_COLLAB &&
            remoteTypingPeer?.equals(activeChatPeer, ignoreCase = true) == true &&
            System.currentTimeMillis() < remoteTypingUntilMillis
    }

    private enum class CtaTone {
        PRIMARY,
        SECONDARY,
        GHOST,
        DANGER,
    }

    private fun primaryCtaLabel(): String {
        return CaptureController.primaryCtaLabel(captureState(), System.currentTimeMillis())
    }

    private fun chatToolLabel(tool: CaptureController.ChatTool): String = CaptureController.chatToolLabel(tool)

    private fun captureState(): CaptureController.State {
        return CaptureController.State(
            selectedKind = selectedKind,
            selectedEntryTimeMillis = selectedEntryTimeMillis,
            customEntryTimeSelected = customEntryTimeSelected,
            collabAssignTaskMode = collabAssignTaskMode,
            collabSendLaterMode = collabSendLaterMode,
            editingEntryId = editingEntryId,
        )
    }

    private fun parseCollabText(text: String): CollabMessage = CollabMessage.parse(text, activeCollabUser)

    private fun collabVisibleInActiveThread(entry: EntryEntity): Boolean {
        val parsed = parseCollabText(entry.text)
        return parsed.isVisibleInThread(activeCollabUser, activeChatPeer)
    }

    private fun displayCollabBody(parsed: CollabMessage): String =
        parsed.visibleBody(activeCollabUser, activeChatPeer)

    private fun chatSenderLabel(author: String): String {
        return if (author.equals(activeCollabUser, ignoreCase = true)) {
            "You"
        } else {
            author.removePrefix("@")
        }
    }

    private fun formatChatTime(createdAtMillis: Long): String {
        return DateFormat.format("h:mm a", createdAtMillis).toString()
    }

    private fun notifyIfMentioned(packet: CollabPacket) {
        if (packet.kind != JournalEntryInput.KIND_COLLAB) {
            if (!EntryUiFormatter.extractMentions(packet.text).any { it.equals(activeCollabUser, ignoreCase = true) }) return
            NotificationHelper.showMention(
                context = this,
                author = EntryUiFormatter.kindLabel(packet.kind),
                body = EntryUiFormatter.compactPreview(packet.text, maxLength = 90),
                createdAtMillis = packet.createdAtMillis,
                targetProfile = activeCollabUser,
            )
            return
        }
        val parsed = parseCollabText(packet.text)
        if (parsed.author.equals(activeCollabUser, ignoreCase = true)) return
        if (!parsed.mentions(activeCollabUser)) return
        NotificationHelper.showMention(
            context = this,
            author = parsed.author,
            body = displayCollabBody(parsed),
            createdAtMillis = packet.createdAtMillis,
            targetProfile = activeCollabUser,
        )
    }

    private fun shouldAcceptRemotePacket(packet: CollabPacket): Boolean {
        if (normalizeKind(packet.kind) == JournalEntryInput.KIND_COLLAB) return true
        return EntryUiFormatter.extractMentions(packet.text)
            .any { it.equals(activeCollabUser, ignoreCase = true) }
    }

    private fun hasMentionForOtherProfile(text: String): Boolean {
        val mentions = EntryUiFormatter.extractMentions(text)
        if (mentions.isEmpty()) return false
        return mentions.any { mention ->
            profileMentions().any { profile ->
                mention.equals(profile, ignoreCase = true) &&
                    !mention.equals(activeCollabUser, ignoreCase = true)
            }
        }
    }

    private fun entryBlock(entry: EntryEntity, index: Int): View {
        val accent = colorForKind(entry.kind)
        val page = normalizeKind(activeFilter.orEmpty())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            alpha = 0f
            translationY = dp(6).toFloat()
        }
        row.layoutParams = blockParams(bottom = dp(5))

        if (page == JournalEntryInput.KIND_JOURNAL) {
            row.addView(journalTimelineMarker(entry, accent), LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.MATCH_PARENT))
        } else {
            row.addView(View(this).apply {
                background = rounded(accent, dp(4), accent, 0)
            }, LinearLayout.LayoutParams(dp(3), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(11), dp(7), dp(11), dp(8))
            background = entryCardBackground(entry.kind)
            elevation = if (page == JournalEntryInput.KIND_JOURNAL) dp(2).toFloat() else dp(1).toFloat()
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(TextView(this).apply {
            text = if (page == JournalEntryInput.KIND_JOURNAL) journalDayLabel(entry.createdAtMillis) else formatTime(entry.createdAtMillis)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_MUTED)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tags = EntryUiFormatter.extractTags(entry.text)
        addMetaPill(meta, EntryUiFormatter.kindPrefix(entry.kind), accent, filled = true)
        if (hasReminderAffordance(entry)) addMetaPill(meta, "Remind", COLOR_ACCENT_AMBER)
        if (tags.isNotEmpty()) addMetaPill(meta, "${tags.size} ${if (tags.size == 1) "tag" else "tags"}", COLOR_OBSIDIAN)
        addMetaPill(meta, "Edit", COLOR_MUTED)
        card.addView(meta)

        card.addView(TextView(this).apply {
            text = EntryUiFormatter.compactPreview(entry.text).let {
                val limit = if (page == JournalEntryInput.KIND_JOURNAL) 104 else 92
                if (it.length > limit) it.take(limit - 3).trimEnd() + "..." else it
            }
            textSize = if (page == JournalEntryInput.KIND_JOURNAL) 14.5f else 14f
            setTextColor(COLOR_INK)
            setLineSpacing(0f, if (page == JournalEntryInput.KIND_JOURNAL) 1.1f else 1.08f)
            maxLines = when (page) {
                JournalEntryInput.KIND_JOURNAL -> 2
                JournalEntryInput.KIND_TASK -> 1
                else -> 2
            }
            setPadding(0, dp(5), 0, 0)
        })

        if (tags.isNotEmpty()) {
            val tagRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, 0)
            }
            tags.take(2).forEach { tag ->
                tagRow.addView(compactChip(tag, false, COLOR_OBSIDIAN))
            }
            if (tags.size > 2) {
                tagRow.addView(compactChip("+${tags.size - 2}", false, COLOR_MUTED))
            }
            card.addView(horizontalScroller(tagRow))
        }

        row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = if (page == JournalEntryInput.KIND_JOURNAL) dp(2) else dp(7)
        })
        attachSwipeActions(row, entry)
        row.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index.coerceAtMost(8) * 18).toLong())
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
        return row
    }

    private fun journalTimelineMarker(entry: EntryEntity, accent: Int): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(1), dp(7), 0)
        }
        column.addView(TextView(this).apply {
            text = DateFormat.format("h:mm", entry.createdAtMillis).toString()
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(accent)
            includeFontPadding = false
        })
        column.addView(TextView(this).apply {
            text = DateFormat.format("a", entry.createdAtMillis).toString().lowercase()
            textSize = 9f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            includeFontPadding = false
        })
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        rail.addView(View(this).apply {
            background = rounded(accent, dp(5), Color.TRANSPARENT, 0)
        }, LinearLayout.LayoutParams(dp(9), dp(9)))
        rail.addView(View(this).apply {
            background = rounded(Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)), dp(1), Color.TRANSPARENT, 0)
        }, LinearLayout.LayoutParams(dp(2), 0, 1f).apply {
            topMargin = dp(2)
        })
        column.addView(rail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return column
    }

    private fun attachSwipeActions(row: View, entry: EntryEntity) {
        var startX = 0f
        var startY = 0f
        var dragging = false
        val threshold = dp(96)
        val maxDrag = dp(132).toFloat()

        row.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    dragging = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && kotlin.math.abs(dx) > dp(12) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        view.translationX = dx.coerceIn(-maxDrag, maxDrag)
                        view.alpha = 1f - (kotlin.math.abs(view.translationX) / (maxDrag * 2f))
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    when {
                        dx <= -threshold -> confirmDeleteEntry(view, entry)
                        dx >= threshold -> openEntryForEdit(view, entry)
                        !dragging && kotlin.math.abs(dx) < dp(8) && kotlin.math.abs(event.rawY - startY) < dp(8) -> openEntryForEdit(view, entry)
                        else -> resetSwipe(view)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun confirmDeleteEntry(view: View, entry: EntryEntity) {
        resetSwipe(view)
        AlertDialog.Builder(this)
            .setTitle("Delete entry?")
            .setMessage(EntryUiFormatter.compactPreview(entry.text, maxLength = 90))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                deleteEntryWithAnimation(view, entry.id)
            }
            .show()
    }

    private fun deleteEntryWithAnimation(view: View, entryId: Long) {
        view.animate()
            .translationX(-view.width.toFloat())
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                activityScope.launch(Dispatchers.IO) {
                    EntryRepository.from(this@MainActivity).deleteEntry(entryId)
                    TaskReminderScheduler.cancel(this@MainActivity, entryId)
                    ScheduledCollabScheduler.cancel(this@MainActivity, entryId)
                    withContext(Dispatchers.Main) {
                        loadEntries()
                    }
                }
            }
            .start()
    }

    private fun openEntryForEdit(view: View, entry: EntryEntity) {
        resetSwipe(view)
        editingEntryId = entry.id
        selectedKind = entry.kind
        selectedEntryTimeMillis = entry.createdAtMillis
        customEntryTimeSelected = true
        collabAssignTaskMode = false
        collabSendLaterMode = false
        quickInput.setText(entry.text)
        quickInput.setSelection(quickInput.text.length)
        renderKindSelector()
        renderFilterSelector()
        updatePostLabel()
        applyCaptureTheme()
        capturePanel.translationY = dp(34).toFloat()
        capturePanel.alpha = 0.88f
        capturePanel.animate()
            .translationY(0f)
            .alpha(1f)
            .scaleX(1.015f)
            .scaleY(1.015f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                capturePanel.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }
            .start()
        quickInput.post {
            quickInput.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(quickInput, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun resetSwipe(view: View) {
        view.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(140)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun updatePostLabel() {
        postActionText.text = primaryCtaLabel()
    }

    private fun handleCallAction() {
        if (normalizeKind(activeFilter.orEmpty()) != JournalEntryInput.KIND_COLLAB) return
        val state = voiceCallManager.state()
        when {
            state.micLive -> voiceCallManager.endCall()
            state.hasIncomingAudio -> {
                if (ensureRecordAudioPermission(RECORD_AUDIO_ACTION_ACCEPT)) voiceCallManager.acceptIncoming()
            }
            else -> {
                if (ensureRecordAudioPermission(RECORD_AUDIO_ACTION_START)) startOutgoingCallIfPeerOnline()
            }
        }
        updateCallAction(voiceCallManager.state())
    }

    private fun startOutgoingCallIfPeerOnline(): Boolean {
        if (!isPeerOnline(activeChatPeer)) {
            Toast.makeText(
                this,
                "${activeChatPeer.removePrefix("@")} offline hai. Online hote hi call karna.",
                Toast.LENGTH_SHORT,
            ).show()
            updateCallAction(voiceCallManager.state())
            return false
        }
        voiceCallManager.startOutgoing()
        return true
    }

    private fun isPeerOnline(profile: String): Boolean {
        return !profile.equals(activeCollabUser, ignoreCase = true) &&
            onlineProfiles.any { it.equals(profile, ignoreCase = true) }
    }

    private fun updateCallAction(state: VoiceCallState? = null) {
        if (!::callActionText.isInitialized) return
        val inCollab = normalizeKind(activeFilter.orEmpty()) == JournalEntryInput.KIND_COLLAB
        callActionText.visibility = if (inCollab) View.VISIBLE else View.GONE
        if (!inCollab) return
        val callState = state ?: if (::voiceCallManager.isInitialized) {
            voiceCallManager.state()
        } else {
            VoiceCallState(
                active = false,
                micLive = false,
                hasIncomingAudio = false,
                peerProfile = null,
                startedAtMillis = 0L,
                latestTranscript = "",
                peerMicLive = false,
            )
        }
        val label = when {
            callState.micLive -> "End ${callTimerLabel(callState.startedAtMillis)}"
            callState.hasIncomingAudio -> "Accept"
            callState.active -> callTimerLabel(callState.startedAtMillis)
            !isPeerOnline(activeChatPeer) -> "Offline"
            else -> "Call"
        }
        callActionText.text = label
        val color = when {
            callState.micLive -> Color.rgb(214, 74, 74)
            callState.hasIncomingAudio -> COLOR_ACCENT_GREEN
            callState.active -> COLOR_ACCENT_BLUE
            !isPeerOnline(activeChatPeer) -> Color.rgb(130, 136, 142)
            else -> COLOR_OBSIDIAN
        }
        callActionText.background = ctaBackground(color)
        lastCallState = callState
        updateCollabHeader()
        if (callState.latestTranscript != lastRenderedCallTranscript && ::entriesContainer.isInitialized) {
            lastRenderedCallTranscript = callState.latestTranscript
            val transcriptView = callTranscriptTextView
            if (transcriptView != null && transcriptView.isAttachedToWindow && callState.latestTranscript.isNotBlank()) {
                transcriptView.text = callState.latestTranscript
                callTranscriptOwnerTextView?.text = if (callState.micLive && !callState.peerMicLive) {
                    "Your voice live"
                } else {
                    "${callState.peerProfile?.removePrefix("@") ?: "Caller"} voice live"
                }
            } else {
                renderEntries()
            }
        }
        mainHandler.removeCallbacks(callTimerTick)
        if (callState.active) mainHandler.postDelayed(callTimerTick, 1_000L)
    }

    private fun callTimerLabel(startedAtMillis: Long): String {
        if (startedAtMillis <= 0L) return "Live"
        val seconds = ((System.currentTimeMillis() - startedAtMillis) / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val remainingSeconds = seconds % 60L
        return "%d:%02d".format(minutes, remainingSeconds)
    }

    private fun showIncomingCallSurfaces(author: String) {
        val peer = normalizeProfile(author) ?: author
        if (isCollabSurfaceVisibleFor(peer)) {
            NotificationHelper.cancelIncomingCall(this)
            activeChatPeer = peer
            updateCallAction(voiceCallManager.state())
            renderEntries()
            return
        }
        NotificationHelper.showIncomingCall(this, peer, activeCollabUser)
        if (canShowBubble()) {
            val intent = Intent(this, BubbleInputService::class.java).apply {
                action = BubbleInputService.ACTION_SHOW_CALL
                putExtra(BubbleInputService.EXTRA_CALL_AUTHOR, peer)
                putExtra(BubbleInputService.EXTRA_CALL_TARGET, activeCollabUser)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
        }
    }

    private fun isCollabSurfaceVisibleFor(peer: String): Boolean {
        return appResumed &&
            normalizeKind(activeFilter.orEmpty()) == JournalEntryInput.KIND_COLLAB &&
            activeChatPeer.equals(peer, ignoreCase = true)
    }

    private fun persistAppSurfaceState() {
        val collabVisible = appResumed &&
            normalizeKind(activeFilter.orEmpty()) == JournalEntryInput.KIND_COLLAB
        val peer = activeChatPeer.lowercase()
        val changed = lastPersistedAppVisible != appResumed ||
            lastPersistedCollabVisible != collabVisible ||
            lastPersistedChatPeer != peer
        if (changed) {
            AppPrefs.updateSurfaceState(this, appResumed, collabVisible, peer)
            lastPersistedAppVisible = appResumed
            lastPersistedCollabVisible = collabVisible
            lastPersistedChatPeer = peer
        }
        val now = System.currentTimeMillis()
        if (collabVisible && (changed || now - lastHideBubbleRequestAt > HIDE_BUBBLE_THROTTLE_MS)) {
            lastHideBubbleRequestAt = now
            hideBubbleWhileChatVisible()
        }
    }

    private fun hideBubbleWhileChatVisible() {
        if (!canShowBubble()) return
        val intent = Intent(this, BubbleInputService::class.java).apply {
            action = BubbleInputService.ACTION_HIDE_BUBBLE
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        }
    }

    private fun attachCaptureSwipe(panel: View, movingView: View = panel) {
        var startX = 0f
        var startY = 0f
        var dragging = false
        val threshold = dp(68)
        val maxDrag = dp(82).toFloat()

        panel.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!dragging && kotlin.math.abs(dx) > dp(14) && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        dragging = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        movingView.translationX = dx.coerceIn(-maxDrag, maxDrag)
                        movingView.alpha = 1f - (kotlin.math.abs(movingView.translationX) / (maxDrag * 4f))
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = event.rawX - startX
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val nextDirection = if (dragging && kotlin.math.abs(dx) >= threshold) {
                        if (dx < 0) 1 else -1
                    } else {
                        0
                    }
                    if (nextDirection == 0 && !dragging) {
                        view.performClick()
                    }
                    movingView.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            if (nextDirection != 0) cycleCaptureKind(nextDirection)
                        }
                        .start()
                    true
                }
                else -> false
            }
        }
    }

    private fun cycleCaptureKind(direction: Int) {
        val kinds = PageRenderer.primaryPageKinds
        if (kinds.isEmpty()) return
        publishTypingFromInput(false)
        val currentIndex = kinds.indexOf(selectedKind).let { if (it >= 0) it else 0 }
        val nextIndex = (currentIndex + direction + kinds.size) % kinds.size
        selectedKind = kinds[nextIndex]
        activeFilter = selectedKind
        editingEntryId = null
        collabAssignTaskMode = false
        collabSendLaterMode = false
        resetSelectedEntryTime()
        updatePostLabel()
        renderKindSelector()
        renderFilterSelector()
        applyCaptureTheme(animate = true)
        renderEntries()
        focusQuickCaptureInput()
    }

    private fun focusQuickCaptureInput() {
        if (!::quickInput.isInitialized) return
        quickInput.postDelayed({
            if (isFinishing || isDestroyed || !quickInput.isAttachedToWindow) return@postDelayed
            quickInput.requestFocus()
            val length = quickInput.text?.length ?: 0
            quickInput.setSelection(length.coerceIn(0, quickInput.text?.length ?: 0))
            showKeyboard(quickInput)
        }, 90L)
    }

    private fun applyCaptureTheme(view: View = capturePanel, animate: Boolean = true) {
        view.background = captureBackground(selectedKind)
        captureKindText.text = EntryUiFormatter.kindPrefix(selectedKind)
        captureKindText.background = rounded(colorForKind(selectedKind), dp(8), colorForKind(selectedKind), 0)
        captureKindText.setTextColor(Color.WHITE)
        postActionText.background = ctaBackground(colorForKind(selectedKind))
        quickInput.hint = captureHint(selectedKind)
        updateTimeSelectorLabel()
        renderCaptureTools()
        updatePageChrome()
        if (animate) {
            view.animate()
                .scaleX(1.015f)
                .scaleY(1.015f)
                .setDuration(90)
                .withEndAction {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                .start()
        }
    }

    private fun captureHint(kind: String): String {
        return CaptureController.captureHint(kind, activeChatPeer)
    }

    private fun updatePageChrome() {
        if (!::pageTitleText.isInitialized || !::pageSubtitleText.isInitialized) return
        val config = PageRenderer.pageConfig(activeFilter, activeChatPeer)
        pageTitleText.text = config.title
        pageSubtitleText.text = config.subtitle
        pageSubtitleText.visibility = if (config.subtitle.isBlank()) View.GONE else View.VISIBLE
        if (::collabPanel.isInitialized) {
            collabPanel.visibility = if (normalizeKind(activeFilter.orEmpty()) == JournalEntryInput.KIND_COLLAB) View.VISIBLE else View.GONE
        }
        if (::searchInput.isInitialized) {
            searchInput.hint = when (normalizeKind(activeFilter.orEmpty())) {
                JournalEntryInput.KIND_JOURNAL -> "Search journal or #tag"
                JournalEntryInput.KIND_IDEA -> "Search ideas, links, sparks"
                JournalEntryInput.KIND_TASK -> "Search tasks or #due"
                JournalEntryInput.KIND_FOCUS -> "Search activity or #done"
                JournalEntryInput.KIND_COLLAB -> "Search chat"
                else -> "Search all screens"
            }
        }
        pageTitleText.setTextColor(config.color)
        updateCallAction()
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            gradientForKind(activeFilter ?: PageRenderer.KIND_ALL_SCREEN),
        ).apply {
            alpha = if (activeFilter == null) 255 else 255
        }
    }

    private fun showEntryTimePicker() {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = if (customEntryTimeSelected) selectedEntryTimeMillis else System.currentTimeMillis()
        }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                TimePickerDialog(
                    this,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        selectedEntryTimeMillis = calendar.timeInMillis
                        customEntryTimeSelected = true
                        updateTimeSelectorLabel()
                        renderCaptureTools()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    DateFormat.is24HourFormat(this),
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun resetSelectedEntryTime() {
        selectedEntryTimeMillis = System.currentTimeMillis()
        customEntryTimeSelected = false
    }

    private fun updateTimeSelectorLabel() {
        timeSelectorText.text = timeSelectorLabel()
        updatePostLabel()
    }

    private fun timeSelectorLabel(): String {
        return CaptureController.timeSelectorLabel(captureState(), System.currentTimeMillis()) { millis ->
            formatTime(millis)
        }
    }

    private fun availableKinds(): List<String> {
        val coreKinds = listOf(
            JournalEntryInput.KIND_JOURNAL,
            JournalEntryInput.KIND_IDEA,
            JournalEntryInput.KIND_TASK,
            JournalEntryInput.KIND_FOCUS,
            JournalEntryInput.KIND_COLLAB,
        )
        val entryKinds = allEntries.map { it.kind }
        return (coreKinds + customKinds + entryKinds)
            .map { normalizeKind(it) }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun profileMentions(): List<String> {
        return (profiles.map { it.mention.lowercase() } + cloudProfiles + onlineProfiles + activeCollabUser)
            .filter { it.startsWith("@") }
            .distinct()
            .ifEmpty { DEFAULT_PROFILE_MENTIONS }
    }

    private fun chatPeerMentions(): List<String> {
        return profileMentions()
            .filterNot { it.equals(activeCollabUser, ignoreCase = true) }
            .ifEmpty { DEFAULT_PROFILE_MENTIONS.filterNot { it.equals(activeCollabUser, ignoreCase = true) } }
    }

    private fun liveChatPeers(): List<String> {
        val activePeers = onlineProfiles
            .filterNot { it.equals(activeCollabUser, ignoreCase = true) }
            .filter { it.startsWith("@") }
            .sorted()
        if (activePeers.isNotEmpty()) return activePeers
        return chatPeerMentions()
    }

    private fun syncActivePeerWithPresence() {
        val activePeers = onlineProfiles
            .filterNot { it.equals(activeCollabUser, ignoreCase = true) }
            .filter { it.startsWith("@") }
            .sorted()
        if (activePeers.isNotEmpty() && activePeers.none { it.equals(activeChatPeer, ignoreCase = true) }) {
            activeChatPeer = activePeers.first()
        }
    }

    private fun showCategoryDialog() {
        val input = EditText(this).apply {
            hint = "Grocery, Work, Call..."
            setSingleLine(true)
            setTextColor(COLOR_INK)
        }
        AlertDialog.Builder(this)
            .setTitle("New category")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                val kind = normalizeKind(input.text?.toString().orEmpty())
                if (kind.isNotEmpty()) {
                    if (!customKinds.contains(kind)) customKinds.add(kind)
                    selectedKind = kind
                    activeFilter = kind
                    collabAssignTaskMode = false
                    collabSendLaterMode = false
                    resetSelectedEntryTime()
                    renderKindSelector()
                    renderFilterSelector()
                    renderEntries()
                }
            }
            .show()
    }

    private fun normalizeKind(value: String): String {
        return EntryKindNormalizer.normalize(value)
    }

    private fun emptyState(): View {
        return TextView(this).apply {
            text = "No matching entries"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(12), dp(26), dp(12), dp(26))
            background = rounded(COLOR_SURFACE, dp(8), COLOR_LINE, 1)
        }
    }

    private fun actionText(label: String, color: Int): TextView {
        return ctaText(label, CtaTone.PRIMARY, color)
    }

    private fun ctaText(label: String, tone: CtaTone, color: Int): TextView {
        val selected = tone == CtaTone.PRIMARY || tone == CtaTone.DANGER
        val fill = when (tone) {
            CtaTone.PRIMARY -> color
            CtaTone.DANGER -> Color.rgb(214, 74, 74)
            CtaTone.SECONDARY -> Color.rgb(239, 243, 240)
            CtaTone.GHOST -> Color.argb(210, 248, 250, 247)
        }
        val textColor = if (selected) Color.WHITE else color
        return TextView(this).apply {
            text = label
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = dp(42)
            minWidth = dp(58)
            setPadding(dp(15), dp(8), dp(15), dp(8))
            background = if (selected) ctaBackground(fill) else rounded(fill, dp(12), COLOR_LINE, 1)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(60).start()
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                        false
                    }
                    else -> false
                }
            }
        }
    }

    private fun chatUserPill(profile: String, online: Boolean, selected: Boolean): View {
        val accent = if (online) COLOR_ACCENT_GREEN else COLOR_MUTED
        val fill = if (selected) Color.argb(238, Color.red(COLOR_OBSIDIAN), Color.green(COLOR_OBSIDIAN), Color.blue(COLOR_OBSIDIAN)) else COLOR_SURFACE
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(40)
            setPadding(dp(10), dp(6), dp(11), dp(6))
            background = rounded(fill, dp(10), if (selected) COLOR_OBSIDIAN else COLOR_LINE, 1)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = dp(7)
            }
            setOnClickListener {
                animatePress(this)
                collabUiController.selectPeer(profile)
            }
        }
        pill.addView(View(this).apply {
            background = rounded(if (online) COLOR_ACCENT_GREEN else Color.argb(170, 150, 150, 150), dp(4), Color.TRANSPARENT, 0)
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply {
            rightMargin = dp(7)
        })
        pill.addView(TextView(this).apply {
            text = profile.removePrefix("@")
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Color.WHITE else COLOR_INK)
            includeFontPadding = false
        })
        return pill
    }

    private fun selectCollabPeer(profile: String) {
        publishTypingFromInput(false)
        activeChatPeer = profile
        selectedKind = JournalEntryInput.KIND_COLLAB
        activeFilter = JournalEntryInput.KIND_COLLAB
        collabAssignTaskMode = false
        collabSendLaterMode = false
        resetSelectedEntryTime()
        renderKindSelector()
        renderFilterSelector()
        applyCaptureTheme()
        renderEntries()
        quickInput.requestFocus()
        showKeyboard(quickInput)
    }

    private fun chip(label: String, selected: Boolean, color: Int): TextView {
        val fill = if (selected) color else COLOR_SURFACE
        val textColor = if (selected) Color.WHITE else color
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(textColor)
            gravity = Gravity.CENTER
            minHeight = dp(38)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(fill, dp(8), if (selected) color else COLOR_LINE, 1)
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                        false
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                        false
                    }
                    else -> false
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                rightMargin = dp(7)
            }
        }
    }

    private fun animatePress(view: View) {
        view.animate()
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(70)
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun horizontalScroller(content: LinearLayout): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }
    }

    private fun blockParams(bottom: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = bottom
        }
    }

    private fun appBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.rgb(250, 251, 248),
                Color.rgb(244, 248, 246),
                Color.rgb(249, 246, 252),
                Color.rgb(246, 248, 243),
            ),
        )
    }

    private fun captureBackground(kind: String): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            gradientForKind(kind),
        ).apply {
            cornerRadius = dp(18).toFloat()
            alpha = 255
            setStroke(dp(1), Color.argb(210, 255, 255, 255))
        }
    }

    private fun captureFooterBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                Color.rgb(247, 249, 246),
                Color.rgb(247, 249, 246),
            ),
        )
    }

    private fun ctaBackground(color: Int): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                color,
                Color.argb(
                    235,
                    lightenChannel(Color.red(color)),
                    lightenChannel(Color.green(color)),
                    lightenChannel(Color.blue(color)),
                ),
            ),
        ).apply {
            cornerRadius = dp(12).toFloat()
            setStroke(dp(1), Color.argb(210, 255, 255, 255))
        }
    }

    private fun chatBubbleBackground(isMine: Boolean): GradientDrawable {
        val colors = if (isMine) {
            intArrayOf(
                Color.rgb(220, 243, 235),
                Color.rgb(203, 232, 223),
            )
        } else {
            intArrayOf(
                Color.rgb(255, 255, 255),
                Color.rgb(245, 247, 250),
            )
        }
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), COLOR_LINE)
        }
    }

    private fun chatShellBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.rgb(255, 255, 255),
                Color.rgb(243, 248, 245),
                Color.rgb(250, 247, 255),
            ),
        ).apply {
            cornerRadius = dp(18).toFloat()
            setStroke(dp(1), COLOR_LINE)
        }
    }

    private fun lightenChannel(channel: Int): Int {
        return (channel + 38).coerceAtMost(255)
    }

    private fun gradientForKind(kind: String): IntArray {
        return when (normalizeKind(kind)) {
            JournalEntryInput.KIND_IDEA -> intArrayOf(
                Color.rgb(239, 244, 255),
                Color.rgb(232, 238, 255),
                Color.rgb(247, 246, 255),
            )
            JournalEntryInput.KIND_TASK -> intArrayOf(
                Color.rgb(255, 246, 233),
                Color.rgb(252, 238, 216),
                Color.rgb(240, 248, 241),
            )
            JournalEntryInput.KIND_FOCUS -> intArrayOf(
                Color.rgb(232, 249, 250),
                Color.rgb(220, 242, 244),
                Color.rgb(247, 246, 255),
            )
            JournalEntryInput.KIND_COLLAB -> intArrayOf(
                Color.rgb(246, 241, 255),
                Color.rgb(237, 231, 252),
                Color.rgb(235, 248, 246),
            )
            JournalEntryInput.KIND_JOURNAL -> intArrayOf(
                Color.rgb(235, 249, 244),
                Color.rgb(222, 242, 235),
                Color.rgb(246, 249, 247),
            )
            PageRenderer.KIND_ALL_SCREEN -> intArrayOf(
                Color.rgb(250, 251, 248),
                Color.rgb(241, 247, 246),
                Color.rgb(248, 245, 252),
            )
            else -> intArrayOf(
                Color.rgb(250, 251, 248),
                Color.rgb(244, 248, 246),
                Color.rgb(249, 246, 252),
            )
        }
    }

    private fun entryCardBackground(kind: String): GradientDrawable {
        val tint = tintForKind(kind)
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.WHITE,
                Color.argb(235, Color.red(tint), Color.green(tint), Color.blue(tint)),
            ),
        ).apply {
            cornerRadius = dp(10).toFloat()
            setStroke(dp(1), COLOR_LINE)
        }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
        }
    }

    private fun colorForKind(kind: String): Int {
        return when (normalizeKind(kind)) {
            JournalEntryInput.KIND_IDEA -> COLOR_ACCENT_BLUE
            JournalEntryInput.KIND_TASK -> COLOR_ACCENT_AMBER
            JournalEntryInput.KIND_FOCUS -> COLOR_ACCENT_TEAL
            JournalEntryInput.KIND_COLLAB -> COLOR_OBSIDIAN
            JournalEntryInput.KIND_JOURNAL -> COLOR_ACCENT_GREEN
            else -> COLOR_OBSIDIAN
        }
    }

    private fun tintForKind(kind: String): Int {
        return when (normalizeKind(kind)) {
            JournalEntryInput.KIND_IDEA -> COLOR_TINT_BLUE
            JournalEntryInput.KIND_TASK -> COLOR_TINT_AMBER
            JournalEntryInput.KIND_FOCUS -> COLOR_TINT_TEAL
            JournalEntryInput.KIND_COLLAB -> COLOR_TINT_OBSIDIAN
            JournalEntryInput.KIND_JOURNAL -> COLOR_TINT_GREEN
            else -> COLOR_TINT_OBSIDIAN
        }
    }

    private fun formatTime(createdAtMillis: Long): String {
        return DateFormat.format("h:mm a - dd MMM", createdAtMillis).toString()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (!hasSavedProfile()) {
            requestedProfile(intent)?.let { setActiveProfile(it) }
        }
        requestedCloudConfig(intent)?.let { config ->
            saveCloudConfig(config.projectUrl, config.anonKey)
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_COLLAB, false) != true &&
            intent?.action != ACTION_OPEN_COLLAB &&
            intent?.action != ACTION_OPEN_FOCUS
        ) {
            return
        }
        if (intent?.action == ACTION_OPEN_FOCUS) {
            selectedKind = JournalEntryInput.KIND_FOCUS
            activeFilter = JournalEntryInput.KIND_FOCUS
            collabAssignTaskMode = false
            collabSendLaterMode = false
            resetSelectedEntryTime()
            renderKindSelector()
            renderFilterSelector()
            applyCaptureTheme(animate = false)
            renderEntries()
            val prefill = intent.getStringExtra(EXTRA_FOCUS_PREFILL).orEmpty()
            if (prefill.isNotBlank()) {
                quickInput.setText(prefill)
                quickInput.setSelection(quickInput.text.length)
            }
            quickInput.requestFocus()
            showKeyboard(quickInput)
            return
        }
        if (intent?.getBooleanExtra(EXTRA_ACCEPT_CALL, false) == true && ::voiceCallManager.isInitialized) {
            if (ensureRecordAudioPermission(RECORD_AUDIO_ACTION_ACCEPT)) voiceCallManager.acceptIncoming()
        }
        selectedKind = JournalEntryInput.KIND_COLLAB
        activeFilter = JournalEntryInput.KIND_COLLAB
        collabAssignTaskMode = false
        collabSendLaterMode = false
        resetSelectedEntryTime()
        renderKindSelector()
        renderFilterSelector()
        renderCollabUsers()
        applyCaptureTheme(animate = false)
        renderEntries()
        if (intent?.getBooleanExtra(EXTRA_START_CALL, false) == true && ::voiceCallManager.isInitialized) {
            if (ensureRecordAudioPermission(RECORD_AUDIO_ACTION_START)) startOutgoingCallIfPeerOnline()
        }
        quickInput.requestFocus()
        showKeyboard(quickInput)
    }

    private fun maybeAskForProfile(intent: Intent?) {
        if (requestedProfile(intent) != null || hasSavedProfile()) return
        quickInput.post { showProfileDialog(force = true) }
    }

    private fun completeInitialSetup() {
        if (ensureNotificationPermission()) {
            NotificationHelper.showPersistentInput(this)
            JournalReminderScheduler.scheduleNext(this)
        }
        ensureBubblePermission()
        startBubbleIfAllowed()
    }

    private fun showLockedProfileDialog() {
        AlertDialog.Builder(this)
            .setTitle(activeCollabUser)
            .setMessage("This username is registered to this device. Live users appear in the chat list.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showProfileDialog(force: Boolean) {
        val input = EditText(this).apply {
            hint = "daksh"
            setSingleLine(true)
            setText(activeCollabUser.removePrefix("@"))
            setSelection(text.length)
            setTextColor(COLOR_INK)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Register chat name")
            .setMessage("Choose a username. Online users will appear in the chat list.")
            .setView(input)
            .setNegativeButton("@daksh") { _, _ ->
                saveProfileChoice("@daksh")
            }
            .setNeutralButton("@sid") { _, _ ->
                saveProfileChoice("@sid")
            }
            .setPositiveButton("Save") { _, _ ->
                saveProfileChoice(input.text?.toString().orEmpty())
            }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val profile = normalizeProfile(input.text?.toString())
                if (profile == null) {
                    input.error = "Use 2-32 letters/numbers"
                } else {
                    saveProfileChoice(profile)
                    dialog.dismiss()
                }
            }
        }
        dialog.setCanceledOnTouchOutside(!force)
        dialog.setCancelable(!force)
        dialog.show()
    }

    private fun saveProfileChoice(profile: String) {
        setActiveProfile(profile)
        selectedKind = JournalEntryInput.KIND_COLLAB
        activeFilter = JournalEntryInput.KIND_COLLAB
        collabAssignTaskMode = false
        collabSendLaterMode = false
        resetSelectedEntryTime()
        renderCollabUsers()
        renderKindSelector()
        renderFilterSelector()
        applyCaptureTheme(animate = false)
        renderEntries()
    }

    private fun showRelayDialog() {
        val input = EditText(this).apply {
            hint = "http://192.168.137.1:49374"
            setSingleLine(true)
            setText(readCustomRelayBaseUrl())
            setSelection(text.length)
            setTextColor(COLOR_INK)
        }
        AlertDialog.Builder(this)
            .setTitle("Collab server")
            .setMessage("Blank uses auto: emulator reverse, emulator host, and Windows hotspot gateway.")
            .setView(input)
            .setNegativeButton("Auto") { _, _ ->
                saveCustomRelayBaseUrl("")
            }
            .setPositiveButton("Save") { _, _ ->
                saveCustomRelayBaseUrl(input.text?.toString().orEmpty())
            }
            .show()
    }

    private fun showCloudDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
        }
        val urlInput = EditText(this).apply {
            hint = "https://project-ref.supabase.co"
            setSingleLine(true)
            setText(readCloudUrl())
            setSelection(text.length)
            setTextColor(COLOR_INK)
        }
        val keyInput = EditText(this).apply {
            hint = "Supabase anon/public key"
            setSingleLine(true)
            setText(readCloudAnonKey())
            setSelection(text.length)
            setTextColor(COLOR_INK)
        }
        content.addView(urlInput)
        content.addView(keyInput)
        AlertDialog.Builder(this)
            .setTitle("Cloud chat")
            .setMessage("Use this for different networks. Paste Supabase Project URL and anon/public key after running the schema SQL.")
            .setView(content)
            .setNegativeButton("Off") { _, _ ->
                saveCloudConfig("", "")
            }
            .setPositiveButton("Save") { _, _ ->
                saveCloudConfig(
                    urlInput.text?.toString().orEmpty(),
                    keyInput.text?.toString().orEmpty(),
                )
                renderCollabUsers()
            }
            .show()
    }

    private fun setActiveProfile(profile: String) {
        val normalized = normalizeProfile(profile) ?: return
        publishTypingFromInput(false)
        if (::voiceCallManager.isInitialized && !normalized.equals(activeCollabUser, ignoreCase = true)) {
            voiceCallManager.endCall()
        }
        activeCollabUser = normalized
        activeChatPeer = preferredPeerFor(normalized)
        saveProfileToPrefs(normalized)
    }

    private fun saveProfileToPrefs(profile: String) {
        AppPrefs.saveLocalProfile(this, profile)
    }

    private fun preferredPeerFor(profile: String): String {
        val defaults = DEFAULT_PROFILE_MENTIONS.filterNot { it.equals(profile, ignoreCase = true) }
        val knownPeers = chatPeerMentions().filterNot { it.equals(profile, ignoreCase = true) }
        return (defaults + knownPeers + activeChatPeer)
            .firstOrNull { it.startsWith("@") && !it.equals(profile, ignoreCase = true) }
            ?: if (profile.equals("@sid", ignoreCase = true)) "@daksh" else "@sid"
    }

    private fun relayBaseUrls(): List<String> {
        return listOfNotNull(readCustomRelayBaseUrl().takeIf { it.isNotBlank() })
    }

    private fun readCustomRelayBaseUrl(): String {
        return AppPrefs.readCustomRelayBaseUrl(this)
    }

    private fun readCloudConfig(): CloudCollabConfig? {
        return AppPrefs.readCloudConfig(this)
    }

    private fun readCloudUrl(): String {
        return AppPrefs.readCloudUrl(this)
    }

    private fun readCloudAnonKey(): String {
        return AppPrefs.readCloudAnonKey(this)
    }

    private fun saveCloudConfig(url: String, anonKey: String) {
        AppPrefs.saveCloudConfig(this, url, anonKey)
    }

    private fun saveCustomRelayBaseUrl(value: String) {
        AppPrefs.saveCustomRelayBaseUrl(this, value)
    }

    private fun hasSavedProfile(): Boolean {
        return AppPrefs.hasLocalProfile(this)
    }

    private fun readLocalProfile(): String {
        return AppPrefs.readLocalProfile(this)
    }

    private fun requestedProfile(intent: Intent?): String? {
        return normalizeProfile(intent?.getStringExtra(EXTRA_PROFILE))
    }

    private fun requestedCloudConfig(intent: Intent?): CloudCollabConfig? {
        if (intent == null) return null
        val url = intent.getStringExtra(EXTRA_CLOUD_URL).orEmpty()
        val key = intent.getStringExtra(EXTRA_CLOUD_ANON_KEY).orEmpty()
        return AppPrefs.requestedCloudConfig(url, key)
    }

    private fun normalizeProfile(profile: String?): String? {
        return AppPrefs.normalizeProfile(profile)
    }

    companion object {
        const val ACTION_OPEN_COLLAB = "com.daksh.anytimejournal.OPEN_COLLAB"
        const val ACTION_OPEN_FOCUS = "com.daksh.anytimejournal.OPEN_FOCUS"
        const val EXTRA_OPEN_COLLAB = "open_collab"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_ACCEPT_CALL = "accept_call"
        const val EXTRA_START_CALL = "start_call"
        const val EXTRA_FOCUS_PREFILL = "focus_prefill"
        const val EXTRA_CLOUD_URL = "cloud_url"
        const val EXTRA_CLOUD_ANON_KEY = "cloud_anon_key"
        private const val REQUEST_NOTIFICATIONS = 10
        private const val REQUEST_RECORD_AUDIO = 11
        private const val RECORD_AUDIO_ACTION_START = "start"
        private const val RECORD_AUDIO_ACTION_ACCEPT = "accept"
        private const val HIDE_BUBBLE_THROTTLE_MS = 5_000L
        private const val ACTION_POST_TAG = "post_action"
        private const val LOG_TAG = "AnytimeJournal"
        private const val ONLINE_WINDOW_MS = 45_000L
        private const val TYPING_VISIBLE_MS = 3_000L
        private const val TYPING_SEND_THROTTLE_MS = 1_200L
        private const val MIN_TYPING_WORD_LENGTH = 2
        private val DEFAULT_PROFILE_MENTIONS = listOf("@daksh", "@sid")
        private fun defaultProfiles(): List<ProfileEntity> {
            return listOf(
                ProfileEntity("@daksh", "Daksh", isLocal = true),
                ProfileEntity("@sid", "Sid", isLocal = false),
            )
        }
        private val COLOR_APP_BG = Color.rgb(250, 251, 248)
        private val COLOR_SURFACE = Color.rgb(255, 255, 255)
        private val COLOR_LINE = Color.rgb(218, 225, 221)
        private val COLOR_INK = Color.rgb(31, 38, 42)
        private val COLOR_MUTED = Color.rgb(102, 116, 122)
        private val COLOR_ACCENT_GREEN = Color.rgb(44, 132, 102)
        private val COLOR_ACCENT_BLUE = Color.rgb(81, 103, 190)
        private val COLOR_ACCENT_AMBER = Color.rgb(174, 105, 31)
        private val COLOR_ACCENT_TEAL = Color.rgb(38, 126, 138)
        private val COLOR_OBSIDIAN = Color.rgb(105, 78, 160)
        private val COLOR_TINT_GREEN = Color.rgb(226, 244, 238)
        private val COLOR_TINT_BLUE = Color.rgb(232, 237, 255)
        private val COLOR_TINT_AMBER = Color.rgb(252, 238, 216)
        private val COLOR_TINT_TEAL = Color.rgb(220, 242, 244)
        private val COLOR_TINT_OBSIDIAN = Color.rgb(239, 233, 252)
    }
}
