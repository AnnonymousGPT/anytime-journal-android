package com.daksh.anytimejournal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabMessageTest {
    @Test
    fun parseReadsAuthorPrefix() {
        val message = CollabMessage.parse("@daksh: hello @sid", "@fallback")

        assertEquals("@daksh", message.author)
        assertEquals("hello @sid", message.body)
    }

    @Test
    fun prepareDraftAddsAuthorAndPeerMention() {
        val stored = CollabMessage.prepareDraft(
            text = "hello",
            author = "@daksh",
            peer = "@sid",
            knownProfiles = listOf("@daksh", "@sid"),
        )

        assertEquals("@daksh: hello @sid", stored)
    }

    @Test
    fun visibleBodyHidesRoutingMentions() {
        val message = CollabMessage.parse("@sid: hello @daksh", "@daksh")

        assertEquals("hello", message.visibleBody("@daksh", "@sid"))
    }

    @Test
    fun threadVisibilityMatchesDirectChat() {
        val direct = CollabMessage.parse("@sid: hello @daksh", "@daksh")
        val unrelated = CollabMessage.parse("@sid: hello @raj", "@daksh")

        assertTrue(direct.isVisibleInThread("@daksh", "@sid"))
        assertFalse(unrelated.isVisibleInThread("@daksh", "@sid"))
    }
}
