package com.daksh.anytimejournal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EntryDao {
    @Insert
    suspend fun insert(entry: EntryEntity): Long

    @Query("SELECT * FROM entries ORDER BY createdAtMillis DESC, id DESC")
    suspend fun latestEntries(): List<EntryEntity>

    @Query("SELECT COUNT(*) FROM entries WHERE text = :text AND kind = :kind AND createdAtMillis = :createdAtMillis")
    suspend fun countExact(text: String, kind: String, createdAtMillis: Long): Int

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE entries SET text = :text, kind = :kind, createdAtMillis = :createdAtMillis WHERE id = :id")
    suspend fun updateEntry(id: Long, text: String, kind: String, createdAtMillis: Long): Int
}
