package com.itantra.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Handles playing synthesized raw PCM float arrays directly to the device speaker.
 * Used for Text-to-Speech output and safety-critical emergency audio alerts.
 *
 * Operational Hardening:
 * 1. Completion Tracking: Polls AudioTrack.playbackHeadPosition to guarantee complete audible
 *    rendering before stopping, preventing syllable clipping at the end of utterances.
 * 2. Audio Focus: Uses AudioFocusRequest (API 26+) to request appropriate transient focus,
 *    supporting ducking for regular speech and exclusive gain for alarm alerts.
 * 3. Volume Intent: Emergency alarms request maximum-volume intent while saving and restoring
 *    the user's original alarm volume on release. (Actual volume behavior depends on Android/OEM policy).
 */
class SpeakerAudioSink(
    private val context: Context? = null
) {

    private var audioTrack: AudioTrack? = null
    private var sampleRate: Int = 16000
    private var totalFramesWritten: Long = 0L
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var originalAlarmVolume: Int? = null
    private var hasElevatedVolume: Boolean = false
    private var hasSetCommunicationDevice: Boolean = false

    /**
     * Initializes the AudioTrack with the specified sample rate and usage attributes.
     * @param sampleRate Typically 16000 Hz or 22050 Hz.
     * @param usage AudioAttributes.USAGE_MEDIA (default) or AudioAttributes.USAGE_ALARM.
     * @param requestMaxVolume When true and usage is USAGE_ALARM, requests maximum alarm volume.
     */
    fun init(
        sampleRate: Int,
        usage: Int = AudioAttributes.USAGE_MEDIA,
        requestMaxVolume: Boolean = false
    ) {
        release() // ensure clean state
        this.sampleRate = sampleRate
        this.totalFramesWritten = 0L

        audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Route to communication device only if explicitly configured for voice communication.
        // For normal USAGE_MEDIA, rely on Android standard media/loudspeaker routing.
        if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) {
            audioManager?.let { am ->
                routeToSpeakerIfPossible(am)
            }
        }

        val contentType = if (usage == AudioAttributes.USAGE_ALARM) {
            AudioAttributes.CONTENT_TYPE_SONIFICATION
        } else {
            AudioAttributes.CONTENT_TYPE_SPEECH
        }

        val attributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(sampleRate / 10 * 4) // minimum 100ms buffer

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("AUDIO_TRACK_INIT_FAILED: AudioTrack state is ${track.state}")
        }
        audioTrack = track

        requestAudioFocus(attributes, usage)

        if (usage == AudioAttributes.USAGE_ALARM && requestMaxVolume) {
            applyEmergencyVolumeIntent()
        }

        track.play()
        android.util.Log.i("SpeakerAudioSink", "AUDIO_PLAY_STARTED: usage=$usage at ${android.os.SystemClock.elapsedRealtime()}")
    }

    private fun routeToSpeakerIfPossible(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val devices = am.availableCommunicationDevices
                val speaker = devices.find { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    val success = am.setCommunicationDevice(speaker)
                    hasSetCommunicationDevice = success
                    android.util.Log.i("SpeakerAudioSink", "AUDIO_ROUTE: setCommunicationDevice(BUILTIN_SPEAKER) success=$success")
                }
            } catch (e: Exception) {
                android.util.Log.w("SpeakerAudioSink", "Could not set communication device to speaker", e)
            }
        }
    }

    private fun clearSpeakerRouting(am: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasSetCommunicationDevice) {
            try {
                am.clearCommunicationDevice()
                hasSetCommunicationDevice = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun requestAudioFocus(attributes: AudioAttributes, usage: Int) {
        try {
            val am = audioManager ?: return

            val focusResult = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusGain = if (usage == AudioAttributes.USAGE_ALARM) {
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                } else {
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                }

                val req = AudioFocusRequest.Builder(focusGain)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { /* handle ducking */ }
                    .build()

                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    if (usage == AudioAttributes.USAGE_ALARM) AudioManager.STREAM_ALARM else AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }

            when (focusResult) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> android.util.Log.i("SpeakerAudioSink", "AUDIO_FOCUS_GRANTED")
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> android.util.Log.i("SpeakerAudioSink", "AUDIO_FOCUS_DELAYED")
                else -> android.util.Log.w("SpeakerAudioSink", "AUDIO_FOCUS_FAILED ($focusResult)")
            }
        } catch (e: Exception) {
            android.util.Log.w("SpeakerAudioSink", "Audio focus request failed with exception", e)
        }
    }

    private fun applyEmergencyVolumeIntent() {
        try {
            val am = audioManager ?: return
            originalAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)
            hasElevatedVolume = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Writes raw float samples to the audio track. Blocks until audio is queued.
     * Completes write across multiple chunks if needed and validates frame counts.
     */
    suspend fun play(samples: FloatArray) = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) return@withContext
        val track = audioTrack ?: throw IllegalStateException("AudioSink not initialized")
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        var offset = 0
        while (offset < samples.size) {
            val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written <= 0) {
                throw IllegalStateException("AUDIO_WRITE_FAILED: AudioTrack.write returned $written at offset $offset/${samples.size}")
            }
            offset += written
            totalFramesWritten += written
        }
        android.util.Log.i("SpeakerAudioSink", "AudioTrack wrote ${samples.size} frames (total=$totalFramesWritten, sampleRate=$sampleRate)")
    }

    /**
     * Waits for all queued PCM frames to physically complete playback through the DAC/speaker
     * before issuing stop(). Uses AudioTrack.playbackHeadPosition to guarantee that the final
     * word/syllable is not clipped.
     */
    suspend fun flushAndStop() = withContext(Dispatchers.IO) {
        val track = audioTrack ?: return@withContext
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING && sampleRate > 0) {
                val maxWaitMillis = ((totalFramesWritten * 1000L) / sampleRate) + 250L
                val startTime = System.currentTimeMillis()

                // Wait until playback head reaches queued frames or timeout expires
                while (track.playbackHeadPosition < totalFramesWritten && (System.currentTimeMillis() - startTime) < maxWaitMillis) {
                    delay(20)
                }
            }
            track.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioManager?.let { clearSpeakerRouting(it) }
        }
    }

    /**
     * Immediately halts playback without waiting for buffered PCM frames to drain.
     * Pauses, flushes hardware/software buffers, and stops the AudioTrack.
     * Used for instant emergency preemption.
     */
    fun stopImmediate() {
        val track = audioTrack ?: return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
            }
            track.flush()
            track.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            audioManager?.let { clearSpeakerRouting(it) }
        }
    }

    /**
     * Releases AudioTrack and restores original alarm volume and audio focus.
     */
    fun release() {
        audioManager?.let { clearSpeakerRouting(it) }

        try {
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null

        // Restore original volume if temporarily elevated
        if (hasElevatedVolume && originalAlarmVolume != null) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume!!, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            hasElevatedVolume = false
            originalAlarmVolume = null
        }

        // Abandon audio focus
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager?.abandonAudioFocusRequest(audioFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioFocusRequest = null
    }
}
