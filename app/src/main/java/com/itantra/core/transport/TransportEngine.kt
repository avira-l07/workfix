package com.itantra.core.transport

import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.domain.model.TransmissionMetrics
import kotlinx.coroutines.flow.Flow

/**
 * Contract for sending/receiving a compact text packet between two
 * devices over a local, offline transport (Bluetooth / Wi-Fi Direct).
 *
 * Implemented by [TransportCoordinator], which wraps whichever
 * [com.itantra.core.transport.peer.PeerTransport] (Bluetooth RFCOMM or
 * Wi-Fi Direct TCP) is currently active, so upper layers stay agnostic
 * to the underlying radio.
 */
interface TransportEngine {

    val isConnected: Boolean
    val isServer: Boolean

    fun observeConnectionState(): Flow<ConnectionState>

    suspend fun disconnect()

    fun notifyAckReceived(messageId: Long)

    /** Notifies the engine that an authenticated packet was received, updating link liveness. */
    fun notifyLivenessReceived() {}

    /** Enables or disables post-verification authenticated silence watchdog. */
    fun setAuthenticatedLivenessEnabled(enabled: Boolean) {}

    /** Sends a complete packet and returns transmission metrics. */
    suspend fun send(packet: ItantraPacket): TransmissionMetrics

    /** Stream of packets received from a connected peer. */
    fun receive(): Flow<ItantraPacket>
}

enum class ConnectionState {
    DISCONNECTED,
    LISTENING,
    CONNECTING,
    CONNECTED,
    ERROR,
}
