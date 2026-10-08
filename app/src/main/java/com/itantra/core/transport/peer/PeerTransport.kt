package com.itantra.core.transport.peer

import com.itantra.core.transport.ConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * The lowest-level byte-oriented transport contract.
 * Transports implementing this interface are responsible for handling stream framing
 * (e.g., reading a 4-byte length prefix and returning the framed payload).
 */
interface PeerTransport {

    /** Current connection state. */
    val isConnected: Boolean
    /** Changes whenever a socket is replaced or torn down, even on this same transport. */
    val connectionId: Long get() = 0L

    /** Emits connection state changes. */
    fun observeConnectionState(): Flow<ConnectionState>

    /**
     * Whether this transport instance is currently acting as the server/listener.
     * Used for deterministic cryptographic roles.
     */
    val isServer: Boolean

    /** Disconnects the transport. */
    suspend fun disconnect()

    /**
     * Sends bytes over the transport. The caller is responsible for providing
     * bytes that are already formatted for the wire (e.g., including length prefix).
     */
    suspend fun send(bytes: ByteArray)
    suspend fun send(bytes: ByteArray, expectedConnectionId: Long) {
        check(connectionId == expectedConnectionId) { "Connection changed before send" }
        send(bytes)
    }

    /**
     * Stream of completely read framed payloads. The implementation must consume the
     * length prefix and emit exactly the body bytes.
     */
    fun receive(): Flow<ByteArray>
}

/** Keep socket identity in the state so a fast reconnect cannot conflate two CONNECTED links. */
internal class SocketState(private val generation: () -> Long) {
    private val snapshots = MutableStateFlow(0L to ConnectionState.DISCONNECTED)
    var value: ConnectionState
        get() = snapshots.value.second
        set(state) { snapshots.value = generation() to state }

    fun observe(): Flow<ConnectionState> = flow {
        var previousGeneration: Long? = null
        snapshots.collect { (id, state) ->
            if (previousGeneration != null && previousGeneration != id && state == ConnectionState.CONNECTED) {
                emit(ConnectionState.DISCONNECTED)
            }
            previousGeneration = id
            emit(state)
        }
    }
}
