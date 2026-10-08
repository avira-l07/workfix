package com.itantra.feature.benchmark

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itantra.core.audio.SpeakerAudioSink
import com.itantra.core.inference.ActiveLanguageSessionManager
import com.itantra.data.benchmark.LocalBenchmarkRepository
import com.itantra.domain.model.EvidenceLevel
import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.TtsEvaluationSession
import com.itantra.domain.model.TtsSentenceEvaluation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TtsJsonSentence(
    val id: String,
    val text: String
)

data class TtsEvaluationState(
    val selectedLanguage: String = "hi",
    val availableLanguages: List<String> = listOf("hi", "en", "bn", "gu", "mr", "kn", "ml", "ta", "te", "or"),
    val evaluatorId: String = "Evaluator 1",
    val sentences: List<TtsJsonSentence> = emptyList(),
    val currentIndex: Int = 0,
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val statusMessage: String = "",
    // Current sentence evaluation form
    val intelligible: Boolean = true,
    val naturalness: Int = 4,
    val pronunciation: Int = 4,
    val comments: String = "",
    // Last synthesis telemetry
    val lastComputeTimeMs: Long = 0L,
    val lastAudioDurationMs: Long = 0L,
    val lastSampleRate: Int = 0,
    val lastSampleCount: Int = 0,
    val lastRtf: Float = 0f,
    val hasSynthesizedCurrent: Boolean = false,
    // Session results
    val recordedEvaluations: List<TtsSentenceEvaluation> = emptyList(),
    val sessionFinished: Boolean = false,
    val savedSession: TtsEvaluationSession? = null
)

@SuppressLint("StaticFieldLeak")
class TtsEvaluationViewModel(
    private val context: Context,
    private val activeLanguageSessionManager: ActiveLanguageSessionManager,
    private val benchmarkRepository: LocalBenchmarkRepository
) : ViewModel() {

    private val _state = MutableStateFlow(TtsEvaluationState())
    val state: StateFlow<TtsEvaluationState> = _state

    private var audioSink: SpeakerAudioSink? = null
    private var lastGeneratedSamples: FloatArray? = null
    private var playbackJob: Job? = null
    private var playbackGeneration = 0L

    private fun stopPlayback() {
        playbackGeneration++
        playbackJob?.cancel()
        audioSink?.stopImmediate()
        lastGeneratedSamples = null
    }

    init {
        loadSentences(_state.value.selectedLanguage)
    }

    fun selectLanguage(lang: String) {
        if (lang == _state.value.selectedLanguage && _state.value.sentences.isNotEmpty()) return
        stopPlayback()
        _state.update {
            it.copy(
                selectedLanguage = lang,
                currentIndex = 0,
                recordedEvaluations = emptyList(),
                sessionFinished = false,
                hasSynthesizedCurrent = false,
                isSynthesizing = false,
                isPlaying = false,
                statusMessage = "",
                savedSession = null
            )
        }
        loadSentences(lang)
    }

    fun setEvaluatorId(id: String) {
        _state.update { it.copy(evaluatorId = id) }
    }

    fun setIntelligible(value: Boolean) {
        _state.update { it.copy(intelligible = value) }
    }

    fun setNaturalness(value: Int) {
        _state.update { it.copy(naturalness = value.coerceIn(1, 5)) }
    }

    fun setPronunciation(value: Int) {
        _state.update { it.copy(pronunciation = value.coerceIn(1, 5)) }
    }

    fun setComments(value: String) {
        _state.update { it.copy(comments = value) }
    }

    private fun loadSentences(lang: String) {
        viewModelScope.launch {
            try {
                val jsonString = context.assets.open("benchmark/tts_sentences.json").bufferedReader().use { it.readText() }
                val json = Json { ignoreUnknownKeys = true }
                val map = json.decodeFromString<Map<String, List<TtsJsonSentence>>>(jsonString)
                val list = map[lang] ?: emptyList()
                _state.update {
                    it.copy(
                        sentences = list,
                        currentIndex = 0,
                        hasSynthesizedCurrent = false,
                        intelligible = true,
                        naturalness = 4,
                        pronunciation = 4,
                        comments = "",
                        statusMessage = if (list.isEmpty()) "No sentences found for $lang" else ""
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(statusMessage = "Error loading sentences: ${e.message}") }
            }
        }
    }

    fun synthesizeAndPlay() {
        if (_state.value.isPlaying || _state.value.isSynthesizing || _state.value.sessionFinished) return
        val currentSentences = _state.value.sentences
        if (currentSentences.isEmpty()) return
        val currentSentence = currentSentences[_state.value.currentIndex]

        val language = _state.value.selectedLanguage
        val previous = playbackJob
        val run = ++playbackGeneration
        _state.update { it.copy(isSynthesizing = true, hasSynthesizedCurrent = false, statusMessage = "Synthesizing...") }
        playbackJob = viewModelScope.launch {
            var sink: SpeakerAudioSink? = null
            try {
                previous?.join()
                val t0 = SystemClock.elapsedRealtimeNanos()
                val langCode = LanguageCode.fromWireCode(language) ?: error("Unknown TTS language")
                val req = SpeechSynthesisRequest(
                    correlationId = currentSentence.id,
                    text = currentSentence.text,
                    languageCode = langCode
                )
                val result = activeLanguageSessionManager.synthesizeTts(req)
                ensureActive()
                com.itantra.core.inference.requireSpeechAudio(result)
                val t1 = SystemClock.elapsedRealtimeNanos()
                val computeTimeMs = (t1 - t0) / 1_000_000

                val samples = result.pcmAudio
                val sampleRate = result.sampleRateHz
                val audioDurationMs = if (sampleRate > 0) (samples.size.toFloat() / sampleRate * 1000).toLong() else 0L
                val rtf = if (audioDurationMs > 0) computeTimeMs.toFloat() / audioDurationMs else 0f

                lastGeneratedSamples = samples

                _state.update {
                    it.copy(
                        isSynthesizing = false,
                        isPlaying = true,
                        lastComputeTimeMs = computeTimeMs,
                        lastAudioDurationMs = audioDurationMs,
                        lastSampleRate = sampleRate,
                        lastSampleCount = samples.size,
                        lastRtf = rtf,
                        statusMessage = "Playing audio ($audioDurationMs ms, compute: $computeTimeMs ms)..."
                    )
                }

                // Play PCM audio
                withContext(Dispatchers.IO) {
                    ensureActive()
                    sink = SpeakerAudioSink(context)
                    audioSink = sink
                    sink!!.init(sampleRate)
                    sink!!.play(samples)
                    sink!!.flushAndStop()
                }
                ensureActive()
                if (run == playbackGeneration) _state.update { it.copy(isPlaying = false, hasSynthesizedCurrent = true, statusMessage = "Playback completed. Please rate intelligibility.") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                e.printStackTrace()
                if (run == playbackGeneration) _state.update { it.copy(isSynthesizing = false, isPlaying = false, hasSynthesizedCurrent = false, statusMessage = "Error: ${e.message}") }
            } finally {
                sink?.release()
                if (audioSink === sink) audioSink = null
            }
        }
    }

    fun playAgain() {
        if (!_state.value.hasSynthesizedCurrent || _state.value.isPlaying || _state.value.isSynthesizing) return
        val samples = lastGeneratedSamples ?: return
        val rate = _state.value.lastSampleRate
        if (rate <= 0) return

        val run = ++playbackGeneration
        _state.update { it.copy(isPlaying = true, statusMessage = "Playing audio again...") }
        playbackJob = viewModelScope.launch(Dispatchers.IO) {
            val sink = SpeakerAudioSink(context)
            audioSink = sink
            try {
                sink.init(rate)
                sink.play(samples)
                sink.flushAndStop()
                ensureActive()
                if (run == playbackGeneration) _state.update { it.copy(isPlaying = false, statusMessage = "Playback completed.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (run == playbackGeneration) _state.update { it.copy(isPlaying = false, statusMessage = "Playback failed: ${error.message}") }
            } finally {
                sink.release()
                if (audioSink === sink) audioSink = null
            }
        }
    }

    fun recordAndNext() {
        if (!_state.value.hasSynthesizedCurrent || _state.value.isPlaying || _state.value.isSynthesizing || _state.value.sessionFinished) return
        val currentSentences = _state.value.sentences
        if (currentSentences.isEmpty()) return
        val currentSentence = currentSentences[_state.value.currentIndex]

        val evaluation = TtsSentenceEvaluation(
            sentenceId = currentSentence.id,
            text = currentSentence.text,
            computeTimeMs = _state.value.lastComputeTimeMs,
            audioDurationMs = _state.value.lastAudioDurationMs,
            sampleRateHz = _state.value.lastSampleRate,
            pcmSampleCount = _state.value.lastSampleCount,
            isNonEmptyPcm = _state.value.lastSampleCount > 0,
            intelligible = _state.value.intelligible,
            naturalness = _state.value.naturalness,
            pronunciation = _state.value.pronunciation,
            comments = _state.value.comments
        )

        val newEvaluations = _state.value.recordedEvaluations + evaluation
        val nextIdx = _state.value.currentIndex + 1
        lastGeneratedSamples = null

        if (nextIdx < currentSentences.size) {
            _state.update {
                it.copy(
                    recordedEvaluations = newEvaluations,
                    currentIndex = nextIdx,
                    hasSynthesizedCurrent = false,
                    intelligible = true,
                    naturalness = 4,
                    pronunciation = 4,
                    comments = "",
                    statusMessage = "Sentence ${nextIdx + 1}/${currentSentences.size}"
                )
            }
        } else {
            // Completed all sentences for this language
            _state.update {
                it.copy(
                    recordedEvaluations = newEvaluations,
                    sessionFinished = true,
                    statusMessage = "Evaluation Complete!"
                )
            }
            saveSession(newEvaluations)
        }
    }

    private fun saveSession(evaluations: List<TtsSentenceEvaluation>) {
        viewModelScope.launch {
            val intelligibleCount = evaluations.count { it.intelligible }
            val total = evaluations.size
            val rate = if (total > 0) intelligibleCount.toFloat() / total else 0f
            val meanNat = if (total > 0) evaluations.map { it.naturalness }.average().toFloat() else 0f
            val meanPron = if (total > 0) evaluations.map { it.pronunciation }.average().toFloat() else 0f
            val meanCompute = if (total > 0) evaluations.map { it.computeTimeMs }.average().toLong() else 0L
            val meanAudio = if (total > 0) evaluations.map { it.audioDurationMs }.average().toLong() else 0L
            val meanRtf = if (meanAudio > 0) meanCompute.toFloat() / meanAudio else 0f

            val session = TtsEvaluationSession(
                timestampMs = System.currentTimeMillis(),
                language = _state.value.selectedLanguage,
                evaluatorId = _state.value.evaluatorId,
                modelIdentity = if (_state.value.selectedLanguage == LanguageCode.MARATHI.wireCode)
                    "Piper mr_IN-google-medium; offline eSpeak; speaker 0" else "Meta MMS TTS VITS ONNX",
                sentencesTested = total,
                intelligibleCount = intelligibleCount,
                intelligibilityPercent = rate,
                meanNaturalness = meanNat,
                meanPronunciation = meanPron,
                meanComputeTimeMs = meanCompute,
                meanAudioDurationMs = meanAudio,
                meanRtf = meanRtf,
                evidenceLevel = EvidenceLevel.HUMAN_REVIEWED,
                evaluations = evaluations
            )

            benchmarkRepository.saveTtsEvaluation(session)
            _state.update { it.copy(savedSession = session) }
        }
    }

    fun resetEvaluation() {
        stopPlayback()
        _state.update {
            it.copy(
                currentIndex = 0,
                recordedEvaluations = emptyList(),
                sessionFinished = false,
                hasSynthesizedCurrent = false,
                isPlaying = false,
                isSynthesizing = false,
                statusMessage = "",
                savedSession = null
            )
        }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}
