package com.itantra.core.location

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Result of requesting device coordinates.
 */
sealed class LocationResult {
    data class Success(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        val timestampMillis: Long
    ) : LocationResult()

    sealed class Failure(val reason: String) : LocationResult() {
        object PermissionDenied : Failure("LOCATION_PERMISSION_DENIED")
        object ProviderDisabled : Failure("GPS_DISABLED")
        object NoFixAvailable : Failure("NO_GPS_FIX")
        data class Error(val message: String) : Failure(message)
    }
}

/**
 * Contract for obtaining device coordinates.
 */
interface LocationProvider {
    suspend fun getCurrentLocation(): LocationResult
}

internal fun isValidGpsCoordinates(latitude: Double, longitude: Double, accuracyMeters: Float): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0 &&
        accuracyMeters.isFinite() && accuracyMeters >= 0f

/** Prefer the fix's monotonic clock; wall-clock changes must not make old coordinates current. */
internal fun isRecentGpsFix(timestampMillis: Long, nowMillis: Long,
    elapsedRealtimeNanos: Long = 0, nowElapsedRealtimeNanos: Long = 0): Boolean =
    if (elapsedRealtimeNanos > 0 && nowElapsedRealtimeNanos > 0)
        nowElapsedRealtimeNanos >= elapsedRealtimeNanos &&
            nowElapsedRealtimeNanos - elapsedRealtimeNanos < 30_000_000_000L
    else timestampMillis > 0 && nowMillis >= timestampMillis && nowMillis - timestampMillis < 30_000L

/**
 * Internal abstraction over Android framework [LocationManager] methods,
 * enabling deterministic lifecycle, cancellation, and leak-free verification in unit tests.
 */
internal interface LocationServiceAdapter {
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun isProviderEnabled(provider: String): Boolean
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun getLastKnownLocation(provider: String): Location?
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun requestLocationUpdates(
        provider: String,
        minTimeMs: Long,
        minDistanceM: Float,
        listener: LocationListener,
        looper: Looper?
    )
    fun removeUpdates(listener: LocationListener)
}

internal class SystemLocationServiceAdapter(
    private val locationManager: LocationManager
) : LocationServiceAdapter {
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun isProviderEnabled(provider: String): Boolean = locationManager.isProviderEnabled(provider)
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun getLastKnownLocation(provider: String): Location? = locationManager.getLastKnownLocation(provider)
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun requestLocationUpdates(
        provider: String,
        minTimeMs: Long,
        minDistanceM: Float,
        listener: LocationListener,
        looper: Looper?
    ) {
        val resolvedLooper = looper ?: try { Looper.getMainLooper() } catch (_: Throwable) { null }
        if (resolvedLooper != null) {
            locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, resolvedLooper)
        } else {
            locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
        }
    }
    override fun removeUpdates(listener: LocationListener) {
        locationManager.removeUpdates(listener)
    }
}

/**
 * Android framework-based GPS provider using [LocationManager].
 *
 * Designed specifically for standalone, offline operation:
 * - Direct satellite fix via [LocationManager.GPS_PROVIDER]
 * - Does NOT silently require internet, cellular, or Google Play Services
 * - Gracefully handles indoor no-fix, disabled GPS, or denied permissions
 * - Guaranteed leak-free listener cleanup on all exit paths (success, timeout, external cancellation, error)
 */
class DefaultGpsLocationProvider internal constructor(
    private val context: Context,
    private val fixTimeoutMillis: Long = 10_000L,
    private val serviceAdapterOverride: LocationServiceAdapter? = null,
    private val permissionChecker: ((Context, String) -> Boolean)? = null
) : LocationProvider {

    constructor(context: Context, fixTimeoutMillis: Long = 10_000L) : this(context, fixTimeoutMillis, null, null)

    private fun hasPermission(permission: String): Boolean {
        return permissionChecker?.invoke(context, permission) ?: (
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        )
    }

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentLocation(): LocationResult {
        // 1. Runtime permission check
        val hasFine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val hasCoarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (!hasFine && !hasCoarse) {
            return LocationResult.Failure.PermissionDenied
        }

        val locationService: LocationServiceAdapter = serviceAdapterOverride ?: run {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return LocationResult.Failure.Error("LocationManager unavailable")
            SystemLocationServiceAdapter(lm)
        }

        // 2. Hardware GPS provider check
        val isGpsEnabled = try {
            locationService.isProviderEnabled(LocationManager.GPS_PROVIDER)
        } catch (_: SecurityException) {
            return LocationResult.Failure.PermissionDenied
        } catch (e: Exception) {
            return LocationResult.Failure.Error(e.message ?: "GPS provider unavailable")
        }

        if (!isGpsEnabled) {
            return LocationResult.Failure.ProviderDisabled
        }

        fun recentCachedFix(): LocationResult.Success? {
            val fix = locationService.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: return null
            if (!isRecentGpsFix(fix.time, System.currentTimeMillis(), fix.elapsedRealtimeNanos,
                    SystemClock.elapsedRealtimeNanos()) ||
                !isValidGpsCoordinates(fix.latitude, fix.longitude, fix.accuracy)) return null
            return LocationResult.Success(fix.latitude, fix.longitude, fix.accuracy, fix.time)
        }

        // 3. Fast-path: Check for a recent last-known location (< 30 seconds old)
        try {
            recentCachedFix()?.let { return it }
        } catch (_: SecurityException) {
            return LocationResult.Failure.PermissionDenied
        } catch (_: Throwable) {}

        // 4. Standalone satellite GPS fix with timeout and leak-free lifecycle management
        return withTimeoutOrNull(fixTimeoutMillis) {
            var listenerRef: LocationListener? = null
            try {
                suspendCancellableCoroutine<LocationResult> { cont ->
                    fun complete(result: LocationResult) = synchronized(cont) {
                        if (cont.isActive) cont.resume(result)
                    }
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (isValidGpsCoordinates(location.latitude, location.longitude, location.accuracy)) {
                                complete(
                                    LocationResult.Success(
                                        latitude = location.latitude,
                                        longitude = location.longitude,
                                        accuracyMeters = location.accuracy,
                                        timestampMillis = if (location.time > 0L) location.time else System.currentTimeMillis()
                                    )
                                )
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {
                            complete(LocationResult.Failure.ProviderDisabled)
                        }
                    }

                    listenerRef = listener

                    try {
                        val looper: Looper? = try {
                            Looper.myLooper() ?: Looper.getMainLooper()
                        } catch (_: Throwable) {
                            null
                        }
                        if (cont.isActive) locationService.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            0L,
                            0f,
                            listener,
                            looper
                        )
                    } catch (e: SecurityException) {
                        complete(LocationResult.Failure.PermissionDenied)
                    } catch (e: Throwable) {
                        complete(LocationResult.Failure.Error(e.message ?: "GPS request failed"))
                    }
                }
            } finally {
                // Remove after registration returns, even if a callback, exception
                // or cancellation occurred inside requestLocationUpdates itself.
                listenerRef?.let { listener ->
                    try { locationService.removeUpdates(listener) } catch (_: Exception) {}
                }
            }
        } ?: run {
            // A timeout must never silently send arbitrarily old cached coordinates.
            try {
                recentCachedFix()?.let { return it }
            } catch (_: SecurityException) {
                return LocationResult.Failure.PermissionDenied
            } catch (_: Throwable) {}

            LocationResult.Failure.NoFixAvailable
        }
    }
}
