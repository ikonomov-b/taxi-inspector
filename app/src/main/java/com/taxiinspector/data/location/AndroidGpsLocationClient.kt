package com.taxiinspector.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.taxiinspector.ride.LocationSample
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Keeps LocationManager and Android Location values behind the domain boundary. */
class AndroidGpsLocationClient internal constructor(
    private val source: GpsLocationSource,
    private val receivedElapsedRealtimeMillis: () -> Long,
) : LocationClient {
    constructor(context: Context) : this(
        source = LocationManagerGpsLocationSource(
            context.applicationContext.getSystemService(LocationManager::class.java),
        ),
        receivedElapsedRealtimeMillis = SystemClock::elapsedRealtime,
    )

    override fun isGpsProviderEnabled(): Boolean = source.isGpsProviderEnabled()

    override fun locationSamples(): Flow<LocationSample> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                location.toDomainSample(receivedElapsedRealtimeMillis())?.let { trySend(it) }
            }
        }

        source.requestGpsUpdates(
            minTimeMillis = UPDATE_INTERVAL_MILLIS,
            minDistanceMeters = 0f,
            listener = listener,
        )
        awaitClose {
            try {
                source.removeUpdates(listener)
            } catch (_: SecurityException) {
                // Permission may have been revoked immediately before cancellation.
            }
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L
    }
}

internal interface GpsLocationSource {
    fun isGpsProviderEnabled(): Boolean

    fun requestGpsUpdates(
        minTimeMillis: Long,
        minDistanceMeters: Float,
        listener: LocationListener,
    )

    fun removeUpdates(listener: LocationListener)
}

private class LocationManagerGpsLocationSource(
    private val locationManager: LocationManager,
) : GpsLocationSource {
    override fun isGpsProviderEnabled(): Boolean =
        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    @SuppressLint("MissingPermission")
    override fun requestGpsUpdates(
        minTimeMillis: Long,
        minDistanceMeters: Float,
        listener: LocationListener,
    ) {
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            minTimeMillis,
            minDistanceMeters,
            listener,
            Looper.getMainLooper(),
        )
    }

    @SuppressLint("MissingPermission")
    override fun removeUpdates(listener: LocationListener) {
        locationManager.removeUpdates(listener)
    }
}

private fun Location.toDomainSample(receivedElapsedMillis: Long): LocationSample? {
    if (!hasAccuracy()) return null

    val mappedAccuracy = accuracy.toDouble()
    val mappedSpeed = if (hasSpeed()) speed.toDouble() else null
    val fixElapsedMillis = TimeUnit.NANOSECONDS.toMillis(elapsedRealtimeNanos)
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
    if (!mappedAccuracy.isFinite() || mappedAccuracy < 0.0) return null
    if (mappedSpeed != null && (!mappedSpeed.isFinite() || mappedSpeed < 0.0)) return null
    if (fixElapsedMillis < 0 || receivedElapsedMillis < 0) return null

    return LocationSample(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = mappedAccuracy,
        provider = when (provider) {
            LocationManager.GPS_PROVIDER -> LocationSample.Provider.Gps
            LocationManager.NETWORK_PROVIDER -> LocationSample.Provider.Network
            else -> LocationSample.Provider.Other
        },
        speedMetersPerSecond = mappedSpeed,
        fixElapsedMillis = fixElapsedMillis,
        receivedElapsedMillis = receivedElapsedMillis,
    )
}
