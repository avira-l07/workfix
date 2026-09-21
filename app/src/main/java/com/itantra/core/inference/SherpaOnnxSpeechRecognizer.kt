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
    private val metricsRecorder: MetricsRecorder
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
                    language = languageCode.wireCode,
                    task = "transcribe",
                    tailPaddings = -1
                ),
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
                confidence = 0f,
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
        val resultText = try {
            stream.acceptWaveform(fullWaveform, sampleRate = 16000)
            val tDecodeStart = android.os.SystemClock.elapsedRealtimeNanos()
            rec.decode(stream)
            pureInferenceMs = (android.os.SystemClock.elapsedRealtimeNanos() - tDecodeStart) / 1_000_000
            val raw = rec.getResult(stream).text.trim()
            val cleaned = raw.replace(Regex("<\\|.*?\\|>"), "")
                .replace(Regex("^[\\(\\)\\[\\]\\s]+|[\\(\\)\\[\\]\\s]+$"), "")
                .trim()
            SpeechDeduplicator.deduplicate(cleaned)
        } finally {
            stream.release()
        }

        SpeechRecognitionResult(
            text = resultText,
            isFinal = true,
            languageCode = languageCode,
            confidence = 1.0f,
            timestampMillis = System.currentTimeMillis(),
            pureInferenceMs = pureInferenceMs
        )
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
        val resultText = try {
            stream.acceptWaveform(fullWaveform, sampleRate = 16000)
            val tDecodeStart = android.os.SystemClock.elapsedRealtimeNanos()
            rec.decode(stream)
            pureInferenceMs = (android.os.SystemClock.elapsedRealtimeNanos() - tDecodeStart) / 1_000_000
            val raw = rec.getResult(stream).text.trim()
            raw.replace(Regex("<\\|.*?\\|>"), "")
                .replace(Regex("^[\\(\\)\\[\\]\\s]+|[\\(\\)\\[\\]\\s]+$"), "")
                .trim()
        } finally {
            stream.release()
        }

        SpeechRecognitionResult(
            text = resultText,
            isFinal = true,
            languageCode = languageCode,
            confidence = 1.0f,
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
