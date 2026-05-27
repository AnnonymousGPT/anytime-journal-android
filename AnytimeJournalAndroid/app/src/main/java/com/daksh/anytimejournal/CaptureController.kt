package com.daksh.anytimejournal

object CaptureController {
    data class State(
        val selectedKind: String,
        val selectedEntryTimeMillis: Long,
        val customEntryTimeSelected: Boolean,
        val collabAssignTaskMode: Boolean,
        val collabSendLaterMode: Boolean,
        val editingEntryId: Long?,
    )

    enum class ChatTool {
        SCHEDULE,
        ASSIGN_TASK,
        TEN_MIN,
        DUE,
    }

    fun primaryCtaLabel(state: State, nowMillis: Long): String {
        return when {
            state.editingEntryId != null -> "Save"
            EntryKindNormalizer.normalize(state.selectedKind) == JournalEntryInput.KIND_COLLAB &&
                state.collabAssignTaskMode -> "Assign"
            EntryKindNormalizer.normalize(state.selectedKind) == JournalEntryInput.KIND_COLLAB &&
                state.collabSendLaterMode &&
                state.customEntryTimeSelected &&
                state.selectedEntryTimeMillis > nowMillis -> "Schedule"
            EntryKindNormalizer.normalize(state.selectedKind) == JournalEntryInput.KIND_COLLAB -> "Send"
            EntryKindNormalizer.normalize(state.selectedKind) == JournalEntryInput.KIND_TASK -> "Add task"
            EntryKindNormalizer.normalize(state.selectedKind) == JournalEntryInput.KIND_IDEA -> "Save idea"
            else -> "Post"
        }
    }

    fun chatToolLabel(tool: ChatTool): String {
        return when (tool) {
            ChatTool.SCHEDULE -> "Later"
            ChatTool.ASSIGN_TASK -> "Task"
            ChatTool.TEN_MIN -> "10 min"
            ChatTool.DUE -> "Due"
        }
    }

    fun captureHint(kind: String, activeChatPeer: String): String {
        return when (EntryKindNormalizer.normalize(kind)) {
            JournalEntryInput.KIND_IDEA -> "Spark, link, priority..."
            JournalEntryInput.KIND_TASK -> "Task, checklist, due..."
            JournalEntryInput.KIND_COLLAB -> "Message ${activeChatPeer.removePrefix("@")}..."
            JournalEntryInput.KIND_JOURNAL -> "Log mood, moment, thought..."
            else -> "Add ${EntryUiFormatter.kindLabel(kind).lowercase()} item..."
        }
    }

    fun timeSelectorLabel(state: State, nowMillis: Long, formatTime: (Long) -> String): String {
        val millis = if (state.customEntryTimeSelected) state.selectedEntryTimeMillis else nowMillis
        if (state.selectedKind == JournalEntryInput.KIND_COLLAB) {
            return when {
                state.collabAssignTaskMode -> "Task ${formatTime(millis)}"
                state.collabSendLaterMode && state.customEntryTimeSelected -> "Send ${formatTime(millis)}"
                else -> "Send now"
            }
        }
        if (!state.customEntryTimeSelected && state.selectedKind == JournalEntryInput.KIND_TASK) {
            return "In 10 min"
        }
        val prefix = if (state.customEntryTimeSelected) "Time" else "Now"
        return "$prefix ${formatTime(millis)}"
    }
}
