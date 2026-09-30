package com.itantra.core.inference

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import com.itantra.core.metrics.MetricsRecorder
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechRecognitionResult
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** Only pass language tokens actually supported by this Whisper export. */
internal fun whisperLanguageCode(code: LanguageCode): String {
    require(code in ModelFileSpecs.supportedSttLanguages) { "Unsupported Whisper language: $code" }
    return code.wireCode
}

internal fun languageCodeFromWhisper(code: String): LanguageCode? =
    LanguageCode.fromWireCode(code.trim())?.takeIf { it in ModelFileSpecs.supportedSttLanguages }

class SherpaOnnxSpeechRecognizer(
    private val context: Context,
    override val languageCode: LanguageCode,
    private val storage: LanguagePackStorage,
    private val metricsRecorder: MetricsRecorder,
    val autoDetect: Boolean = false,
    val targetLanguage: LanguageCode? = null
) : SpeechRecognizerEngine {

    private val usesHindiModel = HindiSttModel.selected(languageCode, autoDetect)

    val isTranslateMode: Boolean
        // The Hindi fine-tune is a transcription model. Translation stays in the
        // existing text-translation stage rather than asking it to emit English.
        get() = !usesHindiModel && targetLanguage == LanguageCode.ENGLISH && languageCode != LanguageCode.ENGLISH


    private var recognizer: OfflineRecognizer? = null
    internal val recognizerMutex = Mutex()
    private val audioChunks = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())

    override var isLoaded: Boolean = false
        private set

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        require(languageCode in ModelFileSpecs.supportedSttLanguages) {
            "${languageCode.name} speech recognition is not supported by the installed Whisper model"
        }

        val spec = if (usesHindiModel) HindiSttModel.spec() else ModelFileSpecs.getSttSpec(languageCode)
        val packsDir = storage.packDirectory(languageCode).parentFile // language_packs dir
        val packDir = storage.packDirectory(languageCode)
        if (usesHindiModel) {
            check(packsDir != null && HindiSttModel.isInstalled(packsDir)) {
                "Hindi speech model is not prepared. Free storage and restart the app to finish installation."
            }
        }

        // Handle shared STT models
        val sttDir = if (spec.isShared && spec.sharedPath != null) {
            File(packsDir, spec.sharedPath)
        } else {
            File(packDir, "stt")
        }

        for (requiredFile in spec.requiredFiles) {
            val f = File(sttDir, requiredFile)
            if (!f.exists() || f.length() == 0L) {
                throw IllegalStateException("Missing or zero-byte STT model file: $requiredFile for language ${languageCode.wireCode}")
            }
        }

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val myPid = android.os.Process.myPid()

        fun getProcessPssBytes(): Long {
            val memoryInfoArray = activityManager.getProcessMemoryInfo(intArrayOf(myPid))
            return if (memoryInfoArray.isNotEmpty()) {
                memoryInfoArray[0].totalPss * 1024L
            } else 0L
        }

        val memoryBefore = getProcessPssBytes()
        val t0 = SystemClock.elapsedRealtimeNanos()

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = 16000,
                featureDim = 80
            ),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(sttDir, spec.mainModelFile).absolutePath,
                    decoder = File(sttDir, spec.auxFile!!).absolutePath,
                    language = if (autoDetect) "" else whisperLanguageCode(languageCode),
                    task = if (isTranslateMode) "translate" else "transcribe",
                    tailPaddings = -1
                ).also { whisperCfg ->
                    android.util.Log.d(
                        "ITANTRA_MIC_FLOW",
                        "SherpaOnnxSpeechRecognizer.load: OfflineWhisperModelConfig built — " +
                        "encoder=${whisperCfg.encoder} | decoder=${whisperCfg.decoder} | " +
                        "language=\"${whisperCfg.language}\" | task=\"${whisperCfg.task}\" (autoDetect=$autoDetect, languageCode=${languageCode.wireCode}, targetLang=${targetLanguage?.wireCode})"
                    )
                },
                tokens = File(sttDir, spec.tokensFile).absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
                // sherpa-onnx's native debug logging adds real per-inference overhead;
                // was left on, which skews exactly the latency numbers we're trying to measure.
                debug = false
            ),
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(config = config)

        val t1 = SystemClock.elapsedRealtimeNanos()
        val memoryAfter = getProcessPssBytes()

        metricsRecorder.recordSystemMemory(memoryAfter - memoryBefore)
        metricsRecorder.recordSttModelLoadTime((t1 - t0) / 1_000_000)

        isLoaded = true
    }

    override suspend fun feed(samples: FloatArray) {
        if (samples.isNotEmpty()) {
            audioChunks.add(samples.clone())
        }
    }

    override suspend fun finalizeUtterance(): SpeechRecognitionResult = withContext(Dispatchers.Default) {
        recognizerMutex.withLock {
            val rec = recognizer ?: throw IllegalStateException("Recognizer not loaded")

            val chunks = synchronized(audioChunks) {
                val copy = audioChunks.toList()
                audioChunks.clear()
                copy
            }

            if (chunks.isEmpty()) {
                return@withContext SpeechRecognitionResult(
                    text = "",
                    isFinal = true,
                    languageCode = languageCode,
                    confidence = null,
                    timestampMillis = 0L
                )
            }

            val totalSamples = chunks.sumOf { it.size }
            // 100ms (1600 samples) silence padding: provides sufficient acoustic tail for phoneme completion
            // without causing excessive silence that triggers autoregressive looping in Whisper.
            val silencePadding = 1600
            val fullWaveform = FloatArray(totalSamples + silencePadding)
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, fullWaveform, offset, chunk.size)
                offset += chunk.size
            }

            val stream = rec.createStream()
            var pureInferenceMs = 0L
            val whisperResult = try {
                stream.acceptWaveform(fullWaveform, sampleRate = 16000)
                val tDecodeStart = android.os.SystemClock.elapsedRealtimeNanos()
                rec.decode(stream)
                pureInferenceMs = (android.os.SystemClock.elapsedRealtimeNanos() - tDecodeStart) / 1_000_000
                rec.getResult(stream)
            } finally {
                stream.release()
            }

            processWhisperResult(whisperResult, pureInferenceMs)
        }
    }

    suspend fun decodeDirect(samples: FloatArray, silencePadding: Int = 0): SpeechRecognitionResult = withContext(Dispatchers.Default) {
        recognizerMutex.withLock {
            val rec = recognizer ?: throw IllegalStateException("Recognizer not loaded")
            val fullWaveform = if (silencePadding > 0) {
                val arr = FloatArray(samples.size + silencePadding)
                System.arraycopy(samples, 0, arr, 0, samples.size)
                arr
            } else {
                samples
            }
            val stream = rec.createStream()
            var pureInferenceMs = 0L
            val whisperResult = try {
                stream.acceptWaveform(fullWaveform, sampleRate = 16000)
                val tDecodeStart = android.os.SystemClock.elapsedRealtimeNanos()
                rec.decode(stream)
                pureInferenceMs = (android.os.SystemClock.elapsedRealtimeNanos() - tDecodeStart) / 1_000_000
                rec.getResult(stream)
            } finally {
                stream.release()
            }

            processWhisperResult(whisperResult, pureInferenceMs)
        }
    }

    private fun processWhisperResult(
        whisperResult: OfflineRecognizerResult,
        pureInferenceMs: Long
    ): SpeechRecognitionResult {
        val rawText = whisperResult.text.trim()
        val detectedCode = whisperResult.lang.trim()
        val cleanedText = TranscriptPostProcessor.postProcess(rawText)

        // Language resolution priority (Phase 2 fix):
        // MANUAL mode: manual selection ALWAYS wins — this is the UI contract.
        // AUTO mode: Whisper detected > script detector > configured fallback.
        val whisperLang = languageCodeFromWhisper(detectedCode)
        val scriptLang = LanguageScriptDetector.detect(cleanedText, manualFallback = languageCode)

        val resolvedLanguage = if (!autoDetect) {
            // MANUAL: user explicitly selected this language — honor it unconditionally
            languageCode
        } else {
            // AUTO: detector/script/fallback chain
            whisperLang ?: scriptLang ?: languageCode
        }

        // Native Whisper translation deliberately returns English text, so a script
        // check would reject valid output there. For direct transcription, reject
        // Latin hallucinations (or another script) instead of sending them as the
        // selected Indic language.
        val scriptDiagnostic = speechOutputDiagnostic(
            cleanedText, detectedCode, resolvedLanguage, autoDetect, isTranslateMode
        )

        // A script mismatch is not evidence of romanized Hindi. Converting arbitrary
        // English output to Devanagari cannot repair recognition and corrupts the
        // valid English output of Whisper's native translation mode.

        if (com.example.itantra.BuildConfig.DEBUG) {
            android.util.Log.d(
                "STT_LANG",
                "STT_MODE=${if (autoDetect) "AUTO" else "MANUAL"} " +
                "STT_HINT=${if (autoDetect) "<auto>" else languageCode.wireCode} " +
                "WHISPER_DETECTED=$detectedCode " +
                "SCRIPT_DETECTED=${scriptLang?.wireCode ?: "none"} " +
                "RESOLVED_SOURCE_LANGUAGE=${resolvedLanguage.name}" +
                (if (scriptDiagnostic != null) " SCRIPT_DIAGNOSTIC=$scriptDiagnostic" else "")
            )
        }

        // Phase 4: confidence = null — Whisper Tiny does not expose meaningful per-utterance
        // confidence. Never hard-code 1.0 which falsely implies perfect accuracy.
        return SpeechRecognitionResult(
            text = cleanedText,
            isFinal = true,
            languageCode = resolvedLanguage,
            confidence = null,
            timestampMillis = System.currentTimeMillis(),
            pureInferenceMs = pureInferenceMs,
            diagnostic = scriptDiagnostic
        )
    }

    override suspend fun reset() {
        audioChunks.clear()
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
            recognizerMutex.withLock {
                audioChunks.clear()
                try {
                    recognizer?.release()
                } catch (t: Throwable) {
                    android.util.Log.w("SherpaOnnxRecognizer", "Error during recognizer.release(): ${t.message}")
                } finally {
                    recognizer = null
                    isLoaded = false
                }
            }
        }
    }
}
