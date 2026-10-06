package com.itantra.core.inference

import com.itantra.domain.model.LanguageCode
import com.itantra.domain.model.SpeechSynthesisRequest
import com.itantra.domain.model.SpeechSynthesisResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SavedSpeechPlayback(
    val itemKey: String? = null,
    val busy: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null,
)

/** Local read-aloud only: no transport, acknowledgments, history updates or stored audio. */
class SavedSpeechPlayer(
    private val scope: CoroutineScope,
    private val session: ActiveLanguageSessionManager,
    private val isInstalled: (LanguageCode) -> Boolean,
    private val playAudio: suspend (SpeechSynthesisResult) -> Unit,
    private val stopAudio: () -> Unit,
) {
    private val _state = MutableStateFlow(SavedSpeechPlayback())
    val state = _state.asStateFlow()
    @Volatile private var job: Job? = null
    private var generation = 0L

    @Synchronized fun play(key: String, text: String, language: LanguageCode?) {
        val previous = job
        stop()
        val run = ++generation
        if (text.isBlank() || language == null || !isInstalled(language)) {
            _state.value = SavedSpeechPlayback(key, error = if (language == null) "Text language is unknown" else if (text.isBlank()) "Nothing to speak" else "Install the ${language.name.lowercase()} Receive (TTS) pack in Language Packs")
            return
        }
        _state.value = SavedSpeechPlayback(key, busy = true, preparing = true)
        job = scope.launch {
            try {
                // Native generation may take a moment to cancel. Do not unload its engine early.
                previous?.cancelAndJoin()
                val audio = session.synthesizeTts(SpeechSynthesisRequest(language, text, "local-replay-$key"))
                ensureActive()
                check(audio.sampleRateHz > 0 && audio.pcmAudio.isNotEmpty() &&
                    audio.pcmAudio.all { it.isFinite() } && audio.pcmAudio.any { kotlin.math.abs(it) > 0.001f }) { "TTS produced silent or invalid audio" }
                publish(run, SavedSpeechPlayback(key, busy = true))
                playAudio(audio)
                ensureActive()
                publish(run, SavedSpeechPlayback(key))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publish(run, SavedSpeechPlayback(key, error = error.message ?: "Speech playback failed"))
            }
        }
    }

    @Synchronized fun stop() {
        ++generation
        job?.cancel()
        stopAudio()
        _state.value = SavedSpeechPlayback(_state.value.itemKey)
    }

    suspend fun awaitStopped() { job?.join() }

    @Synchronized fun unavailable(key: String, reason: String) {
        stop()
        _state.value = SavedSpeechPlayback(key, error = reason)
    }

    @Synchronized private fun publish(run: Long, state: SavedSpeechPlayback) {
        if (run == generation) _state.value = state
    }
}
