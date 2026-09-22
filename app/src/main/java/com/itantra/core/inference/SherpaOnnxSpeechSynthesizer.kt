package com.itantra.core.inference

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.itantra.core.metrics.MetricsRecorder
import com.itantra.core.storage.LanguagePackStorage
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class TtsLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class TtsSynthesisException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class SherpaOnnxSpeechSynthesizer(
    private val context: Context,
    override val languageCode: LanguageCode,
    private val storage: LanguagePackStorage,
    private val metricsRecorder: MetricsRecorder
) : SpeechSynthesizerEngine {

    private var tts: OfflineTts? = null
    private val synthMutex = Mutex()

    override var isLoaded: Boolean = false
        private set

    override suspend fun load() = withContext(Dispatchers.IO) {
        if (isLoaded) return@withContext

        val spec = ModelFileSpecs.getTtsSpec(languageCode)
            ?: throw TtsLoadException("TTS_UNSUPPORTED_LANGUAGE: No TTS spec for language $languageCode")

        val packDir = storage.packDirectory(languageCode)
        val ttsDir = File(packDir, "tts")
        if (!ttsDir.exists() || !ttsDir.isDirectory) {
            throw TtsLoadException("TTS_MODEL_MISSING: TTS directory does not exist for ${languageCode.wireCode}")
        }

        // 1. Validate main model file
        val modelFile = File(ttsDir, spec.mainModelFile)
        if (!modelFile.exists()) {
            throw TtsLoadException("TTS_MODEL_MISSING: ${spec.mainModelFile} missing for ${languageCode.wireCode}")
        }
        if (modelFile.length() == 0L) {
            throw TtsLoadException("TTS_MODEL_EMPTY: ${spec.mainModelFile} is zero-byte for ${languageCode.wireCode}")
        }

        // 2. Validate tokens file
        val tokensFile = File(ttsDir, spec.tokensFile)
        if (!tokensFile.exists()) {
            throw TtsLoadException("TTS_TOKENS_MISSING: ${spec.tokensFile} missing for ${languageCode.wireCode}")
        }
        if (tokensFile.length() == 0L) {
            throw TtsLoadException("TTS_TOKENS_EMPTY: ${spec.tokensFile} is zero-byte for ${languageCode.wireCode}")
        }
        if (!tokensFile.canRead()) {
            throw TtsLoadException("TTS_TOKENS_UNREADABLE: ${spec.tokensFile} cannot be read for ${languageCode.wireCode}")
        }

        val hasValidTokens = try {
            tokensFile.useLines { lines ->
                lines.any { it.trim().isNotEmpty() }
            }
        } catch (e: Exception) {
            throw TtsLoadException("TTS_TOKENS_INVALID: Error reading ${spec.tokensFile} for ${languageCode.wireCode}", e)
        }
        if (!hasValidTokens) {
            throw TtsLoadException("TTS_TOKENS_INVALID: ${spec.tokensFile} contains no valid token entries for ${languageCode.wireCode}")
        }

        // 3. RAM Guard: verify sufficient runtime memory before loading native weights
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            // Model size + 30MB safety headroom
            val requiredBytes = modelFile.length() + 30L * 1024L * 1024L
            if (memInfo.lowMemory || memInfo.availMem < requiredBytes) {
                throw TtsLoadException("TTS_INSUFFICIENT_MEMORY: avail=${memInfo.availMem} required=$requiredBytes lowMemory=${memInfo.lowMemory}")
            }
        }

        val t0 = SystemClock.elapsedRealtimeNanos()
        Log.i("SherpaOnnxTTS", "Loading TTS for ${languageCode.wireCode}: model=${modelFile.name}, size=${modelFile.length()} bytes")

        // 4. Optional lexicon / data directory paths
        val lexiconFile = File(ttsDir, "lexicon.txt")
        val lexiconPath = if (lexiconFile.exists() && lexiconFile.length() > 0L) lexiconFile.absolutePath else ""
        val dataDir = File(ttsDir, "espeak-ng-data")
        val dataDirPath = if (dataDir.exists() && dataDir.isDirectory) dataDir.absolutePath else ""
        val dictDir = File(ttsDir, "dict")
        val dictDirPath = if (dictDir.exists() && dictDir.isDirectory) dictDir.absolutePath else ""

        // 5. Initialize native OfflineTts with error isolation
        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        lexicon = lexiconPath,
                        dataDir = dataDirPath,
                        dictDir = dictDirPath
                    ),
                    numThreads = 1,
                    debug = false
                )
            )
            tts = OfflineTts(config = config)
        } catch (t: Throwable) {
            try { tts?.release() } catch (_: Throwable) {}
            tts = null
            isLoaded = false
            throw TtsLoadException("TTS_NATIVE_LOAD_FAILED: Could not load native TTS for ${languageCode.wireCode}: ${t.message}", t)
        }

        val t1 = SystemClock.elapsedRealtimeNanos()
        val loadTimeMs = (t1 - t0) / 1_000_000
        metricsRecorder.recordTtsModelLoadTime(loadTimeMs)
        Log.i("SherpaOnnxTTS", "TTS model loaded successfully for ${languageCode.wireCode} in ${loadTimeMs}ms, sampleRate=${tts?.sampleRate()}")

        isLoaded = true
    }

    override suspend fun synthesize(request: SpeechSynthesisRequest): SpeechSynthesisResult = withContext(Dispatchers.Default) {
        val engine = tts ?: throw IllegalStateException("TTS_ENGINE_NOT_LOADED")

        // Reject empty or whitespace text truthfully before native inference
        if (request.text.isBlank()) {
            Log.w("SherpaOnnxTTS", "Rejecting blank/empty text for synthesis: correlationId=${request.correlationId}")
            throw IllegalArgumentException("TTS_BLANK_TEXT")
        }

        synthMutex.withLock {
            val t0 = SystemClock.elapsedRealtimeNanos()
            Log.i("SherpaOnnxTTS", "TTS synth start lang=${languageCode.wireCode} chars=${request.text.length} correlationId=${request.correlationId}")

            val generatedAudio = try {
                engine.generate(request.text)
            } catch (t: Throwable) {
                throw TtsSynthesisException("TTS_SYNTHESIS_FAILED: Native generation failed for ${languageCode.wireCode}: ${t.message}", t)
            }

            val samples = generatedAudio.samples
            val sampleRate = generatedAudio.sampleRate

            // Validate generated audio
            if (samples.isEmpty() || sampleRate <= 0) {
                throw TtsSynthesisException("TTS_EMPTY_PCM: Generated ${samples.size} samples at ${sampleRate}Hz")
            }

            val t1 = SystemClock.elapsedRealtimeNanos()
            val synthesisTimeMs = (t1 - t0) / 1_000_000
            val audioDurationMs = (samples.size.toFloat() / sampleRate * 1000).toLong()
            val rtf = if (audioDurationMs > 0) synthesisTimeMs.toFloat() / audioDurationMs.toFloat() else 0f

            metricsRecorder.recordTtsTimeToFirstAudio(synthesisTimeMs)
            metricsRecorder.recordTtsSynthesisDuration(synthesisTimeMs)
            metricsRecorder.recordTtsRealTimeFactor(rtf.toDouble())

            Log.i("SherpaOnnxTTS", "Synthesized ${samples.size} samples at ${sampleRate}Hz in ${synthesisTimeMs}ms (rtf=${"%.2f".format(rtf)})")

            SpeechSynthesisResult(
                correlationId = request.correlationId,
                pcmAudio = samples,
                sampleRateHz = sampleRate,
                channelCount = 1,
                durationMillis = audioDurationMs
            )
        }
    }

    override suspend fun unload() {
        withContext(Dispatchers.IO) {
            synthMutex.withLock {
                try {
                    tts?.release()
                } catch (t: Throwable) {
                    Log.w("SherpaOnnxTTS", "Error during tts.release(): ${t.message}")
                } finally {
                    tts = null
                    isLoaded = false
                }
            }
        }
    }
}

