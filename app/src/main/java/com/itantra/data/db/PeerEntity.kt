package com.itantra.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A peer is saved only after its encrypted profile handshake has been verified. */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val transportAddress: String = "",
    val transportName: String = "Direct",
    val lastSeenMillis: Long,
)
