package com.itantra.core.inference

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class AecStatus { AEC_SUPPORTED, AEC_ENABLED, AEC_DISABLED, AEC_UNAVAILABLE }

/** SharedFlow cannot deliver upstream exceptions. Deliver them as values before sharing. */
internal fun shareMicrophoneFrames(source: Flow<FloatArray>, scope: CoroutineScope): Flow<FloatArray> =
    source.map { Result.success(it) }
        .catch { emit(Result.failure(it)) }
        .buffer(64)
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)
        .map { it.getOrThrow() }

class MicrophoneAudioSource(
    scope: CoroutineScope,
    private val enableAecIfAvailable: Boolean = false,
) {
    var currentAecStatus: AecStatus = AecStatus.AEC_UNAVAILABLE
        private set

    @SuppressLint("MissingPermission")
    val stream: Flow<FloatArray> = shareMicrophoneFrames(flow {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBufferSize > 0) { "Invalid AudioRecord minBufferSize: $minBufferSize" }
        val bufferSize = minBufferSize * 2
        val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        var echoCanceler: AcousticEchoCanceler? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            currentAecStatus = AecStatus.AEC_UNAVAILABLE
            try {
                if (enableAecIfAvailable && AcousticEchoCanceler.isAvailable()) {
                    currentAecStatus = AecStatus.AEC_SUPPORTED
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)
                    currentAecStatus = if (echoCanceler?.setEnabled(true) == AudioEffect.SUCCESS && echoCanceler?.enabled == true)
                        AecStatus.AEC_ENABLED else AecStatus.AEC_DISABLED
                }
            } catch (_: Exception) { currentAecStatus = AecStatus.AEC_UNAVAILABLE }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            val buffer = ShortArray(bufferSize / 2)
            while (currentCoroutineContext().isActive) {
                // Blocking HAL reads can otherwise prevent cancellation and microphone release.
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                check(read >= 0) { "Microphone read failed ($read). Check microphone access and retry." }
                if (read > 0) emit(FloatArray(read) { buffer[it] / 32768f }) else delay(10)
            }
        } finally {
            try { echoCanceler?.release() } catch (_: Exception) {}
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
    }.flowOn(Dispatchers.IO), scope)
}
