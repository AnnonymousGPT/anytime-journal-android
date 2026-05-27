package com.daksh.anytimejournal

data class PreparedJournalEntry(
    val text: String,
    val createdAtMillis: Long,
    val kind: String,
)

object JournalEntryInput {
    fun prepare(rawText: CharSequence?, nowMillis: Long, rawKind: String = KIND_JOURNAL): PreparedJournalEntry? {
        val text = rawText?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return null
        val kind = EntryKindNormalizer.normalize(rawKind).ifEmpty { KIND_JOURNAL }
        return PreparedJournalEntry(text = text, createdAtMillis = nowMillis, kind = kind)
    }

    const val KIND_JOURNAL = "journal"
    const val KIND_IDEA = "idea"
    const val KIND_TASK = "task"
    const val KIND_FOCUS = "focus"
    const val KIND_COLLAB = "collab"
    const val DEFAULT_TASK_REMINDER_MS = 10 * 60 * 1000L
}
