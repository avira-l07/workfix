package com.example.itantra

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.example.itantra.data.settings.SettingsRepository
import com.example.itantra.data.settings.settingsDataStore
import com.example.itantra.ui.screens.chat.DedicatedChatScreen
import com.example.itantra.ui.screens.connect.ConnectScreenContent
import com.example.itantra.ui.screens.connect.PeerConnectionState
import com.example.itantra.ui.screens.connect.PeerDevice
import com.example.itantra.ui.screens.diagnostics.DiagnosticsScreen
import com.example.itantra.ui.screens.diagnostics.DiagnosticsUiState
import com.example.itantra.ui.screens.hub.TransceiverHubScreen
import com.example.itantra.ui.screens.language.LanguagePacksScreen
import com.example.itantra.ui.screens.settings.SettingsScreen
import com.example.itantra.ui.screens.settings.SettingsViewModel
import com.example.itantra.ui.theme.*
import com.itantra.app.AppGraph
import com.itantra.core.audio.WavWriter
import com.itantra.core.inference.SherpaOnnxSpeechRecognizer
import com.itantra.core.metrics.TextNormalizer
import com.itantra.core.metrics.WordErrorRateCalculator
import com.itantra.core.transport.ConnectionState
import com.itantra.domain.model.*
import com.itantra.feature.benchmark.BenchmarkSentenceDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

enum class AppDestination {
    HUB,
    SETTINGS,
    CONNECT,
    LANGUAGE_PACKS,
    DIAGNOSTICS,
    CHAT
}

class MainActivity : ComponentActivity() {

    private var permissionsGranted by mutableStateOf(false)
    val destinationState = mutableStateOf(AppDestination.HUB)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    private var onWifiDirectPermissionCallback: ((Boolean) -> Unit)? = null

    private val wifiDirectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        onWifiDirectPermissionCallback?.invoke(isGranted)
        onWifiDirectPermissionCallback = null
    }

    fun requestWifiDirectPermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                onResult(true)
            } else {
                onWifiDirectPermissionCallback = onResult
                wifiDirectPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            val hasPerm = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                onResult(true)
            } else {
                onWifiDirectPermissionCallback = onResult
                wifiDirectPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private var debugTestReceiver: android.content.BroadcastReceiver? = null

    override fun onDestroy() {
        debugTestReceiver?.let {
            try { unregisterReceiver(it) } catch (e: IllegalArgumentException) { }
        }
        debugTestReceiver = null
        // Unregister bond receiver to avoid leaks.
        AppGraph.bluetoothPeerTransport.unregisterBondReceiver(this)
        // Unregister Wi-Fi Direct receiver to avoid leaks.
        AppGraph.wifiDirectConnectionManager.unregisterReceiver(this)
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.itantra.app.AppGraph.init(this)
        enableEdgeToEdge()

        requestRequiredPermissions()

        val testPttReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
                val switchLang = intent?.getStringExtra("switch_lang")
                if (switchLang != null) {
                    val code = LanguageCode.fromWireCode(switchLang)
                    if (code != null) {
                        com.itantra.app.AppGraph.switchActiveLanguage(code)
                        android.util.Log.i("TEST_LANG", "Switched active language to $code")
                    }
                    return
                }

                val opName = intent?.getStringExtra("operator_name")
                if (opName != null) {
                    lifecycleScope.launch {
                        com.itantra.app.AppGraph.settingsRepository.update { it.copy(operatorName = opName) }
                    }
                }

                val testTtsText = intent?.getStringExtra("test_tts_text")
                if (testTtsText != null) {
                    val langStr = intent?.getStringExtra("test_tts_lang")
                    val targetLang = (if (langStr != null) LanguageCode.fromWireCode(langStr) else null)
                        ?: com.itantra.app.AppGraph.activeLanguageSessionManager.activeLanguage.value
                        ?: LanguageCode.ENGLISH

                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            android.util.Log.i("TEST_TTS", "=== INITIATING TTS TEST: lang=$targetLang, text='$testTtsText' ===")
                            val sessionManager = com.itantra.app.AppGraph.activeLanguageSessionManager
                            val storage = com.itantra.app.AppGraph.languagePackStorage
                            val isInstalled = storage.isTtsInstalled(targetLang)
                            android.util.Log.i("TEST_TTS", "TTS installed for $targetLang: $isInstalled")

                            if (sessionManager.activeLanguage.value != targetLang || sessionManager.currentTtsEngine == null || !sessionManager.currentTtsEngine!!.isLoaded) {
                                sessionManager.switchTo(targetLang, loadStt = true, loadTts = isInstalled)
                            }

                            val packet = com.itantra.core.transport.packet.ItantraPacket(
                                type = com.itantra.core.transport.packet.PacketType.TEXT,
                                flags = com.itantra.domain.model.MessagePriority.NORMAL.toByte(),
                                messageId = System.currentTimeMillis(),
                                languageCode = targetLang,
                                targetLanguage = targetLang,
                                payload = testTtsText.toByteArray(Charsets.UTF_8)
                            )
                            com.itantra.app.AppGraph.transceiverCoordinator.enqueueMessagePacketForPlayback(packet)
                        } catch (e: Exception) {
                            android.util.Log.e("TEST_TTS", "Failed to enqueue TTS test", e)
                        }
                    }
                    return
                }

                val importPacksDir = intent?.getStringExtra("import_packs_dir")
                if (importPacksDir != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val src = java.io.File(importPacksDir)
                        val ok = com.itantra.app.AppGraph.languagePackStorage.importFromDirectory(src)
                        com.itantra.app.AppGraph.languagePackRepository.refreshStates()
                        android.util.Log.i("IMPORT_PACKS", "Import from $importPacksDir result: $ok")
                    }
                    return
                }
                val testPreemption = intent?.getBooleanExtra("test_preemption", false) ?: false
                if (testPreemption) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            android.util.Log.i("TEST_PREEMPTION", "=== STARTING PREEMPTION TEST ===")
                            val sessionManager = com.itantra.app.AppGraph.activeLanguageSessionManager
                            val lang = sessionManager.activeLanguage.value ?: LanguageCode.HINDI
                            if (sessionManager.currentTtsEngine == null || !sessionManager.currentTtsEngine!!.isLoaded) {
                                val shouldLoadTts = com.itantra.app.AppGraph.languagePackStorage.isTtsInstalled(lang)
                                sessionManager.switchTo(lang, loadStt = true, loadTts = shouldLoadTts)
                            }
                            val coordinator = com.itantra.app.AppGraph.transceiverCoordinator
                            coordinator.debugBeepWhenNoVoice = true
                            // Send normal priority message
                            val normalPacket = com.itantra.core.transport.packet.ItantraPacket(
                                type = com.itantra.core.transport.packet.PacketType.TEXT,
                                flags = com.itantra.domain.model.MessagePriority.NORMAL.toByte(),
                                messageId = 888111L,
                                payload = "Non-critical long status update for preemption testing. Continuing playback smoothly until emergency strikes.".toByteArray(Charsets.UTF_8)
                            )
                            coordinator.enqueueMessagePacketForPlayback(normalPacket)

                            // Wait 500ms so playback is actively underway
                            kotlinx.coroutines.delay(500)

                            // Trigger emergency packet mid-playback
                            val tTrigger = android.os.SystemClock.elapsedRealtime()
                            android.util.Log.i("TEST_PREEMPTION", "TRIGGER_EMERGENCY_AT=$tTrigger")

                            val emergencyPacket = com.itantra.core.transport.packet.ItantraPacket(
                                type = com.itantra.core.transport.packet.PacketType.EMERGENCY_CODE,
                                flags = com.itantra.domain.model.MessagePriority.CRITICAL.toByte(),
                                messageId = 888222L,
                                payload = byteArrayOf(com.itantra.domain.model.EmergencyCode.HELP_REQUIRED.id)
                            )
                            coordinator.enqueueMessagePacketForPlayback(emergencyPacket)
                        } catch (e: Exception) {
                            android.util.Log.e("TEST_PREEMPTION", "Error during preemption test", e)
                        }
                    }
                    return
                }

                val testPttTapMs = intent?.getLongExtra("test_ptt_tap_ms", -1L) ?: -1L
                if (testPttTapMs > 0) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        val sampleCount = (testPttTapMs * 16000L / 1000L).toInt()
                        // 440Hz voiced sine wave above RMS threshold (0.003)
                        val samples = FloatArray(sampleCount) {
                            (Math.sin(2.0 * Math.PI * 440.0 * it / 16000.0) * 0.4).toFloat()
                        }
                        android.util.Log.i("TEST_PTT_TAP", "Injecting test PTT audio with duration=${testPttTapMs}ms ($sampleCount samples)")
                        com.itantra.app.AppGraph.transceiverCoordinator.processTestAudio(samples)
                    }
                    return
                }

                val testInvestigate = intent?.getBooleanExtra("test_investigate", false) ?: false
                if (testInvestigate) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            android.util.Log.i("TEST_INVESTIGATE", "=== STARTING INVESTIGATION OF SHORT-PHRASE LOOPING ===")
                            val stt = SherpaOnnxSpeechRecognizer(this@MainActivity, LanguageCode.ENGLISH, com.itantra.app.AppGraph.languagePackStorage, com.itantra.app.AppGraph.metricsRecorder)
                            stt.load()
                            for (id in listOf("EN-CRIT-021", "EN-CRIT-024", "EN-001")) {
                                val wavPath = "benchmark/audio/$id.wav"
                                val rawSamples = assets.open(wavPath).use { WavWriter.readWav(it) }
                                for (pad in listOf(0, 800, 1600, 3200, 6400)) {
                                    val res = stt.decodeDirect(rawSamples, pad)
                                    android.util.Log.i("TEST_INVESTIGATE", "[$id | pad=${pad} samples (${pad/16}ms)] -> '${res.text}'")
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TEST_INVESTIGATE", "Investigation failed", e)
                        }
                    }
                    return
                }

                val runBenchmark = intent?.getBooleanExtra("run_benchmark", false) ?: false
                if (runBenchmark) {
                    lifecycleScope.launch(Dispatchers.Default) {
                        try {
                            android.util.Log.i("TEST_BENCHMARK", "=== STARTING BENCHMARK EXECUTION VIA BROADCAST ===")
                            val jsonText = assets.open("benchmark/en_benchmark.json").bufferedReader().use { it.readText() }
                            val sentenceDefs = Json { ignoreUnknownKeys = true }.decodeFromString<List<BenchmarkSentenceDef>>(jsonText)

                            var stt = com.itantra.app.AppGraph.activeLanguageSessionManager.currentSttEngine
                            if (stt == null || stt.languageCode != LanguageCode.ENGLISH || !stt.isLoaded) {
                                stt = SherpaOnnxSpeechRecognizer(this@MainActivity, LanguageCode.ENGLISH, com.itantra.app.AppGraph.languagePackStorage, com.itantra.app.AppGraph.metricsRecorder)
                                stt.load()
                            }

                            val benchmarkResults = mutableListOf<BenchmarkResult>()
                            val werResults = mutableListOf<com.itantra.core.metrics.WerResult>()
                            val cerResults = mutableListOf<com.itantra.core.metrics.CerResult>()

                            for (item in sentenceDefs) {
                                val wavPath = "benchmark/audio/${item.id}.wav"
                                val samples = try {
                                    assets.open(wavPath).use { stream -> WavWriter.readWav(stream) }
                                } catch (e: Exception) {
                                    android.util.Log.e("TEST_BENCHMARK", "Could not read $wavPath", e)
                                    continue
                                }

                                stt.reset()
                                stt.feed(samples)

                                val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                                val res = stt.finalizeUtterance()
                                val latencyMillis = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000

                                val audioDurationMs = (samples.size.toLong() * 1000L) / 16000L
                                val wer = WordErrorRateCalculator.calculate(item.referenceText, res.text)
                                val cer = WordErrorRateCalculator.calculateCer(item.referenceText, res.text)
                                werResults.add(wer)
                                cerResults.add(cer)

                                val isExactMatch = TextNormalizer.isExactMatch(item.referenceText, res.text)
                                android.util.Log.i("TEST_BENCHMARK", "[${item.id}] Ref: '${item.referenceText}' | Hyp: '${res.text}' | WER=${wer.wer} | CER=${cer.cer} | Latency=${latencyMillis}ms | PureInference=${res.pureInferenceMs}ms")

                                benchmarkResults.add(
                                    BenchmarkResult(
                                        sentenceId = item.id,
                                        category = item.category,
                                        isCritical = item.isCritical,
                                        referenceText = item.referenceText,
                                        recognizedText = res.text,
                                        audioDurationMs = audioDurationMs,
                                        processingMs = res.pureInferenceMs,
                                        finalizationLatencyMs = latencyMillis,
                                        referenceWordCount = wer.referenceWordCount,
                                        wer = wer.wer,
                                        cer = cer.cer,
                                        substitutions = wer.substitutions,
                                        deletions = wer.deletions,
                                        insertions = wer.insertions,
                                        isCriticalMatch = item.isCritical && isExactMatch,
                                        isSuccess = true
                                    )
                                )
                            }

                            if (benchmarkResults.isNotEmpty()) {
                                val corpusWer = WordErrorRateCalculator.calculateCorpusWer(werResults)
                                val corpusCer = WordErrorRateCalculator.calculateCorpusCer(cerResults)
                                val meanRtf = benchmarkResults.map { it.rtf }.average().toFloat()
                                val meanLatency = benchmarkResults.map { it.finalizationLatencyMs }.average().toLong()
                                val sortedLatencies = benchmarkResults.map { it.finalizationLatencyMs }.sorted()
                                val medianLatency = sortedLatencies[sortedLatencies.size / 2]

                                val critCount = benchmarkResults.count { it.isCritical }
                                val critMatches = benchmarkResults.count { it.isCriticalMatch }

                                val session = BenchmarkSession(
                                    timestampMs = System.currentTimeMillis(),
                                    language = "en",
                                    deviceManufacturer = android.os.Build.MANUFACTURER ?: "Generic",
                                    deviceModel = android.os.Build.MODEL ?: "Device",
                                    androidVersion = android.os.Build.VERSION.RELEASE ?: "14",
                                    abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                                    threadCount = 2,
                                    modelVersion = "Whisper Tiny Multilingual (INT8 ONNX)",
                                    totalUtterances = benchmarkResults.size,
                                    totalReferenceWords = corpusWer.totalReferenceWords,
                                    substitutions = corpusWer.totalSubstitutions,
                                    deletions = corpusWer.totalDeletions,
                                    insertions = corpusWer.totalInsertions,
                                    corpusWer = corpusWer.corpusWer,
                                    meanSentenceWer = corpusWer.meanSentenceWer,
                                    corpusCer = corpusCer.corpusCer,
                                    meanSentenceCer = corpusCer.meanSentenceCer,
                                    meanRtf = meanRtf,
                                    medianFinalizationLatencyMs = medianLatency,
                                    meanFinalizationLatencyMs = meanLatency,
                                    totalAudioDurationMs = benchmarkResults.sumOf { it.audioDurationMs },
                                    criticalPhraseCount = critCount,
                                    criticalPhraseExactMatches = critMatches,
                                    criticalPhraseExactMatchRate = if (critCount > 0) critMatches.toFloat() / critCount else 0f,
                                    evidenceLevel = EvidenceLevel.DEVICE_TESTED,
                                    results = benchmarkResults
                                )

                                val savedFile = com.itantra.app.AppGraph.localBenchmarkRepository.saveSession(session)
                                com.itantra.app.AppGraph.metricsRecorder.recordSttInferenceTime(session.meanFinalizationLatencyMs)
                                com.itantra.app.AppGraph.metricsRecorder.recordSttPureInferenceTime(benchmarkResults.map { it.processingMs }.average().toLong())
                                com.itantra.app.AppGraph.metricsRecorder.recordSttRealTimeFactor(meanRtf.toDouble())

                                android.util.Log.i("TEST_BENCHMARK_COMPLETE", "CORPUS_WER=${session.corpusWer} CORPUS_CER=${session.corpusCer} S=${session.substitutions} D=${session.deletions} I=${session.insertions} MEAN_RTF=${session.meanRtf} MEAN_LATENCY=${session.meanFinalizationLatencyMs} FILE=${savedFile.absolutePath}")
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("TEST_BENCHMARK", "Failed to run benchmark", e)
                        }
                    }
                    return
                }

                val navTo = intent?.getStringExtra("navigate_to")
                if (navTo != null) {
                    try {
                        destinationState.value = AppDestination.valueOf(navTo.uppercase())
                        android.util.Log.i("TEST_NAV", "Navigated to ${destinationState.value}")
                        return
                    } catch (e: Exception) {
                        android.util.Log.e("TEST_NAV", "Invalid destination: $navTo", e)
                    }
                }
                val wavPath = intent?.getStringExtra("wav_path")
                if (wavPath != null) {
                    try {
                        var file = java.io.File(wavPath)
                        if (!file.exists() && context != null) {
                            file = java.io.File(context.getExternalFilesDir(null), wavPath)
                        }
                        if (file.exists()) {
                            val bytes = file.readBytes()
                            val pcmBytes = if (bytes.size > 44) bytes.copyOfRange(44, bytes.size) else bytes
                            val sampleCount = pcmBytes.size / 2
                            val floats = FloatArray(sampleCount)
                            val buf = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until sampleCount) {
                                floats[i] = buf.short.toFloat() / 32768.0f
                            }
                            android.util.Log.i("TEST_PTT", "Feeding WAV: ${file.name}, samples=$sampleCount")
                            com.itantra.app.AppGraph.transceiverCoordinator.processTestAudio(floats)
                            return
                        } else {
                            android.util.Log.e("TEST_PTT", "File not found: ${file.absolutePath}")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("TEST_PTT", "Error loading WAV", e)
                    }
                }
                val durationMs = intent?.getLongExtra("duration_ms", 2000L) ?: 2000L
                android.util.Log.i("TEST_PTT", "Received TEST_PTT broadcast for ${durationMs}ms")
                lifecycleScope.launch {
                    com.itantra.app.AppGraph.transceiverCoordinator.startRecording()
                    kotlinx.coroutines.delay(durationMs)
                    com.itantra.app.AppGraph.transceiverCoordinator.stopActiveRecording()
                }
            }
        }
        if (BuildConfig.DEBUG) {
            val pttFilter = android.content.IntentFilter("com.example.itantra.TEST_PTT")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(testPttReceiver, pttFilter, android.content.Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(testPttReceiver, pttFilter)
            }
            debugTestReceiver = testPttReceiver
        }

        // Register bond-state / Android-16 KEY_MISSING receiver so the transport
        // can react to bond loss without relying solely on socket IO errors.
        AppGraph.bluetoothPeerTransport.registerBondReceiver(this)
        // Register Wi-Fi Direct receiver for peer discovery and group formation.
        AppGraph.wifiDirectConnectionManager.registerReceiver(this)

        setContent {
            ITantraTheme(dynamicColor = false) {
                TacticalAppScaffold(
                    permissionsGranted = permissionsGranted,
                    onRequestPermissions = { requestRequiredPermissions() },
                    destinationState = destinationState
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ (API 31+): Nearby Devices permissions cover BT Classic.
            // Location is NOT needed for BT Classic discovery on API 31+.
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            // Android 11 and below: location required for BT Classic discovery.
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        // RECORD_AUDIO is always required for STT.
        permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)

        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        // permissionsGranted reflects Bluetooth + Audio; absence of location on API 31+
        // must not block opening the Connect screen.
        permissionsGranted = allGranted

        if (!allGranted) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}

@Composable
fun TacticalAppScaffold(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    destinationState: MutableState<AppDestination> = remember { mutableStateOf(AppDestination.HUB) }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val settingsRepo = remember { com.itantra.app.AppGraph.settingsRepository }
    val settingsViewModel = remember { SettingsViewModel(settingsRepo) }
    val appSettings by settingsViewModel.settings.collectAsState()

    val coordinator = remember { com.itantra.app.AppGraph.transceiverCoordinator }
    val sessionManager = remember { com.itantra.app.AppGraph.activeLanguageSessionManager }
    val metricsRecorder = remember { com.itantra.app.AppGraph.metricsRecorder }
    val benchmarkRepo = remember { com.itantra.app.AppGraph.localBenchmarkRepository }
    val secureSession = remember { com.itantra.app.AppGraph.secureSessionManager }

    val liveMetrics by metricsRecorder.latest.collectAsState()
    val activeLang by sessionManager.activeLanguage.collectAsState()
    val liveSasCode by secureSession.sasCode.collectAsState()

    var currentDestination by destinationState
    var activeChatPeerId by remember { mutableStateOf("") }

    // Intercept back button when inside a spoke screen
    BackHandler(enabled = currentDestination != AppDestination.HUB) {
        if (currentDestination == AppDestination.CHAT) {
            currentDestination = AppDestination.CONNECT
        } else {
            currentDestination = AppDestination.HUB
        }
    }

    var bannerDismissed by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = ITantraColors.CanvasBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!permissionsGranted && !bannerDismissed) {
                    PermissionWarningBanner(
                        onRequestPermissions = onRequestPermissions,
                        onDismiss = { bannerDismissed = true }
                    )
                }

                when (currentDestination) {
                    AppDestination.HUB -> {
                        TransceiverHubScreen(
                            coordinator = coordinator,
                            sessionManager = sessionManager,
                            onNavigateToConnect = { currentDestination = AppDestination.CONNECT },
                            onNavigateToLanguagePacks = { currentDestination = AppDestination.LANGUAGE_PACKS },
                            onNavigateToDiagnostics = { currentDestination = AppDestination.DIAGNOSTICS },
                            onNavigateToSettings = { currentDestination = AppDestination.SETTINGS },
                            operatorName = appSettings.operatorName,
                        )
                    }

                    AppDestination.SETTINGS -> {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onBack = { currentDestination = AppDestination.HUB },
                        )
                    }

                    AppDestination.CONNECT -> {
                        // Observe real transport connection state
                        val transportState by AppGraph.transportEngine.observeConnectionState()
                            .collectAsState(initial = ConnectionState.DISCONNECTED)

                        var discoveredDevices by remember { mutableStateOf<List<PeerDevice>>(emptyList()) }
                        var isBtDiscovering by remember { mutableStateOf(false) }

                        val btAdapter = remember(context) {
                            (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
                        }

                        DisposableEffect(context, currentDestination) {
                            val receiver = object : android.content.BroadcastReceiver() {
                                override fun onReceive(c: android.content.Context?, intent: android.content.Intent?) {
                                    when (intent?.action) {
                                        android.bluetooth.BluetoothDevice.ACTION_FOUND -> {
                                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE, android.bluetooth.BluetoothDevice::class.java)
                                            } else {
                                                @Suppress("DEPRECATION")
                                                intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                                            }
                                            val rssi = intent.getShortExtra(android.bluetooth.BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                            if (device != null) {
                                                try {
                                                    @Suppress("MissingPermission")
                                                    val devName = device.name ?: "NODE-${device.address.takeLast(5).replace(":", "")}"
                                                    val devId = device.address ?: "bt-${device.hashCode()}"
                                                    val sig = if (rssi != Short.MIN_VALUE.toInt()) rssi else null

                                                    discoveredDevices = (discoveredDevices.filter { it.id != devId } + PeerDevice(
                                                        id = devId,
                                                        name = devName,
                                                        role = "Discovered Operator",
                                                        transport = "Bluetooth RFCOMM",
                                                        signalDbm = sig,
                                                        batteryPercent = null,
                                                        state = PeerConnectionState.AVAILABLE
                                                    ))
                                                } catch (e: SecurityException) {
                                                    // Bluetooth permission missing
                                                }
                                            }
                                        }
                                        android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                                            isBtDiscovering = true
                                        }
                                        android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                                            isBtDiscovering = false
                                        }
                                    }
                                }
                            }

                            val filter = android.content.IntentFilter().apply {
                                addAction(android.bluetooth.BluetoothDevice.ACTION_FOUND)
                                addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_STARTED)
                                addAction(android.bluetooth.BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                            }
                            context.registerReceiver(receiver, filter)

                            onDispose {
                                try {
                                    context.unregisterReceiver(receiver)
                                } catch (ignored: Exception) {}
                                try {
                                    @Suppress("MissingPermission")
                                    if (btAdapter?.isDiscovering == true) {
                                        btAdapter.cancelDiscovery()
                                    }
                                } catch (ignored: Exception) {}
                            }
                        }

                        val connectedDeviceAddress by AppGraph.bluetoothPeerTransport.connectedDeviceAddress.collectAsState()
                        val btLastError by AppGraph.bluetoothPeerTransport.lastError.collectAsState()

                        // Bonded devices list — only read if BLUETOOTH_CONNECT is granted.
                        // On Android 14/15/16 reading bondedDevices / device.name / device.address
                        // without BLUETOOTH_CONNECT throws SecurityException.
                        val bondedList = remember(transportState, connectedDeviceAddress, btAdapter, btLastError) {
                            val hasConnectPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.BLUETOOTH_CONNECT
                                ) == PackageManager.PERMISSION_GRANTED
                            } else true

                            if (!hasConnectPerm) return@remember emptyList()

                            try {
                                @Suppress("MissingPermission") // guarded by hasConnectPerm above
                                btAdapter?.bondedDevices?.map { dev ->
                                    val address = dev.address
                                    val isTargetOfCurrentSession = address != null && address == connectedDeviceAddress
                                    PeerDevice(
                                        id = address ?: "bt-${dev.hashCode()}",
                                        name = dev.name ?: "BONDED-NODE",
                                        role = if (isTargetOfCurrentSession) "Paired Operator" else "Bonded Device",
                                        transport = "Bluetooth RFCOMM",
                                        signalDbm = null,
                                        batteryPercent = null,
                                        state = when {
                                            isTargetOfCurrentSession && transportState == ConnectionState.CONNECTED ->
                                                PeerConnectionState.CONNECTED
                                            isTargetOfCurrentSession && (
                                                transportState == ConnectionState.CONNECTING ||
                                                    transportState == ConnectionState.LISTENING
                                                ) -> PeerConnectionState.CONNECTING
                                            // Not the current session target: show AVAILABLE so
                                            // the CONNECT button is rendered (not "NOT CONNECTED").
                                            else -> PeerConnectionState.AVAILABLE
                                        }
                                    )
                                } ?: emptyList()
                            } catch (e: SecurityException) {
                                emptyList()
                            }
                        }

                        // Combined device list
                        val liveDevices = remember(bondedList, discoveredDevices, transportState) {
                            val map = linkedMapOf<String, PeerDevice>()
                            bondedList.forEach { map[it.id] = it }
                            discoveredDevices.forEach { dev ->
                                if (!map.containsKey(dev.id)) {
                                    map[dev.id] = dev
                                } else {
                                    val existing = map[dev.id]!!
                                    map[dev.id] = existing.copy(signalDbm = dev.signalDbm)
                                }
                            }
                            map.values.toList()
                        }

                        val wifiDirectState by AppGraph.wifiDirectConnectionManager.state.collectAsState()
                        val wifiDirectPeers by AppGraph.wifiDirectConnectionManager.peers.collectAsState()
                        val wifiDirectError by AppGraph.wifiDirectConnectionManager.lastError.collectAsState()
                        val wifiDirectInfo by AppGraph.wifiDirectConnectionManager.connectionInfo.collectAsState()

                        ConnectScreenContent(
                            channelName = "TAC-RELIEF-04",
                            peersInRange = liveDevices.size,
                            devices = liveDevices,
                            isScanning = isBtDiscovering || transportState == ConnectionState.CONNECTING || transportState == ConnectionState.LISTENING,
                            sasCode = liveSasCode,
                            connectionError = btLastError.name.takeIf {
                                btLastError != com.itantra.core.transport.peer.BluetoothError.NONE
                            },
                            wifiDirectPeers = wifiDirectPeers,
                            wifiDirectState = wifiDirectState,
                            wifiDirectError = wifiDirectError.takeIf {
                                it != com.itantra.core.transport.peer.WifiDirectError.NONE
                            }?.name,
                            wifiDirectInfo = wifiDirectInfo,
                            onBack = { currentDestination = AppDestination.HUB },
                            onBroadcastPing = {
                                coroutineScope.launch {
                                    AppGraph.bluetoothPeerTransport.startServer()
                                    val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.BLUETOOTH_SCAN
                                        ) == PackageManager.PERMISSION_GRANTED
                                    } else true
                                    if (hasScan) {
                                        try {
                                            @Suppress("MissingPermission") // hasScan checked above
                                            if (btAdapter?.isDiscovering == true) btAdapter.cancelDiscovery()
                                            @Suppress("MissingPermission")
                                            btAdapter?.startDiscovery()
                                        } catch (e: SecurityException) {
                                            android.util.Log.e("ConnectScreen", "startDiscovery denied", e)
                                        }
                                    }
                                }
                            },
                            onConnect = { device ->
                                coroutineScope.launch {
                                    val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.BLUETOOTH_CONNECT
                                        ) == PackageManager.PERMISSION_GRANTED
                                    } else true
                                    if (!hasConnect) return@launch
                                    try {
                                        @Suppress("MissingPermission") // hasConnect checked above
                                        val target = btAdapter?.bondedDevices?.find { it.address == device.id }
                                            ?: btAdapter?.getRemoteDevice(device.id)
                                        if (target != null) {
                                            AppGraph.bluetoothPeerTransport.connectToDevice(target)
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("ConnectScreen", "connectToDevice failed", e)
                                    }
                                }
                            },
                            onSasConfirmed = {
                                coordinator.confirmPeerVerification()
                            },
                            onOpenChat = { device ->
                                activeChatPeerId = device.id
                                currentDestination = AppDestination.CHAT
                            },
                            onDiscoverWifiDirectPeers = {
                                (context as? MainActivity)?.requestWifiDirectPermission { granted ->
                                    if (granted) {
                                        AppGraph.wifiDirectConnectionManager.discoverPeers()
                                    }
                                } ?: run {
                                    AppGraph.wifiDirectConnectionManager.discoverPeers()
                                }
                            },
                            onConnectWifiDirect = { peer ->
                                AppGraph.wifiDirectConnectionManager.connect(peer)
                            },
                            onDisconnectWifiDirect = {
                                AppGraph.wifiDirectConnectionManager.disconnect()
                            },
                            onOpenChatWifiDirect = { peer ->
                                activeChatPeerId = peer.deviceAddress
                                currentDestination = AppDestination.CHAT
                            }
                        )
                    }

                    AppDestination.LANGUAGE_PACKS -> {
                        LanguagePacksScreen(
                            repository = com.itantra.app.AppGraph.languagePackRepository,
                            sessionManager = sessionManager,
                            onBack = { currentDestination = AppDestination.HUB }
                        )
                    }

                    AppDestination.CHAT -> {
                        val activePeer by coordinator.activePeerProfile.collectAsState()
                        DedicatedChatScreen(
                            peerProfile = activePeer?.let { p ->
                                com.itantra.domain.model.PeerProfile(
                                    deviceId = p.deviceId,
                                    bluetoothAddress = activeChatPeerId,
                                    displayName = p.displayName,
                                    isConnected = p.isConnected,
                                    activeLanguage = p.activeLanguage
                                )
                            },
                            peerId = activeChatPeerId,
                            coordinator = coordinator,
                            onBack = { currentDestination = AppDestination.CONNECT }
                        )
                    }

                    AppDestination.DIAGNOSTICS -> {
                        var latestBenchmarkSession by remember { mutableStateOf<com.itantra.domain.model.BenchmarkSession?>(null) }

                        LaunchedEffect(currentDestination) {
                            val sessions = benchmarkRepo.getAllSessions()
                            if (sessions.isNotEmpty()) {
                                latestBenchmarkSession = sessions.first()
                            }
                        }

                        val battery = com.example.itantra.ui.components.rememberBatteryState()
                        val diagnosticsState = remember(liveMetrics, activeLang, latestBenchmarkSession, battery) {
                            DiagnosticsUiState(
                                // Hindi WER: null because no Hindi audio corpus exists yet in assets/benchmark/audio/
                                wordErrorRatePercent = null,
                                hindiWerNote = "Hindi unmeasured \u2014 no Hindi audio corpus in assets/benchmark/audio/. Audio corpus required for empirical Hindi WER.",
                                // English WER from empirical benchmark measurement (secondary reference)
                                englishWerPercent = latestBenchmarkSession?.let {
                                    if (it.language == "en") String.format(java.util.Locale.US, "%.1f", it.corpusWer * 100f)
                                    else null
                                },
                                characterErrorRatePercent = latestBenchmarkSession?.let {
                                    if (it.language == "en") String.format(java.util.Locale.US, "%.1f", it.corpusCer * 100f)
                                    else null
                                },
                                realTimeFactor = latestBenchmarkSession?.let {
                                    String.format(java.util.Locale.US, "%.2fx", it.meanRtf)
                                } ?: when (val rtf = liveMetrics.stt.realTimeFactor) {
                                    is com.itantra.domain.model.Measurement.Measured<*> -> String.format(java.util.Locale.US, "%.2fx", rtf.value as Double)
                                    else -> null
                                },
                                activeModel = latestBenchmarkSession?.modelVersion ?: sessionManager.currentSttEngine?.let { "Sherpa-ONNX ${it.languageCode.name} STT" },
                                latencyMs = latestBenchmarkSession?.meanFinalizationLatencyMs?.toString()
                                    ?: when (val lat = liveMetrics.stt.inferenceTimeMillis) {
                                        is com.itantra.domain.model.Measurement.Measured<*> -> (lat.value as Long).toString()
                                        else -> null
                                    },
                                packetLossPercent = null,
                                retriesPerTx = null,
                                deliveryRatioPercent = null,
                                cpuPercent = null,
                                jvmHeapMb = "${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)}",
                                processPssMb = try {
                                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                                    val memInfo = am?.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
                                    if (!memInfo.isNullOrEmpty()) "${memInfo[0].totalPss / 1024}" else null
                                } catch (_: Exception) {
                                    null
                                },
                                ramMb = "${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)}",
                                batteryPercent = "${battery.levelPercent}",
                                temperatureC = null,
                            )
                        }

                        DiagnosticsScreen(
                            state = diagnosticsState,
                            onBack = { currentDestination = AppDestination.HUB },
                            onRunDiagnostics = {
                                coroutineScope.launch(Dispatchers.Default) {
                                    try {
                                        // Load benchmark sentence definitions from assets
                                        val jsonText = context.assets.open("benchmark/en_benchmark.json").bufferedReader().use { it.readText() }
                                        val sentenceDefs = Json { ignoreUnknownKeys = true }.decodeFromString<List<BenchmarkSentenceDef>>(jsonText)

                                        // Ensure English STT engine is loaded
                                        var stt = sessionManager.currentSttEngine
                                        if (stt == null || stt.languageCode != LanguageCode.ENGLISH || !stt.isLoaded) {
                                            stt = SherpaOnnxSpeechRecognizer(context, LanguageCode.ENGLISH, AppGraph.languagePackStorage, metricsRecorder)
                                            stt.load()
                                        }

                                        val benchmarkResults = mutableListOf<BenchmarkResult>()
                                        val werResults = mutableListOf<com.itantra.core.metrics.WerResult>()
                                        val cerResults = mutableListOf<com.itantra.core.metrics.CerResult>()

                                        for (item in sentenceDefs) {
                                            val wavPath = "benchmark/audio/${item.id}.wav"
                                            val samples = try {
                                                context.assets.open(wavPath).use { stream ->
                                                    WavWriter.readWav(stream)
                                                }
                                            } catch (e: Exception) {
                                                android.util.Log.e("Benchmark", "Could not read $wavPath", e)
                                                continue
                                            }

                                            stt.reset()
                                            stt.feed(samples)

                                            val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                                            val res = stt.finalizeUtterance()
                                            val latencyMillis = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000

                                            val audioDurationMs = (samples.size.toLong() * 1000L) / 16000L
                                            val wer = WordErrorRateCalculator.calculate(item.referenceText, res.text)
                                            val cer = WordErrorRateCalculator.calculateCer(item.referenceText, res.text)
                                            werResults.add(wer)
                                            cerResults.add(cer)

                                            val isExactMatch = TextNormalizer.isExactMatch(item.referenceText, res.text)

                                            benchmarkResults.add(
                                                BenchmarkResult(
                                                    sentenceId = item.id,
                                                    category = item.category,
                                                    isCritical = item.isCritical,
                                                    referenceText = item.referenceText,
                                                    recognizedText = res.text,
                                                    audioDurationMs = audioDurationMs,
                                                    processingMs = res.pureInferenceMs,
                                                    finalizationLatencyMs = latencyMillis,
                                                    referenceWordCount = wer.referenceWordCount,
                                                    wer = wer.wer,
                                                    cer = cer.cer,
                                                    substitutions = wer.substitutions,
                                                    deletions = wer.deletions,
                                                    insertions = wer.insertions,
                                                    isCriticalMatch = item.isCritical && isExactMatch,
                                                    isSuccess = true
                                                )
                                            )
                                        }

                                        if (benchmarkResults.isNotEmpty()) {
                                            val corpusWer = WordErrorRateCalculator.calculateCorpusWer(werResults)
                                            val corpusCer = WordErrorRateCalculator.calculateCorpusCer(cerResults)
                                            val meanRtf = benchmarkResults.map { it.rtf }.average().toFloat()
                                            val meanLatency = benchmarkResults.map { it.finalizationLatencyMs }.average().toLong()
                                            val sortedLatencies = benchmarkResults.map { it.finalizationLatencyMs }.sorted()
                                            val medianLatency = sortedLatencies[sortedLatencies.size / 2]

                                            val critCount = benchmarkResults.count { it.isCritical }
                                            val critMatches = benchmarkResults.count { it.isCriticalMatch }

                                            val session = BenchmarkSession(
                                                timestampMs = System.currentTimeMillis(),
                                                language = "en",
                                                deviceManufacturer = android.os.Build.MANUFACTURER ?: "Generic",
                                                deviceModel = android.os.Build.MODEL ?: "Device",
                                                androidVersion = android.os.Build.VERSION.RELEASE ?: "14",
                                                abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                                                threadCount = 2,
                                                modelVersion = "Whisper Tiny Multilingual (INT8 ONNX)",
                                                totalUtterances = benchmarkResults.size,
                                                totalReferenceWords = corpusWer.totalReferenceWords,
                                                substitutions = corpusWer.totalSubstitutions,
                                                deletions = corpusWer.totalDeletions,
                                                insertions = corpusWer.totalInsertions,
                                                corpusWer = corpusWer.corpusWer,
                                                meanSentenceWer = corpusWer.meanSentenceWer,
                                                corpusCer = corpusCer.corpusCer,
                                                meanSentenceCer = corpusCer.meanSentenceCer,
                                                meanRtf = meanRtf,
                                                medianFinalizationLatencyMs = medianLatency,
                                                meanFinalizationLatencyMs = meanLatency,
                                                totalAudioDurationMs = benchmarkResults.sumOf { it.audioDurationMs },
                                                criticalPhraseCount = critCount,
                                                criticalPhraseExactMatches = critMatches,
                                                criticalPhraseExactMatchRate = if (critCount > 0) critMatches.toFloat() / critCount else 0f,
                                                evidenceLevel = EvidenceLevel.DEVICE_TESTED,
                                                results = benchmarkResults
                                            )

                                            benchmarkRepo.saveSession(session)
                                            withContext(Dispatchers.Main) {
                                                latestBenchmarkSession = session
                                            }
                                            metricsRecorder.recordSttInferenceTime(session.meanFinalizationLatencyMs)
                                            metricsRecorder.recordSttPureInferenceTime(benchmarkResults.map { it.processingMs }.average().toLong())
                                            metricsRecorder.recordSttRealTimeFactor(meanRtf.toDouble())
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("Benchmark", "Failed to run on-device benchmark", e)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionWarningBanner(
    onRequestPermissions: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    Surface(
        color = ITantraColors.StatusWarning.copy(alpha = 0.95f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Audio / Nearby permissions required for P2P voice",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = onRequestPermissions,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("Grant", color = ITantraColors.StatusWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
