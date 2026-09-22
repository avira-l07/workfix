package com.itantra.core.inference

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive

enum class AecStatus {
    AEC_SUPPORTED,
    AEC_ENABLED,
    AEC_DISABLED,
    AEC_UNAVAILABLE
}

class MicrophoneAudioSource(
    scope: CoroutineScope,
    private val enableAecIfAvailable: Boolean = false
) {

    var currentAecStatus: AecStatus = AecStatus.AEC_UNAVAILABLE
        private set

    @SuppressLint("MissingPermission")
    val stream: SharedFlow<FloatArray> = callbackFlow {
        val sampleRate = 16000
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferSize <= 0) {
            close(IllegalStateException("Invalid AudioRecord minBufferSize: $minBufferSize"))
            return@callbackFlow
        }

        val bufferSize = minBufferSize * 2

        val audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            close(SecurityException("Permission denied for RECORD_AUDIO", e))
            return@callbackFlow
        } catch (e: IllegalArgumentException) {
            close(IllegalArgumentException("Unsupported audio parameters: ${e.message}", e))
            return@callbackFlow
        }

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            try { audioRecord.release() } catch (_: Exception) {}
            close(IllegalStateException("AudioRecord initialization failed (state uninitialized)"))
            return@callbackFlow
        }

        var echoCanceler: AcousticEchoCanceler? = null
        try {
            if (enableAecIfAvailable && AcousticEchoCanceler.isAvailable()) {
                currentAecStatus = AecStatus.AEC_SUPPORTED
                echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)
                if (echoCanceler != null) {
                    val res = echoCanceler.setEnabled(true)
                    currentAecStatus = if (res == AudioEffect.SUCCESS && echoCanceler.enabled) {
                        AecStatus.AEC_ENABLED
                    } else {
                        AecStatus.AEC_DISABLED
                    }
                } else {
                    currentAecStatus = AecStatus.AEC_UNAVAILABLE
                }
            } else {
                currentAecStatus = AecStatus.AEC_UNAVAILABLE
            }
        } catch (t: Throwable) {
            // Devices with buggy HAL or missing effects fallback gracefully
            currentAecStatus = AecStatus.AEC_UNAVAILABLE
            t.printStackTrace()
        }

        try {
            audioRecord.startRecording()
        } catch (e: SecurityException) {
            try { echoCanceler?.release() } catch (_: Throwable) {}
            try { audioRecord.release() } catch (_: Throwable) {}
            close(SecurityException("AudioRecord.startRecording SecurityException: ${e.message}", e))
            return@callbackFlow
        } catch (e: IllegalStateException) {
            try { echoCanceler?.release() } catch (_: Throwable) {}
            try { audioRecord.release() } catch (_: Throwable) {}
            close(IllegalStateException("AudioRecord.startRecording IllegalStateException: ${e.message}", e))
            return@callbackFlow
        }

        val buffer = ShortArray(bufferSize / 2)
        var zeroReadCount = 0

        try {
            while (isActive) {
                val readResult = audioRecord.read(buffer, 0, buffer.size)
                when {
                    readResult > 0 -> {
                        zeroReadCount = 0
                        val floatArray = FloatArray(readResult)
                        for (i in 0 until readResult) {
                            floatArray[i] = buffer[i] / 32768.0f
                        }
                        val sendResult = trySend(floatArray)
                        if (sendResult.isFailure) {
                            android.util.Log.w("MicrophoneAudioSource", "Audio buffer dropped! trySend failed: $sendResult")
                        }
                    }
                    readResult == 0 -> {
                        zeroReadCount++
                        if (zeroReadCount > 10) {
                            kotlinx.coroutines.delay(10)
                        } else {
                            kotlinx.coroutines.yield()
                        }
                    }
                    readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                        android.util.Log.e("MicrophoneAudioSource", "AudioRecord read error: ERROR_DEAD_OBJECT")
                        close(IllegalStateException("AudioRecord dead object"))
                        break
                    }
                    readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                        android.util.Log.e("MicrophoneAudioSource", "AudioRecord read error: ERROR_INVALID_OPERATION")
                        close(IllegalStateException("AudioRecord invalid operation"))
                        break
                    }
                    readResult == AudioRecord.ERROR_BAD_VALUE -> {
                        android.util.Log.e("MicrophoneAudioSource", "AudioRecord read error: ERROR_BAD_VALUE")
                        close(IllegalArgumentException("AudioRecord bad value"))
                        break
                    }
                    else -> {
                        // Any other negative readResult is a terminal error
                        android.util.Log.e("MicrophoneAudioSource", "AudioRecord read terminal error: $readResult")
                        close(IllegalStateException("AudioRecord read error: $readResult"))
                        break
                    }
                }
            }
        } finally {
            try {
                echoCanceler?.release()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            try {
                audioRecord.stop()
            } catch (_: Throwable) {}
            try {
                audioRecord.release()
            } catch (_: Throwable) {}
        }

        awaitClose {
            // Releasing is handled in finally block
        }
    }.buffer(64)
     .flowOn(Dispatchers.IO)
     .shareIn(
         scope = scope,
         started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
         replay = 0
     )
}
