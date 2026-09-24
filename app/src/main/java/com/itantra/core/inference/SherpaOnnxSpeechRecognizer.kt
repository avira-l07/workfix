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
import kotlinx.coroutines.withContext
import java.io.File

class SherpaOnnxSpeechRecognizer(
    private val context: Context,
    override val languageCode: LanguageCode,
    private val storage: LanguagePackStorage,
    private val metricsRecorder: MetricsRecorder,
    val autoDetect: Boolean = false
) : SpeechRecognizerEngine {

    private var recognizer: OfflineRecognizer? = null
    private val audioChunks = java.util.Collections.synchronizedList(mutableListOf<FloatArray>())

    override var isLoaded: Boolean = false
        private set

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        val spec = ModelFileSpecs.getSttSpec(languageCode) ?: throw UnsupportedOperationException("No STT spec for language ${languageCode}")
        val packsDir = storage.packDirectory(languageCode).parentFile // language_packs dir
        val packDir = storage.packDirectory(languageCode)

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
                    language = if (autoDetect) "" else languageCode.wireCode,
                    task = "transcribe",
                    tailPaddings = -1
                ).also { whisperCfg ->
                    android.util.Log.d(
                        "ITANTRA_MIC_FLOW",
                        "SherpaOnnxSpeechRecognizer.load: OfflineWhisperModelConfig built — " +
                        "encoder=${whisperCfg.encoder} | decoder=${whisperCfg.decoder} | " +
                        "language=\"${whisperCfg.language}\" (autoDetect=$autoDetect, languageCode=${languageCode.wireCode})"
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

    suspend fun decodeDirect(samples: FloatArray, silencePadding: Int = 0): SpeechRecognitionResult = withContext(Dispatchers.Default) {
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
        val whisperLang = LanguageCode.fromWireCode(detectedCode)
        val scriptLang = LanguageScriptDetector.detect(cleanedText, manualFallback = languageCode)

        val resolvedLanguage = if (!autoDetect) {
            // MANUAL: user explicitly selected this language — honor it unconditionally
            languageCode
        } else {
            // AUTO: detector/script/fallback chain
            whisperLang ?: scriptLang ?: languageCode
        }

        // Phase 3: Script mismatch diagnostic for manual Hindi
        val scriptDiagnostic = if (!autoDetect && languageCode == LanguageCode.HINDI && cleanedText.isNotBlank()) {
            if (!LanguageScriptDetector.containsDevanagari(cleanedText)) {
                "HINDI_SCRIPT_MISMATCH"
            } else null
        } else null

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
            pureInferenceMs = pureInferenceMs
        )
    }

    override suspend fun reset() {
        audioChunks.clear()
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        audioChunks.clear()
        recognizer?.release()
        recognizer = null
        isLoaded = false
    }
}
