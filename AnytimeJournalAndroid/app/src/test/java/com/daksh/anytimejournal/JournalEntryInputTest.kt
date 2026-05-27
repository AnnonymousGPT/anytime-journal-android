package com.daksh.anytimejournal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JournalEntryInputTest {
    @Test
    fun prepareTrimsTextAndUsesProvidedTimestamp() {
        val entry = JournalEntryInput.prepare("  capture this idea  ", 1234L, "idea")

        assertEquals("capture this idea", entry?.text)
        assertEquals(1234L, entry?.createdAtMillis)
        assertEquals("idea", entry?.kind)
    }

    @Test
    fun prepareRejectsBlankText() {
        assertNull(JournalEntryInput.prepare("   ", 1234L, "journal"))
        assertNull(JournalEntryInput.prepare(null, 1234L, "journal"))
    }

    @Test
    fun prepareFallsBackToJournalForBlankKind() {
        val entry = JournalEntryInput.prepare("note", 1234L, " ")

        assertEquals("journal", entry?.kind)
    }

    @Test
    fun prepareNormalizesCustomCategoryForStorage() {
        val entry = JournalEntryInput.prepare("note", 1234L, " Work Calls! ")

        assertEquals("work-calls", entry?.kind)
    }
}
