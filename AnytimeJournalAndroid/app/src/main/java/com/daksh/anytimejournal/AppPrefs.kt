package com.daksh.anytimejournal

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "anytime_journal_prefs"
    private const val PREF_LOCAL_PROFILE = "local_collab_profile"
    private const val PREF_RELAY_BASE_URL = "relay_base_url"
    private const val PREF_CLOUD_URL = "cloud_supabase_url"
    private const val PREF_CLOUD_ANON_KEY = "cloud_supabase_anon_key"
    private const val PREF_APP_VISIBLE = "app_visible"
    private const val PREF_COLLAB_VISIBLE = "collab_visible"
    private const val PREF_ACTIVE_CHAT_PEER = "active_chat_peer"

    fun hasLocalProfile(context: Context): Boolean {
        return prefs(context).contains(PREF_LOCAL_PROFILE)
    }

    fun readLocalProfile(context: Context): String {
        val saved = prefs(context).getString(PREF_LOCAL_PROFILE, null)
        return normalizeProfile(saved) ?: "@daksh"
    }

    fun saveLocalProfile(context: Context, profile: String) {
        val normalized = normalizeProfile(profile) ?: return
        prefs(context).edit()
            .putString(PREF_LOCAL_PROFILE, normalized)
            .apply()
    }

    fun readCustomRelayBaseUrl(context: Context): String {
        return prefs(context)
            .getString(PREF_RELAY_BASE_URL, "")
            .orEmpty()
    }

    fun saveCustomRelayBaseUrl(context: Context, value: String) {
        prefs(context).edit()
            .putString(PREF_RELAY_BASE_URL, value.trim().trimEnd('/'))
            .apply()
    }

    fun readCloudConfig(context: Context): CloudCollabConfig? {
        return validCloudConfig(readCloudUrl(context), readCloudAnonKey(context))
    }

    fun readCloudUrl(context: Context): String {
        return prefs(context)
            .getString(PREF_CLOUD_URL, "")
            .orEmpty()
    }

    fun readCloudAnonKey(context: Context): String {
        return prefs(context)
            .getString(PREF_CLOUD_ANON_KEY, "")
            .orEmpty()
    }

    fun saveCloudConfig(context: Context, url: String, anonKey: String) {
        prefs(context).edit()
            .putString(PREF_CLOUD_URL, url.trim().trimEnd('/'))
            .putString(PREF_CLOUD_ANON_KEY, anonKey.cleanedCloudKey())
            .apply()
    }

    fun updateSurfaceState(
        context: Context,
        appVisible: Boolean,
        collabVisible: Boolean,
        activeChatPeer: String,
    ) {
        prefs(context).edit()
            .putBoolean(PREF_APP_VISIBLE, appVisible)
            .putBoolean(PREF_COLLAB_VISIBLE, collabVisible)
            .putString(PREF_ACTIVE_CHAT_PEER, activeChatPeer.lowercase())
            .apply()
    }

    fun isCollabForeground(context: Context): Boolean {
        return prefs(context).getBoolean(PREF_APP_VISIBLE, false) &&
            prefs(context).getBoolean(PREF_COLLAB_VISIBLE, false)
    }

    fun activeChatPeer(context: Context): String {
        return prefs(context)
            .getString(PREF_ACTIVE_CHAT_PEER, "")
            .orEmpty()
    }

    fun requestedCloudConfig(url: String, anonKey: String): CloudCollabConfig? {
        return validCloudConfig(url, anonKey)
    }

    fun normalizeProfile(profile: String?): String? {
        val raw = profile?.trim()?.lowercase().orEmpty()
        val normalized = if (raw.startsWith("@")) raw else "@$raw"
        if (!Regex("^@[a-z0-9_-]{2,32}$").matches(normalized)) return null
        return normalized
    }

    private fun validCloudConfig(url: String, anonKey: String): CloudCollabConfig? {
        val cleanUrl = url.trim().trimEnd('/')
        val cleanKey = anonKey.cleanedCloudKey()
        if (!cleanUrl.startsWith("https://") || cleanKey.length < 20) return null
        return CloudCollabConfig(cleanUrl, cleanKey)
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun String.cleanedCloudKey(): String = replace(Regex("\\s+"), "")
}
