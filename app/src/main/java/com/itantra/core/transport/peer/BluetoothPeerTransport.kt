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
    DISCOVERABILITY_DENIED,
    DISCOVERY_FAILED,
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
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
    private var timeoutJob: Job? = null
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong()

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private val stateFlow = SocketState { connectionId }
    private val incomingFlow = MutableSharedFlow<ByteArray>(replay = 16, extraBufferCapacity = 64)
    private var closeConnectedSocket: (() -> Unit)? = null

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
    override val connectionId: Long get() = connectionGeneration.get()

    override fun observeConnectionState(): Flow<ConnectionState> = stateFlow.observe()

    @Volatile private var pendingBondDevice: BluetoothDevice? = null

    /**
     * Explicit lifecycle flag indicating whether the passive RFCOMM server listener
     * should be active (e.g. while the Connect screen is active and Bluetooth transport is selected).
     */
    @Volatile
    var listenerDesired: Boolean = false

    fun setLastError(error: BluetoothError) {
        _lastError.value = error
    }

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
                val generation = connectionGeneration.get()
                if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            failConnection(connectionGeneration.get(), BluetoothError.BLUETOOTH_DISABLED)
                        }
                        BluetoothAdapter.STATE_ON -> scope.launch { ensureBluetoothListener() }
                    }
                    return
                }
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
                        val devAddr = safeGetAddress(device ?: return)
                        debugLog("BOND_STATE_CHANGED: ${bondStateName(prevBondState)} -> ${bondStateName(bondState)}" +
                            " peer=$isActivePeer addr=${devAddr?.take(8)?.plus("***")}")

                        // FIX 005: First-time bonding transition handling
                        val pending = pendingBondDevice
                        if (pending != null && safeGetAddress(pending) == devAddr) {
                            when (bondState) {
                                BluetoothDevice.BOND_BONDED -> {
                                    debugLog("Pending target bonded: $devAddr. Establishing RFCOMM connection.")
                                    val generation = connectionGeneration.get()
                                    scope.launch {
                                        connectionMutex.withLock {
                                            if (connectionGeneration.get() == generation && pendingBondDevice === pending) {
                                                pendingBondDevice = null
                                                timeoutJob?.cancel()
                                                launchClient(device, generation)
                                            }
                                        }
                                    }
                                }
                                BluetoothDevice.BOND_NONE -> {
                                    if (prevBondState == BluetoothDevice.BOND_BONDING) {
                                        debugLog("Pending target bonding failed or cancelled: $devAddr")
                                        failConnection(generation, BluetoothError.PAIRING_FAILED)
                                    }
                                }
                                BluetoothDevice.BOND_BONDING -> {
                                    debugLog("Pending target is bonding: $devAddr")
                                }
                            }
                        }

                        // Android 15: if active peer lost its bond mid-session, invalidate.
                        if (isActivePeer && isConnected &&
                            bondState == BluetoothDevice.BOND_NONE &&
                            prevBondState == BluetoothDevice.BOND_BONDED
                        ) {
                            debugLog("Active peer bond lost (BOND_NONE after BOND_BONDED) — disconnecting")
                            scope.launch { failConnection(generation, BluetoothError.BOND_LOST) }
                        }
                    }

                    ACTION_KEY_MISSING -> {
                        // Android 16: remote lost link key. BOND_BONDED may still appear
                        // locally, but the RFCOMM session is no longer authenticated.
                        if (isActivePeer) {
                            debugLog("ACTION_KEY_MISSING for active peer — bond lost, session invalid")
                            scope.launch { failConnection(generation, BluetoothError.BOND_LOST) }
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(ACTION_KEY_MISSING)
        }
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        bondReceiver = receiver
        debugLog("Bond/key-missing receiver registered (API ${Build.VERSION.SDK_INT})")
        // Pairing may complete while the Activity is being recreated and no receiver is attached.
        val pending = pendingBondDevice
        val generation = connectionGeneration.get()
        if (pending != null) scope.launch {
            connectionMutex.withLock {
                if (connectionGeneration.get() == generation && pendingBondDevice === pending && hasConnectPermission()) {
                    try {
                        @SuppressLint("MissingPermission")
                        if (pending.bondState == BluetoothDevice.BOND_BONDED) {
                            pendingBondDevice = null
                            timeoutJob?.cancel()
                            launchClient(pending, generation)
                        }
                    } catch (_: SecurityException) { failConnection(generation, BluetoothError.PERMISSION_DENIED) }
                }
            }
        }
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

    /**
     * Idempotently ensures that the RFCOMM server socket is actively listening.
     * Safe against deadlocks — acquires connectionMutex internally.
     * Does nothing if:
     * - listenerDesired is false
     * - already CONNECTED
     * - already LISTENING with active serverSocket and live connectionJob
     * - missing BLUETOOTH_CONNECT permission
     * - Bluetooth adapter disabled
     */
    suspend fun ensureBluetoothListener() {
        val adapter = bluetoothAdapter ?: return
        if (!listenerDesired) {
            debugLog("ensureBluetoothListener: listenerDesired is false, skipping")
            return
        }
        if (!hasConnectPermission()) {
            debugLog("ensureBluetoothListener: BLUETOOTH_CONNECT not granted")
            return
        }
        if (!adapterEnabled()) {
            debugLog("ensureBluetoothListener: Bluetooth adapter disabled")
            return
        }

        connectionMutex.withLock {
            if (!listenerDesired) return@withLock
            if (stateFlow.value == ConnectionState.CONNECTED || stateFlow.value == ConnectionState.CONNECTING) {
                debugLog("ensureBluetoothListener: already connected, skipping listener start")
                return@withLock
            }
            if (stateFlow.value == ConnectionState.LISTENING && connectionJob?.isActive == true) {
                debugLog("ensureBluetoothListener: already listening, idempotent skip")
                return@withLock
            }

            disconnectInternal()
            _isServer = true
            val generation = connectionGeneration.get()
            stateFlow.value = ConnectionState.LISTENING

            connectionJob = scope.launch {
                debugLog("SERVER listening on UUID: $ITANTRA_UUID")
                try {
                    @SuppressLint("MissingPermission")
                    val srv = adapter.listenUsingRfcommWithServiceRecord(NAME, ITANTRA_UUID)
                    synchronized(this@BluetoothPeerTransport) {
                        if (connectionGeneration.get() != generation || !listenerDesired) {
                            srv.close()
                            return@launch
                        }
                        serverSocket = srv
                    }
                    val socket = withContext(Dispatchers.IO) { srv.accept() }
                    if (socket != null) {
                        debugLog("SERVER accepted RFCOMM connection")
                        if (hasScanPermission()) {
                            try {
                                @SuppressLint("MissingPermission")
                                if (adapter.isDiscovering) {
                                    adapter.cancelDiscovery()
                                    debugLog("SERVER cancelled active discovery on RFCOMM accept")
                                }
                            } catch (_: Exception) {}
                        }
                        manageConnectedSocket(socket, generation)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        debugLog("SERVER accept error: ${e.javaClass.simpleName}: ${e.message}")
                        failConnection(generation, if (e is SecurityException) BluetoothError.PERMISSION_DENIED
                            else BluetoothError.RFCOMM_CONNECT_FAILED)
                    }
                }
            }
        }
    }

    suspend fun startServer() {
        listenerDesired = true
        ensureBluetoothListener()
    }

    /**
     * Explicitly stops the listening RFCOMM server socket and marks listenerDesired = false.
     */
    suspend fun stopServer() {
        listenerDesired = false
        connectionMutex.withLock {
            if (_isServer && !isConnected) {
                debugLog("stopServer: stopping RFCOMM listening server socket")
                disconnectInternal()
            }
        }
    }

    fun stopListening() {
        listenerDesired = false
        scope.launch { stopServer() }
    }

    // -------------------------------------------------------------------------
    // Client mode
    // -------------------------------------------------------------------------

    /**
     * FIX 005: Initiates connection to a Bluetooth device with explicit bonding workflow.
     * If already bonded: connects immediately.
     * If bonding: waits for BOND_BONDED broadcast.
     * If not bonded: calls createBond() and awaits BOND_BONDED before RFCOMM setup.
     */
    suspend fun connectToDevice(device: BluetoothDevice) = connectionMutex.withLock {
        // A second tap cannot replace a pending or verified link. Disconnect explicitly first.
        if (isConnected || stateFlow.value == ConnectionState.CONNECTING) return@withLock
        disconnectInternal()
        val generation = connectionGeneration.get()
        _isServer = false
        _lastError.value = BluetoothError.NONE
        if (!hasConnectPermission()) {
            failConnection(generation, BluetoothError.PERMISSION_DENIED)
            return@withLock
        }
        try {
            if (bluetoothAdapter?.isEnabled != true) {
                failConnection(generation, BluetoothError.BLUETOOTH_DISABLED)
                return@withLock
            }
            stateFlow.value = ConnectionState.CONNECTING
            @SuppressLint("MissingPermission")
            val bondState = device.bondState
            if (bondState == BluetoothDevice.BOND_BONDED) {
                launchClient(device, generation)
            } else {
                pendingBondDevice = device
                timeoutJob = scope.launch {
                    delay(60_000L)
                    failConnection(generation, BluetoothError.PAIRING_FAILED)
                }
                @SuppressLint("MissingPermission")
                val started = bondState == BluetoothDevice.BOND_BONDING || device.createBond()
                if (!started) failConnection(generation, BluetoothError.PAIRING_FAILED)
            }
        } catch (_: SecurityException) {
            failConnection(generation, BluetoothError.PERMISSION_DENIED)
        }
    }

    private fun launchClient(device: BluetoothDevice, generation: Long) {
        stateFlow.value = ConnectionState.CONNECTING
        connectionJob = scope.launch {
            var socket: BluetoothSocket? = null
            try {
                if (hasScanPermission()) {
                    @SuppressLint("MissingPermission")
                    bluetoothAdapter?.cancelDiscovery()
                }
                @SuppressLint("MissingPermission")
                val pending = device.createRfcommSocketToServiceRecord(ITANTRA_UUID)
                socket = pending
                synchronized(this@BluetoothPeerTransport) {
                    if (connectionGeneration.get() != generation) {
                        pending.close()
                        return@launch
                    }
                    activeSocket = pending
                }
                // Coroutine timeout alone cannot interrupt BluetoothSocket.connect(). Closing
                // the registered socket from another coroutine actually unblocks Android IO.
                timeoutJob = scope.launch {
                    delay(CONNECT_TIMEOUT_MS)
                    failConnection(generation, BluetoothError.CONNECT_TIMEOUT)
                }
                @SuppressLint("MissingPermission")
                pending.connect()
                synchronized(this@BluetoothPeerTransport) {
                    if (connectionGeneration.get() == generation) timeoutJob?.cancel()
                }
                manageConnectedSocket(pending, generation)
            } catch (e: CancellationException) {
                try { socket?.close() } catch (_: Exception) {}
                throw e
            } catch (e: Exception) {
                try { socket?.close() } catch (_: Exception) {}
                failConnection(generation, if (e is SecurityException) BluetoothError.PERMISSION_DENIED
                    else BluetoothError.RFCOMM_CONNECT_FAILED)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connected socket lifecycle
    // -------------------------------------------------------------------------

    @Synchronized
    private fun manageConnectedSocket(socket: BluetoothSocket, generation: Long) {
        if (connectionGeneration.get() != generation) {
            socket.close()
            return
        }
        activeSocket = socket
        attachStreams(socket.inputStream, socket.outputStream,
            safeGetAddress(socket.remoteDevice), generation) { socket.close() }
    }

    // The same stream path is used for Android RFCOMM and blocking-stream regression tests.
    @Synchronized
    internal fun attachStreams(input: InputStream, output: OutputStream, address: String?,
                               generation: Long, closeSocket: () -> Unit) {
        if (connectionGeneration.get() != generation) {
            closeSocket()
            return
        }
        inputStream = input
        outputStream = output
        closeConnectedSocket = closeSocket
        _lastError.value = BluetoothError.NONE
        _connectedDeviceAddress.value = address
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        stateFlow.value = ConnectionState.CONNECTED
        startReaderLoop(input, generation)
    }

    private fun startReaderLoop(inStream: InputStream, generation: Long) {
        readJob?.cancel()
        readJob = scope.launch {
            try {
                while (isActive) {
                    val lengthBuffer = ByteArray(4)
                    readExactly(inStream, lengthBuffer)
                    val frameLength = ByteBuffer.wrap(lengthBuffer).order(ByteOrder.BIG_ENDIAN).int
                    if (frameLength <= 0 || frameLength > PacketDecoder.MAX_FRAME_BODY_SIZE) {
                        throw IOException("Invalid frame length: $frameLength")
                    }
                    val frameData = ByteArray(frameLength)
                    readExactly(inStream, frameData)
                    synchronized(this@BluetoothPeerTransport) {
                        if (connectionGeneration.get() != generation) return@launch
                        // Bounded replay retains HELLO if the upper collector attaches late.
                        if (!incomingFlow.tryEmit(frameData)) throw IOException("Receive queue full")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failConnection(generation, BluetoothError.SOCKET_DISCONNECTED)
            }
        }
    }

    private fun readExactly(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count <= 0) throw IOException("Remote closed RFCOMM socket mid-frame")
            offset += count
        }
    }

    // -------------------------------------------------------------------------
    // Send
    // -------------------------------------------------------------------------

    override suspend fun send(bytes: ByteArray) = send(bytes, connectionId)

    override suspend fun send(bytes: ByteArray, expectedConnectionId: Long) {
        // Throw on disconnect so callers cannot falsely mark a message as SENT.
        if (!isConnected) throw IOException("Cannot send: transport is not connected")

        val generation = expectedConnectionId
        withContext(Dispatchers.IO) {
            writeMutex.withLock {
                val timeout = scope.launch {
                    delay(CONNECT_TIMEOUT_MS)
                    failConnection(generation, BluetoothError.SOCKET_DISCONNECTED)
                }
                try {
                    val out = synchronized(this@BluetoothPeerTransport) {
                        if (connectionGeneration.get() != generation || !isConnected) {
                            throw IOException("Cannot send: Bluetooth connection changed")
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
                    failConnection(generation, BluetoothError.SOCKET_DISCONNECTED)
                    throw e // Propagate so callers set message state to ERROR, not SENT
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
        connectionMutex.withLock { disconnectInternal() }
        if (listenerDesired) {
            ensureBluetoothListener()
        }
    }

    @Synchronized
    private fun failConnection(generation: Long, error: BluetoothError) {
        if (connectionGeneration.get() != generation) return
        _lastError.value = error
        disconnectInternal()
        stateFlow.value = ConnectionState.ERROR
        if (listenerDesired && error != BluetoothError.PERMISSION_DENIED && error != BluetoothError.BLUETOOTH_DISABLED) {
            val retryGeneration = connectionGeneration.get()
            scope.launch {
                delay(500)
                if (connectionGeneration.get() == retryGeneration) ensureBluetoothListener()
            }
        }
    }

    @Synchronized
    private fun disconnectInternal() {
        connectionGeneration.incrementAndGet()
        pendingBondDevice = null
        stateFlow.value = ConnectionState.DISCONNECTED
        _connectedDeviceAddress.value = null
        timeoutJob?.cancel(); timeoutJob = null
        connectionJob?.cancel(); connectionJob = null
        readJob?.cancel(); readJob = null
        // Close the socket first: unlike coroutine cancellation this aborts blocking IO.
        try { closeConnectedSocket?.invoke() } catch (_: Exception) {}
        closeConnectedSocket = null
        try { activeSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        inputStream = null; outputStream = null
        activeSocket = null; serverSocket = null
        incomingFlow.resetReplayCache()
        _isServer = false
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

    private fun adapterEnabled(): Boolean = try {
        hasConnectPermission() && bluetoothAdapter?.isEnabled == true
    } catch (_: SecurityException) { false }

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
