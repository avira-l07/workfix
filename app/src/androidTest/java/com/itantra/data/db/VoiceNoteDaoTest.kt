package com.itantra.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceNoteDaoTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    @Test fun deletingVoiceNoteLeavesMessagesUntouched() {
        val message = MessageEntity(
            messageId = 99L,
            languageWireCode = "en",
            targetLanguageWireCode = "en",
            priority = 0,
            text = "sent message",
            originalText = null,
            translationStatusName = "NONE",
            sourceName = "LOCAL",
            createdAtLocal = 100L,
            stateName = "DELIVERED",
        )
        db.messageDao().insert(message)
        val first = db.voiceNoteDao().insert(VoiceNoteEntity(transcribedText = "keep", languageWireCode = "en", createdAtMillis = 100L))
        val second = db.voiceNoteDao().insert(VoiceNoteEntity(transcribedText = "remove", languageWireCode = "en", createdAtMillis = 200L))

        assertEquals(1, db.voiceNoteDao().delete(second))
        assertEquals(listOf("keep"), runBlocking { db.voiceNoteDao().observeAll().first() }.map { it.transcribedText })
        assertEquals("sent message", db.messageDao().getAll().single { it.messageId == 99L }.text)
        assertTrue(first > 0)
    }
}
