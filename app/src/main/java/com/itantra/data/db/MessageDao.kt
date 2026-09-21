package com.itantra.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(messages: List<MessageEntity>): List<Long>

    @Query("SELECT * FROM messages ORDER BY createdAtLocal ASC")
    fun getAll(): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY createdAtLocal ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAtLocal ASC")
    fun observeByPeerId(peerId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE peerId = :peerId ORDER BY createdAtLocal ASC")
    fun getByPeerId(peerId: String): List<MessageEntity>

    @Query("SELECT DISTINCT peerId FROM messages WHERE peerId != ''")
    fun observeAllPeerIds(): Flow<List<String>>

    @Query("DELETE FROM messages WHERE messageId = :messageId")
    fun delete(messageId: Long): Int

    @Query("DELETE FROM messages")
    fun clear(): Int
}
