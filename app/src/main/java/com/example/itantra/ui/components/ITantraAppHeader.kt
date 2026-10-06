package com.example.itantra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.itantra.core.crypto.SecureSessionState
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.domain.model.EmergencyCode

/** The primary tabs share the same real appearance and emergency actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ITantraAppHeader(coordinator: TransceiverCoordinator, onToggleTheme: () -> Unit,
    onSettings: () -> Unit) {
    val peer by coordinator.activePeerProfile.collectAsState()
    val security by coordinator.secureSessionManager.state.collectAsState()
    val verified = security == SecureSessionState.SECURE_VERIFIED && peer?.isConnected == true
    var showSos by remember { mutableStateOf(false) }
    var emergency by remember { mutableStateOf<EmergencyCode?>(null) }
    TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        title = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.GraphicEq, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
            Text("iTantra", style = MaterialTheme.typography.titleLarge)
        } }, actions = {
            IconButton(onClick = onToggleTheme) {
                Icon(if (MaterialTheme.colorScheme.background.luminance() < .5f) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                    "Switch light or dark appearance")
            }
            Button(onClick = { showSos = true }, shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) {
                Icon(Icons.Filled.WarningAmber, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp)); Text("SOS")
            }
            IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings and appearance") }
        })
    if (showSos) AlertDialog(onDismissRequest = { showSos = false }, title = { Text("Emergency message") }, text = { Column {
        Text("Send to your connected peer. Confirm before sending.")
        listOf(EmergencyCode.HELP_REQUIRED to "Need help", EmergencyCode.MEDICAL_EMERGENCY to "Medical SOS",
            EmergencyCode.ROAD_BLOCKED to "Road blocked", EmergencyCode.EVACUATE to "Evacuate").forEach { (code, name) ->
            TextButton(onClick = { emergency = code; showSos = false }) { Text(name) }
        }
        TextButton(onClick = { emergency = EmergencyCode.HELP_REQUIRED; showSos = false }) { Text("Critical priority alert") }
    } }, confirmButton = { TextButton(onClick = { showSos = false }) { Text("Close") } })
    emergency?.let { code -> AlertDialog(onDismissRequest = { emergency = null },
        title = { Text("Send ${code.name.lowercase().replace('_', ' ')}?") },
        text = { Text(if (verified) "Send a critical encrypted alert to your connected peer." else "Connect and verify a peer before sending.") },
        confirmButton = { TextButton(onClick = { coordinator.sendEmergencyCode(code); emergency = null }, enabled = verified) { Text("Send alert") } },
        dismissButton = { TextButton(onClick = { emergency = null }) { Text("Cancel") } }) }
}
