package com.itantra.core.transport.peer

import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.packet.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.net.*

class WifiSocketAuditTest {
    private suspend fun connect(transport: WifiDirectPeerTransport): Socket {
        val port = ServerSocket(0).use { it.localPort }
        transport.startServer(port)
        val socket = withTimeout(3000) {
            while (true) {
                try { return@withTimeout Socket(InetAddress.getLoopbackAddress(), port) }
                catch (_: java.io.IOException) { delay(10) }
            }
            error("unreachable")
        }
        withTimeout(2000) { transport.observeConnectionState().first { it == ConnectionState.CONNECTED } }
        return socket
    }

    @Test fun `one byte fragments and coalesced frames retain exact packet boundaries`() = runBlocking {
        val transport = WifiDirectPeerTransport()
        val socket = connect(transport)
        try {
            val frames = (1L..4).map { PacketEncoder.encode(ItantraPacket(PacketType.SECURE_HELLO, messageId = it, payload = ByteArray(80))) }
            frames.first().forEach { socket.getOutputStream().write(it.toInt()); socket.getOutputStream().flush() }
            socket.getOutputStream().write(frames.drop(1).reduce { a,b -> a+b })
            delay(50)
            val received = withTimeout(2000) { transport.receive().take(4).toList() }
            assertEquals(listOf(1L,2L,3L,4L), received.map { PacketDecoder.decode(it).messageId })
        } finally { socket.close(); transport.disconnect() }
    }

    @Test fun `malformed lengths and truncated bodies preserve terminal error`() = runBlocking {
        for (bytes in listOf(byteArrayOf(0,0,0,0), byteArrayOf(-1,-1,-1,-1),
                byteArrayOf(127,127,127,127), byteArrayOf(0,0,0,8,1,2))) {
            val transport = WifiDirectPeerTransport()
            val socket = connect(transport)
            try {
                socket.getOutputStream().write(bytes)
                socket.shutdownOutput()
                withTimeout(2000) { transport.observeConnectionState().first { it == ConnectionState.ERROR } }
                assertFalse(transport.isConnected)
                assertEquals(WifiDirectError.SOCKET_CLOSED, transport.lastError.value)
            } finally { socket.close(); transport.disconnect() }
        }
    }

    @Test fun `queued writer cannot send old session bytes after reconnect`() = runBlocking {
        val transport = WifiDirectPeerTransport()
        val first = connect(transport)
        val lock = transport.javaClass.getDeclaredField("writeMutex").apply { isAccessible = true }.get(transport) as Mutex
        lock.lock()
        var second: Socket? = null
        try {
            val oldId = transport.connectionId
            val sending = async(Dispatchers.IO) { runCatching { transport.send(byteArrayOf(1,2,3), oldId) } }
            delay(50)
            first.close()
            transport.disconnect()
            second = connect(transport)
            lock.unlock()
            assertTrue(withTimeout(2000) { sending.await() }.isFailure)
            assertTrue(transport.isConnected)
            second.soTimeout = 100
            assertTrue(runCatching { second.getInputStream().read() }.exceptionOrNull() is SocketTimeoutException)
        } finally { if (lock.isLocked) lock.unlock(); first.close(); second?.close(); transport.disconnect() }
    }

    @Test fun `cancel before accept cannot resurrect listener or connection`() = runBlocking {
        val transport = WifiDirectPeerTransport()
        repeat(12) {
            val port = ServerSocket(0).use { it.localPort }
            transport.startServer(port)
            transport.disconnect()
            delay(10)
            assertEquals(ConnectionState.DISCONNECTED, transport.observeConnectionState().first())
            assertFalse(transport.isConnected)
            ServerSocket(port).close() // no leaked bind from the cancelled accept job
        }
    }
}
