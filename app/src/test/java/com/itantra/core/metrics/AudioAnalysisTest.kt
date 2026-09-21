package com.itantra.core.metrics

import com.itantra.core.audio.WavWriter
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.math.sqrt

class AudioAnalysisTest {

    @Test
    fun analyzeTailSilence() {
        val audioDir = File("src/main/assets/benchmark/audio")
        val files = listOf(
            "EN-CRIT-017.wav", // Success
            "EN-CRIT-018.wav", // Success
            "EN-CRIT-020.wav", // Success
            "EN-CRIT-021.wav", // Repetition loop failure
            "EN-CRIT-022.wav", // Success
            "EN-CRIT-024.wav"  // Repetition loop failure
        )

        for (fileName in files) {
            val file = File(audioDir, fileName)
            if (!file.exists()) {
                println("File not found: ${file.absolutePath}")
                continue
            }
            val samples = FileInputStream(file).use { WavWriter.readWav(it) }
            val totalMs = (samples.size * 1000L) / 16000L

            // Analyze trailing 500ms
            val tailSamplesCount = (500 * 16).coerceAtMost(samples.size)
            val tailStart = samples.size - tailSamplesCount
            var sumSq = 0.0
            for (i in tailStart until samples.size) {
                sumSq += samples[i] * samples[i]
            }
            val tailRms = sqrt(sumSq / tailSamplesCount)

            // Find where speech actually ends (RMS of 50ms sliding window drops below 0.01)
            val windowSize = 800 // 50ms
            var lastVoicedSample = 0
            for (i in 0 until samples.size - windowSize step 400) {
                var wSum = 0.0
                for (j in 0 until windowSize) {
                    wSum += samples[i + j] * samples[i + j]
                }
                val wRms = sqrt(wSum / windowSize)
                if (wRms > 0.01) {
                    lastVoicedSample = i + windowSize
                }
            }
            val trailingSilenceMs = ((samples.size - lastVoicedSample) * 1000L) / 16000L

            println("[$fileName] Total=${totalMs}ms | SpeechEnd=${(lastVoicedSample * 1000L)/16000L}ms | ExistingTrailingSilence=${trailingSilenceMs}ms | Tail500msRMS=$tailRms")
        }
    }
}
