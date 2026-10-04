package com.itantra.core.inference

import org.junit.Assert.*
import org.junit.Test

class DigitalSilenceTest {
    @Test fun `empty and all-zero samples do not require native decode`() {
        assertTrue(isDigitalSilence(floatArrayOf()))
        for (seconds in listOf(1, 2, 5)) assertTrue(isDigitalSilence(FloatArray(seconds * 16000)))
        assertTrue(isDigitalSilence(floatArrayOf(-0f, 0f)))
    }
    @Test fun `very quiet speech is not removed by an amplitude threshold`() {
        val samples = FloatArray(16000)
        samples[7999] = 0.0000001f
        assertFalse(isDigitalSilence(samples))
        samples[7999] = -0.0000001f
        assertFalse(isDigitalSilence(samples))
    }
}
