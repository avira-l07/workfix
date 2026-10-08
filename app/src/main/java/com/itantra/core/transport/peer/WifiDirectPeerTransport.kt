package com.itantra.core.transport.peer

import android.util.Log
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.packet.PacketDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Implementation of [PeerTransport] over Wi-Fi Direct TCP Sockets.
 *
 * Implements the exact same length-prefixed binary framing as [BluetoothPeerTransport],
 * allowing upper-layer protocols (packets, cryptography, SAS verification) to operate
 * transparently across Wi-Fi Direct without any packet-level changes.
 *
 * Fixed TCP Port: 8988
 * Connect Timeout: 12,000 ms
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WifiDirectPeerTransport : PeerTransport {

    companion object {
        const val DEFAULT_PORT = 8988
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val TAG = "WifiDirectTransport"

        private fun debugLog(msg: String) {
            // Guarded debug logging — never log message plaintext, private keys, or shared secrets.
            Log.d(TAG, msg)
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var readJob: Job? = null

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val stateFlow = SocketState { connectionId }
    // FIX 019: replay = 16 so initial SECURE_HELLO is never dropped before TransportCoordinator subscribes
    private val incomingFlow = MutableSharedFlow<ByteArray>(replay = 16, extraBufferCapacity = 64)

    private val _lastError = MutableStateFlow(WifiDirectError.NONE)
    val lastError: StateFlow<WifiDirectError> = _lastError.asStateFlow()

    private val _connectedHostAddress = MutableStateFlow<String?>(null)
    val connectedHostAddress: StateFlow<String?> = _connectedHostAddress.asStateFlow()

    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong()

    private var _isServer = false
    override val isServer: Boolean get() = _isServer

    override val isConnected: Boolean
        get() = stateFlow.value == ConnectionState.CONNECTED
    override val connectionId: Long get() = connectionGeneration.get()

    override fun observeConnectionState(): Flow<ConnectionState> = stateFlow.observe()

    override fun receive(): Flow<ByteArray> = incomingFlow

    // -------------------------------------------------------------------------
    // TCP Server (Wi-Fi Direct Group Owner)
    // -------------------------------------------------------------------------

    suspend fun startServer(port: Int = DEFAULT_PORT) {
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = true
            _lastError.value = WifiDirectError.NONE
            val generation = connectionGeneration.get()
            stateFlow.value = ConnectionState.LISTENING

            connectionJob = scope.launch(Dispatchers.IO) {
                debugLog("TCP Server starting on port $port (Group Owner role)")
                try {
                    val srv = ServerSocket()
                    srv.reuseAddress = true
                    srv.soTimeout = 30_000  // Don't block accept() forever; allows clean coroutine cancellation
                    synchronized(this@WifiDirectPeerTransport) {
                        if (connectionGeneration.get() != generation) {
                            srv.close()
                            return@launch
                        }
                        serverSocket = srv
                    }
                    srv.bind(InetSocketAddress(port))
                    debugLog("TCP Server bound and listening on port $port")

                    val socket = srv.accept()
                    debugLog("TCP Server accepted socket from ${socket.inetAddress?.hostAddress?.take(8)}***")
                    manageConnectedSocket(socket, generation)
                } catch (e: CancellationException) {
                    debugLog("Server accept job cancelled")
                    throw e
                } catch (e: java.net.SocketTimeoutException) {
                    debugLog("Server accept timed out after 30s — no client arrived")
                    failConnection(generation, WifiDirectError.TCP_SERVER_FAILED)
                } catch (e: Exception) {
                    debugLog("Server error: ${e.javaClass.simpleName} - ${e.message}")
                    failConnection(generation, WifiDirectError.TCP_SERVER_FAILED)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // TCP Client (Wi-Fi Direct Client Node)
    // -------------------------------------------------------------------------

    suspend fun connectToHost(hostAddress: InetAddress, port: Int = DEFAULT_PORT) {
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = false
            _lastError.value = WifiDirectError.NONE
            val generation = connectionGeneration.get()
            stateFlow.value = ConnectionState.CONNECTING

            connectionJob = scope.launch(Dispatchers.IO) {
                debugLog("TCP Client connecting to ${hostAddress.hostAddress?.take(8)}***:$port")

                // Retry up to 3 times with 1.5s backoff: the Group Owner's ServerSocket may not be
                // bound yet when WIFI_P2P_CONNECTION_CHANGED fires on the client side.
                val maxAttempts = 3
                val retryDelayMs = 1_500L
                var lastException: Exception? = null

                for (attempt in 1..maxAttempts) {
                    if (!isActive) break
                    if (attempt > 1) {
                        debugLog("TCP Client retry attempt $attempt/$maxAttempts after ${retryDelayMs}ms delay")
                        delay(retryDelayMs)
                    }
                    var pendingSocket: Socket? = null
                    try {
                        val socket = Socket()
                        pendingSocket = socket
                        synchronized(this@WifiDirectPeerTransport) {
                            if (connectionGeneration.get() != generation) {
                                socket.close()
                                return@launch
                            }
                            activeSocket = socket
                        }
                        socket.tcpNoDelay = true
                        socket.keepAlive = true
                        socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                        debugLog("TCP Client connected successfully to group owner (attempt $attempt)")
                        pendingSocket = null
                        manageConnectedSocket(socket, generation)
                        return@launch  // Success — exit retry loop
                    } catch (e: CancellationException) {
                        try { pendingSocket?.close() } catch (_: Exception) {}
                        debugLog("Client connect job cancelled")
                        throw e
                    } catch (e: SocketTimeoutException) {
                        try { pendingSocket?.close() } catch (_: Exception) {}
                        debugLog("TCP connect timeout on attempt $attempt")
                        lastException = e
                        if (attempt == maxAttempts) {
                            failConnection(generation, WifiDirectError.TCP_CONNECT_TIMEOUT)
                        }
                    } catch (e: Exception) {
                        try { pendingSocket?.close() } catch (_: Exception) {}
                        debugLog("TCP Client connect error attempt $attempt: ${e.javaClass.simpleName} - ${e.message}")
                        lastException = e
                        if (attempt == maxAttempts) {
                            failConnection(generation, WifiDirectError.TCP_CONNECT_FAILED)
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connected Socket Management
    // -------------------------------------------------------------------------

    @Synchronized
    private fun manageConnectedSocket(socket: Socket, generation: Long) {
        if (connectionGeneration.get() != generation) {
            socket.close()
            return
        }
        socket.tcpNoDelay = true
        socket.keepAlive = true
        activeSocket = socket
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        _connectedHostAddress.value = socket.inetAddress?.hostAddress

        // Close serverSocket once connected to stop accepting further clients
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        stateFlow.value = ConnectionState.CONNECTED
        debugLog("TCP transport CONNECTED: role=${if (_isServer) "SERVER" else "CLIENT"}")
        startReaderLoop(socket, generation)
    }

    // -------------------------------------------------------------------------
    // TCP Reader Loop
    // -------------------------------------------------------------------------

    private fun startReaderLoop(socket: Socket, generation: Long) {
        readJob?.cancel()
        val inStream = inputStream ?: return
        readJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    // 1. Read 4-byte big-endian frame length prefix
                    val lengthBuffer = ByteArray(4)
                    var bytesRead = 0
                    while (bytesRead < 4) {
                        val read = inStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                        if (read == -1) throw IOException("Remote peer closed TCP socket (EOF)")
                        bytesRead += read
                    }
                    val frameLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).getInt()

                    // 2. Validate frame length constraint
                    if (frameLength <= 0 || frameLength > PacketDecoder.MAX_FRAME_BODY_SIZE) {
                        throw IOException("Invalid frame length: $frameLength (max=${PacketDecoder.MAX_FRAME_BODY_SIZE})")
                    }

                    // 3. Read exact frame body
                    val frameData = ByteArray(frameLength)
                    bytesRead = 0
                    while (bytesRead < frameLength) {
                        val read = inStream.read(frameData, bytesRead, frameLength - bytesRead)
                        if (read == -1) throw IOException("Remote peer closed TCP socket mid-frame (EOF)")
                        bytesRead += read
                    }

                    // 4. Emit framed payload upward
                    if (connectionGeneration.get() != generation) return@launch
                    synchronized(this@WifiDirectPeerTransport) {
                        if (connectionGeneration.get() != generation) return@launch
                        if (!incomingFlow.tryEmit(frameData)) throw IOException("Receive queue full")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                synchronized(this@WifiDirectPeerTransport) {
                    if (connectionGeneration.get() != generation || activeSocket !== socket) return@launch
                    debugLog("Reader loop terminated: ${e.javaClass.simpleName}: ${e.message}")
                    failConnection(generation, WifiDirectError.SOCKET_CLOSED)
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send
    // -------------------------------------------------------------------------

    override suspend fun send(bytes: ByteArray) = send(bytes, connectionId)

    override suspend fun send(bytes: ByteArray, expectedConnectionId: Long) {
        // Throw on disconnect so callers cannot falsely mark a message as SENT
        if (!isConnected) throw IOException("Cannot send: Wi-Fi Direct transport is not connected")
        val generation = expectedConnectionId

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val timeout = scope.launch {
                    delay(CONNECT_TIMEOUT_MS.toLong())
                    failConnection(generation, WifiDirectError.SEND_FAILED)
                }
                try {
                    val out = synchronized(this@WifiDirectPeerTransport) {
                        if (connectionGeneration.get() != generation || !isConnected) {
                            throw IOException("Cannot send: Wi-Fi Direct connection changed")
                        }
                        outputStream ?: throw IOException("Cannot send: output stream is null")
                    }
                    out.write(bytes)
                    out.flush()
                    if (connectionGeneration.get() != generation) throw IOException("Connection ended during write")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    debugLog("send() IOException: ${e.message}")
                    failConnection(generation, WifiDirectError.SEND_FAILED)
                    throw e
                } finally {
                    timeout.cancel()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect
    // -------------------------------------------------------------------------

    override suspend fun disconnect() {
        connectionMutex.withLock {
            debugLog("disconnect() invoked manually")
            disconnectInternal()
        }
    }

    @Synchronized
    private fun failConnection(generation: Long, error: WifiDirectError) {
        if (connectionGeneration.get() != generation) return
        _lastError.value = error
        disconnectInternal()
        stateFlow.value = ConnectionState.ERROR
    }

    @Synchronized
    private fun disconnectInternal() {
        connectionGeneration.incrementAndGet()
        stateFlow.value = ConnectionState.DISCONNECTED
        _connectedHostAddress.value = null
        connectionJob?.cancel()
        connectionJob = null
        readJob?.cancel()
        readJob = null

        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}

        // FIX 019: Reset replay cache on terminal disconnect so old frames are not replayed on reconnect
        incomingFlow.resetReplayCache()

        inputStream = null
        outputStream = null
        activeSocket = null
        serverSocket = null
        _isServer = false
    }
}
