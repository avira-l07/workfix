package com.itantra.core.transport.peer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
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
 * Structured Bluetooth error codes exposed to the UI layer.
 * These replace silent failures and Logcat-only error information.
 */
enum class BluetoothError {
    NONE,
    BLUETOOTH_DISABLED,
    PERMISSION_DENIED,
    PAIRING_REQUIRED,
    PAIRING_FAILED,
    CONNECT_TIMEOUT,
    RFCOMM_CONNECT_FAILED,
    BOND_LOST,           // Android 16: ACTION_KEY_MISSING or bond dropped mid-session
    SOCKET_DISCONNECTED,
    HANDSHAKE_FAILED,    // Set by upper layer
}

/**
 * Implementation of [PeerTransport] over Bluetooth Classic RFCOMM.
 *
 * Android 14/15/16 compatibility:
 * - All Bluetooth operations are gated by actual runtime BLUETOOTH_CONNECT checks;
 *   @SuppressLint is only used where a prior runtime check is documented inline.
 * - ACTION_BOND_STATE_CHANGED is observed to handle BOND_NONE after pairing failures.
 * - Android 16: ACTION_KEY_MISSING is observed to handle bond-loss mid-session.
 * - Socket timeout path closes the socket before throwing, preventing resource leaks.
 * - Server/client roles are set before any IO operation and never flipped mid-session.
 */
class BluetoothPeerTransport(
    private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter?
) : PeerTransport {

    companion object {
        val ITANTRA_UUID: UUID = UUID.fromString("20f01a35-26a1-432a-bc95-021b36d0130a")
        const val NAME = "iTantraTransceiver"
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val TAG = "BtPeerTransport"

        // Android 16 API 36 constant — may not exist in older SDK compilations.
        // Defined as a string so it compiles regardless of targetSdk.
        private const val ACTION_KEY_MISSING = "android.bluetooth.device.action.KEY_MISSING"
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

    private val _lastError = MutableStateFlow(BluetoothError.NONE)
    /** Last structured Bluetooth error — observe in UI to show meaningful error messages. */
    val lastError: StateFlow<BluetoothError> = _lastError

    private val connectionMutex = Mutex()
    private val writeMutex = Mutex()

    private var _isServer = false
    override val isServer: Boolean get() = _isServer

    override val isConnected: Boolean
        get() = stateFlow.value == ConnectionState.CONNECTED

    override fun observeConnectionState(): Flow<ConnectionState> = stateFlow

    // -------------------------------------------------------------------------
    // Android 16 bond-loss receiver
    // -------------------------------------------------------------------------

    private var bondReceiver: BroadcastReceiver? = null

    /**
     * Registers a receiver for bond-state changes and Android 16 KEY_MISSING.
     * Must be called from a Context that can register receivers.
     *
     * Android 15: BOND_NONE after a previously BOND_BONDED device means the peer
     * removed or lost the link key. The session must be invalidated.
     *
     * Android 16: ACTION_KEY_MISSING fires when the remote device no longer holds
     * a matching link key. Bond info may still appear locally as BOND_BONDED, but
     * the authenticated connection is no longer usable.
     */
    fun registerBondReceiver(ctx: Context) {
        if (bondReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }

                val activeAddress = _connectedDeviceAddress.value
                val isActivePeer = device != null && activeAddress != null &&
                    safeGetAddress(device) == activeAddress

                when (action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val bondState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE
                        )
                        val prevBondState = intent.getIntExtra(
                            BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE
                        )
                        debugLog("BOND_STATE_CHANGED: ${bondStateName(prevBondState)} -> ${bondStateName(bondState)}" +
                            " peer=$isActivePeer addr=${device?.address?.take(8)?.plus("***")}")

                        // Android 15: if active peer lost its bond mid-session, invalidate.
                        if (isActivePeer && isConnected &&
                            bondState == BluetoothDevice.BOND_NONE &&
                            prevBondState == BluetoothDevice.BOND_BONDED
                        ) {
                            debugLog("Active peer bond lost (BOND_NONE after BOND_BONDED) — disconnecting")
                            _lastError.value = BluetoothError.BOND_LOST
                            scope.launch { disconnectOnError() }
                        }
                    }

                    ACTION_KEY_MISSING -> {
                        // Android 16: remote lost link key. BOND_BONDED may still appear
                        // locally, but the RFCOMM session is no longer authenticated.
                        if (isActivePeer) {
                            debugLog("ACTION_KEY_MISSING for active peer — bond lost, session invalid")
                            _lastError.value = BluetoothError.BOND_LOST
                            scope.launch { disconnectOnError() }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_KEY_MISSING)
        }
        ctx.registerReceiver(receiver, filter)
        bondReceiver = receiver
        debugLog("Bond/key-missing receiver registered (API ${Build.VERSION.SDK_INT})")
    }

    fun unregisterBondReceiver(ctx: Context) {
        bondReceiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        bondReceiver = null
    }

    // -------------------------------------------------------------------------
    // Permission helpers — Android 14/15/16 require runtime BLUETOOTH_CONNECT
    // -------------------------------------------------------------------------

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Pre-API 31: permission is install-time only
        }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    // -------------------------------------------------------------------------
    // Server mode
    // -------------------------------------------------------------------------

    suspend fun startServer() {
        if (!hasConnectPermission()) {
            debugLog("startServer: BLUETOOTH_CONNECT not granted")
            _lastError.value = BluetoothError.PERMISSION_DENIED
            stateFlow.value = ConnectionState.ERROR
            return
        }
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = true
            _lastError.value = BluetoothError.NONE

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                debugLog("startServer: Bluetooth adapter disabled")
                _lastError.value = BluetoothError.BLUETOOTH_DISABLED
                stateFlow.value = ConnectionState.ERROR
                return
            }

            connectionJob = scope.launch {
                stateFlow.value = ConnectionState.LISTENING
                debugLog("SERVER listening on UUID: $ITANTRA_UUID")
                try {
                    // BLUETOOTH_CONNECT runtime check done above; suppress lint.
                    @SuppressLint("MissingPermission")
                    val srv = bluetoothAdapter.listenUsingRfcommWithServiceRecord(NAME, ITANTRA_UUID)
                    serverSocket = srv
                    val socket = withContext(Dispatchers.IO) { srv.accept() }
                    if (socket != null) {
                        debugLog("SERVER accepted connection from ${socket.remoteDevice?.address?.take(8)}***")
                        manageConnectedSocket(socket)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        debugLog("SERVER accept error: ${e.javaClass.simpleName}: ${e.message}")
                        _lastError.value = BluetoothError.RFCOMM_CONNECT_FAILED
                        if (stateFlow.value != ConnectionState.CONNECTED) {
                            stateFlow.value = ConnectionState.DISCONNECTED
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Client mode
    // -------------------------------------------------------------------------

    suspend fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            debugLog("connectToDevice: BLUETOOTH_CONNECT not granted")
            _lastError.value = BluetoothError.PERMISSION_DENIED
            stateFlow.value = ConnectionState.ERROR
            return
        }
        connectionMutex.withLock {
            disconnectInternal()
            _isServer = false
            _lastError.value = BluetoothError.NONE

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                debugLog("connectToDevice: Bluetooth adapter disabled")
                _lastError.value = BluetoothError.BLUETOOTH_DISABLED
                stateFlow.value = ConnectionState.ERROR
                return
            }

            connectionJob = scope.launch {
                stateFlow.value = ConnectionState.CONNECTING
                val deviceAddr = safeGetAddress(device)
                debugLog("CLIENT connecting to ${deviceAddr?.take(8)}*** (API ${Build.VERSION.SDK_INT})")

                // Cancel discovery before connecting — critical for RFCOMM stability.
                // BLUETOOTH_SCAN permission check before calling cancelDiscovery.
                if (hasScanPermission()) {
                    try {
                        @SuppressLint("MissingPermission")
                        val cancelling = bluetoothAdapter.isDiscovering
                        if (cancelling) {
                            @SuppressLint("MissingPermission")
                            val cancelled = bluetoothAdapter.cancelDiscovery()
                            debugLog("CLIENT cancelled active discovery before connect: $cancelled")
                        }
                    } catch (e: SecurityException) {
                        debugLog("CLIENT cancelDiscovery SecurityException: ${e.message}")
                    }
                }

                // Android 14/15/16: BOND_BONDED alone does NOT prove authentication is valid.
                // We check it for informational logging only.
                val bondState = try {
                    @SuppressLint("MissingPermission")
                    device.bondState
                } catch (_: SecurityException) { -1 }
                debugLog("CLIENT bond state before connect: ${bondStateName(bondState)}")

                var connectedSocket: BluetoothSocket? = null

                // Secure RFCOMM only. Insecure fallback removed: server always uses
                // listenUsingRfcommWithServiceRecord (secure), so insecure clients fail.
                try {
                    // Create socket THEN connect. If connect() throws/times out,
                    // close socket here to prevent resource leak.
                    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT checked above
                    val socket = device.createRfcommSocketToServiceRecord(ITANTRA_UUID)
                    debugLog("CLIENT secure RFCOMM socket created, attempting connect...")
                    try {
                        withTimeout(CONNECT_TIMEOUT_MS) {
                            withContext(Dispatchers.IO) { socket.connect() }
                        }
                        connectedSocket = socket
                    } catch (e: Exception) {
                        // CRITICAL: close the locally created socket before giving up —
                        // otherwise it leaks even after the coroutine ends.
                        try { socket.close() } catch (_: IOException) {}
                        debugLog("CLIENT RFCOMM connect failed: ${e.javaClass.simpleName}: ${e.message}")
                        _lastError.value = if (e is TimeoutCancellationException) {
                            BluetoothError.CONNECT_TIMEOUT
                        } else {
                            BluetoothError.RFCOMM_CONNECT_FAILED
                        }
                    }
                } catch (e: SecurityException) {
                    debugLog("CLIENT createRfcommSocket SecurityException: ${e.message}")
                    _lastError.value = BluetoothError.PERMISSION_DENIED
                }

                if (connectedSocket != null && isActive) {
                    debugLog("CLIENT RFCOMM connected to ${deviceAddr?.take(8)}***")
                    manageConnectedSocket(connectedSocket)
                } else {
                    debugLog("CLIENT failed — setting ERROR state")
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connected socket lifecycle
    // -------------------------------------------------------------------------

    private fun manageConnectedSocket(socket: BluetoothSocket) {
        activeSocket = socket
        inputStream = socket.inputStream
        outputStream = socket.outputStream
        // Safe read of address — may require BLUETOOTH_CONNECT on API 31+.
        _connectedDeviceAddress.value = if (hasConnectPermission()) {
            try {
                @SuppressLint("MissingPermission")
                socket.remoteDevice?.address
            } catch (_: SecurityException) { null }
        } else null
        stateFlow.value = ConnectionState.CONNECTED

        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null

        startReaderLoop()
    }

    private fun startReaderLoop() {
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            try {
                val inStream = inputStream ?: return@launch
                while (isActive) {
                    val lengthBuffer = ByteArray(4)
                    var bytesRead = 0
                    while (bytesRead < 4) {
                        val read = inStream.read(lengthBuffer, bytesRead, 4 - bytesRead)
                        if (read == -1) throw IOException("Remote closed RFCOMM socket")
                        bytesRead += read
                    }
                    val frameLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).getInt()

                    if (frameLength <= 0 || frameLength > PacketDecoder.MAX_FRAME_BODY_SIZE) {
                        throw IOException("Invalid frame length: $frameLength")
                    }

                    val frameData = ByteArray(frameLength)
                    bytesRead = 0
                    while (bytesRead < frameLength) {
                        val read = inStream.read(frameData, bytesRead, frameLength - bytesRead)
                        if (read == -1) throw IOException("Remote closed RFCOMM socket mid-frame")
                        bytesRead += read
                    }
                    incomingFlow.emit(frameData)
                }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    debugLog("Reader loop terminated: ${e.javaClass.simpleName}: ${e.message}")
                    if (stateFlow.value == ConnectionState.CONNECTED) {
                        _lastError.value = BluetoothError.SOCKET_DISCONNECTED
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
                    debugLog("send() IOException: ${e.message}")
                    _lastError.value = BluetoothError.SOCKET_DISCONNECTED
                    stateFlow.value = ConnectionState.ERROR
                    disconnectInternal()
                    throw e // Propagate so callers set message state to ERROR, not SENT
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect
    // -------------------------------------------------------------------------

    override suspend fun disconnect() {
        connectionMutex.withLock { disconnectInternal() }
    }

    private suspend fun disconnectOnError() {
        connectionMutex.withLock {
            stateFlow.value = ConnectionState.ERROR
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
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        activeSocket = null
        serverSocket = null
    }

    override fun receive(): Flow<ByteArray> = incomingFlow

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun safeGetAddress(device: BluetoothDevice): String? {
        return if (hasConnectPermission()) {
            try {
                @SuppressLint("MissingPermission")
                device.address
            } catch (_: SecurityException) { null }
        } else null
    }

    private fun bondStateName(state: Int) = when (state) {
        BluetoothDevice.BOND_NONE -> "BOND_NONE"
        BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
        BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
        else -> "UNKNOWN($state)"
    }

    /** DEBUG-only structured log. Never logs message content or key material. */
    private fun debugLog(message: String) {
        if (com.example.itantra.BuildConfig.DEBUG) {
            android.util.Log.d(TAG, "[API${Build.VERSION.SDK_INT}][${if (_isServer) "SERVER" else "CLIENT"}] $message")
        }
    }
}
