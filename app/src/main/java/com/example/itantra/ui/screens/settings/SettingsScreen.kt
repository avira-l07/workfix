package com.example.itantra.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.data.settings.AppSettings
import com.example.itantra.data.settings.ColorPalette
import com.example.itantra.data.settings.ThemeMode
import com.example.itantra.ui.theme.ITantraColors
import com.example.itantra.ui.theme.itantraColorScheme

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    pairingSectionContent: (@Composable () -> Unit)? = null,
    isBackendWired: Boolean = true,
    onWipe: (Set<com.itantra.core.storage.DataRemovalChoice>) -> Unit = {},
    onLanguagePacks: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onRecycleBin: () -> Unit = {},
) {
    val settings by viewModel.settings.collectAsState()
    val operatorNameInput by viewModel.operatorNameInput.collectAsState()
    val uriHandler = LocalUriHandler.current
    var showResetConfirm by remember { mutableStateOf(false) }

    if (!isBackendWired) {
        NotYetAvailableGate()
        return
    }

    Box(
        modifier = Modifier.fillMaxSize().background(ITantraColors.CanvasBg),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().widthIn(max = 880.dp).fillMaxWidth(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Make it yours.",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.8).sp,
                    )
                    Text(
                        "Simple controls for your voice, connection, and local data.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ITantraColors.TextMuted,
                    )
                }
            }

            item { DeviceIdentityBanner() }
            item {
                OperatorIdentitySection(
                    operatorName = operatorNameInput,
                    onNameChange = viewModel::setOperatorName,
                )
            }

            item {
                val targetLang by com.itantra.app.AppGraph.languagePackRepository.observeTargetLanguage()
                    .collectAsState(initial = null)
                SettingsSection(title = "Voice & connection") {
                    Text("Outgoing translation", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Choose the target language for outgoing voice and typed messages. Auto follows the connected peer's advertised language.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                    Text(
                        "Offline translation uses downloaded models. Check MT READY for both languages in Language packs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.Primary,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(
                                selected = targetLang == null,
                                onClick = { com.itantra.app.AppGraph.setTargetLanguage(null) },
                                label = { Text("Auto / peer default") },
                            )
                        }
                        items(com.itantra.domain.model.LanguageCatalog.all) { language ->
                            FilterChip(
                                selected = targetLang == language.code,
                                onClick = { com.itantra.app.AppGraph.setTargetLanguage(language.code) },
                                label = { Text(language.nativeDisplayName) },
                            )
                        }
                    }
                    HorizontalDivider(color = ITantraColors.BorderSubtle)
                    Text("Connection & verification", style = MaterialTheme.typography.titleSmall)
                    TransportInfo()
                    if (pairingSectionContent != null) {
                        pairingSectionContent()
                    }
                    HorizontalDivider(color = ITantraColors.BorderSubtle)
                    Text("Voice activity sensitivity", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Fixed sensitivity in this build. Field calibration has not yet been verified on low- and mid-range phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                    Text(
                        "Dynamic noise suppression level: Not available in this build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                }
            }

            item {
                SettingsSection(title = "Language & diagnostics") {
                    Text(
                        "Manage offline models, spoken playback, and device checks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                    OutlinedButton(onClick = onLanguagePacks, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Language packs")
                    }
                    OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Insights, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Diagnostics & field tests")
                    }
                    OutlinedButton(onClick = onRecycleBin, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Recycle bin · restore within 7 days")
                    }
                }
            }

            item {
                SettingsSection(title = "Emergency & local data") {
                    Text("Emergency playback", style = MaterialTheme.typography.titleSmall)
                    EmergencyBehaviorControls()
                    HorizontalDivider(color = ITantraColors.BorderSubtle)
                    Text("Restore default settings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Reset your preferences. Messages and downloaded language packs stay.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                    OutlinedButton(
                        onClick = { showResetConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Restore defaults")
                    }
                }
            }

            item {
                SettingsSection(title = "Look & feel") {
                    AppearanceControls(
                        settings = settings,
                        onThemeModeChange = viewModel::setThemeMode,
                        onPaletteChange = viewModel::setColorPalette,
                    )
                }
            }

            item {
                SettingsSection(title = "Speech model credits") {
                    Text(
                        "Indian-language recognition: AI4Bharat IndicConformer exports. English recognition: NVIDIA NeMo FastConformer export. Marathi voice: Piper mr_IN-google-medium, based on OpenSLR 64. Other voices: Meta MMS TTS ONNX exports (CC BY-NC 4.0). Translation: Google ML Kit on-device translation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                    TextButton(onClick = { uriHandler.openUri("https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx") }) {
                        Text("STT model sources and licences")
                    }
                    TextButton(onClick = { uriHandler.openUri("https://huggingface.co/willwade/mms-tts-multilingual-models-onnx") }) {
                        Text("MMS voices and licence")
                    }
                    TextButton(onClick = { uriHandler.openUri("https://huggingface.co/rhasspy/piper-voices/blob/c10ece1aade47bb51c153c893d14e5bf8e5b7117/mr/mr_IN/google/medium/MODEL_CARD") }) {
                        Text("Marathi Piper voice and credits")
                    }
                    TextButton(onClick = { uriHandler.openUri("https://developers.google.com/ml-kit/language/translation/translation-terms") }) {
                        Text("Translation terms")
                    }
                }
            }

            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = ITantraColors.ErrorContainer,
                    border = BorderStroke(1.dp, ITantraColors.StatusDanger.copy(alpha = 0.25f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Manage private data", style = MaterialTheme.typography.titleMedium, color = ITantraColors.StatusDanger)
                        Text(
                            "Choose which local data to permanently delete. Individual items can also be removed through the recycle bin. Downloaded models stay.",
                            style = MaterialTheme.typography.bodySmall,
                            color = ITantraColors.TextMuted,
                        )
                        WipeDataAction(onWipe)
                    }
                }
            }

            item {
                Text(
                    "iTantra ${com.example.itantra.BuildConfig.VERSION_NAME}\nSpeech and translation work offline after their selected models are installed.",
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
            title = { Text("Restore default settings?") },
            text = { Text("Restore the operator name, appearance and stored voice settings. Messages and downloaded language packs stay.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefault()
                    showResetConfirm = false
                }) { Text("Restore defaults") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NotYetAvailableGate(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Settings isn't wired to the backend yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Controls are unavailable until they can change app behavior.",
            style = MaterialTheme.typography.bodyMedium,
            color = ITantraColors.TextMuted,
        )
    }
}

@Composable
private fun DeviceIdentityBanner() {
    val profile by com.itantra.app.AppGraph.deviceProfileManager.profile.collectAsState()
    val deviceId = profile.deviceId.takeIf { it.isNotBlank() }
    val clipboard = LocalClipboardManager.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = ITantraColors.SurfaceWhite,
        border = BorderStroke(1.dp, ITantraColors.BorderSubtle),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Filled.Smartphone, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Device ID", style = MaterialTheme.typography.labelLarge)
                Text(
                    deviceId ?: "Device identity unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = ITantraColors.TextMuted,
                )
            }
            TextButton(
                enabled = deviceId != null,
                onClick = { deviceId?.let { clipboard.setText(AnnotatedString(it)) } },
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("Copy")
            }
        }
    }
}

@Composable
private fun OperatorIdentitySection(operatorName: TextFieldValue?, onNameChange: (TextFieldValue) -> Unit) {
    val name = operatorName?.text.orEmpty()
    SettingsSection(title = "Your profile") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(44.dp).background(ITantraColors.PrimaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (name.isBlank()) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = ITantraColors.Primary)
                } else {
                    Text(name.trim().take(2).uppercase(), color = ITantraColors.Primary, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Operator name", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Local station identifier for this device. Not transmitted over Bluetooth RFCOMM.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted,
                )
            }
        }
        OutlinedTextField(
            value = operatorName ?: TextFieldValue(),
            onValueChange = onNameChange,
            enabled = operatorName != null,
            label = { Text("Local operator name") },
            placeholder = { Text("Enter a name or callsign") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ITantraColors.Primary,
                unfocusedBorderColor = ITantraColors.BorderSubtle,
                focusedContainerColor = ITantraColors.SurfaceWhite,
                unfocusedContainerColor = ITantraColors.SurfaceWhite,
            ),
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = ITantraColors.SurfaceWhite,
            border = BorderStroke(1.dp, ITantraColors.BorderSubtle),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun AppearanceControls(
    settings: AppSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onPaletteChange: (ColorPalette) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier.size(44.dp).background(ITantraColors.PrimaryContainer, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Palette, contentDescription = null, tint = ITantraColors.Primary)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("A space that feels like you.", style = MaterialTheme.typography.titleSmall)
            Text("Choose your colors. Settle into light or dark.", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("Brightness", style = MaterialTheme.typography.labelLarge, color = ITantraColors.TextMuted)
    Row(modifier = Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM).forEach { mode ->
            val selected = settings.themeMode == mode
            Surface(
                modifier = Modifier.weight(1f).selectable(selected = selected, role = Role.RadioButton, onClick = { onThemeModeChange(mode) }),
                shape = RoundedCornerShape(14.dp),
                color = if (selected) ITantraColors.PrimaryContainer else ITantraColors.SurfaceVariant,
                border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ITantraColors.Primary else ITantraColors.BorderSubtle),
                contentColor = if (selected) ITantraColors.Primary else ITantraColors.TextMuted,
            ) {
                Column(
                    modifier = Modifier.defaultMinSize(minHeight = 82.dp).padding(horizontal = 7.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        when (mode) {
                            ThemeMode.LIGHT -> Icons.Filled.LightMode
                            ThemeMode.DARK -> Icons.Filled.DarkMode
                            ThemeMode.SYSTEM -> Icons.Filled.DesktopWindows
                        },
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("Your color story", style = MaterialTheme.typography.labelLarge, color = ITantraColors.TextMuted)
    Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ColorPalette.entries.chunked(2).forEach { palettes ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                palettes.forEach { palette ->
                    PaletteCard(
                        palette = palette,
                        selected = settings.colorPalette == palette,
                        onClick = { onPaletteChange(palette) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ITantraColors.TextMuted, modifier = Modifier.size(16.dp))
        Text("Saved on this device. Every screen follows your choice.", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
    }
}

@Composable
private fun PaletteCard(palette: ColorPalette, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val swatch = itantraColorScheme(dark = MaterialTheme.colorScheme.background.luminance() < 0.5f, palette = palette)
    Surface(
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = ITantraColors.SurfaceWhite,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) ITantraColors.Primary else ITantraColors.BorderSubtle),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(10.dp)).background(swatch.primaryContainer)) {
                Box(Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-12).dp).size(54.dp).background(swatch.secondary.copy(alpha = 0.5f), CircleShape))
                Box(Modifier.align(Alignment.BottomEnd).offset(x = (-20).dp, y = 28.dp).size(74.dp).border(1.dp, swatch.primary.copy(alpha = 0.35f), CircleShape))
                Row(modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Box(Modifier.size(24.dp).background(swatch.primary, CircleShape).border(2.dp, swatch.primaryContainer, CircleShape))
                    Box(Modifier.offset(x = (-7).dp).size(24.dp).background(swatch.secondary, CircleShape).border(2.dp, swatch.primaryContainer, CircleShape))
                }
                if (selected) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).background(ITantraColors.Primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = ITantraColors.OnPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Text(
                palette.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 3.dp, top = 8.dp),
            )
            Text(
                when (palette) {
                    ColorPalette.OCEAN -> "Blue & seafoam"
                    ColorPalette.FOREST -> "Pine & soft gold"
                    ColorPalette.IRIS -> "Violet & rose"
                    ColorPalette.EMBER -> "Clay & warm sage"
                },
                style = MaterialTheme.typography.bodySmall,
                color = ITantraColors.TextMuted,
                modifier = Modifier.padding(start = 3.dp, top = 2.dp, bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun TransportInfo() {
    Text("Bluetooth RFCOMM and Wi-Fi Direct TCP are available.", style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
    Text(
        "Messages are end-to-end encrypted once you confirm the verification code on both devices.",
        style = MaterialTheme.typography.bodySmall,
        color = ITantraColors.TextMuted,
    )
}

@Composable
private fun EmergencyBehaviorControls() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = ITantraColors.StatusSuccess, modifier = Modifier.size(18.dp))
            Text("Evacuation & SOS confirmation is always active", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "Demands dual-tap safety confirmation before transmitting emergency distress codes.",
            style = MaterialTheme.typography.bodySmall,
            color = ITantraColors.TextMuted,
        )
        Text(
            "Emergency audio override & volume slider: Not available in this build (plays at system emergency level).",
            style = MaterialTheme.typography.bodySmall,
            color = ITantraColors.TextMuted,
        )
        Text(
            "TTS emergency announcement: Plays automatically when pack is installed.",
            style = MaterialTheme.typography.bodySmall,
            color = ITantraColors.TextMuted,
        )
    }
}
