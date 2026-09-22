package com.example.itantra.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.data.settings.AppSettings
import com.example.itantra.ui.components.rememberBatteryState
import com.example.itantra.ui.theme.ITantraColors

/**
 * Direct port of 01_settings/code.html.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    pairingSectionContent: (@Composable () -> Unit)? = null,
    isBackendWired: Boolean = true,
) {
    val settings by viewModel.settings.collectAsState()
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = { SettingsTopBar(onBack = onBack) },
    ) { padding ->
        if (!isBackendWired) {
            NotYetAvailableGate(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DeviceIdentityBanner() }
            item {
                OperatorIdentitySection(
                    operatorName = settings.operatorName,
                    onNameChange = viewModel::setOperatorName
                )
            }

            item {
                SettingsSection(icon = "📶", title = "Transport", tag = "SEC-01") {
                    TransportInfo()
                }
            }

            item {
                SettingsSection(icon = "🎙", title = "Voice-Activity Sensitivity (VAD)", tag = "SEC-02") {
                    VadSensitivitySlider(
                        value = settings.vadSensitivity,
                        onValueChange = viewModel::setVadSensitivity,
                    )
                    Spacer(Modifier.height(16.dp))
                    NoiseSuppressionSlider(
                        value = settings.noiseSuppressionDb,
                        onValueChange = viewModel::setNoiseSuppressionDb,
                    )
                }
            }

            item {
                val targetLang by com.itantra.app.AppGraph.languagePackRepository.observeTargetLanguage().collectAsState(initial = null)
                SettingsSection(icon = "🌐", title = "Outgoing Translation & Target Language", tag = "SEC-03") {
                    Text(
                        "Configure the target language for outgoing voice and typed messages. Set to Auto to automatically adapt to the connected peer's advertised language.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isAuto = (targetLang == null)
                        FilterChip(
                            selected = isAuto,
                            onClick = { com.itantra.app.AppGraph.setTargetLanguage(null) },
                            label = { Text("Auto / Peer Default") }
                        )
                        val isHindi = (targetLang == com.itantra.domain.model.LanguageCode.HINDI)
                        FilterChip(
                            selected = isHindi,
                            onClick = { com.itantra.app.AppGraph.setTargetLanguage(com.itantra.domain.model.LanguageCode.HINDI) },
                            label = { Text("हिन्दी (HI)") }
                        )
                        val isEnglish = (targetLang == com.itantra.domain.model.LanguageCode.ENGLISH)
                        FilterChip(
                            selected = isEnglish,
                            onClick = { com.itantra.app.AppGraph.setTargetLanguage(com.itantra.domain.model.LanguageCode.ENGLISH) },
                            label = { Text("English (EN)") }
                        )
                    }
                }
            }

            item {
                SettingsSection(
                    icon = "🚨",
                    title = "Emergency Playback & Behavior",
                    tag = "CRITICAL",
                    tagColor = ITantraColors.StatusDanger,
                ) {
                    EmergencyBehaviorControls(
                        settings = settings,
                        onOverrideSilentChange = viewModel::setEmergencyOverrideSilent,
                        onPlaybackVolumeChange = viewModel::setEmergencyPlaybackVolume,
                        onTtsAnnounceChange = viewModel::setEmergencyTtsAnnounce,
                        onRequireConfirmationChange = viewModel::setEmergencyRequireConfirmation,
                    )
                }
            }

            if (pairingSectionContent != null) {
                item {
                    SettingsSection(icon = "🔒", title = "Device Pairing & Verification", tag = "SEC-04") {
                        pairingSectionContent()
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ITantraColors.StatusDanger),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.StatusDanger.copy(alpha = 0.3f)),
                ) {
                    Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset Settings to Field Default")
                }
            }

            item {
                // Standing scope note, matches the one shown once on the Transceiver Hub -
                // translation is a deliberate decision for this build, not a bug. See
                // UnavailableTranslationEngine's doc comment for how to re-enable it later.
                Text(
                    "Voice transcripts only \u2014 translation not included in this build",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Text(
                    "Diagnostic Firmware: v4.12.0-SOV\niTantra Tactical Defense Systems · Zero Cloud Dependency Mode Active",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset all VAD calibration and transport settings to field default?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefault()
                    showResetConfirm = false
                }) { Text("Reset", color = ITantraColors.StatusDanger) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Column {
                Text("Settings", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    "Transport, voice & security",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            val battery = rememberBatteryState()
            val chargingTag = if (battery.isCharging) " ⚡" else ""
            Text(
                "AES-256-GCM · ${battery.levelPercent}%$chargingTag",
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.TextMuted,
                modifier = Modifier.padding(end = 16.dp),
            )
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = ITantraColors.SurfaceWhite),
    )
}

@Composable
private fun NotYetAvailableGate(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Settings isn't wired to the backend yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Per DESIGN_SPEC.md Rule 0, controls stay disabled until they actually change app behavior.",
            style = MaterialTheme.typography.bodyMedium,
            color = ITantraColors.TextMuted,
        )
    }
}

@Composable
private fun DeviceIdentityBanner() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceId = remember(context) {
        try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: android.os.Build.MODEL
            val hex = Integer.toHexString(androidId.hashCode()).take(4).uppercase()
            "0x$hex-${android.os.Build.MODEL.take(5).uppercase()}"
        } catch (e: Exception) {
            "0xNODE-DEV"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(8.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(ITantraColors.StatusSuccess, RoundedCornerShape(50)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "DEVICE ID: $deviceId · READY",
            style = MaterialTheme.typography.labelSmall,
            color = ITantraColors.TextHeadline,
        )
    }
}

@Composable
private fun OperatorIdentitySection(
    operatorName: String,
    onNameChange: (String) -> Unit
) {
    SettingsSection(icon = "👤", title = "Operator Callsign & Identity", tag = "IDENT") {
        Column {
            Text(
                "Local station identifier for this device. Not transmitted over Bluetooth RFCOMM.",
                style = MaterialTheme.typography.bodySmall,
                color = ITantraColors.TextMuted,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = operatorName,
                onValueChange = onNameChange,
                placeholder = { Text("e.g. ALPHA-1, Capt. Sharma") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ITantraColors.Primary,
                    unfocusedBorderColor = ITantraColors.BorderSubtle,
                    focusedContainerColor = ITantraColors.SurfaceWhite,
                    unfocusedContainerColor = ITantraColors.SurfaceWhite,
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = ITantraColors.TextHeadline
                )
            )
        }
    }
}

@Composable
private fun SettingsSection(
    icon: String,
    title: String,
    tag: String,
    tagColor: Color = ITantraColors.Primary,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(icon)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                tag,
                style = MaterialTheme.typography.labelSmall,
                color = tagColor,
                modifier = Modifier
                    .background(tagColor.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
                .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
                .padding(16.dp),
            content = content,
        )
    }
}

/**
 * Bluetooth RFCOMM is the only transport in this build. Wi-Fi Direct and automatic transport switching
 * were cut from scope; the stored transportPreference setting is no longer read or shown.
 */
@Composable
private fun TransportInfo() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Encrypted Bluetooth RFCOMM", style = MaterialTheme.typography.labelLarge)
        Text(
            "All traffic between two devices runs over a direct Bluetooth RFCOMM link. Messages are " +
                "end-to-end encrypted once you confirm the verification code on both devices.",
            style = MaterialTheme.typography.bodySmall,
            color = ITantraColors.TextMuted,
        )
        Text(
            "Wi-Fi Direct and automatic transport switching are not included in this build.",
            style = MaterialTheme.typography.bodySmall,
            color = ITantraColors.TextMuted,
        )
    }
}

@Composable
private fun VadSensitivitySlider(value: Int, onValueChange: (Int) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("VAD Sensitivity (Continuous Mode)", style = MaterialTheme.typography.labelLarge)
            Text(
                vadLabel(value),
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.Primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 1f..3f,
            steps = 1,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Low\n(Noisy areas)", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
            Text("High\n(Quiet speech)", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
        }
    }
}

private fun vadLabel(value: Int) = when (value) { 1 -> "LOW THRESHOLD"; 3 -> "HIGH THRESHOLD"; else -> "STANDARD THRESHOLD" }

@Composable
private fun NoiseSuppressionSlider(value: Int, onValueChange: (Int) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Dynamic Noise Suppression Level", style = MaterialTheme.typography.labelLarge)
            Text(
                "-${value} dB THRESHOLD (${noiseSuppressionLabel(value)})",
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.Primary,
            )
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            valueRange = 6f..36f,
            steps = 14,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-6 dB\n(Gentle)", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
            Text("-36 dB\n(Aggressive)", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted)
        }
    }
}

private fun noiseSuppressionLabel(db: Int) = when {
    db <= 12 -> "GENTLE"
    db >= 30 -> "AGGRESSIVE"
    else -> "MODERATE"
}

@Composable
private fun EmergencyBehaviorControls(
    settings: AppSettings,
    onOverrideSilentChange: (Boolean) -> Unit,
    onPlaybackVolumeChange: (Int) -> Unit,
    onTtsAnnounceChange: (Boolean) -> Unit,
    onRequireConfirmationChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsToggleRow(
            title = "Override Silent/Vibrate Mode for Emergency Alerts",
            description = "Forces loudspeaker gain during incoming tactical distress codes.",
            checked = settings.emergencyOverrideSilent,
            onCheckedChange = onOverrideSilentChange,
        )
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Emergency Audio Playback Level", style = MaterialTheme.typography.labelLarge)
                Text(
                    "${settings.emergencyPlaybackVolume}%" + if (settings.emergencyPlaybackVolume == 100) " (MAX)" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = ITantraColors.StatusDanger,
                )
            }
            Slider(
                value = settings.emergencyPlaybackVolume.toFloat(),
                onValueChange = { onPlaybackVolumeChange(it.toInt()) },
                valueRange = 80f..100f,
                colors = SliderDefaults.colors(thumbColor = ITantraColors.StatusDanger, activeTrackColor = ITantraColors.StatusDanger),
            )
        }
        SettingsToggleRow(
            title = "Text-to-Speech (TTS) Emergency Announcement",
            description = "Reads out incoming coordinates and alert text into operator headset.",
            checked = settings.emergencyTtsAnnounce,
            onCheckedChange = onTtsAnnounceChange,
        )
        SettingsToggleRow(
            title = "Require Confirmation for Critical Broadcasts",
            description = "Demands dual-tap validation before transmitting emergency evacuation codes.",
            checked = settings.emergencyRequireConfirmation,
            onCheckedChange = onRequireConfirmationChange,
            badge = "SAFETY LOCK",
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = ITantraColors.StatusSuccess,
                        modifier = Modifier
                            .background(ITantraColors.StatusSuccess.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
