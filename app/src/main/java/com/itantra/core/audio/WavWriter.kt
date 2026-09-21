package com.itantra.core.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavWriter {

    /**
     * Write float audio samples in range [-1.0, 1.0] to a standard 16-bit PCM WAV file.
     */
    fun writeWavFile(
        file: File,
        samples: FloatArray,
        sampleRate: Int = 16000,
        channels: Int = 1
    ) {
        val shortBuffer = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            shortBuffer[i] = (clamped * 32767.0f).toInt().toShort()
        }
        writeWavFile(file, shortBuffer, sampleRate, channels)
    }

    /**
     * Write 16-bit PCM short audio samples to a standard WAV file.
     */
    fun writeWavFile(
        file: File,
        samples: ShortArray,
        sampleRate: Int = 16000,
        channels: Int = 1
    ) {
        file.parentFile?.mkdirs()
        val dataSize = samples.size * 2
        val totalSize = 36 + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                // "RIFF"
                put('R'.code.toByte())
                put('I'.code.toByte())
                put('F'.code.toByte())
                put('F'.code.toByte())
                putInt(totalSize)
                // "WAVE"
                put('W'.code.toByte())
                put('A'.code.toByte())
                put('V'.code.toByte())
                put('E'.code.toByte())
                // "fmt "
                put('f'.code.toByte())
                put('m'.code.toByte())
                put('t'.code.toByte())
                put(' '.code.toByte())
                putInt(16) // Subchunk1Size for PCM
                putShort(1) // AudioFormat: 1 = PCM
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channels * 2) // ByteRate
                putShort((channels * 2).toShort()) // BlockAlign
                putShort(16) // BitsPerSample
                // "data"
                put('d'.code.toByte())
                put('a'.code.toByte())
                put('t'.code.toByte())
                put('a'.code.toByte())
                putInt(dataSize)
            }
            fos.write(header.array())

            // Write PCM16 data in little endian
            val pcmBytes = ByteBuffer.allocate(samples.size * 2).apply {
                order(ByteOrder.LITTLE_ENDIAN)
                for (s in samples) {
                    putShort(s)
                }
            }
            fos.write(pcmBytes.array())
            fos.flush()
        }
    }

    /**
     * Read 16-bit mono 16kHz PCM audio samples from an InputStream into FloatArray in [-1.0f, 1.0f].
     */
    fun readWav(inputStream: java.io.InputStream): FloatArray {
        val bytes = inputStream.readBytes()
        if (bytes.size < 44) return FloatArray(0)

        // Find "data" chunk
        var dataOffset = 12
        while (dataOffset < bytes.size - 8) {
            if (bytes[dataOffset] == 'd'.code.toByte() &&
                bytes[dataOffset + 1] == 'a'.code.toByte() &&
                bytes[dataOffset + 2] == 't'.code.toByte() &&
                bytes[dataOffset + 3] == 'a'.code.toByte()
            ) {
                break
            }
            dataOffset++
        }
        val pcmOffset = if (dataOffset < bytes.size - 8) dataOffset + 8 else 44
        val pcmByteCount = bytes.size - pcmOffset
        val sampleCount = pcmByteCount / 2

        val buffer = ByteBuffer.wrap(bytes, pcmOffset, sampleCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            floats[i] = buffer.short / 32768.0f
        }
        return floats
    }
}
