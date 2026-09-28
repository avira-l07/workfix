package com.itantra.regression

import com.itantra.domain.model.MessageState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class Phase1BugfixTest {
    @Test fun clockRollbackPreservesMonotonicIdsAndSalt() {
        BugfixFixture().use { f ->
            val first = f.nextId()
            val future = System.currentTimeMillis() + 100_000L
            (f.field("lastMessageTick") as java.util.concurrent.atomic.AtomicLong).set(future)
            val next = f.nextId()
            assertTrue(next > (future shl 12))
            assertEquals(first and 0xfff, next and 0xfff)
            assertTrue(f.nextId() > next)
        }
    }
    @Test fun concurrentIdsAreUniqueAndSequentialIdsIncrease() = runBlocking {
        BugfixFixture().use { f ->
            val ids = (1..1000).map { async(Dispatchers.Default) { f.nextId() } }.awaitAll()
            assertEquals(1000, ids.toSet().size)
            val sequential = (1..1000).map { f.nextId() }
            assertTrue(sequential.zipWithNext().all { (a, b) -> b > a })
        }
    }

    @Test fun concurrentAddsAndUpdatesLoseNothing() = runBlocking {
        BugfixFixture().use { f ->
            (1L..1000L).map { id -> async(Dispatchers.Default) { f.add(sampleMessage(id)) } }.awaitAll()
            assertEquals(1000, f.coordinator.messages.value.size)
            (1L..1000L).map { id -> async(Dispatchers.Default) {
                f.update(id) { it.copy(state = MessageState.DELIVERED) }
            } }.awaitAll()
            assertTrue(f.coordinator.messages.value.all { it.state == MessageState.DELIVERED })
        }
    }

    @Test fun persistenceKeepsLatestSnapshotAfterRapidUpdates() = runBlocking {
        val dao = RecordingMessageDao()
        BugfixFixture(dao).use { f ->
            // Sequential logical updates, issued while IO writers are independently scheduled.
            repeat(1000) { index ->
                val id = index.toLong() + 1
                f.add(sampleMessage(id))
                f.update(id) { it.copy(state = MessageState.DELIVERED) }
            }
            f.awaitCondition { dao.writes.get() == 2000 }
            assertEquals(1000, dao.rows.size)
            assertTrue("An old snapshot replaced DELIVERED", dao.rows.values.all { it.stateName == "DELIVERED" })
        }
    }
}
