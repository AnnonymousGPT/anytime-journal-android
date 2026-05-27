package com.daksh.anytimejournal

object EntryUiFormatter {
    fun kindLabel(kind: String): String {
        val normalized = kind.trim().lowercase()
        return when (normalized) {
            JournalEntryInput.KIND_IDEA -> "Idea"
            JournalEntryInput.KIND_TASK -> "Task"
            JournalEntryInput.KIND_COLLAB -> "Collab"
            JournalEntryInput.KIND_JOURNAL, "" -> "Journal"
            else -> normalized.replaceFirstChar { it.titlecase() }
        }
    }

    fun kindPrefix(kind: String): String {
        return when (val normalized = kind.trim().lowercase()) {
            JournalEntryInput.KIND_IDEA -> "#idea"
            JournalEntryInput.KIND_TASK -> "#task"
            JournalEntryInput.KIND_COLLAB -> "#collab"
            JournalEntryInput.KIND_JOURNAL, "" -> "#journal"
            else -> "#$normalized"
        }
    }

    fun extractTags(text: String): List<String> {
        return TAG_REGEX.findAll(text)
            .map { it.value.lowercase() }
            .distinct()
            .take(MAX_VISIBLE_TAGS)
            .toList()
    }

    fun extractMentions(text: String): List<String> {
        return MENTION_REGEX.findAll(text)
            .map { it.value.lowercase() }
            .distinct()
            .take(MAX_VISIBLE_TAGS)
            .toList()
    }

    fun compactPreview(text: String, maxLength: Int = 140): String {
        if (maxLength <= 0) return ""
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        if (normalized.length <= maxLength) return normalized
        if (maxLength <= 3) return ".".repeat(maxLength)
        return normalized.take(maxLength - 3).trimEnd() + "..."
    }

    private const val MAX_VISIBLE_TAGS = 4
    private val TAG_REGEX = Regex("#[A-Za-z0-9_-]+")
    private val MENTION_REGEX = Regex("@[A-Za-z0-9_-]+")
}
