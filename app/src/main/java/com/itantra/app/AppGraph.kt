package com.itantra.app

import android.content.Context
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.core.inference.DeviceCapabilityDetector
import com.itantra.core.inference.EngineFactory
import com.itantra.core.inference.SherpaOnnxSpeechRecognizer
import com.itantra.core.inference.SpeechRecognizerEngine
import com.itantra.core.inference.SpeechSynthesizerEngine
import com.itantra.core.metrics.InMemoryMetricsRecorder
import com.itantra.core.metrics.MetricsRecorder
import com.itantra.data.languagepack.AssetLanguagePackStorage
import com.itantra.data.languagepack.FileLanguagePackStorage
import com.itantra.data.languagepack.LanguagePackManifestParser
import com.itantra.data.languagepack.RealLanguagePackRepository
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.repository.LanguagePackRepository
import com.itantra.data.benchmark.LocalBenchmarkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

import android.bluetooth.BluetoothManager
import com.itantra.core.crypto.SecureSessionManager
import com.itantra.core.transceiver.TransceiverCoordinator
import com.itantra.core.translation.TranslationRouter
import com.example.itantra.data.settings.SettingsRepository
import com.example.itantra.data.settings.settingsDataStore

object AppGraph {
    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
            // Auto-activate default Hindi STT pack for immediate PTT availability
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val storage = languagePackStorage
                    storage.extractBundledAssetsIfNeeded()
                    val prefs = context.applicationContext.getSharedPreferences("lang_prefs", Context.MODE_PRIVATE)
                    val savedLang = prefs.getString("source_lang", null)?.let { LanguageCode.fromWireCode(it) }
                    val langToActivate = savedLang ?: LanguageCode.HINDI
                    languagePackRepository.setActiveLanguage(langToActivate)
                    val shouldLoadTts = storage.isTtsInstalled(langToActivate)
                    activeLanguageSessionManager.switchTo(langToActivate, loadStt = true, loadTts = shouldLoadTts)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // Automatically keep activeLanguageSessionManager in sync with repository changes
                try {
                    languagePackRepository.observeActiveLanguage().collect { lang ->
                        if (lang != null && activeLanguageSessionManager.activeLanguage.value != lang) {
                            try {
                                val shouldLoadTts = languagePackStorage.isTtsInstalled(lang)
                                activeLanguageSessionManager.switchTo(lang, loadStt = true, loadTts = shouldLoadTts)
                            } catch (e: Exception) {
                                android.util.Log.e("AppGraph", "Failed to switch sessionManager to $lang", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun switchActiveLanguage(code: LanguageCode) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                languagePackRepository.setActiveLanguage(code)
                val shouldLoadTts = languagePackStorage.isTtsInstalled(code)
                activeLanguageSessionManager.switchTo(code, loadStt = true, loadTts = shouldLoadTts)
            } catch (e: Exception) {
                android.util.Log.e("AppGraph", "Failed to switch active language to $code", e)
            }
        }
    }

    private val context: Context
        get() = requireNotNull(appContext) { "AppGraph not initialized" }

    val metricsRecorder: MetricsRecorder by lazy { InMemoryMetricsRecorder() }

    val languagePackStorage: AssetLanguagePackStorage by lazy {
        AssetLanguagePackStorage(context)
    }

    val languagePackRepository: LanguagePackRepository by lazy {
        RealLanguagePackRepository(
            context,
            languagePackStorage,
            LanguagePackManifestParser
        )
    }

    val localBenchmarkRepository: LocalBenchmarkRepository by lazy {
        LocalBenchmarkRepository(context)
    }

    val activeLanguageSessionManager: ActiveLanguageSessionManager by lazy {
        val factory = object : EngineFactory {
            override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine {
                return SherpaOnnxSpeechRecognizer(context, language, languagePackStorage, metricsRecorder)
            }

            override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine? {
                if (!languagePackStorage.isTtsInstalled(language)) {
                    return null
                }
                return com.itantra.core.inference.SherpaOnnxSpeechSynthesizer(
                    context, language, languagePackStorage, metricsRecorder
                )
            }
        }
        ActiveLanguageSessionManager(
            engineFactory = factory,
            capabilityDetector = DeviceCapabilityDetector(context)
        )
    }

    val bluetoothPeerTransport: com.itantra.core.transport.peer.BluetoothPeerTransport by lazy {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        com.itantra.core.transport.peer.BluetoothPeerTransport(context, bluetoothManager.adapter)
    }

    val transportEngine: com.itantra.core.transport.TransportCoordinator by lazy {
        // Default to Bluetooth initially
        com.itantra.core.transport.TransportCoordinator(bluetoothPeerTransport)
    }

    val secureSessionManager: SecureSessionManager by lazy {
        SecureSessionManager()
    }

    // Translation is a deliberate scope decision for this build, not a bug: the real MT model
    // (~1.6GB) was never bundled into the APK and the native JNI bridge was never compiled.
    // See UnavailableTranslationEngine's doc comment for how to re-enable it later.
    val translationEngine: com.itantra.core.translation.TranslationEngine by lazy {
        com.itantra.core.translation.UnavailableTranslationEngine()
    }

    val translationRouter: TranslationRouter by lazy {
        TranslationRouter(translationEngine)
    }

    val deviceProfileManager: com.itantra.core.profile.DeviceProfileManager by lazy {
        com.itantra.core.profile.DeviceProfileManager(context)
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(context.settingsDataStore)
    }

    val database: com.itantra.data.db.AppDatabase by lazy {
        androidx.room.Room.databaseBuilder(
            context,
            com.itantra.data.db.AppDatabase::class.java,
            "itantra.db"
        )
        .addMigrations(com.itantra.data.db.AppDatabase.MIGRATION_1_2)
        .build()
    }

    val transceiverCoordinator: TransceiverCoordinator by lazy {
        TransceiverCoordinator(
            context,
            activeLanguageSessionManager,
            languagePackRepository,
            transportEngine,
            metricsRecorder,
            secureSessionManager,
            translationRouter,
            database.messageDao(),
            deviceProfileManager
        ).apply {
            attachSettings(settingsRepository)
        }
    }
}
