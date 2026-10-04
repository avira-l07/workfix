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

    /** Read mono 16 kHz PCM16 or IEEE-float32 WAV; reject unsupported audio explicitly. */
    fun readWav(inputStream: java.io.InputStream): FloatArray {
        val bytes = inputStream.readBytes()
        require(bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "Invalid WAV header" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val riffEnd = 8L + (buffer.getInt(4).toLong() and 0xffffffffL)
        require(riffEnd in 12L..bytes.size.toLong()) { "Truncated WAV file" }
        var offset = 12
        var format = 0
        var bits = 0
        var dataOffset = -1
        var dataSize = 0
        while (offset.toLong() + 8 <= riffEnd) {
            val id = String(bytes, offset, 4, Charsets.US_ASCII)
            val size = buffer.getInt(offset + 4).toLong() and 0xffffffffL
            val start = offset + 8
            val end = start.toLong() + size
            require(end <= riffEnd) { "Truncated WAV chunk: $id" }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format chunk" }
                    format = buffer.getShort(start).toInt() and 0xffff
                    bits = buffer.getShort(start + 14).toInt() and 0xffff
                    require(buffer.getShort(start + 2).toInt() == 1 && buffer.getInt(start + 4) == 16000) {
                        "STT sample must be mono 16 kHz"
                    }
                    require((format == 1 && bits == 16) || (format == 3 && bits == 32)) {
                        "Unsupported WAV encoding: format=$format, bits=$bits"
                    }
                    require(buffer.getShort(start + 12).toInt() == bits / 8) { "Invalid WAV block alignment" }
                }
                "data" -> {
                    require(dataOffset == -1) { "Multiple WAV data chunks are unsupported" }
                    dataOffset = start
                    dataSize = size.toInt()
                }
            }
            offset = (end + (size and 1L)).toInt()
        }
        require(bits > 0 && dataOffset >= 0 && dataSize > 0 && dataSize % (bits / 8) == 0) {
            "Missing or invalid WAV audio data"
        }
        buffer.position(dataOffset)
        return FloatArray(dataSize / (bits / 8)) {
            val sample = if (format == 3) buffer.float else buffer.short / 32768.0f
            require(sample.isFinite()) { "Non-finite WAV sample" }
            sample
        }
    }
}
