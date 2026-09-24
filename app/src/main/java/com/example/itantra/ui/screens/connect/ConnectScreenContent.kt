package com.example.itantra.ui.screens.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.ui.theme.ITantraColors

/**
 * Peer device data model representing a discovered Nearby / Bluetooth / Wi-Fi peer.
 */
data class PeerDevice(
    val id: String,
    val name: String,
    val role: String,          // e.g. "Field Lead", "Scout", "Medic"
    val transport: String,     // "Bluetooth RFCOMM" - the only transport in this build
    val signalDbm: Int? = null,
    val batteryPercent: Int? = null,
    val state: PeerConnectionState,
)

enum class PeerConnectionState {
    AVAILABLE,
    LISTENING,
    CONNECTING,
    SECURE_HANDSHAKE,
    VERIFY_SAS,
    SECURE_CONNECTED,
    DISCONNECTED,
    ERROR
}

enum class TransportMode { BLUETOOTH, WIFI_DIRECT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreenContent(
    channelName: String,
    peersInRange: Int,
    devices: List<PeerDevice>,
    isScanning: Boolean,
    sasCode: String? = null,
    connectionError: String? = null,
    myDeviceId: String = "IT-????-????",
    myDisplayName: String = "This Device",
    selectedTransportMode: TransportMode = TransportMode.BLUETOOTH,
    wifiDirectPeers: List<com.itantra.core.transport.peer.WifiDirectPeer> = emptyList(),
    wifiDirectState: com.itantra.core.transport.peer.WifiDirectState = com.itantra.core.transport.peer.WifiDirectState.OFF,
    wifiDirectError: String? = null,
    wifiDirectInfo: android.net.wifi.p2p.WifiP2pInfo? = null,
    sasRemainingSeconds: Int? = null,
    secureSessionState: com.itantra.core.crypto.SecureSessionState = com.itantra.core.crypto.SecureSessionState.NO_SESSION,
    connectedDeviceAddress: String? = null,
    onBack: () -> Unit,
    onBroadcastPing: () -> Unit,
    onConnect: (PeerDevice) -> Unit,
    onSasConfirmed: (PeerDevice) -> Unit = {},
    onSasRejected: () -> Unit = {},
    onOpenChat: (PeerDevice) -> Unit = {},
    onDiscoverWifiDirectPeers: () -> Unit = {},
    onConnectWifiDirect: (com.itantra.core.transport.peer.WifiDirectPeer) -> Unit = {},
    onDisconnectWifiDirect: () -> Unit = {},
    onOpenChatWifiDirect: (com.itantra.core.transport.peer.WifiDirectPeer) -> Unit = {},
    onTransportModeChanged: (TransportMode) -> Unit = {},
) {
    var currentMode by remember { mutableStateOf(selectedTransportMode) }
    var isLocallyConfirmed by remember { mutableStateOf(false) }

    LaunchedEffect(sasCode, secureSessionState) {
        if (sasCode.isNullOrBlank() || secureSessionState != com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION) {
            isLocallyConfirmed = false
        }
    }

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = {
            TopAppBar(
                title = { Text("Connect") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to hub")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ITantraColors.SurfaceWhite),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Mode selector: BLUETOOTH vs WI-FI DIRECT
            PrimaryTabRow(
                selectedTabIndex = currentMode.ordinal,
                containerColor = ITantraColors.SurfaceWhite,
                contentColor = ITantraColors.Primary
            ) {
                Tab(
                    selected = currentMode == TransportMode.BLUETOOTH,
                    onClick = {
                        currentMode = TransportMode.BLUETOOTH
                        onTransportModeChanged(TransportMode.BLUETOOTH)
                    },
                    text = { Text("BLUETOOTH", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                )
                Tab(
                    selected = currentMode == TransportMode.WIFI_DIRECT,
                    onClick = {
                        currentMode = TransportMode.WIFI_DIRECT
                        onTransportModeChanged(TransportMode.WIFI_DIRECT)
                    },
                    text = { Text("WI-FI DIRECT", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                )
            }

            if (currentMode == TransportMode.BLUETOOTH) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { MyProfileCard(myDeviceId = myDeviceId, myDisplayName = myDisplayName) }
                    item { ActiveChannelCard(channelName, isWifi = false) }
                    if (!connectionError.isNullOrBlank()) {
                        item { ConnectionErrorCard(error = connectionError) }
                    }
                    item { ScanningCard(isScanning = isScanning, peersInRange = peersInRange) }
                    item {
                        Text(
                            "DISCOVERED BLUETOOTH DEVICES",
                            style = MaterialTheme.typography.titleSmall,
                            color = ITantraColors.TextHeadline,
                        )
                    }
                    if (devices.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                                    .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.Sensors,
                                        contentDescription = null,
                                        tint = ITantraColors.TextMuted,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "No peer devices discovered",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ITantraColors.TextMuted
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Tap 'Broadcast Discovery Ping' to search nearby radios",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ITantraColors.TextMuted
                                    )
                                }
                            }
                        }
                    } else {
                        items(devices, key = { it.id }) { device ->
                            PeerDeviceCard(
                                device = device,
                                onConnect = { onConnect(device) },
                                onOpenChat = { onOpenChat(device) }
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = onBroadcastPing,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                        ) {
                            Icon(Icons.Filled.Sensors, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("BROADCAST DISCOVERY PING")
                        }
                    }
                }
            } else {
                // WI-FI DIRECT SECTION
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { MyProfileCard(myDeviceId = myDeviceId, myDisplayName = myDisplayName) }
                    item { ActiveChannelCard(channelName, isWifi = true) }
                    if (!wifiDirectError.isNullOrBlank()) {
                        item { WifiDirectErrorCard(error = wifiDirectError) }
                    }
                    item {
                        WifiDirectStatusCard(
                            state = wifiDirectState,
                            peerCount = wifiDirectPeers.size,
                            info = wifiDirectInfo
                        )
                    }
                    item {
                        Text(
                            "WI-FI DIRECT PEERS",
                            style = MaterialTheme.typography.titleSmall,
                            color = ITantraColors.TextHeadline,
                        )
                    }
                    if (wifiDirectPeers.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                                    .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.Sensors,
                                        contentDescription = null,
                                        tint = ITantraColors.TextMuted,
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "No Wi-Fi Direct peers discovered",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = ITantraColors.TextMuted
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Tap 'Discover Wi-Fi Direct Peers' to search nearby devices",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ITantraColors.TextMuted
                                    )
                                }
                            }
                        }
                    } else {
                        items(wifiDirectPeers, key = { it.deviceAddress }) { peer ->
                            val isTcpConnected = wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTED
                            WifiDirectPeerCard(
                                peer = peer,
                                secureState = secureSessionState,
                                isTcpConnected = isTcpConnected,
                                isConnecting = wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTING ||
                                        wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.GROUP_FORMED ||
                                        wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.TCP_CONNECTING,
                                onConnect = { onConnectWifiDirect(peer) },
                                onOpenChat = { onOpenChatWifiDirect(peer) }
                            )
                        }
                    }
                    item {
                        Button(
                            onClick = onDiscoverWifiDirectPeers,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                        ) {
                            Icon(Icons.Filled.Sensors, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("DISCOVER WI-FI DIRECT PEERS")
                        }
                    }
                    if (wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTED ||
                        wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTING ||
                        wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.GROUP_FORMED ||
                        wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.TCP_CONNECTING
                    ) {
                        item {
                            OutlinedButton(
                                onClick = onDisconnectWifiDirect,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ITantraColors.OnErrorContainer)
                            ) {
                                Text("DISCONNECT WI-FI DIRECT")
                            }
                        }
                    }
                }
            }
        }
    }

    val shouldShowSasDialog = secureSessionState == com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION && !sasCode.isNullOrBlank()

    if (shouldShowSasDialog) {
        val targetName = if (currentMode == TransportMode.BLUETOOTH) {
            devices.find { it.id == connectedDeviceAddress }?.name ?: "Remote Node"
        } else {
            wifiDirectPeers.firstOrNull()?.deviceName ?: "Wi-Fi Direct Peer"
        }
        val targetPeerDevice = devices.find { it.id == connectedDeviceAddress } ?: PeerDevice(
            id = connectedDeviceAddress ?: "peer-node",
            name = targetName,
            role = "Peer Operator",
            transport = if (currentMode == TransportMode.BLUETOOTH) "Bluetooth RFCOMM" else "Wi-Fi Direct P2P",
            state = PeerConnectionState.VERIFY_SAS
        )

        SasVerificationDialog(
            deviceName = targetName,
            sasCode = sasCode ?: "GENERATING...",
            remainingSeconds = sasRemainingSeconds,
            isLocallyConfirmed = isLocallyConfirmed,
            onConfirm = {
                isLocallyConfirmed = true
                onSasConfirmed(targetPeerDevice)
            },
            onDismiss = {
                onSasRejected()
            }
        )
    }
}

@Composable
private fun MyProfileCard(myDeviceId: String, myDisplayName: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.AccentSubtle, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text("MY PROFILE", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(myDisplayName, style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
                Text(myDeviceId, style = MaterialTheme.typography.labelMedium, color = ITantraColors.Primary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ActiveChannelCard(channelName: String, isWifi: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text("ACTIVE CHANNEL", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
        Spacer(Modifier.height(4.dp))
        Text("# $channelName", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(ITantraColors.StatusSuccess, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(
                if (isWifi) "OFFLINE · WI-FI DIRECT PEER-TO-PEER" else "OFFLINE · DIRECT PEER-TO-PEER",
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.TextMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (isWifi) "TCP 8988 · NO ROUTER" else "RFCOMM · NO ROUTER",
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.TextHeadline,
            )
        }
    }
}

@Composable
private fun ScanningCard(isScanning: Boolean, peersInRange: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (isScanning) "SCANNING FOR BLUETOOTH DEVICES..." else "SCAN PAUSED",
            style = MaterialTheme.typography.labelMedium,
            color = ITantraColors.Primary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "ENCRYPTED BLUETOOTH RFCOMM ONLY",
            style = MaterialTheme.typography.labelSmall,
            color = ITantraColors.TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "$peersInRange DEVICES IN RANGE",
            style = MaterialTheme.typography.labelSmall,
            color = ITantraColors.Primary,
            modifier = Modifier
                .background(ITantraColors.AccentSubtle, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PeerDeviceCard(device: PeerDevice, onConnect: () -> Unit, onOpenChat: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall)
                Text(device.role, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
            }
            when (device.state) {
                PeerConnectionState.SECURE_CONNECTED -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("SECURE CONNECTED") },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = ITantraColors.StatusSuccess.copy(alpha = 0.12f),
                            disabledLabelColor = ITantraColors.StatusSuccess,
                        ),
                    )
                    Button(
                        onClick = onOpenChat,
                        colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("OPEN CHAT", style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
                PeerConnectionState.VERIFY_SAS -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("VERIFY SAS") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = ITantraColors.Primary.copy(alpha = 0.15f),
                        disabledLabelColor = ITantraColors.Primary
                    )
                )
                PeerConnectionState.SECURE_HANDSHAKE -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("WAITING FOR SECURE HANDSHAKE…") },
                )
                PeerConnectionState.CONNECTING -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("CONNECTING…") },
                )
                PeerConnectionState.LISTENING -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("LISTENING") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = ITantraColors.AccentSubtle,
                        disabledLabelColor = ITantraColors.Primary
                    )
                )
                PeerConnectionState.AVAILABLE -> OutlinedButton(onClick = onConnect) {
                    Text("CONNECT")
                }
                PeerConnectionState.DISCONNECTED -> OutlinedButton(onClick = onConnect) {
                    Text("CONNECT")
                }
                PeerConnectionState.ERROR -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("ERROR") },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = ITantraColors.ErrorContainer,
                            disabledLabelColor = ITantraColors.OnErrorContainer
                        )
                    )
                    OutlinedButton(onClick = onConnect) {
                        Text("RETRY")
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip(label = "SIGNAL", value = device.signalDbm?.let { "$it dBm" } ?: "Not measured")
            StatChip(label = "LINK", value = device.transport)
            StatChip(label = "POWER", value = device.batteryPercent?.let { "$it%" } ?: "Not measured")
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .background(ITantraColors.CanvasBg, RoundedCornerShape(6.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
        Text(value, style = MaterialTheme.typography.labelMedium, color = ITantraColors.TextHeadline)
    }
}

@Composable
fun SasVerificationDialog(
    deviceName: String,
    sasCode: String = "-- ---",
    remainingSeconds: Int? = null,
    isLocallyConfirmed: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val isRealSas = !sasCode.isNullOrBlank() && sasCode != "GENERATING..." && sasCode != "-- ---"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "VERIFY SECURITY CODE",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                if (remainingSeconds != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "$remainingSeconds seconds remaining",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (remainingSeconds <= 10) ITantraColors.OnErrorContainer else ITantraColors.Primary,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Compare this 6-digit Short Authentication String with the display on $deviceName:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.TextBody
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ITantraColors.AccentSubtle, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = sasCode,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = if (isRealSas) ITantraColors.Primary else ITantraColors.TextMuted,
                        letterSpacing = 4.sp
                    )
                }
                if (isLocallyConfirmed) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ITantraColors.StatusSuccess.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ITantraColors.StatusSuccess
                        )
                        Text(
                            "WAITING FOR PEER TO CONFIRM…",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = ITantraColors.StatusSuccess
                        )
                    }
                } else {
                    Text(
                        "E2EE cryptographic verification ensures no man-in-the-middle attack is active.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = isRealSas && !isLocallyConfirmed,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ITantraColors.StatusSuccess,
                    disabledContainerColor = if (isLocallyConfirmed) ITantraColors.StatusSuccess.copy(alpha = 0.4f) else ITantraColors.BorderSubtle,
                    disabledContentColor = if (isLocallyConfirmed) androidx.compose.ui.graphics.Color.White else ITantraColors.TextMuted
                )
            ) {
                Text(if (isLocallyConfirmed) "CONFIRMED LOCALLY" else "CODES MATCH · TRUST")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("REJECT / CANCEL")
            }
        }
    )
}

@Composable
fun SasVerificationDialog(
    device: PeerDevice,
    sasCode: String = "-- ---",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) = SasVerificationDialog(
    deviceName = device.name,
    sasCode = sasCode,
    onConfirm = onConfirm,
    onDismiss = onDismiss
)

@Composable
private fun ConnectionErrorCard(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.ErrorContainer, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.OnErrorContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Sensors,
                contentDescription = null,
                tint = ITantraColors.OnErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "BLUETOOTH CONNECTION ISSUE",
                    style = MaterialTheme.typography.labelSmall,
                    color = ITantraColors.OnErrorContainer
                )
                Text(
                    text = when (error) {
                        "BLUETOOTH_DISABLED" -> "Bluetooth is disabled. Please turn on Bluetooth."
                        "PERMISSION_DENIED" -> "Bluetooth permissions not granted. Grant Nearby Devices."
                        "PAIRING_REQUIRED" -> "Pairing required before connecting."
                        "PAIRING_FAILED" -> "Pairing failed. Try pairing again."
                        "CONNECT_TIMEOUT" -> "Connection timed out. Move closer and retry."
                        "RFCOMM_CONNECT_FAILED" -> "RFCOMM link failed. Ensure peer is listening."
                        "BOND_LOST" -> "Bluetooth bond lost — re-pair device."
                        "SOCKET_DISCONNECTED" -> "Bluetooth socket disconnected."
                        "HANDSHAKE_FAILED" -> "Secure handshake failed. Reconnect."
                        else -> error
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.OnErrorContainer
                )
            }
        }
    }
}

@Composable
private fun WifiDirectStatusCard(
    state: com.itantra.core.transport.peer.WifiDirectState,
    peerCount: Int,
    info: android.net.wifi.p2p.WifiP2pInfo?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val (stateText, badgeColor) = when (state) {
            com.itantra.core.transport.peer.WifiDirectState.CONNECTED -> "CONNECTED · TCP 8988" to ITantraColors.StatusSuccess
            com.itantra.core.transport.peer.WifiDirectState.TCP_CONNECTING -> "TCP SOCKET CONNECTING…" to ITantraColors.Primary
            com.itantra.core.transport.peer.WifiDirectState.GROUP_FORMED -> "P2P GROUP FORMED" to ITantraColors.Primary
            com.itantra.core.transport.peer.WifiDirectState.CONNECTING -> "NEGOTIATING P2P LINK…" to ITantraColors.Primary
            com.itantra.core.transport.peer.WifiDirectState.DISCOVERING -> "SEARCHING FOR WI-FI DIRECT PEERS…" to ITantraColors.Primary
            com.itantra.core.transport.peer.WifiDirectState.AVAILABLE -> "WI-FI DIRECT READY" to ITantraColors.TextHeadline
            com.itantra.core.transport.peer.WifiDirectState.PERMISSION_REQUIRED -> "PERMISSION REQUIRED" to ITantraColors.OnErrorContainer
            com.itantra.core.transport.peer.WifiDirectState.OFF -> "WI-FI P2P DISABLED" to ITantraColors.TextMuted
            com.itantra.core.transport.peer.WifiDirectState.ERROR -> "ERROR" to ITantraColors.OnErrorContainer
        }

        Text(
            text = stateText,
            style = MaterialTheme.typography.labelMedium,
            color = badgeColor,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "DIRECT PHONE-TO-PHONE · NO ROUTER · NO INTERNET",
            style = MaterialTheme.typography.labelSmall,
            color = ITantraColors.TextMuted,
        )

        if (info != null && info.groupFormed) {
            Spacer(Modifier.height(8.dp))
            val role = if (info.isGroupOwner) "Group Owner (TCP Server)" else "Client (TCP Client)"
            val host = info.groupOwnerAddress?.hostAddress?.let { "${it.take(8)}***" } ?: "local"
            Text(
                text = "ROLE: $role · HOST: $host",
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.Primary
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "$peerCount PEERS IN RANGE",
            style = MaterialTheme.typography.labelSmall,
            color = ITantraColors.Primary,
            modifier = Modifier
                .background(ITantraColors.AccentSubtle, RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun WifiDirectErrorCard(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.ErrorContainer, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.OnErrorContainer.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Sensors,
                contentDescription = null,
                tint = ITantraColors.OnErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "WI-FI DIRECT ISSUE",
                    style = MaterialTheme.typography.labelSmall,
                    color = ITantraColors.OnErrorContainer
                )
                Text(
                    text = when (error) {
                        "PERMISSION_DENIED" -> "Nearby Wi-Fi permission required for Wi-Fi Direct"
                        "P2P_NOT_SUPPORTED" -> "Wi-Fi Direct is not supported on this device."
                        "P2P_DISABLED" -> "Wi-Fi Direct is unavailable. Enable Wi-Fi."
                        "DISCOVERY_FAILED" -> "Failed to start peer discovery. Ensure Wi-Fi is enabled."
                        "NO_PEERS_FOUND" -> "No Wi-Fi Direct peers found nearby."
                        "CONNECT_REQUEST_FAILED" -> "Failed to initiate connection to peer."
                        "GROUP_FORMATION_FAILED" -> "Wi-Fi Direct group formation failed."
                        "GROUP_OWNER_ADDRESS_MISSING" -> "Group owner address is missing."
                        "TCP_SERVER_FAILED" -> "Failed to start TCP server on port 8988."
                        "TCP_CONNECT_TIMEOUT" -> "Connection timed out reaching group owner TCP server."
                        "TCP_CONNECT_FAILED" -> "TCP connection to peer failed."
                        "SOCKET_CLOSED" -> "Wi-Fi Direct socket disconnected."
                        "SEND_FAILED" -> "Failed to transmit data frame over Wi-Fi Direct."
                        "RECEIVE_FAILED" -> "Failed to receive data from peer."
                        else -> error
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.OnErrorContainer
                )
            }
        }
    }
}

@Composable
private fun WifiDirectPeerCard(
    peer: com.itantra.core.transport.peer.WifiDirectPeer,
    secureState: com.itantra.core.crypto.SecureSessionState,
    isTcpConnected: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onOpenChat: () -> Unit = {}
) {
    val isSecureConnected = isTcpConnected && secureState == com.itantra.core.crypto.SecureSessionState.SECURE_VERIFIED
    val isVerifyingSas = isTcpConnected && secureState == com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION
    val isHandshaking = isTcpConnected && !isSecureConnected && !isVerifyingSas

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(peer.deviceName, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (isSecureConnected) {
                        if (peer.isGroupOwner) "Secure Group Owner · ${peer.statusDisplay}" else "Secure Peer · ${peer.statusDisplay}"
                    } else {
                        if (peer.isGroupOwner) "Unverified Group Owner · ${peer.statusDisplay}" else "Unverified Wi-Fi Peer · ${peer.statusDisplay}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted
                )
            }
            when {
                isSecureConnected -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("SECURE CONNECTED") },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledContainerColor = ITantraColors.StatusSuccess.copy(alpha = 0.12f),
                            disabledLabelColor = ITantraColors.StatusSuccess,
                        ),
                    )
                    Button(
                        onClick = onOpenChat,
                        colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("OPEN CHAT", style = MaterialTheme.typography.labelSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }
                isVerifyingSas -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("VERIFY SAS") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = ITantraColors.Primary.copy(alpha = 0.15f),
                        disabledLabelColor = ITantraColors.Primary
                    )
                )
                isHandshaking -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("WAITING FOR SECURE HANDSHAKE…") },
                )
                isConnecting -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("CONNECTING…") },
                )
                else -> OutlinedButton(onClick = onConnect) {
                    Text("CONNECT")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatChip(label = "ADDRESS", value = peer.maskedAddress)
            StatChip(label = "LINK", value = "Wi-Fi Direct P2P")
            StatChip(label = "STATUS", value = peer.statusDisplay)
        }
    }
}
