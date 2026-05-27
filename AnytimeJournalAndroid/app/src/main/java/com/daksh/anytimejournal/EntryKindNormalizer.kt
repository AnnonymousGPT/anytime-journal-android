package com.daksh.anytimejournal

object EntryKindNormalizer {
    fun normalize(value: String): String {
        return value.trim().lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
    }
}
