package com.itantra.core.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundServiceCompatibilityTest {
    @Test fun `microphone type is only used on API30 and later`() {
        for (sdk in 26..29) assertEquals(0, microphoneForegroundServiceType(sdk))
        for (sdk in 30..37) assertEquals(128, microphoneForegroundServiceType(sdk))
    }
}
