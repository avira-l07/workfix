package com.example.itantra.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.ITantraColors
import com.itantra.core.inference.FiveLanguageSelfTest
import kotlinx.coroutines.launch

/**
 * Diagnostics UI state — null values render "Not yet measured" per DESIGN_SPEC.md Rule 0.
 *
 * [wordErrorRatePercent] is the PRIMARY WER for the app's active language (Hindi).
 * [englishWerPercent]    is the English benchmark result (secondary reference only).
 * [hindiWerNote]         explains the metric status honestly when the active model
 *                        cannot produce valid Hindi WER (e.g. script mismatch).
 */
data class DiagnosticsUiState(
    val installedSttCount: Int = 0,
    val installedTtsCount: Int = 0,
    val installedSpeechPairCount: Int = 0,
    val catalogLanguageCount: Int = 10,
    val latestVoiceFrameBytes: Int? = null,
    val latestVoicePcmBytes: Int? = null,
    val latestVoiceReductionPercent: Double? = null,
    val activeLink: String = "Disconnected",
    val lastPacketAckMillis: Long? = null,
    // Primary Hindi metrics (app's actual use language)
    val wordErrorRatePercent: String? = null,   // Hindi WER — null when not yet measured
    val hindiWerNote: String? = null,           // Explains N/A or script-mismatch status
    // English benchmark (secondary reference)
    val englishWerPercent: String? = null,
    val characterErrorRatePercent: String? = null,
    val realTimeFactor: String? = null,
    val activeModel: String? = null,
    val latencyMs: String? = null,
    val packetLossPercent: String? = null,
    val retriesPerTx: String? = null,
    val deliveryRatioPercent: String? = null,
    val cpuPercent: String? = null,
    val jvmHeapMb: String? = null,
    val processPssMb: String? = null,
    val ramMb: String? = null,
    val batteryPercent: String? = null,
    val bluetoothStatus: String? = null,
    val wifiDirectStatus: String? = null,
    val temperatureC: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onRunFiveLanguageSelfTest: () -> Unit,
    fiveLanguageSelfTestResults: List<FiveLanguageSelfTest.Result> = emptyList(),
    fiveLanguageSelfTestRunning: Boolean = false,
    fiveLanguageSelfTestEnabled: Boolean = true,
    fiveLanguageSelfTestError: String? = null,
    onExportEvidence: () -> Unit,
    diagnosticsEnabled: Boolean = true,
) {
    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to hub",
                            tint = ITantraColors.TextHeadline
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ITantraColors.SurfaceWhite,
                    titleContentColor = ITantraColors.TextHeadline
                ),
                windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { QuickStatSummary(state) }
            item { AiBenchmarkSection(state) }
            item {
                DiagnosticsSection(title = "Ten-language desktop reference · 30 clips each") {
                    Text("Clean FLEURS speech on Windows, 2–4 Oct 2026. Phone and two-device results: Not verified.",
                        style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                    MetricRow("Hindi STT WER / CER", "9.1% / 3.9%")
                    MetricRow("English STT WER / CER", "6.9% / 3.8%")
                    MetricRow("Tamil STT WER / CER", "21.8% / 8.7%")
                    MetricRow("Telugu STT WER / CER", "22.7% / 7.1%")
                    MetricRow("Odia STT WER / CER", "19.3% / 5.1%")
                    MetricRow("Bengali STT WER / CER", "14.4% / 3.6%")
                    MetricRow("Gujarati STT WER / CER", "18.5% / 5.8%")
                    MetricRow("Marathi STT WER / CER", "15.7% / 4.6%")
                    MetricRow("Marathi STT / TTS mean", "757 / 181 ms · desktop")
                    MetricRow("Marathi Piper TTS p95 / pack", "252 ms / 94.8 MB · desktop")
                    MetricRow("Kannada STT WER / CER", "17.2% / 6.3% · 30 clips")
                    MetricRow("Kannada STT mean / p95", "440 / 1,123 ms · desktop")
                    Text("Kannada: select Kannada mic and download the dedicated CTC pack. Native script 30/30 on desktop; phone and listener validation: Not verified. Auto-detect still uses Tiny and is unreliable for Kannada.",
                        style = MaterialTheme.typography.bodySmall, color = ITantraColors.StatusWarning)
                    MetricRow("Malayalam STT WER / CER", "28.9% / 7.1% · 30 clips")
                    MetricRow("Malayalam independent test WER / CER", "22.9% / 5.3% · 100 clips")
                    MetricRow("Malayalam STT / TTS mean", "664 / 968 ms · desktop")
                    Text("Malayalam is experimental: download the new STT pack and select Malayalam mic. Desktop peak STT memory was 722–946 MB; phone memory and voice quality are Not verified. General Malayalam translation is unavailable.",
                        style = MaterialTheme.typography.bodySmall, color = ITantraColors.StatusWarning)
                    Text("Marathi: download the new Piper TTS pack. Offline phoneme probes retain the doctor vowel (ॉ); 30 desktop synthesis trials passed. Phone performance and native-listener pronunciation are Not verified. STT remains the existing dedicated model; select Marathi mic.",
                        style = MaterialTheme.typography.bodySmall, color = ITantraColors.StatusWarning)
                    MetricRow("Fine-tuned ta / te / bn / gu", "Not run; current packs active")
                    Text("Tamil, Telugu, Odia, Marathi, Kannada and Malayalam remain above the 15% WER target.",
                        style = MaterialTheme.typography.bodySmall, color = ITantraColors.StatusWarning)
                }
            }
            item {
                DiagnosticsSection(title = "Ten-language phone self-test") {
                    Text(
                        "Runs one bundled recording and speaks one phrase in each language. Listen to all ten voices. This is not a two-phone validation or a 30-utterance WER test.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onRunFiveLanguageSelfTest,
                        enabled = fiveLanguageSelfTestEnabled && !fiveLanguageSelfTestRunning,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    ) {
                        Text(if (fiveLanguageSelfTestRunning) "RUNNING SELF-TEST…"
                            else if (!fiveLanguageSelfTestEnabled) "DISCONNECT AND STOP LISTENING FIRST"
                            else "RUN TEN-LANGUAGE SELF-TEST")
                    }
                    fiveLanguageSelfTestError?.let {
                        Text("Self-test stopped: $it", style = MaterialTheme.typography.bodySmall,
                            color = ITantraColors.StatusWarning)
                    }
                    fiveLanguageSelfTestResults.forEach { result ->
                        Spacer(Modifier.height(8.dp))
                        Text(result.language.name.lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
                        Text("STT: ${result.script} · " +
                            "${result.sttDecodeMs?.let { "$it ms" } ?: "Not measured"}",
                            style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextHeadline)
                        Text("TTS: ${if (result.ttsGenerated) "PCM generated; listen to confirm" else "Failed / not verified"} · " +
                            "${result.ttsSynthesisMs?.let { "$it ms" } ?: "Not measured"}",
                            style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextHeadline)
                        result.ttsSampleRateHz?.let { MetricRow("TTS sample rate", "$it Hz") }
                        result.sampledProcessPssMb?.let { MetricRow("Sampled process PSS", "$it MB") }
                        if (result.sttText.isNotBlank()) Text("Heard: ${result.sttText}",
                            style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
                        result.sttError?.let { Text("STT: $it", style = MaterialTheme.typography.bodySmall,
                            color = ITantraColors.StatusWarning) }
                        result.ttsError?.let { Text("TTS: $it", style = MaterialTheme.typography.bodySmall,
                            color = ITantraColors.StatusWarning) }
                    }
                }
            }
            item { TranslationDiagnosticsSection() }
            item { CommunicationSection(state) }
            item { DeviceSection(state) }
            item {
                Button(
                    onClick = onRunDiagnostics,
                    enabled = diagnosticsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                ) {
                    Text(if (diagnosticsEnabled) "RUN ENGLISH STT BENCHMARK" else "STOP CONTINUOUS LISTENING FIRST")
                }
            }
            item {
                OutlinedButton(onClick = onExportEvidence, modifier = Modifier.fillMaxWidth()) {
                    Text("EXPORT FIELD TEST SUMMARY")
                }
            }
            item {
                Text(
                    "Exports saved measurement summaries only. Two-phone audible delay and human voice ratings require the field test.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ITantraColors.TextMuted,
                )
            }
        }
    }
}

@Composable
private fun QuickStatSummary(s: DiagnosticsUiState) = DiagnosticsSection(title = "Quick Stat Summary") {
    Text(
        "Your five category weights total 100%. They are priorities, not an achieved app score.",
        style = MaterialTheme.typography.bodySmall,
        color = ITantraColors.TextMuted,
    )
    Spacer(Modifier.height(8.dp))

    val frame = s.latestVoiceFrameBytes
    val pcm = s.latestVoicePcmBytes
    val reduction = s.latestVoiceReductionPercent
    QuickStatRow(
        "Low-Bitrate Voice Compression", 25,
        if (frame != null && pcm != null && reduction != null)
            "$frame B frame / $pcm B raw PCM · ${String.format(java.util.Locale.US, "%.1f", reduction)}% fewer bytes"
        else "Not measured on this device yet",
        "Same-message comparison with raw PCM; not a radio throughput test.",
    )
    QuickStatRow(
        "Offline Indic STT & TTS Pipeline", 25,
        "${s.installedSpeechPairCount}/${s.catalogLanguageCount} speech pairs installed · STT ${s.installedSttCount} · TTS ${s.installedTtsCount}",
        "10 selected speech models benchmarked on desktop. Installed files are not proof of phone accuracy or translation.",
    )
    QuickStatRow(
        "P2P Transport & Turnaround Latency", 20,
        "${s.activeLink} · last packet ACK ${s.lastPacketAckMillis?.let { "$it ms" } ?: "not measured"}",
        "Speech-end to audible remote playback: not measured.",
    )
    QuickStatRow(
        "Live Two-Device Offline Demo", 15,
        "Not verified",
        "Requires a real two-phone offline voice exchange and listener check.",
    )
    QuickStatRow(
        "Disaster Resilience & Architecture Roadmap", 15,
        "Direct peer path and SOS in app",
        "Field resilience unverified; mesh and satellite gateway are not implemented.",
    )
}

@Composable
private fun QuickStatRow(title: String, weight: Int, value: String, note: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = ITantraColors.TextHeadline,
                modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text("$weight% weight", style = MaterialTheme.typography.labelSmall, color = ITantraColors.Primary)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = ITantraColors.TextHeadline)
        Text(note, style = MaterialTheme.typography.bodySmall, color = ITantraColors.TextMuted)
    }
}

@Composable
private fun TranslationDiagnosticsSection() {
    val mlKit = com.itantra.app.AppGraph.translationEngine as? com.itantra.core.translation.MlKitOfflineTranslationEngine
    val modelState = mlKit?.modelState?.collectAsState()?.value ?: com.itantra.core.translation.TranslationModelState.NOT_INSTALLED
    val coroutineScope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }

    DiagnosticsSection(title = "Translation · Hindi ↔ English") {
        MetricRow(
            "Hindi → English",
            when (modelState) {
                com.itantra.core.translation.TranslationModelState.READY -> "READY OFFLINE (ML Kit)"
                com.itantra.core.translation.TranslationModelState.DOWNLOADING -> "DOWNLOADING..."
                com.itantra.core.translation.TranslationModelState.FAILED -> "FAILED"
                com.itantra.core.translation.TranslationModelState.NOT_INSTALLED -> "NOT PROVISIONED"
            }
        )
        MetricRow(
            "English → Hindi",
            when (modelState) {
                com.itantra.core.translation.TranslationModelState.READY -> "READY OFFLINE"
                com.itantra.core.translation.TranslationModelState.DOWNLOADING -> "DOWNLOADING..."
                com.itantra.core.translation.TranslationModelState.FAILED -> "FAILED"
                com.itantra.core.translation.TranslationModelState.NOT_INSTALLED -> "NOT PROVISIONED"
            }
        )
        if (modelState != com.itantra.core.translation.TranslationModelState.READY) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    isDownloading = true
                    coroutineScope.launch {
                        mlKit?.prepareOfflineModels()
                        isDownloading = false
                    }
                },
                enabled = !isDownloading && modelState != com.itantra.core.translation.TranslationModelState.DOWNLOADING,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary)
            ) {
                Text(
                    if (isDownloading || modelState == com.itantra.core.translation.TranslationModelState.DOWNLOADING)
                        "DOWNLOADING EN→HI MODEL..."
                    else
                        "PROVISION EN→HI OFFLINE MODEL (WHILE ONLINE)"
                )
            }
        }
    }
}

@Composable
private fun AiBenchmarkSection(s: DiagnosticsUiState) = DiagnosticsSection(title = "AI \u00b7 Speech Models") {
    // Hindi WER is the primary metric — this app's primary language is Hindi
    MetricRow("Hindi WER (primary)", s.wordErrorRatePercent?.let { "$it%" })
    if (s.hindiWerNote != null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            Text(
                s.hindiWerNote,
                style = MaterialTheme.typography.labelSmall,
                color = ITantraColors.TextMuted,
            )
        }
    }
    // English benchmark (secondary — shown for reference only)
    MetricRow("English WER (ref only)", s.englishWerPercent?.let { "$it%" })
    MetricRow("Character Error Rate", s.characterErrorRatePercent?.let { "$it%" })
    MetricRow("Real-Time Factor", s.realTimeFactor)
    MetricRow("Model / latest benchmark", s.activeModel)
    MetricRow("STT finalization", s.latencyMs?.let { "$it ms" })
}

@Composable
private fun CommunicationSection(s: DiagnosticsUiState) = DiagnosticsSection(title = "Communication · Link Health") {
    MetricRow("Packet Loss", s.packetLossPercent?.let { "$it%" })
    MetricRow("Retries / TX", s.retriesPerTx)
    MetricRow("Delivery Ratio", s.deliveryRatioPercent?.let { "$it%" })
}

@Composable
private fun DeviceSection(s: DiagnosticsUiState) = DiagnosticsSection(title = "Device · Resource Use") {
    MetricRow("CPU", s.cpuPercent?.let { "$it%" })
    MetricRow("JVM Heap Used", (s.jvmHeapMb ?: s.ramMb)?.let { "$it MB" })
    s.processPssMb?.let {
        MetricRow("Process PSS", "$it MB")
    }
    MetricRow("Battery", s.batteryPercent?.let { "$it%" })
    MetricRow("Bluetooth hardware", s.bluetoothStatus)
    MetricRow("Wi-Fi Direct hardware", s.wifiDirectStatus)
    MetricRow("Temperature", s.temperatureC?.let { "$it °C" })
}

@Composable
private fun DiagnosticsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = ITantraColors.TextHeadline)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = ITantraColors.TextMuted,
            modifier = Modifier.padding(end = 16.dp)
        )
        Text(
            value ?: "Not yet measured",
            style = MaterialTheme.typography.bodyMedium,
            color = if (value != null) ITantraColors.TextHeadline else ITantraColors.TextMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1f, fill = false)
        )
    }
}
