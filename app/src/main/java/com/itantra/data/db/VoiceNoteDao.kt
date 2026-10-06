package com.itantra.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VoiceNoteDao {
    @Insert fun insert(note: VoiceNoteEntity): Long
    @Query("SELECT * FROM voice_notes WHERE deletedAtMillis IS NULL ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<VoiceNoteEntity>>
    @Query("SELECT * FROM voice_notes WHERE deletedAtMillis IS NOT NULL ORDER BY deletedAtMillis DESC")
    fun observeTrash(): Flow<List<VoiceNoteEntity>>
    @Query("UPDATE voice_notes SET deletedAtMillis = :now WHERE id = :id AND deletedAtMillis IS NULL")
    fun moveToTrash(id: Long, now: Long): Int
    @Query("UPDATE voice_notes SET deletedAtMillis = NULL WHERE id = :id AND deletedAtMillis > :cutoff")
    fun restore(id: Long, cutoff: Long): Int
    @Query("DELETE FROM voice_notes WHERE deletedAtMillis IS NOT NULL AND deletedAtMillis <= :cutoff")
    fun purgeExpired(cutoff: Long): Int
    @Query("DELETE FROM voice_notes WHERE id = :id")
    fun delete(id: Long): Int
    @Query("DELETE FROM voice_notes WHERE id = :id AND deletedAtMillis IS NOT NULL")
    fun deleteTrashed(id: Long): Int
}
