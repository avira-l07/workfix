package com.itantra.core.transport.peer

import android.content.ContextWrapper
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.packet.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BluetoothSocketLifecycleTest {
    private fun transport() = BluetoothPeerTransport(ContextWrapper(null), null)
    private class BlockedInput : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun read(): Int { entered.countDown(); release.await(5, TimeUnit.SECONDS); return -1 }
        override fun close() {} // Deliberately emulate a late completion from obsolete IO.
    }

    @Test fun `fragmented frames and early HELLO survive until subscriber attaches`() = runBlocking {
        val transport = transport()
        val input = PipedInputStream(4096)
        val remote = PipedOutputStream(input)
        try {
            transport.attachStreams(input, ByteArrayOutputStream(), "peer", transport.connectionId) { input.close() }
            val frame = PacketEncoder.encode(ItantraPacket(PacketType.SECURE_HELLO, messageId = 21, payload = ByteArray(120)))
            frame.forEach { remote.write(it.toInt()); remote.flush() }
            delay(50)
            val received = withTimeout(2000) { transport.receive().first() }
            assertArrayEquals(frame.copyOfRange(4, frame.size), received)
            transport.disconnect()
            assertNull(withTimeoutOrNull(100) { transport.receive().first() })
        } finally { remote.close(); transport.disconnect() }
    }

    @Test fun `cancelled old reader cannot tear down replacement connection`() = runBlocking {
        val transport = transport()
        val oldInput = BlockedInput()
        val newInput = BlockedInput()
        try {
            transport.attachStreams(oldInput, ByteArrayOutputStream(), "old", transport.connectionId) {}
            assertTrue(oldInput.entered.await(2, TimeUnit.SECONDS))
            transport.disconnect()
            transport.attachStreams(newInput, ByteArrayOutputStream(), "new", transport.connectionId) {}
            oldInput.release.countDown()
            delay(100)
            assertTrue(transport.isConnected)
            assertEquals("new", transport.connectedDeviceAddress.value)
        } finally { oldInput.release.countDown(); newInput.release.countDown(); transport.disconnect() }
    }

    @Test fun `late write error does not close or write into replacement socket`() = runBlocking {
        val transport = transport()
        val oldInput = BlockedInput()
        val newInput = BlockedInput()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementOutput = ByteArrayOutputStream()
        try {
            val oldOutput = object : OutputStream() {
                override fun write(value: Int) { entered.countDown(); release.await(5, TimeUnit.SECONDS); throw IOException("Old socket closed") }
            }
            transport.attachStreams(oldInput, oldOutput, "old", transport.connectionId) {}
            val oldId = transport.connectionId
            val sending = async(Dispatchers.IO) { runCatching { transport.send(byteArrayOf(1), oldId) } }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            transport.disconnect()
            transport.attachStreams(newInput, replacementOutput, "new", transport.connectionId) {}
            release.countDown()
            assertTrue(sending.await().isFailure)
            assertTrue(transport.isConnected)
            assertEquals(0, replacementOutput.size())
            assertTrue(runCatching { transport.send(byteArrayOf(2), oldId) }.isFailure)
            assertTrue(transport.isConnected)
            transport.send(byteArrayOf(3))
            assertArrayEquals(byteArrayOf(3), replacementOutput.toByteArray())
        } finally { release.countDown(); oldInput.release.countDown(); newInput.release.countDown(); transport.disconnect() }
    }

    @Test fun `invalid lengths and truncated frame close connection with visible error`() = runBlocking {
        for (frame in listOf(byteArrayOf(0,0,0,0), byteArrayOf(-1,-1,-1,-1), byteArrayOf(0,0,0,9,1,2))) {
            val transport = transport()
            try {
                transport.attachStreams(ByteArrayInputStream(frame), ByteArrayOutputStream(), "peer", transport.connectionId) {}
                withTimeout(2000) { transport.observeConnectionState().first { it == ConnectionState.ERROR } }
                assertFalse(transport.isConnected)
                assertEquals(BluetoothError.SOCKET_DISCONNECTED, transport.lastError.value)
            } finally { transport.disconnect() }
        }
    }

    @Test fun `fast reconnect exposes a disconnect boundary even to a slow observer`() = runBlocking {
        var generation = 1L
        val state = SocketState { generation }
        state.value = ConnectionState.CONNECTED
        val seen = mutableListOf<ConnectionState>()
        val collecting = launch(start = CoroutineStart.UNDISPATCHED) { state.observe().collect { seen.add(it) } }
        // Both mutations occur before the collector resumes. Bare StateFlow<ConnectionState> loses this boundary.
        generation++
        state.value = ConnectionState.DISCONNECTED
        state.value = ConnectionState.CONNECTED
        yield()
        collecting.cancelAndJoin()
        assertEquals(listOf(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED, ConnectionState.CONNECTED), seen)
    }

    @Test fun `stalled native write is bounded by closing socket rather than coroutine timeout alone`() = runBlocking {
        val transport = transport()
        val input = BlockedInput()
        val release = CountDownLatch(1)
        try {
            val output = object : OutputStream() {
                override fun write(value: Int) { release.await(16, TimeUnit.SECONDS); throw IOException("Closed") }
            }
            transport.attachStreams(input, output, "peer", transport.connectionId) { release.countDown() }
            val result = withTimeout(15_000) { runCatching { transport.send(byteArrayOf(1)) } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertFalse(transport.isConnected)
            assertEquals(BluetoothError.SOCKET_DISCONNECTED, transport.lastError.value)
        } finally { release.countDown(); input.release.countDown(); transport.disconnect() }
    }
}
