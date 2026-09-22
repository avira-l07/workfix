package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enforces the project's core model-lifecycle rule:
 *
 *   - Strictly at most ONE STT engine resident in RAM at once.
 *   - Strictly at most ONE TTS engine resident in RAM at once.
 *   - STT and TTS lifecycles are INDEPENDENT: changing TTS language does NOT unload
 *     the active STT engine, and changing STT does not disrupt active TTS.
 */
enum class LanguageSessionState {
    IDLE,
    LOADING_STT,
    LOADING_TTS,
    READY,
    ERROR
}

class ActiveLanguageSessionManager(
    private val engineFactory: EngineFactory = EngineFactory.NoOp,
    private val capabilityDetector: DeviceCapabilityDetector? = null
) {
    private val switchMutex = Mutex()

    var currentSttEngine: SpeechRecognizerEngine? = null
        private set
    var currentTtsEngine: SpeechSynthesizerEngine? = null
        private set

    private val _activeSttLanguage = MutableStateFlow<LanguageCode?>(null)
    val activeSttLanguage: StateFlow<LanguageCode?> = _activeSttLanguage

    private val _activeTtsLanguage = MutableStateFlow<LanguageCode?>(null)
    val activeTtsLanguage: StateFlow<LanguageCode?> = _activeTtsLanguage

    private val _isSttAutoDetect = MutableStateFlow<Boolean>(true)
    val isSttAutoDetect: StateFlow<Boolean> = _isSttAutoDetect

    private val _activeLanguage = MutableStateFlow<LanguageCode?>(null)
    val activeLanguage: StateFlow<LanguageCode?> = _activeLanguage

    private val _sessionState = MutableStateFlow(LanguageSessionState.IDLE)
    val sessionState: StateFlow<LanguageSessionState> = _sessionState

    /**
     * Ensures an STT recognizer is loaded for [language] under [autoDetect] mode.
     * Reuses if already satisfied in memory.
     * If switching, unloads previous STT only (never touches TTS).
     */
    suspend fun ensureStt(language: LanguageCode, autoDetect: Boolean = true) {
        switchMutex.withLock {
            val sttLoaded = currentSttEngine != null && currentSttEngine?.isLoaded == true
            val sameMode = _isSttAutoDetect.value == autoDetect
            val sameLang = _activeSttLanguage.value == language || autoDetect

            if (sttLoaded && sameMode && sameLang) {
                return@withLock
            }

            // CORE_ONLY constrained device guard
            if (capabilityDetector?.determineProfile() == DeviceCapabilityDetector.CapabilityProfile.CORE_ONLY) {
                currentSttEngine?.unload()
                currentSttEngine = null
                _activeSttLanguage.value = null
                _sessionState.value = LanguageSessionState.ERROR
                return@withLock
            }

            // Unload previous STT only (strictly 1 STT in RAM, DO NOT UNLOAD TTS)
            currentSttEngine?.unload()
            currentSttEngine = null

            _sessionState.value = LanguageSessionState.LOADING_STT
            try {
                val newStt = engineFactory.createRecognizer(language, autoDetect)
                if (newStt != null) {
                    newStt.load()
                    currentSttEngine = newStt
                    _activeSttLanguage.value = language
                    _isSttAutoDetect.value = autoDetect
                    _activeLanguage.value = language
                    _sessionState.value = LanguageSessionState.READY
                } else {
                    _sessionState.value = LanguageSessionState.ERROR
                    throw IllegalStateException("STT_MODEL_NOT_INSTALLED: No recognizer available for ${language.wireCode}")
                }
            } catch (e: Throwable) {
                currentSttEngine?.unload()
                currentSttEngine = null
                _sessionState.value = LanguageSessionState.ERROR
                throw e
            }
        }
    }

    /**
     * Ensures only the requested [language]'s TTS engine is resident in memory.
     * Reuses if already loaded for [language].
     * If a different TTS is loaded, unloads previous TTS first before instantiating and loading the new one.
     * Crucially preserves any currently loaded STT engine intact without reload.
     */
    suspend fun ensureTts(language: LanguageCode) {
        switchMutex.withLock {
            val ttsLoaded = currentTtsEngine != null && currentTtsEngine?.isLoaded == true
            if (ttsLoaded && currentTtsEngine?.languageCode == language) {
                return@withLock
            }

            // CORE_ONLY constrained device guard
            if (capabilityDetector?.determineProfile() == DeviceCapabilityDetector.CapabilityProfile.CORE_ONLY) {
                currentTtsEngine?.unload()
                currentTtsEngine = null
                _activeTtsLanguage.value = null
                _sessionState.value = LanguageSessionState.ERROR
                return@withLock
            }

            // Strictly only one TTS model in RAM at once: unload previous TTS first (DO NOT UNLOAD STT)
            currentTtsEngine?.unload()
            currentTtsEngine = null

            _sessionState.value = LanguageSessionState.LOADING_TTS
            try {
                val newTts = engineFactory.createSynthesizer(language)
                if (newTts != null) {
                    newTts.load()
                    currentTtsEngine = newTts
                    _activeTtsLanguage.value = language
                    if (_activeLanguage.value == null) {
                        _activeLanguage.value = language
                    }
                    _sessionState.value = LanguageSessionState.READY
                } else {
                    _sessionState.value = LanguageSessionState.ERROR
                    throw IllegalStateException("TTS_MODEL_NOT_INSTALLED: No synthesizer available for ${language.wireCode}")
                }
            } catch (e: Throwable) {
                currentTtsEngine?.unload()
                currentTtsEngine = null
                _sessionState.value = LanguageSessionState.ERROR
                throw e
            }
        }
    }

    /**
     * Legacy compatibility: switches active inference resources to [target].
     */
    suspend fun switchTo(target: LanguageCode, loadStt: Boolean = true, loadTts: Boolean = true) {
        switchMutex.withLock {
            val isSameLang = _activeLanguage.value == target
            val sttLoaded = currentSttEngine != null && currentSttEngine?.isLoaded == true
            val ttsLoaded = currentTtsEngine != null && currentTtsEngine?.isLoaded == true
            val needsStt = loadStt && !sttLoaded
            val needsTts = loadTts && !ttsLoaded

            if (isSameLang && !needsStt && !needsTts && _sessionState.value == LanguageSessionState.READY) {
                return@withLock
            }

            // Unload old engines
            currentSttEngine?.unload()
            currentTtsEngine?.unload()
            currentSttEngine = null
            currentTtsEngine = null
            _activeLanguage.value = null
            _activeSttLanguage.value = null
            _activeTtsLanguage.value = null

            // CORE_ONLY guard
            if (capabilityDetector?.determineProfile() == DeviceCapabilityDetector.CapabilityProfile.CORE_ONLY) {
                _sessionState.value = LanguageSessionState.ERROR
                return@withLock
            }

            var newStt: SpeechRecognizerEngine? = null
            var newTts: SpeechSynthesizerEngine? = null

            try {
                if (loadStt) {
                    _sessionState.value = LanguageSessionState.LOADING_STT
                    newStt = engineFactory.createRecognizer(target, false)
                    if (newStt != null) {
                        newStt.load()
                    } else {
                        throw IllegalStateException("STT not available for $target")
                    }
                }

                if (loadTts) {
                    _sessionState.value = LanguageSessionState.LOADING_TTS
                    newTts = engineFactory.createSynthesizer(target)
                    newTts?.load()
                }

                val sttOk = !loadStt || (newStt != null && newStt.isLoaded)
                val ttsOk = (newTts != null && newTts.isLoaded)

                if (sttOk && (ttsOk || !loadTts || (loadStt && newTts == null))) {
                    currentSttEngine = newStt
                    currentTtsEngine = newTts
                    _activeSttLanguage.value = if (sttOk) target else null
                    _activeTtsLanguage.value = if (ttsOk) target else null
                    _isSttAutoDetect.value = false
                    _activeLanguage.value = target
                    _sessionState.value = LanguageSessionState.READY
                } else {
                    newStt?.unload()
                    newTts?.unload()
                    currentSttEngine = null
                    currentTtsEngine = null
                    _activeLanguage.value = null
                    _sessionState.value = LanguageSessionState.ERROR
                }
            } catch (e: Throwable) {
                newStt?.unload()
                newTts?.unload()
                currentSttEngine = null
                currentTtsEngine = null
                _activeLanguage.value = null
                _sessionState.value = LanguageSessionState.ERROR
                throw e
            }
        }
    }

    /**
     * Legacy compatibility: ensures requested inference capabilities (STT and/or TTS) are active for [target].
     */
    suspend fun ensureCapabilities(target: LanguageCode, requireStt: Boolean = true, requireTts: Boolean = true) {
        switchMutex.withLock {
            val isSameLang = _activeLanguage.value == target
            val sttLoaded = currentSttEngine != null && currentSttEngine?.isLoaded == true
            val ttsLoaded = currentTtsEngine != null && currentTtsEngine?.isLoaded == true
            val needsStt = requireStt && !sttLoaded
            val needsTts = requireTts && !ttsLoaded

            if (isSameLang && !needsStt && !needsTts && _sessionState.value == LanguageSessionState.READY) {
                return@withLock
            }

            if (capabilityDetector?.determineProfile() == DeviceCapabilityDetector.CapabilityProfile.CORE_ONLY) {
                currentSttEngine?.unload()
                currentTtsEngine?.unload()
                currentSttEngine = null
                currentTtsEngine = null
                _activeLanguage.value = null
                _sessionState.value = LanguageSessionState.ERROR
                return@withLock
            }

            if (isSameLang && _activeLanguage.value != null) {
                var loadFailed = false
                if (needsTts) {
                    _sessionState.value = LanguageSessionState.LOADING_TTS
                    try {
                        val newTts = engineFactory.createSynthesizer(target)
                        if (newTts != null) {
                            newTts.load()
                            if (currentTtsEngine !== newTts) {
                                currentTtsEngine?.unload()
                            }
                            currentTtsEngine = newTts
                            _activeTtsLanguage.value = target
                        } else {
                            loadFailed = true
                        }
                    } catch (e: Throwable) {
                        loadFailed = true
                    }
                }
                if (needsStt) {
                    _sessionState.value = LanguageSessionState.LOADING_STT
                    try {
                        val newStt = engineFactory.createRecognizer(target, false)
                        if (newStt != null) {
                            newStt.load()
                            if (currentSttEngine !== newStt) {
                                currentSttEngine?.unload()
                            }
                            currentSttEngine = newStt
                            _activeSttLanguage.value = target
                            _isSttAutoDetect.value = false
                        } else {
                            loadFailed = true
                        }
                    } catch (e: Throwable) {
                        loadFailed = true
                    }
                }

                val sttOk = !requireStt || (currentSttEngine != null && currentSttEngine?.isLoaded == true)
                val ttsOk = !requireTts || (currentTtsEngine != null && currentTtsEngine?.isLoaded == true)
                if (sttOk && ttsOk && !loadFailed) {
                    _sessionState.value = LanguageSessionState.READY
                } else {
                    _sessionState.value = LanguageSessionState.ERROR
                }
                return@withLock
            }

            // Different language
            try {
                currentSttEngine?.unload()
                currentTtsEngine?.unload()
                currentSttEngine = null
                currentTtsEngine = null
                _activeLanguage.value = null

                var newStt: SpeechRecognizerEngine? = null
                var newTts: SpeechSynthesizerEngine? = null
                var failed = false

                if (requireStt) {
                    _sessionState.value = LanguageSessionState.LOADING_STT
                    try {
                        newStt = engineFactory.createRecognizer(target, false)
                        newStt?.load()
                    } catch (e: Throwable) {
                        failed = true
                    }
                }
                if (requireTts) {
                    _sessionState.value = LanguageSessionState.LOADING_TTS
                    try {
                        newTts = engineFactory.createSynthesizer(target)
                        newTts?.load()
                    } catch (e: Throwable) {
                        failed = true
                    }
                }

                val sttOk = !requireStt || (newStt != null && newStt.isLoaded)
                val ttsOk = (newTts != null && newTts.isLoaded)

                if (!failed && sttOk && (ttsOk || !requireTts || (requireStt && newTts == null))) {
                    currentSttEngine = newStt
                    currentTtsEngine = newTts
                    _activeSttLanguage.value = if (sttOk) target else null
                    _activeTtsLanguage.value = if (ttsOk) target else null
                    _isSttAutoDetect.value = false
                    _activeLanguage.value = target
                    _sessionState.value = LanguageSessionState.READY
                } else {
                    newStt?.unload()
                    newTts?.unload()
                    currentSttEngine = null
                    currentTtsEngine = null
                    _activeLanguage.value = null
                    _sessionState.value = LanguageSessionState.ERROR
                }
            } catch (e: Throwable) {
                _sessionState.value = LanguageSessionState.ERROR
            }
        }
    }

    suspend fun unloadIfActive(target: LanguageCode) {
        switchMutex.withLock {
            if (_activeSttLanguage.value == target) {
                currentSttEngine?.unload()
                currentSttEngine = null
                _activeSttLanguage.value = null
            }
            if (_activeTtsLanguage.value == target) {
                currentTtsEngine?.unload()
                currentTtsEngine = null
                _activeTtsLanguage.value = null
            }
            if (_activeLanguage.value == target) {
                _activeLanguage.value = null
            }
            if (currentSttEngine == null && currentTtsEngine == null) {
                _sessionState.value = LanguageSessionState.IDLE
            }
        }
    }

    suspend fun releaseAll() {
        switchMutex.withLock {
            currentSttEngine?.unload()
            currentTtsEngine?.unload()
            currentSttEngine = null
            currentTtsEngine = null
            _activeSttLanguage.value = null
            _activeTtsLanguage.value = null
            _activeLanguage.value = null
            _sessionState.value = LanguageSessionState.IDLE
        }
    }
}

/**
 * Seam for constructing real engines per language. [NoOp] exists as a
 * safe default for tests; production wiring is in [com.itantra.app.AppGraph].
 */
interface EngineFactory {
    fun createRecognizer(language: LanguageCode, autoDetect: Boolean): SpeechRecognizerEngine? =
        createRecognizer(language)

    fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine?
    fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine?

    object NoOp : EngineFactory {
        override fun createRecognizer(language: LanguageCode, autoDetect: Boolean): SpeechRecognizerEngine? =
            throw NotImplementedError("No SpeechRecognizerEngine configured in NoOp EngineFactory")
        override fun createRecognizer(language: LanguageCode): SpeechRecognizerEngine? =
            throw NotImplementedError("No SpeechRecognizerEngine configured in NoOp EngineFactory")
        override fun createSynthesizer(language: LanguageCode): SpeechSynthesizerEngine? =
            throw NotImplementedError("No SpeechSynthesizerEngine configured in NoOp EngineFactory")
    }
}
