package com.itantra.core.transport.peer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.itantra.core.transport.ConnectionState
import com.itantra.core.transport.packet.PacketDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Implementation of [PeerTransport] over Bluetooth Classic RFCOMM.
 * Handles length-prefix framing and socket lifecycle.
 */
@SuppressLint("MissingPermission")
class BluetoothPeerTransport(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) : PeerTransport {

    companion object {
        // Shared stable UUID for iTantra transceivers across all devices
        val ITANTRA_UUID: UUID = UUID.fromString("20f01a35-26a1-432a-bc95-021b36d0130a")
        const val NAME = "iTantraTransceiver"
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    private var readJob: Job? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val stateFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val incomingFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress

    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()

    private var _isServer = false
    override val isServer: Boolean get() = _isServer

    override val isConnected: Boolean
        get() = stateFlow.value == ConnectionState.CONNECTED

    override fun observeConnectionState(): Flow<ConnectionState> = stateFlow

    suspend fun startServer() {
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = true
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                stateFlow.value = ConnectionState.ERROR
                return
            }

            connectionJob = scope.launch {
                stateFlow.value = ConnectionState.LISTENING
                android.util.Log.i("BluetoothPeerTransport", "Server listening for RFCOMM on UUID: $ITANTRA_UUID")
                try {
                    serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME, ITANTRA_UUID)
                    val socket = withContext(Dispatchers.IO) {
                        serverSocket?.accept() // Blocking call until client connects or socket closed
                    }
                    if (socket != null) {
                        android.util.Log.i("BluetoothPeerTransport", "Accepted RFCOMM connection from ${socket.remoteDevice?.address}")
                        manageConnectedSocket(socket)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        android.util.Log.w("BluetoothPeerTransport", "Server accept error: ${e.message}")
                        if (stateFlow.value != ConnectionState.CONNECTED) {
                            stateFlow.value = ConnectionState.DISCONNECTED
                        }
                    }
                }
            }
        }
    }

    suspend fun connectToDevice(device: BluetoothDevice) {
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = false
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                stateFlow.value = ConnectionState.ERROR
                return
            }

            connectionJob = scope.launch {
                stateFlow.value = ConnectionState.CONNECTING
                _connectedDeviceAddress.value = device.address
                android.util.Log.i("BluetoothPeerTransport", "Initiating RFCOMM connection to ${device.address} (${device.name})")

                // 1. cancelDiscovery() before connecting - critical for modern Android bandwidth & stability
                try {
                    if (bluetoothAdapter.isDiscovering) {
                        bluetoothAdapter.cancelDiscovery()
                        android.util.Log.i("BluetoothPeerTransport", "Cancelled active Bluetooth discovery before connecting")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BluetoothPeerTransport", "Error cancelling discovery: ${e.message}")
                }

                var connectedSocket: BluetoothSocket? = null

                // 2. Standard secure RFCOMM with controlled timeout.
                // Insecure fallback removed: server only listens on secure RFCOMM, so an
                // insecure client socket will always be rejected by the server side.
                // Using one auth path on both sides avoids a silent protocol mismatch.
                try {
                    connectedSocket = withTimeout(CONNECT_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) {
                            val socket = device.createRfcommSocketToServiceRecord(ITANTRA_UUID)
                            socket.connect()
                            socket
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("BluetoothPeerTransport", "Secure RFCOMM failed: ${e.message}")
                    try { connectedSocket?.close() } catch (ignored: Exception) {}
                    connectedSocket = null
                }

                if (connectedSocket != null && isActive) {
                    android.util.Log.i("BluetoothPeerTransport", "RFCOMM connected to ${device.address}")
                    manageConnectedSocket(connectedSocket)
                } else {
                    android.util.Log.e("BluetoothPeerTransport", "Failed to connect RFCOMM to ${device.address}")
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                }
            }
        }
    }

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        activeSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        _connectedDeviceAddress.value = socket.remoteDevice?.address
        stateFlow.value = ConnectionState.CONNECTED

        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null

        startReaderLoop()
    }

    private fun startReaderLoop() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val inStream = inputStream ?: return@launch
                while (isActive) {
                    // 1. Read 4-byte frame length
                    val lengthBuffer = ByteArray(4)
                    var bytesRead = 0
                    while (bytesRead < 4) {
                        val read = inStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                        if (read == -1) throw IOException("Remote closed RFCOMM socket")
                        bytesRead += read
                    }
                    val frameLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).getInt()

                    if (frameLength <= 0 || frameLength > PacketDecoder.MAX_FRAME_BODY_SIZE) {
                        throw IOException("Invalid frame length received: $frameLength")
                    }

                    // 2. Read frame body
                    val frameData = ByteArray(frameLength)
                    bytesRead = 0
                    while (bytesRead < frameLength) {
                        val read = inStream.read(frameData, bytesRead, frameLength - bytesRead)
                        if (read == -1) throw IOException("Remote closed RFCOMM socket mid-frame")
                        bytesRead += read
                    }

                    // 3. Emit deframed raw packet data upward
                    incomingFlow.emit(frameData)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    android.util.Log.w("BluetoothPeerTransport", "Reader loop terminated: ${e.message}")
                    if (stateFlow.value == ConnectionState.CONNECTED) {
                        stateFlow.value = ConnectionState.DISCONNECTED
                    }
                }
                disconnectInternal()
            }
        }
    }

    override suspend fun send(bytes: ByteArray) {
        // Throw on disconnect so callers cannot falsely mark a message as SENT.
        if (!isConnected) throw IOException("Cannot send: transport is not connected")

        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                try {
                    val out = outputStream ?: run {
                        stateFlow.value = ConnectionState.ERROR
                        disconnectInternal()
                        throw IOException("Cannot send: output stream is null")
                    }
                    out.write(bytes)
                    out.flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    android.util.Log.e("BluetoothPeerTransport", "Send failed: ${e.message}")
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                    throw e // Propagate so callers set message state to ERROR, not SENT
                }
            }
        }
    }

    override suspend fun disconnect() {
        connectionMutex.withLock {
            disconnectInternal()
        }
    }

    private fun disconnectInternal() {
        stateFlow.value = ConnectionState.DISCONNECTED
        _connectedDeviceAddress.value = null
        connectionJob?.cancel()
        connectionJob = null
        readJob?.cancel()
        readJob = null
        try { inputStream?.close() } catch (e: Exception) {}
        try { outputStream?.close() } catch (e: Exception) {}
        try { activeSocket?.close() } catch (e: Exception) {}
        try { serverSocket?.close() } catch (e: Exception) {}
        inputStream = null
        outputStream = null
        activeSocket = null
        serverSocket = null
    }

    override fun receive(): Flow<ByteArray> = incomingFlow
}
