package com.daksh.anytimejournal

data class CollabMessage(
    val author: String,
    val body: String,
) {
    fun mentions(profile: String): Boolean {
        return EntryUiFormatter.extractMentions(body)
            .any { it.equals(profile, ignoreCase = true) }
    }

    fun visibleBody(localProfile: String, peerProfile: String): String {
        return body
            .replace(Regex("(?i)\\s*${Regex.escape(localProfile)}\\b"), "")
            .replace(Regex("(?i)\\s*${Regex.escape(peerProfile)}\\b"), "")
            .trim()
            .ifBlank { "Note" }
    }

    fun isVisibleInThread(localProfile: String, peerProfile: String): Boolean {
        val mentions = EntryUiFormatter.extractMentions(body)
        val authoredByMe = author.equals(localProfile, ignoreCase = true)
        val authoredByPeer = author.equals(peerProfile, ignoreCase = true)
        val mentionsMe = mentions.any { it.equals(localProfile, ignoreCase = true) }
        val mentionsPeer = mentions.any { it.equals(peerProfile, ignoreCase = true) }
        if (authoredByMe && mentionsPeer) return true
        if (authoredByPeer && mentionsMe) return true
        return mentions.isEmpty() && (authoredByMe || authoredByPeer)
    }

    fun serialize(): String = "$author: $body"

    companion object {
        private val AUTHOR_PREFIX = Regex("^(@[A-Za-z0-9_-]+):\\s*(.*)$")

        fun parse(text: String, fallbackAuthor: String): CollabMessage {
            val match = AUTHOR_PREFIX.find(text.trim())
            return if (match != null) {
                CollabMessage(match.groupValues[1].lowercase(), match.groupValues[2].trim())
            } else {
                CollabMessage(fallbackAuthor.trim().lowercase(), text.trim())
            }
        }

        fun prepareDraft(text: String, author: String, peer: String, knownProfiles: List<String>): String {
            val trimmed = text.trim()
            val normalizedAuthor = author.trim().lowercase()
            val normalizedPeer = peer.trim().lowercase()
            val authored = if (knownProfiles.any { trimmed.startsWith("${it.lowercase()}:", ignoreCase = true) }) {
                trimmed
            } else {
                "$normalizedAuthor: $trimmed"
            }
            val parsed = parse(authored, normalizedAuthor)
            if (parsed.mentions(normalizedPeer)) return authored
            return parsed.copy(body = "${parsed.body} $normalizedPeer".trim()).serialize()
        }
    }
}
