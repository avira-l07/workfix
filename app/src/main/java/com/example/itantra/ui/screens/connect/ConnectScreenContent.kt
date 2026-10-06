package com.example.itantra.ui.screens.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
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
    val transport: String,     // e.g. "Bluetooth RFCOMM" or "Wi-Fi Direct P2P"
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

@Composable
fun ConnectScreenContent(
    channelName: String,
    peersInRange: Int,
    devices: List<PeerDevice>,
    isScanning: Boolean,
    sasCode: String? = null,
    connectionError: String? = null,
    bluetoothHardwareStatus: String? = null,
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
    selectedWifiPeerAddress: String? = null,
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
    var currentMode by remember(selectedTransportMode) { mutableStateOf(selectedTransportMode) }
    var isLocallyConfirmed by remember(sasCode, connectedDeviceAddress, selectedWifiPeerAddress) { mutableStateOf(false) }

    LaunchedEffect(sasCode, secureSessionState) {
        if (sasCode.isNullOrBlank() || secureSessionState != com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION) {
            isLocallyConfirmed = false
        }
    }

    val isWifi = currentMode == TransportMode.WIFI_DIRECT
    val hasWifiSession = wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTED ||
            wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTING ||
            wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.GROUP_FORMED ||
            wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.TCP_CONNECTING ||
            wifiDirectInfo?.groupFormed == true

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ITantraColors.CanvasBg),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Find your people.",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = ITantraColors.TextHeadline,
                )
                Text(
                    "Choose the right nearby device and verify it together.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.TextMuted,
                )
            }
        }
        if (isWifi) {
            item {
                Text(
                    "Wi-Fi Direct connects phones nearby. Open Connect on both phones to discover each other, even without a router.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ITantraColors.Primary,
                    modifier = Modifier.fillMaxWidth()
                        .background(ITantraColors.AccentSubtle, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                )
            }
        }
        item {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(ITantraColors.SurfaceWhite, RoundedCornerShape(24.dp))
                    .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(24.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                TransportSelector(currentMode) { mode ->
                    currentMode = mode
                    onTransportModeChanged(mode)
                }
                Text(
                    "Nearby devices",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = ITantraColors.TextHeadline,
                )
                if (!isWifi) {
                    if (hasWifiSession) {
                        Text("Disconnect Wi-Fi Direct before starting a Bluetooth connection.", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onDisconnectWifiDirect, modifier = Modifier.fillMaxWidth()) {
                            Text("Disconnect Wi-Fi Direct")
                        }
                    }
                    if (!connectionError.isNullOrBlank()) {
                        ConnectionErrorCard(error = connectionError)
                    } else if (bluetoothHardwareStatus != null && bluetoothHardwareStatus != "On") {
                        ConnectionErrorCard(error = bluetoothHardwareStatus)
                    }
                    ScanningCard(isScanning = isScanning, peersInRange = peersInRange)
                    if (devices.isEmpty()) {
                        DiscoveryEmptyState("No devices shown yet", "Open Connect on the other phone, then start discovery.")
                    } else {
                        // ponytail: peer rows share one discovery card; use lazy items if nearby lists grow large.
                        devices.forEach { device ->
                            key(device.id) {
                                PeerDeviceCard(
                                    device = device,
                                    onConnect = { onConnect(device) },
                                    onOpenChat = { onOpenChat(device) },
                                    canConnect = !hasWifiSession,
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onBroadcastPing,
                        enabled = !hasWifiSession,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Discover Bluetooth devices")
                    }
                } else {
                    if (!wifiDirectError.isNullOrBlank()) {
                        WifiDirectErrorCard(error = wifiDirectError)
                    }
                    WifiDirectStatusCard(wifiDirectState, wifiDirectPeers.size, wifiDirectInfo)
                    if (wifiDirectPeers.isEmpty()) {
                        DiscoveryEmptyState("No Wi-Fi Direct peers yet", "Open Connect on the other phone and choose Wi-Fi Direct.")
                    } else {
                        wifiDirectPeers.forEach { peer ->
                            key(peer.deviceAddress) {
                                val isSelectedPeer = selectedWifiPeerAddress != null && peer.deviceAddress == selectedWifiPeerAddress
                                WifiDirectPeerCard(
                                    peer = peer,
                                    secureState = secureSessionState,
                                    isTcpConnected = isSelectedPeer && wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTED,
                                    isConnecting = isSelectedPeer && hasWifiSession && wifiDirectState != com.itantra.core.transport.peer.WifiDirectState.CONNECTED,
                                    canConnect = !hasWifiSession,
                                    onConnect = { onConnectWifiDirect(peer) },
                                    onOpenChat = { onOpenChatWifiDirect(peer) },
                                )
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = onDiscoverWifiDirectPeers,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Icon(Icons.Filled.Sensors, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Discover Wi-Fi Direct peers")
                    }
                    if (hasWifiSession) {
                        OutlinedButton(
                            onClick = onDisconnectWifiDirect,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ITantraColors.OnErrorContainer),
                        ) {
                            Text("Disconnect Wi-Fi Direct")
                        }
                    }
                }
            }
        }
        item {
            ActiveChannelCard(
                channelName = channelName,
                peerName = devices.find { it.id == connectedDeviceAddress }?.name
                    ?: wifiDirectPeers.find { it.deviceAddress == selectedWifiPeerAddress }?.deviceName,
                isWifi = connectedDeviceAddress == null && hasWifiSession,
                secureState = secureSessionState,
                hasLink = devices.any { it.id == connectedDeviceAddress && it.state in setOf(PeerConnectionState.SECURE_HANDSHAKE, PeerConnectionState.VERIFY_SAS, PeerConnectionState.SECURE_CONNECTED) }
                    || wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTED,
            )
        }
        item { MyProfileCard(myDeviceId = myDeviceId, myDisplayName = myDisplayName) }
        item { ConnectionGuide() }
    }

    val shouldShowSasDialog = secureSessionState == com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION && !sasCode.isNullOrBlank()

    if (shouldShowSasDialog) {
        val verifyingWifi = connectedDeviceAddress == null &&
                wifiDirectState == com.itantra.core.transport.peer.WifiDirectState.CONNECTED
        val targetName = if (!verifyingWifi) {
            devices.find { it.id == connectedDeviceAddress }?.name ?: "Remote Node"
        } else {
            wifiDirectPeers.find { it.deviceAddress == selectedWifiPeerAddress }?.deviceName ?: "Wi-Fi Direct Peer"
        }
        val targetPeerDevice = devices.find { it.id == connectedDeviceAddress } ?: PeerDevice(
            id = if (verifyingWifi) selectedWifiPeerAddress ?: "wifi-peer" else connectedDeviceAddress ?: "peer-node",
            name = targetName,
            role = "Peer Operator",
            transport = if (verifyingWifi) "Wi-Fi Direct P2P" else "Bluetooth RFCOMM",
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
private fun TransportSelector(currentMode: TransportMode, onModeChanged: (TransportMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().selectableGroup()
            .background(ITantraColors.AccentSubtle.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TransportMode.entries.forEach { mode ->
            val selected = currentMode == mode
            Row(
                modifier = Modifier.weight(1f)
                    .background(if (selected) ITantraColors.SurfaceWhite else Color.Transparent, RoundedCornerShape(11.dp))
                    .selectable(selected = selected, role = Role.Tab, onClick = { onModeChanged(mode) })
                    .heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    if (mode == TransportMode.BLUETOOTH) Icons.Filled.Bluetooth else Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = if (selected) ITantraColors.Primary else ITantraColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (mode == TransportMode.BLUETOOTH) "Bluetooth" else "Wi-Fi Direct",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) ITantraColors.Primary else ITantraColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryEmptyState(title: String, description: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Link, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(32.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
        Text(description, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun ConnectionGuide() {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(24.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(24.dp)).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().background(ITantraColors.AccentSubtle.copy(alpha = 0.5f), RoundedCornerShape(20.dp)).padding(vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(52.dp))
            Spacer(Modifier.width(18.dp))
            Icon(Icons.Filled.Link, contentDescription = null, tint = ITantraColors.StatusSuccess, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(18.dp))
            Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(52.dp))
        }
        Text("Know who you're connecting to.", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = ITantraColors.TextHeadline)
        Text("Names can look alike. Check the device ID and compare the security code face to face.", style = MaterialTheme.typography.bodyMedium, color = ITantraColors.TextMuted)
        listOf(
            "Discover nearby devices" to "Open Connect on both phones and choose the same connection type.",
            "Choose one peer" to "Each nearby device appears separately. Pick the phone you want to talk to.",
            "Compare the same six digits" to "Both people must confirm the code before the secure session is marked verified.",
        ).forEachIndexed { index, (title, description) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${index + 1}", color = ITantraColors.Primary, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.background(ITantraColors.AccentSubtle, RoundedCornerShape(9.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun MyProfileCard(myDeviceId: String, myDisplayName: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(24.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your device", style = MaterialTheme.typography.labelMedium, color = ITantraColors.TextMuted)
            Text(myDisplayName, style = MaterialTheme.typography.titleMedium, color = ITantraColors.TextHeadline)
            Text(myDeviceId, style = MaterialTheme.typography.bodySmall, color = ITantraColors.Primary)
        }
    }
}

@Composable
private fun ActiveChannelCard(
    channelName: String,
    peerName: String?,
    isWifi: Boolean,
    secureState: com.itantra.core.crypto.SecureSessionState,
    hasLink: Boolean,
) {
    val isVerified = hasLink && secureState == com.itantra.core.crypto.SecureSessionState.SECURE_VERIFIED
    val isVerifying = hasLink && secureState == com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(24.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(24.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Connection details", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = ITantraColors.TextHeadline)
        PeerStatusBadge(
            text = if (isVerified) "Verified" else if (isVerifying) "Compare security codes" else if (hasLink) "Not verified yet" else "Not connected",
            color = if (isVerified) ITantraColors.StatusSuccess else ITantraColors.TextMuted,
        )
        ConnectionFact("Peer", if (isVerified) channelName else peerName ?: "No peer selected")
        ConnectionFact("Transport", if (hasLink) { if (isWifi) "Wi-Fi Direct" else "Bluetooth" } else "No active link")
        ConnectionFact("Peer identity", if (isVerified) "Code confirmed on both phones" else "Awaiting verification")
        Text(
            if (isVerified) "Your messages go directly to this verified peer." else "Choose a nearby device and confirm the same six-digit code on both phones.",
            style = MaterialTheme.typography.bodySmall,
            color = ITantraColors.TextMuted,
        )
    }
}

@Composable
private fun ConnectionFact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted, modifier = Modifier.weight(0.8f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextBody, modifier = Modifier.weight(1.2f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

@Composable
private fun ScanningCard(isScanning: Boolean, peersInRange: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (isScanning) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = ITantraColors.Primary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (isScanning) "Looking for nearby phones…" else "Discovery paused",
                style = MaterialTheme.typography.bodyMedium,
                color = ITantraColors.Primary,
            )
            Text("$peersInRange ${if (peersInRange == 1) "device" else "devices"} shown · Bluetooth", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
        }
    }
}

@Composable
private fun PeerAvatar(name: String) {
    Box(
        modifier = Modifier.size(42.dp).background(ITantraColors.SuccessContainer, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().take(2).uppercase(), style = MaterialTheme.typography.labelLarge, color = ITantraColors.OnSuccessContainer)
    }
}

@Composable
private fun PeerStatusBadge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.1f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun PeerDeviceCard(device: PeerDevice, onConnect: () -> Unit, onOpenChat: () -> Unit = {}, canConnect: Boolean = true) {
    val isVerified = device.state == PeerConnectionState.SECURE_CONNECTED
    val status = when (device.state) {
        PeerConnectionState.SECURE_CONNECTED -> "Verified with you"
        PeerConnectionState.VERIFY_SAS -> "Compare security codes"
        PeerConnectionState.SECURE_HANDSHAKE -> "Securing connection…"
        PeerConnectionState.CONNECTING -> "Connecting…"
        PeerConnectionState.LISTENING -> "Listening for a connection"
        PeerConnectionState.AVAILABLE -> "Available nearby"
        PeerConnectionState.DISCONNECTED -> "Disconnected"
        PeerConnectionState.ERROR -> "Connection issue"
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(ITantraColors.AccentSubtle.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PeerAvatar(device.name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
                Text(if (device.id.length > 12) "${device.id.take(8)}…" else device.id, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                if (device.role.isNotBlank()) Text(device.role, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
            }
        }
        PeerStatusBadge(status, if (isVerified) ITantraColors.StatusSuccess else if (device.state == PeerConnectionState.ERROR) ITantraColors.StatusDanger else ITantraColors.Primary)
        HorizontalDivider(color = ITantraColors.BorderSubtle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(device.signalDbm?.let { "Signal $it dBm" } ?: "Signal not measured", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                Text(device.transport, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                Text(device.batteryPercent?.let { "Battery $it%" } ?: "Battery not provided", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
            }
            when (device.state) {
                PeerConnectionState.SECURE_CONNECTED -> FilledTonalButton(onClick = onOpenChat, shape = RoundedCornerShape(12.dp)) { Text("Open chat") }
                PeerConnectionState.AVAILABLE, PeerConnectionState.DISCONNECTED, PeerConnectionState.ERROR -> OutlinedButton(onClick = onConnect, enabled = canConnect, shape = RoundedCornerShape(12.dp)) {
                    Text(if (device.state == PeerConnectionState.ERROR) "Retry" else "Connect")
                }
                else -> Unit
            }
        }
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
                    "Compare security codes",
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
                            "Waiting for the other phone to confirm…",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = ITantraColors.StatusSuccess
                        )
                    }
                } else {
                    Text(
                        "Only confirm when the same six digits appear on both phones. This verifies who you are connected to.",
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
                    disabledContentColor = if (isLocallyConfirmed) ITantraColors.OnSuccess else ITantraColors.TextMuted
                )
            ) {
                Text(if (isLocallyConfirmed) "Confirmed on this phone" else "Codes match")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Reject / cancel")
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
            .background(ITantraColors.ErrorContainer, RoundedCornerShape(20.dp))
            .border(1.dp, ITantraColors.OnErrorContainer.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
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
                    text = "Bluetooth connection issue",
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
    info: android.net.wifi.p2p.WifiP2pInfo?,
) {
    val (stateText, badgeColor) = when (state) {
        com.itantra.core.transport.peer.WifiDirectState.CONNECTED -> "Wi-Fi link connected" to ITantraColors.Primary
        com.itantra.core.transport.peer.WifiDirectState.TCP_CONNECTING -> "Opening the direct connection…" to ITantraColors.Primary
        com.itantra.core.transport.peer.WifiDirectState.GROUP_FORMED -> "Wi-Fi group formed" to ITantraColors.Primary
        com.itantra.core.transport.peer.WifiDirectState.CONNECTING -> "Connecting to a nearby phone…" to ITantraColors.Primary
        com.itantra.core.transport.peer.WifiDirectState.DISCOVERING -> "Looking for nearby phones…" to ITantraColors.Primary
        com.itantra.core.transport.peer.WifiDirectState.AVAILABLE -> "Wi-Fi Direct ready" to ITantraColors.TextHeadline
        com.itantra.core.transport.peer.WifiDirectState.PERMISSION_REQUIRED -> "Nearby-device permission required" to ITantraColors.OnErrorContainer
        com.itantra.core.transport.peer.WifiDirectState.OFF -> "Turn on Wi-Fi to discover devices" to ITantraColors.OnErrorContainer
        com.itantra.core.transport.peer.WifiDirectState.ERROR -> "Wi-Fi Direct needs attention" to ITantraColors.OnErrorContainer
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stateText, style = MaterialTheme.typography.bodyMedium, color = badgeColor)
        Text("$peerCount ${if (peerCount == 1) "device" else "devices"} shown · Wi-Fi Direct", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
        if (info != null && info.groupFormed) {
            val role = if (info.isGroupOwner) "Group owner (TCP server)" else "Client (TCP client)"
            val host = info.groupOwnerAddress?.hostAddress?.let { "${it.take(8)}…" } ?: "local"
            Text("$role · Host $host · Port 8988", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
        }
    }
}
@Composable
private fun WifiDirectErrorCard(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.ErrorContainer, RoundedCornerShape(20.dp))
            .border(1.dp, ITantraColors.OnErrorContainer.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
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
                    text = "Wi-Fi Direct issue",
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
    canConnect: Boolean,
    onConnect: () -> Unit,
    onOpenChat: () -> Unit = {},
) {
    val isSecureConnected = isTcpConnected && secureState == com.itantra.core.crypto.SecureSessionState.SECURE_VERIFIED
    val isVerifyingSas = isTcpConnected && secureState == com.itantra.core.crypto.SecureSessionState.WAITING_USER_VERIFICATION
    val isHandshaking = isTcpConnected && !isSecureConnected && !isVerifyingSas
    val hasSecurityError = isTcpConnected && (secureState == com.itantra.core.crypto.SecureSessionState.FAILED || secureState == com.itantra.core.crypto.SecureSessionState.HANDSHAKE_TIMEOUT)
    val status = when {
        isSecureConnected -> "Verified with you"
        isVerifyingSas -> "Compare security codes"
        hasSecurityError -> "Verification failed"
        isHandshaking -> "Securing connection…"
        isConnecting -> "Connecting…"
        else -> "Unverified nearby device"
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(ITantraColors.AccentSubtle.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(18.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PeerAvatar(peer.deviceName)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(peer.deviceName, style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
                Text(peer.maskedAddress, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                Text(if (peer.isGroupOwner) "Group owner · ${peer.statusDisplay}" else peer.statusDisplay, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
            }
        }
        PeerStatusBadge(status, if (isSecureConnected) ITantraColors.StatusSuccess else if (hasSecurityError) ITantraColors.StatusDanger else ITantraColors.Primary)
        HorizontalDivider(color = ITantraColors.BorderSubtle)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Wi-Fi Direct", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                Text("Signal not provided", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
            }
            when {
                isSecureConnected -> FilledTonalButton(onClick = onOpenChat, shape = RoundedCornerShape(12.dp)) { Text("Open chat") }
                !isVerifyingSas && !isHandshaking && !isConnecting -> OutlinedButton(onClick = onConnect, enabled = canConnect, shape = RoundedCornerShape(12.dp)) {
                    Text("Connect")
                }
                else -> Unit
            }
        }
        if (!canConnect && !isTcpConnected && !isConnecting) {
            Text("Disconnect the current peer to choose this device.", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
        }
    }
}
