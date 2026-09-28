package com.itantra.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceNoteDao {
    @Insert fun insert(note: VoiceNoteEntity): Long
    @Query("SELECT * FROM voice_notes ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<VoiceNoteEntity>>
    @Query("DELETE FROM voice_notes WHERE id = :id")
    fun delete(id: Long): Int
}
