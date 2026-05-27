package com.daksh.anytimejournal

/**
 * Phase-1 boundary for chat chrome, online users, and typing UI state.
 *
 * MainActivity still owns CollabSyncManager/VoiceCallManager lifecycles. This controller
 * is intentionally small first so collab UI can be migrated without touching networking.
 */
class CollabUiController(
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onSelectPeer(profile: String)
        fun onRenderEntries()
        fun onPublishTyping(peer: String, typing: Boolean)
    }

    fun visibleOnlineUsers(activeUser: String, onlineProfiles: Set<String>): List<String> {
        return (onlineProfiles + activeUser)
            .filter { it.startsWith("@") }
            .distinct()
            .sorted()
    }

    fun selectPeer(profile: String) {
        callbacks.onSelectPeer(profile)
    }

    fun publishTyping(peer: String, typing: Boolean) {
        callbacks.onPublishTyping(peer, typing)
    }

    fun renderEntries() {
        callbacks.onRenderEntries()
    }
}
