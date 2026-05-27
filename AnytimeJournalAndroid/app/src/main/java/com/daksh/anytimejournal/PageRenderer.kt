package com.daksh.anytimejournal

import android.graphics.Color

object PageRenderer {
    const val KIND_ALL_SCREEN = "all"

    val primaryPageKinds = listOf(
        JournalEntryInput.KIND_JOURNAL,
        JournalEntryInput.KIND_IDEA,
        JournalEntryInput.KIND_TASK,
        JournalEntryInput.KIND_COLLAB,
    )

    data class PageConfig(
        val title: String,
        val subtitle: String,
        val color: Int,
    )

    fun pageConfig(kind: String?, activeChatPeer: String): PageConfig {
        val normalized = kind?.let { EntryKindNormalizer.normalize(it) }
        return when (normalized) {
            null -> PageConfig("All", "Unified feed across journal, ideas, tasks, and tagged cross-links", COLOR_ACCENT_GREEN)
            JournalEntryInput.KIND_JOURNAL -> PageConfig("Journal", "Timeline for logs, tags, reminders, ideas, and mentions", COLOR_ACCENT_GREEN)
            JournalEntryInput.KIND_IDEA -> PageConfig("Ideas", "Compact spark board for links, priorities, and raw concepts", COLOR_ACCENT_BLUE)
            JournalEntryInput.KIND_TASK -> PageConfig("Tasks", "Fast checklist lane with due time and reminders", COLOR_ACCENT_AMBER)
            JournalEntryInput.KIND_COLLAB -> PageConfig("Collab chat", "Live chat, online users, calls, typing, and shared notes", COLOR_OBSIDIAN)
            else -> PageConfig(EntryUiFormatter.kindLabel(normalized), "Custom tagged entries live inside All", COLOR_OBSIDIAN)
        }
    }

    fun entryMatchesPage(entry: EntryEntity, pageKind: String?): Boolean {
        val page = pageKind?.let { EntryKindNormalizer.normalize(it) } ?: return true
        val entryKind = EntryKindNormalizer.normalize(entry.kind)
        if (page == JournalEntryInput.KIND_COLLAB) return entryKind == JournalEntryInput.KIND_COLLAB
        if (entryKind == page) return true
        if (page !in crossPageKinds) return false
        return EntryUiFormatter.extractTags(entry.text)
            .map { EntryKindNormalizer.normalize(it.removePrefix("#")) }
            .any { it == page }
    }

    private val crossPageKinds = setOf(
        JournalEntryInput.KIND_JOURNAL,
        JournalEntryInput.KIND_IDEA,
        JournalEntryInput.KIND_TASK,
    )

    private val COLOR_ACCENT_GREEN = Color.rgb(44, 132, 102)
    private val COLOR_ACCENT_BLUE = Color.rgb(81, 103, 190)
    private val COLOR_ACCENT_AMBER = Color.rgb(174, 105, 31)
    private val COLOR_OBSIDIAN = Color.rgb(105, 78, 160)
}
