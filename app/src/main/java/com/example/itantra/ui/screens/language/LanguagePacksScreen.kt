package com.example.itantra.ui.screens.language

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.itantra.ui.theme.ITantraColors
import com.itantra.app.AppGraph
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.LanguagePackAvailability
import com.itantra.domain.model.LanguagePackInstallState
import com.itantra.domain.repository.LanguagePackRepository
import kotlinx.coroutines.launch

enum class PackStatus { READY, ACTIVE, DOWNLOADING, UPDATE_AVAILABLE, AVAILABLE }

data class LanguagePack(
    val code: String,
    val nameEn: String,
    val nameNative: String,
    val version: String,
    val sizeMb: Int,
    val sttMb: Int,
    val ttsMb: Int,
    val region: String,
    val status: PackStatus,
    val downloadProgress: Float = 0f,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePacksScreen(
    repository: LanguagePackRepository = remember { AppGraph.languagePackRepository },
    sessionManager: ActiveLanguageSessionManager = remember { AppGraph.activeLanguageSessionManager },
    onBack: () -> Unit,
    onDownloadPack: (LanguagePack) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val packSummaries by repository.observePackSummaries().collectAsState(initial = emptyList())
    val activeLangCode by repository.observeActiveLanguage().collectAsState(initial = null)

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }

    val packs = packSummaries.map { summary ->
        val sttMb = ((summary.sttSizeBytes ?: (40L * 1024 * 1024)) / (1024 * 1024)).toInt()
        val ttsMb = ((summary.ttsSizeBytes ?: (45L * 1024 * 1024)) / (1024 * 1024)).toInt()
        val status = when {
            summary.language.code == activeLangCode || summary.availability == LanguagePackAvailability.ACTIVE -> PackStatus.ACTIVE
            summary.sttInstallState == LanguagePackInstallState.DOWNLOADING || summary.ttsInstallState == LanguagePackInstallState.DOWNLOADING -> PackStatus.DOWNLOADING
            summary.sttInstallState == LanguagePackInstallState.UPDATE_AVAILABLE || summary.ttsInstallState == LanguagePackInstallState.UPDATE_AVAILABLE -> PackStatus.UPDATE_AVAILABLE
            summary.isDownloaded -> PackStatus.READY
            else -> PackStatus.AVAILABLE
        }
        LanguagePack(
            code = summary.language.code.name.lowercase(),
            nameEn = summary.language.displayName,
            nameNative = summary.language.nativeDisplayName,
            version = "v1.0.0",
            sizeMb = sttMb + ttsMb,
            sttMb = sttMb,
            ttsMb = ttsMb,
            region = when (summary.language.code) {
                LanguageCode.HINDI, LanguageCode.ENGLISH -> "NORTH REGION"
                LanguageCode.MARATHI, LanguageCode.GUJARATI -> "WEST REGION"
                LanguageCode.BENGALI, LanguageCode.ODIA -> "EAST REGION"
                LanguageCode.TAMIL, LanguageCode.TELUGU, LanguageCode.KANNADA, LanguageCode.MALAYALAM -> "SOUTH REGION"
            },
            status = status,
            downloadProgress = (summary.downloadProgressPercent ?: 0) / 100f
        )
    }

    val filteredPacks = packs.filter { pack ->
        (searchQuery.isEmpty() || pack.nameEn.contains(searchQuery, ignoreCase = true) || pack.nameNative.contains(searchQuery)) &&
                when (selectedFilter) {
                    "Installed" -> pack.status == PackStatus.READY || pack.status == PackStatus.ACTIVE || pack.status == PackStatus.UPDATE_AVAILABLE
                    "Available" -> pack.status == PackStatus.AVAILABLE
                    else -> true
                }
    }

    Scaffold(
        containerColor = ITantraColors.CanvasBg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Language Packs", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "On-device speech models",
                            style = MaterialTheme.typography.bodySmall,
                            color = ITantraColors.TextMuted,
                        )
                    }
                },
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
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StorageOverviewCard(packs = packs)
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ITantraColors.AccentSubtle, RoundedCornerShape(10.dp))
                        .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "SCOPE NOTICE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ITantraColors.Primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "English is the tested baseline for this build (24 acoustic benchmark WAVs). " +
                            "The other 9 Indian languages run on the same shared multilingual Whisper-tiny " +
                            "model but have no dedicated offline audio benchmark set yet — treat their " +
                            "recognition accuracy as unverified until measured.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ITantraColors.TextMuted,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter by language or region...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = ITantraColors.TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Installed", "Available").forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ITantraColors.Primary,
                                selectedLabelColor = ITantraColors.SurfaceWhite,
                            ),
                        )
                    }
                }
            }

            if (filteredPacks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (packs.isEmpty()) "Loading language catalog..." else "No language packs match filter",
                            style = MaterialTheme.typography.bodyMedium,
                            color = ITantraColors.TextMuted
                        )
                    }
                }
            } else {
                items(filteredPacks, key = { it.code }) { pack ->
                    LanguagePackCard(
                        pack = pack,
                        onActivate = {
                            val langCode = LanguageCode.entries.find { it.name.equals(pack.code, ignoreCase = true) }
                            if (langCode != null) {
                                coroutineScope.launch {
                                    repository.setActiveLanguage(langCode)
                                    try {
                                        val shouldLoadTts = AppGraph.languagePackStorage.isTtsInstalled(langCode)
                                        sessionManager.switchTo(langCode, loadStt = true, loadTts = shouldLoadTts)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        },
                        onDownload = {
                            val langCode = LanguageCode.entries.find { it.name.equals(pack.code, ignoreCase = true) }
                            if (langCode != null) {
                                coroutineScope.launch {
                                    repository.setActiveLanguage(langCode)
                                    try {
                                        val shouldLoadTts = AppGraph.languagePackStorage.isTtsInstalled(langCode)
                                        sessionManager.switchTo(langCode, loadStt = true, loadTts = shouldLoadTts)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                            onDownloadPack(pack)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageOverviewCard(packs: List<LanguagePack>) {
    val context = LocalContext.current

    val (freeGbStr, totalGbStr, totalBytes) = remember(context) {
        try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            val available = stat.availableBytes
            val total = stat.totalBytes
            val freeGb = String.format(java.util.Locale.US, "%.1f GB", available / (1024.0 * 1024.0 * 1024.0))
            val totalGb = String.format(java.util.Locale.US, "%.1f GB", total / (1024.0 * 1024.0 * 1024.0))
            Triple(freeGb, totalGb, total)
        } catch (e: Exception) {
            Triple("--", "--", 0L)
        }
    }

    val activePack = packs.find { it.status == PackStatus.ACTIVE }
    val standbyPacks = packs.filter { it.status == PackStatus.READY }
    val activeMb = activePack?.sizeMb ?: 0
    val standbyMb = standbyPacks.sumOf { it.sizeMb }
    val totalInstalledMb = activeMb + standbyMb
    val installedCount = (if (activePack != null) 1 else 0) + standbyPacks.size

    val activeFraction = if (totalBytes > 0) (activeMb.toFloat() * 1024 * 1024 / totalBytes).coerceIn(0.01f, 0.4f) else 0.05f
    val standbyFraction = if (totalBytes > 0) (standbyMb.toFloat() * 1024 * 1024 / totalBytes).coerceIn(0f, 0.4f) else 0.05f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ITantraColors.SurfaceWhite, RoundedCornerShape(12.dp))
            .border(1.dp, ITantraColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storage, contentDescription = null, tint = ITantraColors.Primary)
                Spacer(Modifier.width(8.dp))
                Text("Offline Language Models", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            Text("AI4Bharat · ON-DEVICE", style = MaterialTheme.typography.labelSmall, color = ITantraColors.Primary, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("TOTAL MODEL STORAGE", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = ITantraColors.TextMuted)
            Text(
                "${if (totalInstalledMb >= 1000) String.format(java.util.Locale.US, "%.2f GB", totalInstalledMb / 1024f) else "$totalInstalledMb MB"} / $freeGbStr Available",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = ITantraColors.TextHeadline
            )
        }
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Color(0xFFE2E8F0), RoundedCornerShape(4.dp))
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (activeFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(activeFraction)
                            .background(ITantraColors.Primary, RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp))
                    )
                }
                if (standbyFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(standbyFraction)
                            .background(Color(0xFF10B981))
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(ITantraColors.Primary, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("Active Core", fontSize = 11.sp, color = ITantraColors.TextMuted)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(8.dp).background(Color(0xFF10B981), androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(4.dp))
                Text("Standby Packs", fontSize = 11.sp, color = ITantraColors.TextMuted)
            }
            Text(
                "Installed: $installedCount ${if (installedCount == 1) "pack" else "packs"}",
                fontSize = 11.sp,
                color = ITantraColors.TextHeadline,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LanguagePackCard(pack: LanguagePack, onActivate: () -> Unit = {}, onDownload: () -> Unit = {}) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (pack.status == PackStatus.READY) Modifier.clickable { onActivate() }
                else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (pack.status == PackStatus.ACTIVE) ITantraColors.Primary else ITantraColors.BorderSubtle,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pack.nameEn, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(pack.nameNative, style = MaterialTheme.typography.titleMedium, color = ITantraColors.Primary)
                }

                when (pack.status) {
                    PackStatus.ACTIVE -> StatusBadge("ACTIVE CORE", ITantraColors.Primary, ITantraColors.SurfaceWhite)
                    PackStatus.READY -> StatusBadge("READY", ITantraColors.StatusSuccess, ITantraColors.SurfaceWhite)
                    PackStatus.UPDATE_AVAILABLE -> StatusBadge("UPDATE", ITantraColors.StatusWarning, ITantraColors.SurfaceWhite)
                    PackStatus.DOWNLOADING -> StatusBadge("DOWNLOADING", ITantraColors.Primary, ITantraColors.SurfaceWhite)
                    PackStatus.AVAILABLE -> StatusBadge("AVAILABLE", ITantraColors.TextMuted, ITantraColors.SurfaceWhite)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "${pack.version} • ${pack.sizeMb} MB (${pack.sttMb}MB STT / ${pack.ttsMb}MB TTS) • ${pack.region}",
                style = MaterialTheme.typography.bodySmall,
                color = ITantraColors.TextMuted,
            )

            if (pack.status == PackStatus.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { pack.downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = ITantraColors.Primary,
                )
            }

            if (pack.status == PackStatus.READY) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("USE THIS LANGUAGE (ACTIVATE STT)")
                }
            } else if (pack.status == PackStatus.ACTIVE) {
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = ITantraColors.Primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("CURRENTLY ACTIVE SPEECH MODEL", style = MaterialTheme.typography.labelMedium, color = ITantraColors.Primary, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (pack.status == PackStatus.AVAILABLE || pack.status == PackStatus.UPDATE_AVAILABLE) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(
                        if (pack.status == PackStatus.UPDATE_AVAILABLE) Icons.Filled.Update else Icons.Filled.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (pack.status == PackStatus.UPDATE_AVAILABLE) "UPDATE MODEL (${pack.sizeMb} MB)" else "DOWNLOAD PACK (${pack.sizeMb} MB)")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, bgColor: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    Text(
        text = text,
        color = textColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
