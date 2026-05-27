package com.daksh.anytimejournal

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryUiFormatterTest {
    @Test
    fun kindLabelFallsBackToJournal() {
        assertEquals("Journal", EntryUiFormatter.kindLabel(" "))
        assertEquals("Idea", EntryUiFormatter.kindLabel("idea"))
        assertEquals("Task", EntryUiFormatter.kindLabel("TASK"))
    }

    @Test
    fun extractTagsDeduplicatesAndLimitsTags() {
        val tags = EntryUiFormatter.extractTags("#Work note #work #home #idea #extra #later")

        assertEquals(listOf("#work", "#home", "#idea", "#extra"), tags)
    }

    @Test
    fun compactPreviewCollapsesWhitespaceAndTruncates() {
        val preview = EntryUiFormatter.compactPreview("  one\n\n two   three four  ", maxLength = 14)

        assertEquals("one two thr...", preview)
    }

    @Test
    fun compactPreviewKeepsResultWithinMaxLength() {
        val preview = EntryUiFormatter.compactPreview("abcdef", maxLength = 3)

        assertEquals("...", preview)
        assertEquals(3, preview.length)
    }
}
