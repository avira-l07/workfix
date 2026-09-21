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

    private val stateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    // FIX 019: replay = 16 so initial SECURE_HELLO is never dropped before TransportCoordinator subscribes
    private val incomingFlow = MutableSharedFlow<ByteArray>(replay = 16, extraBufferCapacity = 64)

    private val _lastError = MutableStateFlow(WifiDirectError.NONE)
    val lastError: StateFlow<WifiDirectError> = _lastError.asStateFlow()

    private val _connectedHostAddress = MutableStateFlow<String?>(null)
    val connectedHostAddress: StateFlow<String?> = _connectedHostAddress.asStateFlow()

    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()

    private var _isServer = false
    override val isServer: Boolean get() = _isServer

    override val isConnected: Boolean
        get() = stateFlow.value == ConnectionState.CONNECTED

    override fun observeConnectionState(): Flow<ConnectionState> = stateFlow

    override fun receive(): Flow<ByteArray> = incomingFlow

    // -------------------------------------------------------------------------
    // TCP Server (Wi-Fi Direct Group Owner)
    // -------------------------------------------------------------------------

    suspend fun startServer(port: Int = DEFAULT_PORT) {
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = true
            _lastError.value = WifiDirectError.NONE

            connectionJob = scope.launch(Dispatchers.IO) {
                stateFlow.value = ConnectionState.LISTENING
                debugLog("TCP Server starting on port $port (Group Owner role)")
                try {
                    val srv = ServerSocket()
                    srv.reuseAddress = true
                    srv.bind(InetSocketAddress(port))
                    serverSocket = srv
                    debugLog("TCP Server bound and listening on port $port")

                    val socket = srv.accept()
                    debugLog("TCP Server accepted socket from ${socket.inetAddress?.hostAddress?.take(8)}***")
                    manageConnectedSocket(socket)
                } catch (e: CancellationException) {
                    debugLog("Server accept job cancelled")
                    throw e
                } catch (e: Exception) {
                    debugLog("Server error: ${e.javaClass.simpleName} - ${e.message}")
                    _lastError.value = WifiDirectError.TCP_SERVER_FAILED
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
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

            connectionJob = scope.launch(Dispatchers.IO) {
                stateFlow.value = ConnectionState.CONNECTING
                debugLog("TCP Client connecting to ${hostAddress.hostAddress?.take(8)}***:$port")
                // FIX 018: Keep pendingSocket outside try to guarantee close on timeout, error, or cancellation
                var pendingSocket: Socket? = null
                try {
                    val socket = Socket()
                    pendingSocket = socket
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(hostAddress, port), CONNECT_TIMEOUT_MS)
                    debugLog("TCP Client connected successfully to group owner")
                    pendingSocket = null
                    manageConnectedSocket(socket)
                } catch (e: CancellationException) {
                    try { pendingSocket?.close() } catch (_: Exception) {}
                    debugLog("Client connect job cancelled")
                    throw e
                } catch (e: SocketTimeoutException) {
                    try { pendingSocket?.close() } catch (_: Exception) {}
                    debugLog("TCP connect timeout to group owner")
                    _lastError.value = WifiDirectError.TCP_CONNECT_TIMEOUT
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                } catch (e: Exception) {
                    try { pendingSocket?.close() } catch (_: Exception) {}
                    debugLog("TCP Client connect error: ${e.javaClass.simpleName} - ${e.message}")
                    _lastError.value = WifiDirectError.TCP_CONNECT_FAILED
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connected Socket Management
    // -------------------------------------------------------------------------

    private fun manageConnectedSocket(socket: Socket) {
        activeSocket = socket
        inputStream = socket.getInputStream()
        outputStream = socket.getOutputStream()
        _connectedHostAddress.value = socket.inetAddress?.hostAddress

        // Close serverSocket once connected to stop accepting further clients
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        stateFlow.value = ConnectionState.CONNECTED
        debugLog("TCP transport CONNECTED: role=${if (_isServer) "SERVER" else "CLIENT"}")
        startReaderLoop()
    }

    // -------------------------------------------------------------------------
    // TCP Reader Loop
    // -------------------------------------------------------------------------

    private fun startReaderLoop() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val inStream = inputStream ?: return@launch
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
                    incomingFlow.emit(frameData)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    debugLog("Reader loop terminated: ${e.javaClass.simpleName}: ${e.message}")
                    if (stateFlow.value == ConnectionState.CONNECTED) {
                        _lastError.value = WifiDirectError.SOCKET_CLOSED
                        stateFlow.value = ConnectionState.DISCONNECTED
                    }
                }
                disconnectInternal()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Send
    // -------------------------------------------------------------------------

    override suspend fun send(bytes: ByteArray) {
        // Throw on disconnect so callers cannot falsely mark a message as SENT
        if (!isConnected) throw IOException("Cannot send: Wi-Fi Direct transport is not connected")

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val out = outputStream ?: run {
                        stateFlow.value = ConnectionState.ERROR
                        _lastError.value = WifiDirectError.SEND_FAILED
                        disconnectInternal()
                        throw IOException("Cannot send: output stream is null")
                    }
                    out.write(bytes)
                    out.flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    debugLog("send() IOException: ${e.message}")
                    _lastError.value = WifiDirectError.SEND_FAILED
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                    throw e
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

    private fun disconnectInternal() {
        stateFlow.value = ConnectionState.DISCONNECTED
        _connectedHostAddress.value = null
        connectionJob?.cancel()
        connectionJob = null
        readJob?.cancel()
        readJob = null

        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}

        // FIX 019: Reset replay cache on terminal disconnect so old frames are not replayed on reconnect
        incomingFlow.resetReplayCache()

        inputStream = null
        outputStream = null
        activeSocket = null
        serverSocket = null
        _isServer = false
    }
}
