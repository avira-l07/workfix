package com.itantra.regression

import com.itantra.domain.model.*
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class RecordingCancellationTest {
    @Test fun cancellingCaptureClearsAudioWithoutSendingAndLaterReleaseDoesNothing() {
        BugfixFixture().use { f ->
            val id = 101L
            f.add(sampleMessage(id, MessageState.RECORDING))
            val capture = Job()
            f.coordinator.javaClass.getDeclaredField("recordingJob").apply { isAccessible = true }.set(f.coordinator, capture)
            f.coordinator.javaClass.getDeclaredField("activeRecordingMessageId").apply { isAccessible = true }.setLong(f.coordinator, id)
            val audio = floatArrayOf(.2f, -.2f)
            @Suppress("UNCHECKED_CAST")
            val chunks = f.field("activeRecordingChunks") as MutableList<FloatArray>
            chunks.add(audio)

            f.coordinator.cancelActiveRecording()
            f.coordinator.stopActiveRecording()

            assertTrue(capture.isCancelled)
            assertTrue(chunks.isEmpty())
            assertTrue(audio.all { it == 0f })
            assertEquals("Recording cancelled", f.coordinator.messages.value.single().text)
            assertEquals(MessageState.ERROR, f.coordinator.messages.value.single().state)
            assertTrue(f.sent.isEmpty())
        }
    }

    @Test fun leavingScreenAfterCaptureFinishedDoesNotChangeDeliveredMessage() {
        BugfixFixture().use { f ->
            val completed = sampleMessage(102L, MessageState.DELIVERED)
            f.add(completed)
            // A completed capture can still have its old ID; only an active job is cancellable.
            f.coordinator.javaClass.getDeclaredField("activeRecordingMessageId").apply { isAccessible = true }.setLong(f.coordinator, completed.messageId)
            f.coordinator.cancelActiveRecording()
            assertEquals(completed, f.coordinator.messages.value.single())
            assertTrue(f.sent.isEmpty())
        }
    }
}
