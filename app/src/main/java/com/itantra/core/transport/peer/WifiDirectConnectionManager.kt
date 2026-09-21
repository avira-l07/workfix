package com.itantra.core.transport.peer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.itantra.core.transport.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * High-level Wi-Fi Direct connection states for UI representation.
 */
enum class WifiDirectState {
    OFF,
    PERMISSION_REQUIRED,
    DISCOVERING,
    AVAILABLE,
    CONNECTING,
    GROUP_FORMED,
    TCP_CONNECTING,
    CONNECTED,
    ERROR
}

/**
 * Structured Wi-Fi Direct error codes exposed to UI and logging.
 */
enum class WifiDirectError(val userMessage: String) {
    NONE(""),
    PERMISSION_DENIED("Nearby Wi-Fi permission required for Wi-Fi Direct"),
    P2P_NOT_SUPPORTED("Wi-Fi Direct is not supported on this device."),
    P2P_DISABLED("Wi-Fi Direct is unavailable. Enable Wi-Fi."),
    DISCOVERY_FAILED("Failed to start peer discovery. Ensure Wi-Fi is enabled."),
    NO_PEERS_FOUND("No Wi-Fi Direct peers found nearby."),
    CONNECT_REQUEST_FAILED("Failed to initiate connection to peer."),
    GROUP_FORMATION_FAILED("Wi-Fi Direct group formation failed."),
    GROUP_OWNER_ADDRESS_MISSING("Group owner address is missing."),
    TCP_SERVER_FAILED("Failed to start TCP server on port 8988."),
    TCP_CONNECT_TIMEOUT("Connection timed out reaching group owner TCP server."),
    TCP_CONNECT_FAILED("TCP connection to peer failed."),
    SOCKET_CLOSED("Wi-Fi Direct socket disconnected."),
    SEND_FAILED("Failed to transmit data frame over Wi-Fi Direct."),
    RECEIVE_FAILED("Failed to receive data from peer.")
}

/**
 * Manages Wi-Fi Direct peer discovery, P2P group formation, and binds to [WifiDirectPeerTransport]
 * for TCP socket communication.
 */
class WifiDirectConnectionManager(
    private val context: Context,
    val peerTransport: WifiDirectPeerTransport,
    private val onTcpConnected: () -> Unit = {},
    private val onTcpDisconnected: () -> Unit = {}
) {

    companion object {
        private const val TAG = "WifiDirectConnMgr"

        private fun debugLog(msg: String) {
            // Guarded debug logging — never log message plaintext, keys, or SAS material
            Log.d(TAG, msg)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private var channel: WifiP2pManager.Channel? = null

    private val _state = MutableStateFlow(WifiDirectState.OFF)
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    private val _peers = MutableStateFlow<List<WifiDirectPeer>>(emptyList())
    val peers: StateFlow<List<WifiDirectPeer>> = _peers.asStateFlow()

    private val _lastError = MutableStateFlow(WifiDirectError.NONE)
    val lastError: StateFlow<WifiDirectError> = _lastError.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _thisDevice = MutableStateFlow<WifiDirectPeer?>(null)
    val thisDevice: StateFlow<WifiDirectPeer?> = _thisDevice.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    init {
        val supported = isWifiDirectSupported()
        debugLog("API Level: ${Build.VERSION.SDK_INT}, Wi-Fi Direct supported: $supported")

        if (!supported) {
            _state.value = WifiDirectState.ERROR
            _lastError.value = WifiDirectError.P2P_NOT_SUPPORTED
        } else {
            channel = wifiP2pManager?.initialize(context, Looper.getMainLooper()) {
                debugLog("Wi-Fi P2P Channel disconnected")
                _state.value = WifiDirectState.ERROR
                _lastError.value = WifiDirectError.P2P_DISABLED
            }
            _state.value = WifiDirectState.AVAILABLE
        }

        // Observe lower-level transport connection state
        scope.launch {
            peerTransport.observeConnectionState().collect { transportState ->
                when (transportState) {
                    ConnectionState.CONNECTED -> {
                        _state.value = WifiDirectState.CONNECTED
                        debugLog("TCP socket CONNECTED — notifying onTcpConnected callback")
                        onTcpConnected()
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (_state.value == WifiDirectState.CONNECTED || _state.value == WifiDirectState.TCP_CONNECTING) {
                            debugLog("TCP socket DISCONNECTED — resetting state")
                            _state.value = WifiDirectState.AVAILABLE
                            _connectionInfo.value = null
                            onTcpDisconnected()
                        }
                    }
                    ConnectionState.ERROR -> {
                        _state.value = WifiDirectState.ERROR
                        _lastError.value = peerTransport.lastError.value
                        debugLog("TCP socket ERROR: ${_lastError.value}")
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Checks whether the device hardware supports Wi-Fi Direct.
     */
    fun isWifiDirectSupported(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
    }

    /**
     * Checks whether runtime permissions are granted for Wi-Fi Direct discovery.
     */
    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    // -------------------------------------------------------------------------
    // BroadcastReceiver Management
    // -------------------------------------------------------------------------

    fun registerReceiver(ctx: Context) {
        if (isReceiverRegistered) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val p2pState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        val isP2pEnabled = p2pState == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        debugLog("WIFI_P2P_STATE_CHANGED_ACTION: isEnabled=$isP2pEnabled")
                        if (!isP2pEnabled) {
                            _state.value = WifiDirectState.OFF
                            _lastError.value = WifiDirectError.P2P_DISABLED
                            _peers.value = emptyList()
                        } else if (_state.value == WifiDirectState.OFF || _lastError.value == WifiDirectError.P2P_DISABLED) {
                            _state.value = WifiDirectState.AVAILABLE
                            _lastError.value = WifiDirectError.NONE
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        debugLog("WIFI_P2P_PEERS_CHANGED_ACTION received — requesting peer list")
                        requestPeersInternal()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo: NetworkInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        val p2pInfo: WifiP2pInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        }

                        val isConnected = networkInfo?.isConnected == true || p2pInfo?.groupFormed == true
                        debugLog("WIFI_P2P_CONNECTION_CHANGED_ACTION: isConnected=$isConnected")

                        if (isConnected) {
                            requestConnectionInfoInternal()
                        } else {
                            if (_state.value == WifiDirectState.CONNECTED ||
                                _state.value == WifiDirectState.TCP_CONNECTING ||
                                _state.value == WifiDirectState.GROUP_FORMED
                            ) {
                                debugLog("Wi-Fi P2P link disconnected — cleaning up transport")
                                scope.launch(Dispatchers.IO) {
                                    peerTransport.disconnect()
                                }
                                _state.value = WifiDirectState.AVAILABLE
                                _connectionInfo.value = null
                            }
                        }
                    }

                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        if (device != null) {
                            val peer = WifiDirectPeer.fromWifiP2pDevice(device)
                            _thisDevice.value = peer
                            debugLog("THIS_DEVICE_CHANGED: name=${peer.deviceName}, addr=${peer.maskedAddress}")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        ctx.registerReceiver(r, filter)
        receiver = r
        isReceiverRegistered = true
        debugLog("Wi-Fi Direct BroadcastReceiver registered")
    }

    fun unregisterReceiver(ctx: Context) {
        if (!isReceiverRegistered) return
        receiver?.let {
            try { ctx.unregisterReceiver(it) } catch (_: Exception) {}
        }
        receiver = null
        isReceiverRegistered = false
        debugLog("Wi-Fi Direct BroadcastReceiver unregistered")
    }

    // -------------------------------------------------------------------------
    // Peer Discovery
    // -------------------------------------------------------------------------

    fun discoverPeers() {
        if (!isWifiDirectSupported()) {
            _state.value = WifiDirectState.ERROR
            _lastError.value = WifiDirectError.P2P_NOT_SUPPORTED
            return
        }

        if (!hasPermission()) {
            _state.value = WifiDirectState.PERMISSION_REQUIRED
            _lastError.value = WifiDirectError.PERMISSION_DENIED
            return
        }

        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            _state.value = WifiDirectState.ERROR
            _lastError.value = WifiDirectError.P2P_NOT_SUPPORTED
            return
        }

        _state.value = WifiDirectState.DISCOVERING
        _lastError.value = WifiDirectError.NONE
        debugLog("Calling WifiP2pManager.discoverPeers()")

        @SuppressLint("MissingPermission")
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                debugLog("discoverPeers: initiation succeeded")
            }

            override fun onFailure(reasonCode: Int) {
                debugLog("discoverPeers: initiation failed with reason=$reasonCode")
                _state.value = WifiDirectState.ERROR
                _lastError.value = WifiDirectError.DISCOVERY_FAILED
            }
        })
    }

    private fun requestPeersInternal() {
        if (!hasPermission()) return
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        @SuppressLint("MissingPermission")
        mgr.requestPeers(ch) { peerList ->
            val list = peerList?.deviceList?.map { WifiDirectPeer.fromWifiP2pDevice(it) } ?: emptyList()
            val distinct = list.distinctBy { it.deviceAddress }
            _peers.value = distinct
            debugLog("Peers updated: count=${distinct.size}")

            if (_state.value == WifiDirectState.DISCOVERING) {
                if (distinct.isNotEmpty()) {
                    _state.value = WifiDirectState.AVAILABLE
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Connect to Peer
    // -------------------------------------------------------------------------

    fun connect(peer: WifiDirectPeer) {
        if (!hasPermission()) {
            _state.value = WifiDirectState.PERMISSION_REQUIRED
            _lastError.value = WifiDirectError.PERMISSION_DENIED
            return
        }

        val mgr = wifiP2pManager
        val ch = channel
        if (mgr == null || ch == null) {
            _state.value = WifiDirectState.ERROR
            _lastError.value = WifiDirectError.P2P_NOT_SUPPORTED
            return
        }

        _state.value = WifiDirectState.CONNECTING
        _lastError.value = WifiDirectError.NONE
        debugLog("Connecting to peer: ${peer.maskedAddress} (${peer.deviceName})")

        val config = WifiP2pConfig().apply {
            deviceAddress = peer.deviceAddress
        }

        @SuppressLint("MissingPermission")
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                debugLog("WifiP2pManager.connect() negotiation started")
                // Note: Connection is established only when connection info reports groupFormed == true
            }

            override fun onFailure(reasonCode: Int) {
                debugLog("WifiP2pManager.connect() failed: reason=$reasonCode")
                _state.value = WifiDirectState.ERROR
                _lastError.value = WifiDirectError.CONNECT_REQUEST_FAILED
            }
        })
    }

    // -------------------------------------------------------------------------
    // Connection Info Handling & TCP Launch
    // -------------------------------------------------------------------------

    private fun requestConnectionInfoInternal() {
        val mgr = wifiP2pManager ?: return
        val ch = channel ?: return

        mgr.requestConnectionInfo(ch) { info ->
            if (info != null) {
                handleConnectionInfo(info)
            } else {
                debugLog("requestConnectionInfo returned null")
            }
        }
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        _connectionInfo.value = info
        debugLog("Connection info: groupFormed=${info.groupFormed}, isGroupOwner=${info.isGroupOwner}, groupOwnerAddr=${info.groupOwnerAddress?.hostAddress?.take(8)}***")

        if (!info.groupFormed) {
            debugLog("Group not formed yet")
            return
        }

        _state.value = WifiDirectState.GROUP_FORMED
        _state.value = WifiDirectState.TCP_CONNECTING

        scope.launch(Dispatchers.IO) {
            if (info.isGroupOwner) {
                debugLog("This device is Group Owner — starting TCP Server on port ${WifiDirectPeerTransport.DEFAULT_PORT}")
                peerTransport.startServer(WifiDirectPeerTransport.DEFAULT_PORT)
            } else {
                val groupOwnerAddress = info.groupOwnerAddress
                if (groupOwnerAddress == null) {
                    debugLog("Group owner address missing!")
                    _state.value = WifiDirectState.ERROR
                    _lastError.value = WifiDirectError.GROUP_OWNER_ADDRESS_MISSING
                    return@launch
                }
                debugLog("This device is Client — connecting to group owner at ${groupOwnerAddress.hostAddress?.take(8)}***:${WifiDirectPeerTransport.DEFAULT_PORT}")
                peerTransport.connectToHost(groupOwnerAddress, WifiDirectPeerTransport.DEFAULT_PORT)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Disconnect & Cleanup
    // -------------------------------------------------------------------------

    fun disconnect() {
        debugLog("Manual disconnect called")
        val mgr = wifiP2pManager
        val ch = channel

        if (mgr != null && ch != null) {
            // Cancel pending connection if in progress
            if (_state.value == WifiDirectState.CONNECTING) {
                mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { debugLog("cancelConnect success") }
                    override fun onFailure(reason: Int) { debugLog("cancelConnect failure: $reason") }
                })
            }

            // Remove current group to avoid leaving a stale P2P group
            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { debugLog("removeGroup success") }
                override fun onFailure(reason: Int) { debugLog("removeGroup failure: $reason") }
            })
        }

        scope.launch(Dispatchers.IO) {
            peerTransport.disconnect()
        }

        _state.value = WifiDirectState.AVAILABLE
        _connectionInfo.value = null
        _peers.value = emptyList()
    }
}
