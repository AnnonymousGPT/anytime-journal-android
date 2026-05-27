package com.daksh.anytimejournal

import android.content.Context

class EntryRepository(private val dao: EntryDao) {
    suspend fun saveReply(
        rawText: CharSequence?,
        nowMillis: Long = System.currentTimeMillis(),
        kind: String = JournalEntryInput.KIND_JOURNAL,
    ): Long? {
        val prepared = JournalEntryInput.prepare(rawText, nowMillis, kind) ?: return null
        return dao.insert(
            EntryEntity(
                text = prepared.text,
                createdAtMillis = prepared.createdAtMillis,
                kind = prepared.kind,
            ),
        )
    }

    suspend fun latestEntries(): List<EntryEntity> = dao.latestEntries()

    suspend fun saveRemoteCollab(text: String, createdAtMillis: Long): Long? {
        return saveRemoteSharedEntry(text, createdAtMillis, JournalEntryInput.KIND_COLLAB)
    }

    suspend fun saveRemoteSharedEntry(
        text: String,
        createdAtMillis: Long,
        kind: String,
    ): Long? {
        val prepared = JournalEntryInput.prepare(
            text,
            createdAtMillis,
            kind,
        ) ?: return null
        if (dao.countExact(prepared.text, prepared.kind, prepared.createdAtMillis) > 0) return null
        return dao.insert(
            EntryEntity(
                text = prepared.text,
                createdAtMillis = prepared.createdAtMillis,
                kind = prepared.kind,
            ),
        )
    }

    suspend fun deleteEntry(id: Long) {
        dao.deleteById(id)
    }

    suspend fun updateEntry(
        id: Long,
        rawText: CharSequence?,
        kind: String,
        createdAtMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        val prepared = JournalEntryInput.prepare(rawText, createdAtMillis, kind) ?: return false
        return dao.updateEntry(id, prepared.text, prepared.kind, prepared.createdAtMillis) == 1
    }

    companion object {
        fun from(context: Context): EntryRepository {
            return EntryRepository(AppDatabase.get(context).entryDao())
        }
    }
}

class ProfileRepository(private val dao: ProfileDao) {
    suspend fun seedDefaults() {
        dao.upsertAll(
            listOf(
                ProfileEntity("@daksh", "Daksh", isLocal = true),
                ProfileEntity("@sid", "Sid", isLocal = false),
            ),
        )
    }

    suspend fun profiles(): List<ProfileEntity> = dao.profiles()

    companion object {
        fun from(context: Context): ProfileRepository {
            return ProfileRepository(AppDatabase.get(context).profileDao())
        }
    }
}
