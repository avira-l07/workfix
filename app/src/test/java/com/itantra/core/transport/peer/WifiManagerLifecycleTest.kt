package com.itantra.core.transport.peer

import android.content.ContextWrapper
import android.net.wifi.p2p.WifiP2pInfo
import com.itantra.core.transport.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class WifiManagerLifecycleTest {
    private val context = object : ContextWrapper(null) {
        override fun getSystemService(name: String): Any? = null
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int = 0
    }
    private fun info(owner: Boolean = true, address: InetAddress? = InetAddress.getLoopbackAddress()) = WifiP2pInfo().apply {
        groupFormed = true; isGroupOwner = owner; groupOwnerAddress = address
    }
    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(3000) { while (!condition()) delay(5) }
    private fun cancel(manager: WifiDirectConnectionManager) {
        (manager.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(manager) as CoroutineScope).cancel()
    }

    @Test fun `initial disconnected socket cannot erase pending P2P group setup`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val transport = WifiDirectPeerTransport()
        val manager = WifiDirectConnectionManager(context, transport, hardwareSupported = true)
        try {
            manager.handleConnectionInfo(info())
            waitFor { transport.observeConnectionState().first() == ConnectionState.LISTENING }
            assertEquals(WifiDirectState.TCP_CONNECTING, manager.state.value)
            assertNotNull(manager.connectionInfo.value)
        } finally { manager.disconnect(); transport.disconnect(); cancel(manager); Dispatchers.resetMain() }
    }

    @Test fun `missing owner address fails before starting client and late group is ignored after cancel`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val transport = WifiDirectPeerTransport()
        val manager = WifiDirectConnectionManager(context, transport, hardwareSupported = true)
        try {
            manager.handleConnectionInfo(info(owner = false, address = null))
            assertEquals(WifiDirectError.GROUP_OWNER_ADDRESS_MISSING, manager.lastError.value)
            assertEquals(WifiDirectState.ERROR, manager.state.value)
            manager.disconnect()
            manager.handleConnectionInfo(info(), generation = 0L)
            delay(50)
            assertEquals(WifiDirectState.AVAILABLE, manager.state.value)
            assertEquals(ConnectionState.DISCONNECTED, transport.observeConnectionState().first())
        } finally { transport.disconnect(); cancel(manager); Dispatchers.resetMain() }
    }

    @Test fun `duplicate group callback preserves socket and radio off closes it with one restore callback`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val transport = WifiDirectPeerTransport()
        val connected = AtomicInteger(); val restored = AtomicInteger()
        val manager = WifiDirectConnectionManager(context, transport, { connected.incrementAndGet() }, { restored.incrementAndGet() }, true)
        var socket: Socket? = null
        try {
            val group = info()
            manager.handleConnectionInfo(group)
            socket = withTimeout(3000) {
                while (true) {
                    try { return@withTimeout Socket(InetAddress.getLoopbackAddress(), WifiDirectPeerTransport.DEFAULT_PORT) }
                    catch (_: java.io.IOException) { delay(10) }
                }
                error("unreachable")
            }
            waitFor { manager.state.value == WifiDirectState.CONNECTED }
            val id = transport.connectionId
            manager.handleConnectionInfo(group)
            assertEquals(id, transport.connectionId)
            assertEquals(1, connected.get())
            manager.terminate(WifiDirectState.OFF, WifiDirectError.P2P_DISABLED)
            waitFor { !transport.isConnected && restored.get() == 1 }
            assertEquals(WifiDirectState.OFF, manager.state.value)
            assertNull(manager.connectionInfo.value)
            assertEquals(WifiDirectError.P2P_DISABLED, manager.lastError.value)
            assertEquals(-1, socket.getInputStream().read())
        } finally { socket?.close(); transport.disconnect(); cancel(manager); Dispatchers.resetMain() }
    }
}
