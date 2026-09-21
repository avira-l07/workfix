package com.itantra.core.transport

import com.itantra.core.transport.peer.PeerTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Local in-memory loopback transport conforming to [PeerTransport].
 * Designed specifically for automated loopback verification without physical Bluetooth hardware.
 *
 * Clearly labeled: LOCAL LOOPBACK VERIFIED (not physical transport).
 */
class LoopbackTransport(
    override val isServer: Boolean = false
) : PeerTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.CONNECTED)
    override val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    override fun observeConnectionState(): Flow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingFlow = MutableSharedFlow<ByteArray>(replay = 16, extraBufferCapacity = 128)
    override fun receive(): Flow<ByteArray> = _incomingFlow.asSharedFlow()

    var peerEndpoint: LoopbackTransport? = null

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun connect() {
        _connectionState.value = ConnectionState.CONNECTED
    }

    /**
     * Sends bytes over the loopback transport.
     * Consumes the 4-byte length prefix (simulating stream framing reader)
     * and delivers the frame body bytes directly to the peer's receive flow.
     */
    override suspend fun send(bytes: ByteArray) {
        if (!isConnected) {
            throw IllegalStateException("Cannot send over disconnected LoopbackTransport")
        }
        val peer = peerEndpoint ?: throw IllegalStateException("Peer endpoint not linked in LoopbackTransport")

        if (bytes.size < 4) {
            throw IllegalArgumentException("Bytes too short to contain 4-byte frame length prefix")
        }

        val frameBodyLength = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        val frameBody = bytes.copyOfRange(4, bytes.size)
        require(frameBody.size == frameBodyLength) {
            "Frame body size (${frameBody.size}) does not match length prefix ($frameBodyLength)"
        }

        peer._incomingFlow.emit(frameBody)
    }

    companion object {
        /**
         * Creates a paired bidirectional in-memory loopback connection.
         * [sideA] sends bytes to [sideB], and [sideB] sends bytes to [sideA].
         */
        fun createConnectedPair(): Pair<LoopbackTransport, LoopbackTransport> {
            val sideA = LoopbackTransport(isServer = true)
            val sideB = LoopbackTransport(isServer = false)
            sideA.peerEndpoint = sideB
            sideB.peerEndpoint = sideA
            return Pair(sideA, sideB)
        }
    }
}
