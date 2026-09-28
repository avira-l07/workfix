package com.itantra.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A private local transcript created from the Hub microphone. It is not a chat message. */
@Entity(tableName = "voice_notes")
data class VoiceNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transcribedText: String,
    val languageWireCode: String,
    val createdAtMillis: Long,
)
