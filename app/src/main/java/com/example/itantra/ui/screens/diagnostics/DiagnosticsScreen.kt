package com.example.itantra.ui.screens.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.itantra.ui.theme.ITantraColors

/**
 * Diagnostics UI state — null values render "Not yet measured" per DESIGN_SPEC.md Rule 0.
 *
 * [wordErrorRatePercent] is the PRIMARY WER for the app's active language (Hindi).
 * [englishWerPercent]    is the English benchmark result (secondary reference only).
 * [hindiWerNote]         explains the metric status honestly when the active model
 *                        cannot produce valid Hindi WER (e.g. script mismatch).
 */
data class DiagnosticsUiState(
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
    val temperatureC: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit,
    onRunDiagnostics: () -> Unit,
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
            item { AiBenchmarkSection(state) }
            item { CommunicationSection(state) }
            item { DeviceSection(state) }
            item {
                Button(
                    onClick = onRunDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                ) {
                    Text("RUN COMPREHENSIVE ON-DEVICE DIAGNOSTICS")
                }
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
    MetricRow("Active Model", s.activeModel)
}

@Composable
private fun CommunicationSection(s: DiagnosticsUiState) = DiagnosticsSection(title = "Communication · Link Health") {
    MetricRow("Latency", s.latencyMs?.let { "$it ms" })
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
