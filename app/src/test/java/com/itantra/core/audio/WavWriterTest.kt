package com.itantra.core.audio

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavWriterTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `PCM16 written audio retains sample count and amplitudes`() {
        val file = temp.newFile("pcm.wav")
        WavWriter.writeWavFile(file, shortArrayOf(-32768, -16384, 0, 16384, 32767))
        assertArrayEquals(floatArrayOf(-1f, -.5f, 0f, .5f, 32767f/32768),
            file.inputStream().use { WavWriter.readWav(it) }, 0f)
    }

    @Test fun `float WAV skips metadata and honors data chunk size`() {
        assertArrayEquals(floatArrayOf(-.5f, 0f, .75f),
            WavWriter.readWav(ByteArrayInputStream(floatWav())), 0f)
    }

    @Test fun `bundled Malayalam float samples match independent soundfile decoder`() {
        val samples = File("src/main/assets/benchmark/five_self_test/ml.wav").inputStream()
            .use { WavWriter.readWav(it) }
        assertEquals(332160, samples.size)
        val bytes = ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { bytes.putFloat(it) }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
        assertEquals("b25d087009228ac26511eb67cdee5c60bb92d01085c2382ec7c4541b9131010d", digest)
    }

    @Test fun `wrong rate truncated chunks and nonfinite audio fail explicitly`() {
        val wrongRate = floatWav().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 8000) }
        val nonfinite = floatWav().apply { ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).putFloat(54, Float.NaN) }
        val truncated = floatWav().copyOf(55)
        for (bad in listOf(wrongRate, nonfinite, truncated, byteArrayOf())) {
            assertThrows(IllegalArgumentException::class.java) {
                WavWriter.readWav(ByteArrayInputStream(bad))
            }
        }
    }

    private fun floatWav(): ByteArray = ByteBuffer.allocate(78).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(70); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16)
        putShort(3); putShort(1); putInt(16000); putInt(64000); putShort(4); putShort(32)
        // Odd-sized metadata chunk includes padding; its content resembles a chunk marker.
        put("JUNK".toByteArray()); putInt(1); put('d'.code.toByte()); put(0)
        put("data".toByteArray()); putInt(12); putFloat(-.5f); putFloat(0f); putFloat(.75f)
        put("LIST".toByteArray()); putInt(4); putInt(0x7fffffff)
    }.array()
}
