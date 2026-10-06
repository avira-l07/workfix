package com.itantra.regression

import com.itantra.data.db.MessageEntity
import com.itantra.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RecycleBinRegressionTest {
    @Test fun sevenDaysIsAnExactDeadlineAndUnsafeMessagesCannotBeDeleted() {
        val deleted = 1_800_000_000_000L
        assertTrue(RecycleBinPolicy.canRestore(deleted, deleted + RecycleBinPolicy.RETENTION_MILLIS - 1))
        assertFalse(RecycleBinPolicy.canRestore(deleted, deleted + RecycleBinPolicy.RETENTION_MILLIS))
        assertEquals(7, RecycleBinPolicy.daysRemaining(deleted, deleted))
        assertEquals(1, RecycleBinPolicy.daysRemaining(deleted, deleted + RecycleBinPolicy.RETENTION_MILLIS - 1))
        assertFalse(RecycleBinPolicy.canTrash(sampleMessage(1, MessageState.RECORDING)))
        assertFalse(RecycleBinPolicy.canTrash(sampleMessage(1, MessageState.STT_COMPLETE)))
        assertFalse(RecycleBinPolicy.canTrash(sampleMessage(1, MessageState.ERROR).copy(priority = MessagePriority.CRITICAL)))
        assertTrue(RecycleBinPolicy.canTrash(sampleMessage(1, MessageState.ACKNOWLEDGED).copy(priority = MessagePriority.CRITICAL)))
    }

    @Test fun deletionSurvivesRestartAndRestoreKeepsOriginalDateWithoutResending() = runBlocking {
        val dao = RecordingMessageDao()
        val now = System.currentTimeMillis()
        val original = sampleMessage(100, MessageState.DELIVERED).copy(createdAtLocal = now - 86_400_000)
        BugfixFixture(dao).use { f ->
            f.add(original)
            assertTrue(f.coordinator.moveMessageToTrash(100, now))
            assertTrue(f.coordinator.messages.value.isEmpty())
            f.awaitCondition { dao.rows[100]?.deletedAtMillis == now }
        }
        BugfixFixture(dao).use { f ->
            f.awaitCondition { f.coordinator.trashedMessages.value.size == 1 }
            assertTrue(f.coordinator.restoreMessage(100, now + 1))
            assertEquals(original, f.coordinator.messages.value.single())
            f.awaitCondition { dao.rows[100]?.deletedAtMillis == null }
            assertTrue(f.sent.isEmpty())
            assertTrue(f.coordinator.trashedMessages.value.isEmpty())
        }
    }

    @Test fun expiredHistoryIsPurgedOnRestartAndCannotBeRestored() = runBlocking {
        val dao = RecordingMessageDao()
        val now = System.currentTimeMillis()
        dao.insert(MessageEntity.fromDomain(sampleMessage(100, MessageState.SENT)
            .copy(deletedAtMillis = now - RecycleBinPolicy.RETENTION_MILLIS)))
        BugfixFixture(dao).use { f ->
            f.awaitCondition { dao.rows.isEmpty() }
            assertFalse(f.coordinator.restoreMessage(100, now))
            assertTrue(f.coordinator.messages.value.isEmpty())
            assertTrue(f.coordinator.trashedMessages.value.isEmpty())
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun permanentDeletionAndLateDeliveryDoNotResurrectHistory() = runBlocking {
        val dao = RecordingMessageDao()
        BugfixFixture(dao).use { f ->
            f.add(sampleMessage(100, MessageState.SENT))
            assertTrue(f.coordinator.moveMessageToTrash(100))
            f.awaitCondition { dao.rows[100]?.deletedAtMillis != null }
            f.update(100) { it.copy(state = MessageState.DELIVERED) }
            assertTrue(f.coordinator.messages.value.isEmpty())
            f.coordinator.deleteTrashedMessage(100)
            f.awaitCondition { dao.rows.isEmpty() }
            assertFalse(f.coordinator.restoreMessage(100))
            assertTrue(f.sent.isEmpty())
        }
    }
}
