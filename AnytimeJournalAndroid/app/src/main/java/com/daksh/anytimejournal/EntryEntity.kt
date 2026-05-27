package com.daksh.anytimejournal

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "entries",
    indices = [
        Index(value = ["createdAtMillis", "id"]),
        Index(value = ["text", "kind", "createdAtMillis"]),
    ],
)
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAtMillis: Long,
    val kind: String = JournalEntryInput.KIND_JOURNAL,
)
