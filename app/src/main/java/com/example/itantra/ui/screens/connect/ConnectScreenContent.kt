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

enum class PeerConnectionState { CONNECTED, AVAILABLE, CONNECTING, DISCONNECTED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreenContent(
    channelName: String,
    peersInRange: Int,
    devices: List<PeerDevice>,
    isScanning: Boolean,
    sasCode: String? = null,
    myDeviceId: String = "IT-????-????",
    myDisplayName: String = "This Device",
    onBack: () -> Unit,
    onBroadcastPing: () -> Unit,
    onConnect: (PeerDevice) -> Unit,
    onSasConfirmed: (PeerDevice) -> Unit = {},
    onOpenChat: (PeerDevice) -> Unit = {},
) {
    var verifyingDevice by remember { mutableStateOf<PeerDevice?>(null) }

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
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { MyProfileCard(myDeviceId = myDeviceId, myDisplayName = myDisplayName) }
            item { ActiveChannelCard(channelName) }
            item { ScanningCard(isScanning = isScanning, peersInRange = peersInRange) }
            item {
                Text(
                    "DISCOVERED DEVICES",
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
                        onConnect = {
                            verifyingDevice = device
                            onConnect(device)
                        },
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
    }

    verifyingDevice?.let { dev ->
        SasVerificationDialog(
            device = dev,
            sasCode = sasCode ?: "GENERATING...",
            onConfirm = {
                onSasConfirmed(dev)
                verifyingDevice = null
            },
            onDismiss = { verifyingDevice = null }
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
private fun ActiveChannelCard(channelName: String) {
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
                "OFFLINE · DIRECT PEER-TO-PEER",
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.TextMuted,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "DIRECT LINK · NO FORWARDING",
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
                PeerConnectionState.CONNECTED -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("CONNECTED") },
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
                PeerConnectionState.CONNECTING -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("CONNECTING…") },
                )
                PeerConnectionState.AVAILABLE -> OutlinedButton(onClick = onConnect) {
                    Text("CONNECT")
                }
                PeerConnectionState.DISCONNECTED -> AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("NOT CONNECTED") },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledContainerColor = ITantraColors.TextMuted.copy(alpha = 0.12f),
                        disabledLabelColor = ITantraColors.TextMuted,
                    ),
                )
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
    device: PeerDevice,
    sasCode: String = "-- ---",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Verify Device (SAS)", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Compare this 6-digit Short Authentication String with the display on ${device.name}:",
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
                        color = ITantraColors.Primary,
                        letterSpacing = 4.sp
                    )
                }
                Text(
                    "E2EE cryptographic verification ensures no man-in-the-middle attack is active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.StatusSuccess)
            ) {
                Text("CODES MATCH · TRUST")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL")
            }
        }
    )
}

