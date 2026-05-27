package com.daksh.anytimejournal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(profiles: List<ProfileEntity>)

    @Query("SELECT * FROM profiles ORDER BY isLocal DESC, mention ASC")
    suspend fun profiles(): List<ProfileEntity>
}
