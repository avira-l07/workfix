package com.itantra.core.transport

import android.content.pm.ServiceInfo
import com.itantra.core.service.operationalForegroundServiceType
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionServiceTypeTest {
    @Test fun `verified peer service does not request microphone type`() {
        for (sdk in listOf(29, 30, 33, 34, 36, 37)) {
            assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                operationalForegroundServiceType(sdk, microphone = false, peer = true))
        }
    }
    @Test fun `voice and peer service combines types and supports older Android`() {
        assertEquals(0, operationalForegroundServiceType(26, true, true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, operationalForegroundServiceType(29, true, true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            operationalForegroundServiceType(34, true, true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, operationalForegroundServiceType(34, true, false))
    }
}
