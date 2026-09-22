package com.itantra.core.transport


import com.itantra.core.transport.packet.ItantraPacket
import com.itantra.core.transport.packet.PacketDecoder
import com.itantra.core.transport.packet.PacketEncoder
import com.itantra.core.transport.packet.PacketType
import com.itantra.core.transport.peer.PeerTransport
import com.itantra.domain.model.Measurement
import com.itantra.domain.model.TransmissionMetrics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages the active [PeerTransport] (e.g., Bluetooth or Wi-Fi), and handles framing,
 * packet encoding/decoding, ACK-based RTT measurement, and connection liveness
 * (heartbeat + idle watchdog).
 * Implements [TransportEngine] so that upper layers remain unaware of the underlying transport mechanism.
 */
class TransportCoordinator(
    initialTransport: PeerTransport
) : TransportEngine {

    private val _activeTransport = kotlinx.coroutines.flow.MutableStateFlow(initialTransport)
    val activeTransportFlow: kotlinx.coroutines.flow.StateFlow<PeerTransport> = _activeTransport

    private val activeTransport: PeerTransport
        get() = _activeTransport.value

    companion object {
        private const val ACK_TIMEOUT_MS = 5000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 15_000L
        private const val PEER_SILENCE_TIMEOUT_MS = 180_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val incomingFlow = MutableSharedFlow<ItantraPacket>(extraBufferCapacity = 64)

    // Fix: previously ACK waits were done via `ackFlow.first { it == messageId }` started
    // *after* activeTransport.send() was already in flight. Because the underlying flow has
    // no replay, a fast peer reply could arrive and be emitted before the collector subscribed,
    // silently dropping the ACK and forcing a full 5s timeout even though delivery succeeded.
    // Registering a waiter for this exact messageId *before* sending removes that race.
    private val ackWaiters = ConcurrentHashMap<Long, CompletableDeferred<Long>>()

    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var connectionStateJob: Job? = null
    private val lastRxAtMs = AtomicLong(0L)

    @Volatile
    private var authenticatedLivenessEnabled = false

    internal fun isAuthenticatedLivenessEnabled(): Boolean = authenticatedLivenessEnabled
    internal fun getLastRxAtMs(): Long = lastRxAtMs.get()
    internal fun setLastRxAtMs(timeMs: Long) { lastRxAtMs.set(timeMs) }

    init {
        startObservingTransport()
    }

    /**
     * Switches the active transport dynamically.
     */
    suspend fun switchTransport(newTransport: PeerTransport) {
        val oldTransport = _activeTransport.value
        if (oldTransport === newTransport) return

        authenticatedLivenessEnabled = false
        oldTransport.disconnect()
        _activeTransport.value = newTransport
        startObservingTransport()
    }

    private fun startObservingTransport() {
        readJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        connectionStateJob?.cancel()
        authenticatedLivenessEnabled = false
        lastRxAtMs.set(System.currentTimeMillis())

        connectionStateJob = scope.launch {
            activeTransport.observeConnectionState().collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    lastRxAtMs.set(System.currentTimeMillis())
                    android.util.Log.i("TransportCoordinator", "Link CONNECTED: reset lastRxAtMs to " + lastRxAtMs.get())
                } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                    authenticatedLivenessEnabled = false
                }
            }
        }

        readJob = scope.launch {
            activeTransport.receive().collect { frameData ->
                try {
                    val packet = PacketDecoder.decode(frameData)
                    if (
                        packet.type == PacketType.HEARTBEAT &&
                        packet.securityVersion == 0.toByte()
                    ) {
                        // Reject unauthenticated/plain heartbeat.
                        return@collect
                    }
                    // Emit packet upward for decryption/authentication (including encrypted HEARTBEAT)
                    incomingFlow.emit(packet)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)
                if (!authenticatedLivenessEnabled) continue
                if (!activeTransport.isConnected) {
                    authenticatedLivenessEnabled = false
                    continue
                }
                val silentMs = System.currentTimeMillis() - lastRxAtMs.get()
                if (silentMs >= PEER_SILENCE_TIMEOUT_MS) {
                    android.util.Log.w(
                        "TransportCoordinator",
                        "Authenticated silence timeout: no authenticated traffic for ${silentMs}ms, disconnecting"
                    )
                    authenticatedLivenessEnabled = false
                    activeTransport.disconnect()
                }
            }
        }
    }

    override val isConnected: Boolean
        get() = activeTransport.isConnected

    override val isServer: Boolean
        get() = activeTransport.isServer

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeConnectionState(): Flow<ConnectionState> {
        return _activeTransport.flatMapLatest { transport ->
            transport.observeConnectionState()
        }
    }

    override suspend fun disconnect() {
        authenticatedLivenessEnabled = false
        activeTransport.disconnect()
        readJob?.cancel()
        heartbeatJob?.cancel()
        watchdogJob?.cancel()
        connectionStateJob?.cancel()
        // Fail any in-flight ACK waiters instead of leaving them to time out naturally.
        ackWaiters.values.forEach { it.cancel() }
        ackWaiters.clear()
    }

    override fun notifyAckReceived(messageId: Long) {
        ackWaiters.remove(messageId)?.complete(System.nanoTime())
    }

    override fun notifyLivenessReceived() {
        lastRxAtMs.set(System.currentTimeMillis())
    }

    override fun setAuthenticatedLivenessEnabled(enabled: Boolean) {
        if (enabled) {
            authenticatedLivenessEnabled = true
            lastRxAtMs.set(System.currentTimeMillis())
        } else {
            authenticatedLivenessEnabled = false
        }
    }

    override suspend fun send(packet: ItantraPacket): TransmissionMetrics {
        // Throw on disconnect — returning empty metrics here would cause callers to
        // mark the message as SENT even though nothing was written to the socket.
        if (!isConnected) throw java.io.IOException("Cannot send: transport is not connected")

        return withContext(Dispatchers.IO) {
            val t0 = System.nanoTime()
            val encoded = PacketEncoder.encode(packet)
            var txLatency: Long? = null

            val expectsAck = packet.type == PacketType.TEXT || packet.type == PacketType.EMERGENCY_CODE
            val ackDeferred = if (expectsAck) CompletableDeferred<Long>() else null
            if (ackDeferred != null) {
                // Register the waiter BEFORE sending, so an ACK that comes back before we'd
                // otherwise have started listening can never be missed.
                ackWaiters[packet.messageId] = ackDeferred
            }

            // FIX 001: Separate transport write failure from ACK timeout.
            // If activeTransport.send throws, cancel waiter and re-throw immediately.
            try {
                activeTransport.send(encoded)
            } catch (e: Exception) {
                if (ackDeferred != null) {
                    ackWaiters.remove(packet.messageId)
                    ackDeferred.cancel()
                }
                throw e
            }

            if (ackDeferred != null) {
                try {
                    val t1 = withTimeout(ACK_TIMEOUT_MS) { ackDeferred.await() }
                    txLatency = t1 - t0
                } catch (e: TimeoutCancellationException) {
                    // No ACK received in time after successful physical transmission
                    println("ACK timeout for message: ${packet.messageId}")
                } finally {
                    ackWaiters.remove(packet.messageId)
                }
            }

            val ciphertextBytes = packet.payload.size
            val semanticBytes = if (packet.securityVersion == 1.toByte() && packet.payload.size >= 16) {
                packet.payload.size - 16
            } else {
                packet.payload.size
            }
            val wireBytes = encoded.size

            TransmissionMetrics(
                payloadBytes = Measurement.Measured(packet.payload.size),
                semanticPayloadBytes = Measurement.Measured(semanticBytes),
                secureBytes = Measurement.Measured(ciphertextBytes),
                finalFrameBytes = Measurement.Measured(wireBytes),
                packetBytes = Measurement.Measured(wireBytes),
                transmissionLatencyMillis = txLatency?.let { Measurement.Measured(it / 1_000_000) } ?: Measurement.NotMeasured
            )
        }
    }

    override fun receive(): Flow<ItantraPacket> = incomingFlow
}
