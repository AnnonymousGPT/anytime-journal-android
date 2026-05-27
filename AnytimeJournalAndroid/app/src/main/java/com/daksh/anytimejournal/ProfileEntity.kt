package com.daksh.anytimejournal

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val mention: String,
    val displayName: String,
    val isLocal: Boolean = false,
)
