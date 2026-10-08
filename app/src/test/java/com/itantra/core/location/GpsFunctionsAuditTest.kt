package com.itantra.core.location

import android.content.ContextWrapper
import android.location.Location
import android.location.LocationListener
import android.os.Looper
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GpsFunctionsAuditTest {
    private open class Service : LocationServiceAdapter {
        var listener: LocationListener? = null
        var removals = 0
        override fun isProviderEnabled(provider: String) = true
        override fun getLastKnownLocation(provider: String): Location? = null
        override fun requestLocationUpdates(provider: String, minTimeMs: Long, minDistanceM: Float,
            listener: LocationListener, looper: Looper?) { this.listener = listener }
        override fun removeUpdates(listener: LocationListener) { removals++; this.listener = null }
    }
    private fun provider(service: Service) = DefaultGpsLocationProvider(
        ContextWrapper(null), 30L, service, permissionChecker = { _, _ -> true })

    @Test fun `freshness rejects stale future and missing fix times`() {
        val now = 1_800_000_000_000L
        assertTrue(isRecentGpsFix(now, now))
        assertTrue(isRecentGpsFix(now - 29_999, now))
        assertFalse(isRecentGpsFix(now - 30_000, now))
        assertFalse(isRecentGpsFix(now - 86_400_000, now))
        assertFalse(isRecentGpsFix(now + 1, now))
        assertFalse(isRecentGpsFix(0, now))
    }

    @Test fun `monotonic fix age wins when wall clock changes`() {
        assertTrue(isRecentGpsFix(100, 1_800_000_000_000, 1_000_000_000, 2_000_000_000))
        assertFalse(isRecentGpsFix(100, 100, 1_000_000_000, 31_000_000_000))
        assertFalse(isRecentGpsFix(100, 100, 2_000_000_000, 1_000_000_000))
    }

    @Test fun `coordinate validation accepts boundaries and rejects nonfinite or invalid sensor values`() {
        assertTrue(isValidGpsCoordinates(-90.0, 180.0, 0f))
        assertTrue(isValidGpsCoordinates(19.076, 72.8777, 4.5f))
        for ((lat, lon, accuracy) in listOf(Triple(Double.NaN, 0.0, 1f),
            Triple(91.0, 0.0, 1f), Triple(0.0, -181.0, 1f), Triple(0.0, Double.POSITIVE_INFINITY, 1f),
            Triple(0.0, 0.0, Float.NaN), Triple(0.0, 0.0, Float.POSITIVE_INFINITY), Triple(0.0, 0.0, -1f))) {
            assertFalse(isValidGpsCoordinates(lat, lon, accuracy))
        }
    }

    @Test fun `partially registered request is removed when platform throws`() = runBlocking {
        val service = object : Service() {
            override fun requestLocationUpdates(provider: String, minTimeMs: Long, minDistanceM: Float,
                listener: LocationListener, looper: Looper?) {
                super.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper)
                throw SecurityException("Permission revoked during registration")
            }
        }
        assertSame(LocationResult.Failure.PermissionDenied, provider(service).getCurrentLocation())
        assertEquals(1, service.removals)
        assertNull(service.listener)
    }

    @Test fun `cancellation inside registration cannot leave listener running`() = runBlocking {
        val job = Job()
        val service = object : Service() {
            override fun requestLocationUpdates(provider: String, minTimeMs: Long, minDistanceM: Float,
                listener: LocationListener, looper: Looper?) {
                job.cancel()
                super.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, looper)
            }
        }
        try { withContext(job) { provider(service).getCurrentLocation() }; fail("Expected cancellation") }
        catch (_: CancellationException) {}
        assertEquals(1, service.removals)
        assertNull(service.listener)
    }

    @Test fun `timeout does not silently reuse an unverified cached location`() = runBlocking {
        val service = object : Service() {
            override fun getLastKnownLocation(provider: String) = Location("gps") // no fix time
        }
        assertSame(LocationResult.Failure.NoFixAvailable, provider(service).getCurrentLocation())
        assertEquals(1, service.removals)
        assertNull(service.listener)
    }
}
