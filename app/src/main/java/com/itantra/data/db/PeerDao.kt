package com.itantra.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeenMillis DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE deviceId = :deviceId LIMIT 1")
    fun getByDeviceId(deviceId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfAbsent(peer: PeerEntity): Long

    @Query("UPDATE peers SET displayName = :displayName, lastSeenMillis = :lastSeenMillis WHERE deviceId = :deviceId")
    fun updateProfile(deviceId: String, displayName: String, lastSeenMillis: Long)

    @Query("UPDATE peers SET transportAddress = :address, transportName = :transportName WHERE deviceId = :deviceId")
    fun updateTransport(deviceId: String, address: String, transportName: String)

    @Transaction
    fun rememberVerifiedPeer(peer: PeerEntity) {
        if (peer.deviceId.isBlank()) return
        insertIfAbsent(peer)
        updateProfile(peer.deviceId, peer.displayName, peer.lastSeenMillis)
        if (peer.transportAddress.isNotBlank()) {
            updateTransport(peer.deviceId, peer.transportAddress, peer.transportName)
        }
    }
}
