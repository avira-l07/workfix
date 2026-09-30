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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.Warning
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
import com.itantra.core.translation.TranslationModelState
import com.itantra.domain.model.LanguageCatalog
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.LanguagePackAvailability
import com.itantra.domain.model.LanguagePackInstallState
import com.itantra.domain.repository.LanguagePackRepository
import com.itantra.feature.languages.LanguagePacksViewModel
import kotlinx.coroutines.launch

enum class ReadinessBadgeState {
    READY_OFFLINE,
    NOT_PROVISIONED,
    DOWNLOADING,
    FAILED,
}

data class LanguagePackItem(
    val code: String,
    val languageCode: LanguageCode,
    val nameEn: String,
    val nameNative: String,
    val version: String,
    val sizeMb: Int,
    val sttMb: Int,
    val ttsMb: Int,
    val region: String,
    val readiness: ReadinessBadgeState,
    val downloadProgress: Float = 0f,
    val isTarget: Boolean = false,
    val isActiveCore: Boolean = false,
    val isSttReady: Boolean = false,
    val isTtsReady: Boolean = false,
    val isTranslationReady: Boolean = false,
    val isMlOrOr: Boolean = false,
    val isSpeakSelected: Boolean = false,
    val isListenSelected: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePacksScreen(
    repository: LanguagePackRepository = remember { AppGraph.languagePackRepository },
    sessionManager: ActiveLanguageSessionManager = remember { AppGraph.activeLanguageSessionManager },
    viewModel: LanguagePacksViewModel = remember {
        LanguagePacksViewModel(
            repository = repository,
            sessionManager = sessionManager,
            storage = AppGraph.languagePackStorage,
            translationEngine = AppGraph.translationEngine,
        )
    },
    onBack: () -> Unit,
    onDownloadPack: (LanguagePackItem) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    val packSummaries by repository.observePackSummaries().collectAsState(initial = emptyList())
    val activeLangCode by repository.observeActiveLanguage().collectAsState(initial = null)
    val targetLangCode by repository.observeTargetLanguage().collectAsState(initial = null)
    val micLanguage by sessionManager.activeSttLanguage.collectAsState()
    val micAutoDetect by sessionManager.isSttAutoDetect.collectAsState()
    val sessionState by sessionManager.sessionState.collectAsState()

    val enabledMicLangs by viewModel.enabledMicLanguages.collectAsState()
    val enabledListenLangs by viewModel.enabledListenLanguages.collectAsState()
    val stagedMicLangs by viewModel.stagedMicLanguages.collectAsState()
    val stagedListenLangs by viewModel.stagedListenLanguages.collectAsState()
    val translationStates by viewModel.translationStates.collectAsState()

    val effectiveMicLangs = stagedMicLangs ?: enabledMicLangs
    val effectiveListenLangs = stagedListenLangs ?: enabledListenLangs
    val hasStagedChanges = (stagedMicLangs != null && stagedMicLangs != enabledMicLangs) ||
            (stagedListenLangs != null && stagedListenLangs != enabledListenLangs)

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }

    val packs = packSummaries.map { summary ->
        val langCode = summary.language.code
        val isMlOrOr = (langCode == LanguageCode.MALAYALAM || langCode == LanguageCode.ODIA)
        val sttMb = ((summary.sttSizeBytes ?: (40L * 1024 * 1024)) / (1024 * 1024)).toInt()
        val ttsMb = ((summary.ttsSizeBytes ?: (45L * 1024 * 1024)) / (1024 * 1024)).toInt()
        val isSttReady = summary.isSttDownloaded &&
            langCode in com.itantra.core.inference.ModelFileSpecs.supportedSttLanguages
        val isTtsReady = summary.isTtsDownloaded

        val pairMtState = translationStates[langCode]
        val isTranslationReady = isOfflineTranslationReady(langCode, translationStates)

        val isDownloading = summary.sttInstallState == LanguagePackInstallState.DOWNLOADING ||
                summary.ttsInstallState == LanguagePackInstallState.DOWNLOADING ||
                pairMtState == TranslationModelState.DOWNLOADING

        val isFailed = summary.sttInstallState == LanguagePackInstallState.ERROR ||
                summary.sttInstallState == LanguagePackInstallState.CORRUPTED ||
                summary.ttsInstallState == LanguagePackInstallState.ERROR ||
                summary.ttsInstallState == LanguagePackInstallState.CORRUPTED ||
                pairMtState == TranslationModelState.FAILED

        val readiness = when {
            isDownloading -> ReadinessBadgeState.DOWNLOADING
            isFailed -> ReadinessBadgeState.FAILED
            isSttReady && isTtsReady -> ReadinessBadgeState.READY_OFFLINE
            else -> ReadinessBadgeState.NOT_PROVISIONED
        }

        LanguagePackItem(
            code = langCode.name.lowercase(),
            languageCode = langCode,
            nameEn = summary.language.displayName,
            nameNative = summary.language.nativeDisplayName,
            version = if (langCode == LanguageCode.HINDI) "Hindi Small v1" else "v1.0.0",
            sizeMb = sttMb + ttsMb,
            sttMb = sttMb,
            ttsMb = ttsMb,
            region = when (langCode) {
                LanguageCode.HINDI, LanguageCode.ENGLISH -> "NORTH REGION"
                LanguageCode.MARATHI, LanguageCode.GUJARATI -> "WEST REGION"
                LanguageCode.BENGALI, LanguageCode.ODIA -> "EAST REGION"
                LanguageCode.TAMIL, LanguageCode.TELUGU, LanguageCode.KANNADA, LanguageCode.MALAYALAM -> "SOUTH REGION"
            },
            readiness = readiness,
            downloadProgress = (summary.downloadProgressPercent ?: 0) / 100f,
            isTarget = (langCode == targetLangCode),
            isActiveCore = !micAutoDetect && langCode == micLanguage &&
                sessionState == com.itantra.core.inference.LanguageSessionState.READY,
            isSttReady = isSttReady,
            isTtsReady = isTtsReady,
            isTranslationReady = isTranslationReady,
            isMlOrOr = isMlOrOr,
            isSpeakSelected = effectiveMicLangs.contains(langCode),
            isListenSelected = effectiveListenLangs.contains(langCode),
        )
    }

    val filteredPacks = packs.filter { pack ->
        (searchQuery.isEmpty() || pack.nameEn.contains(searchQuery, ignoreCase = true) || pack.nameNative.contains(searchQuery)) &&
                when (selectedFilter) {
                    "Installed" -> pack.readiness == ReadinessBadgeState.READY_OFFLINE || pack.isActiveCore
                    "Available" -> pack.readiness != ReadinessBadgeState.READY_OFFLINE
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
                            "On-device speech models & translation",
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
            // Staged Selection / Apply & Provision Banner
            if (hasStagedChanges) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = ITantraColors.Primary.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(1.5.dp, ITantraColors.Primary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "STAGED CONFIGURATION",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = ITantraColors.Primary
                                    )
                                    Text(
                                        "Speaking: ${effectiveMicLangs.size} • Receiving: ${effectiveListenLangs.size}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = ITantraColors.TextMuted
                                    )
                                }
                                TextButton(onClick = { viewModel.resetStagedLanguages() }) {
                                    Text("DISCARD", style = MaterialTheme.typography.labelSmall, color = ITantraColors.StatusDanger)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { viewModel.applyAndProvisionSelected() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "APPLY & PROVISION SELECTED LANGUAGES",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            // Storage Overview
            item {
                StorageOverviewCard(packs = packs)
            }

            // Malayalam & Odia Disclosure Banner
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFFFFF8E1),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD54F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(20.dp).padding(top = 1.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "MALAYALAM & ODIA NOTICE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Malayalam: transcription only. Odia: speech recognition unavailable.",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFFE65100),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Offline translation is unavailable for Malayalam and Odia. The bundled Whisper model supports Malayalam transcription but does not support Odia speech recognition. TTS readiness is shown separately.",
                                style = MaterialTheme.typography.bodySmall,
                                color = ITantraColors.TextHeadline.copy(alpha = 0.85f),
                            )
                        }
                    }
                }
            }

            // Active / Target Language Summary Card
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = ITantraColors.SurfaceWhite,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ITantraColors.BorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("ACTIVE MIC ENGINE", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted, fontWeight = FontWeight.Bold)
                                Text(
                                    when (sessionState) {
                                        com.itantra.core.inference.LanguageSessionState.LOADING_STT -> "Loading microphone model…"
                                        com.itantra.core.inference.LanguageSessionState.ERROR -> "Microphone model unavailable"
                                        else -> if (micAutoDetect && micLanguage != null) "Auto-detect" else
                                            micLanguage?.let { LanguageCatalog.byCode(it).displayName } ?: "None"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ITantraColors.Primary
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = ITantraColors.TextMuted, modifier = Modifier.size(16.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text("TRANSLATE TO", style = MaterialTheme.typography.labelSmall, color = ITantraColors.TextMuted, fontWeight = FontWeight.Bold)
                                Text(
                                    targetLangCode?.let { LanguageCatalog.byCode(it).displayName } ?: "Auto / Peer Default",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (targetLangCode != null) ITantraColors.Primary else ITantraColors.TextHeadline
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider(color = ITantraColors.BorderSubtle, thickness = 0.5.dp)
                        if (!micAutoDetect && micLanguage != null && micLanguage == targetLangCode) {
                            Text(
                                "Microphone and target are the same: speech is transcribed without translation. Choose English as the target for Hindi → English.",
                                modifier = Modifier.padding(vertical = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = ITantraColors.TextBody
                            )
                        }
                        Spacer(Modifier.height(8.dp))

                        Text("SPEAKING (MIC) SELECTED:", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = ITantraColors.TextMuted, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            effectiveMicLangs.forEach { code ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = ITantraColors.Primary.copy(alpha = 0.1f),
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, ITantraColors.Primary.copy(alpha = 0.3f))
                                ) {
                                    Text(
                                        LanguageCatalog.byCode(code).displayName,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = ITantraColors.Primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text("RECEIVING (TTS) SELECTED:", style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, color = ITantraColors.TextMuted, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            effectiveListenLangs.forEach { code ->
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFFE8F5E9),
                                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF81C784))
                                ) {
                                    Text(
                                        LanguageCatalog.byCode(code).displayName,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Search query field
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

            // Filter chips
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
                        onToggleMic = { viewModel.toggleStagedMicLanguage(pack.languageCode) },
                        onToggleListen = { viewModel.toggleStagedListenLanguage(pack.languageCode) },
                        onActivate = { viewModel.activateLanguage(pack.languageCode) },
                        onDownload = {
                            viewModel.downloadPack(pack.languageCode)
                            onDownloadPack(pack)
                        },
                        onRetry = { viewModel.retryProvision(pack.languageCode) },
                        onCancelDownload = { viewModel.cancelDownload(pack.languageCode) },
                        onSetTarget = { viewModel.setTargetLanguage(pack.languageCode) },
                        onClearTarget = { viewModel.setTargetLanguage(null) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageOverviewCard(packs: List<LanguagePackItem>) {
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

    val activePack = packs.find { it.isActiveCore }
    val standbyPacks = packs.filter { it.readiness == ReadinessBadgeState.READY_OFFLINE && !it.isActiveCore }
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
private fun LanguagePackCard(
    pack: LanguagePackItem,
    onToggleMic: () -> Unit = {},
    onToggleListen: () -> Unit = {},
    onActivate: () -> Unit = {},
    onDownload: () -> Unit = {},
    onRetry: () -> Unit = {},
    onCancelDownload: () -> Unit = {},
    onSetTarget: () -> Unit = {},
    onClearTarget: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ITantraColors.SurfaceWhite),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (pack.isActiveCore) ITantraColors.Primary else ITantraColors.BorderSubtle,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row: Names + Diagnostics Status Badge
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

                DiagnosticsBadge(pack.readiness, pack.downloadProgress)
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "${pack.version} • ${pack.sizeMb} MB (${pack.sttMb}MB STT / ${pack.ttsMb}MB TTS) • ${pack.region}",
                style = MaterialTheme.typography.bodySmall,
                color = ITantraColors.TextMuted,
            )

            // Malayalam / Odia Warning Disclosure Banner on Card
            if (pack.isMlOrOr) {
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFFFF8E1),
                    border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFFFFD54F)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFE65100),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (pack.languageCode == LanguageCode.ODIA)
                                "Speech recognition and translation unavailable; TTS is separate"
                            else "Voice transcript only — translation not available for this language",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Staged Selective Role Toggles: Speak (Mic) & Receive (TTS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = pack.isSpeakSelected,
                    onClick = onToggleMic,
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (pack.isSpeakSelected) ITantraColors.SurfaceWhite else ITantraColors.TextHeadline
                        )
                    },
                    label = {
                        Text(
                            "Speak (Mic)",
                            fontWeight = if (pack.isSpeakSelected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = ITantraColors.Primary,
                        selectedLabelColor = ITantraColors.SurfaceWhite,
                    )
                )

                FilterChip(
                    selected = pack.isListenSelected,
                    onClick = onToggleListen,
                    modifier = Modifier.weight(1f),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (pack.isListenSelected) ITantraColors.SurfaceWhite else ITantraColors.TextHeadline
                        )
                    },
                    label = {
                        Text(
                            "Receive (TTS)",
                            fontWeight = if (pack.isListenSelected) FontWeight.Bold else FontWeight.Normal,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFF2E7D32),
                        selectedLabelColor = ITantraColors.SurfaceWhite,
                    )
                )
            }

            // Component Readiness Indicators (STT, TTS, MT)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ComponentPill(label = "STT", isReady = pack.isSttReady)
                ComponentPill(label = "TTS", isReady = pack.isTtsReady)
                if (pack.isMlOrOr) {
                    ComponentPill(label = "MT: BYPASS", isReady = true, isMuted = true)
                } else {
                    ComponentPill(label = "MT", isReady = pack.isTranslationReady)
                }
            }

            // Progress / Download / Active Actions
            if (pack.readiness == ReadinessBadgeState.DOWNLOADING) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { pack.downloadProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = ITantraColors.Primary,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancelDownload,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 6.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("CANCEL DOWNLOAD", style = MaterialTheme.typography.labelMedium)
                }
            } else if (pack.readiness == ReadinessBadgeState.FAILED) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.StatusDanger),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("RETRY PROVISION (${pack.sizeMb} MB)")
                }
            } else if (pack.readiness == ReadinessBadgeState.NOT_PROVISIONED) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("PREPARE MISSING MODELS")
                }
            }

            if (pack.isActiveCore) {
                Spacer(Modifier.height(8.dp))
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
                        Text("CURRENT ACTIVE MIC ENGINE", style = MaterialTheme.typography.labelMedium, color = ITantraColors.Primary, fontWeight = FontWeight.Bold)
                    }
                }
            } else if (pack.isSttReady) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onActivate,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = ITantraColors.Primary),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("SET AS ACTIVE MIC ENGINE")
                }
            }

            // Target Language / Translation Outgoing Selector
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pack.isTarget) {
                    Surface(
                        color = ITantraColors.Primary.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f).padding(end = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Translate, contentDescription = null, tint = ITantraColors.Primary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("TARGET LANGUAGE", style = MaterialTheme.typography.labelSmall, color = ITantraColors.Primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    OutlinedButton(
                        onClick = onClearTarget,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("RESET TO AUTO", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    OutlinedButton(
                        onClick = onSetTarget,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.fillMaxWidth().height(32.dp)
                    ) {
                        Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("SET AS TRANSLATION TARGET", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticsBadge(status: ReadinessBadgeState, progress: Float) {
    val (label, bgColor, textColor, borderColor) = when (status) {
        ReadinessBadgeState.READY_OFFLINE -> Quad(
            "READY OFFLINE",
            Color(0xFFE8F5E9),
            Color(0xFF2E7D32),
            Color(0xFF81C784)
        )
        ReadinessBadgeState.NOT_PROVISIONED -> Quad(
            "NOT PROVISIONED",
            Color(0xFFF5F5F5),
            Color(0xFF757575),
            Color(0xFFE0E0E0)
        )
        ReadinessBadgeState.DOWNLOADING -> Quad(
            if (progress > 0f) "DOWNLOADING ${(progress * 100).toInt()}%" else "DOWNLOADING...",
            Color(0xFFE3F2FD),
            Color(0xFF1565C0),
            Color(0xFF90CAF9)
        )
        ReadinessBadgeState.FAILED -> Quad(
            "FAILED (RETRY)",
            Color(0xFFFFEBEE),
            Color(0xFFC62828),
            Color(0xFFEF9A9A)
        )
    }

    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, borderColor)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

@Composable
private fun ComponentPill(label: String, isReady: Boolean, isMuted: Boolean = false) {
    val (bgColor, borderColor, textColor) = when {
        isMuted -> Triple(Color(0xFFFFF8E1), Color(0xFFFFD54F), Color(0xFFE65100))
        isReady -> Triple(Color(0xFFE8F5E9), Color(0xFF81C784), Color(0xFF2E7D32))
        else -> Triple(Color(0xFFF5F5F5), Color(0xFFE0E0E0), ITantraColors.TextMuted)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, borderColor)
    ) {
        Text(
            text = if (isMuted) label else if (isReady) "$label READY" else "$label MISSING",
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = textColor
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
