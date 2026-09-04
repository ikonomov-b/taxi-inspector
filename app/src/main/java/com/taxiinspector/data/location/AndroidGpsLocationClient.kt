package com.taxiinspector.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.taxiinspector.ride.LocationSample
import java.util.concurrent.ConcurrentHashMap
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
        // Satellite status arrives on its own callback rather than attached to a fix, so the
        // latest observation is carried forward and applied to fixes received soon after it.
        // Both callbacks are delivered on the main looper, so these need no synchronisation.
        var observedBand = LocationSample.Band.Unknown
        var observedElapsedMillis: Long? = null

        val statusListener = GnssStatusListener { satellitesInView, carrierFrequenciesHz ->
            observedBand = GnssBandClassifier.classify(carrierFrequenciesHz)
            observedElapsedMillis = receivedElapsedRealtimeMillis()
            if (Log.isLoggable(FIELD_TAG, Log.DEBUG)) {
                Log.d(
                    FIELD_TAG,
                    "status band=$observedBand " +
                        "l5=${GnssBandClassifier.l5SignalCount(carrierFrequenciesHz)} " +
                        "usedInFix=${carrierFrequenciesHz.size} " +
                        "inView=$satellitesInView",
                )
            }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val receivedElapsedMillis = receivedElapsedRealtimeMillis()
                val band = observedElapsedMillis
                    ?.takeIf { receivedElapsedMillis - it in 0..BAND_FRESHNESS_MILLIS }
                    ?.let { observedBand }
                    ?: LocationSample.Band.Unknown
                val sample = location.toDomainSample(receivedElapsedMillis, band)
                if (sample == null) logDroppedFix(location) else logFieldQuality(sample)
                sample?.let { trySend(it) }
            }
        }

        source.requestGpsUpdates(
            minTimeMillis = UPDATE_INTERVAL_MILLIS,
            minDistanceMeters = 0f,
            listener = listener,
        )
        // Requested second, and tolerantly: knowing the band only refines the movement
        // floor, so a receiver that refuses this subscription must still bill a ride. The
        // cost of trailing the location request is that the first fix or two read Unknown,
        // which is the conservative floor anyway.
        runCatching { source.registerGnssStatus(statusListener) }
        awaitClose {
            // Independently, so that a failure to release one still releases the other.
            try {
                source.removeUpdates(listener)
            } catch (_: SecurityException) {
                // Permission may have been revoked immediately before cancellation.
            }
            runCatching { source.removeGnssStatus(statusListener) }
        }
    }

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L

        /** Mirrors RideEngine's own five-second freshness rule for location data. */
        const val BAND_FRESHNESS_MILLIS = 5_000L

        /**
         * Field diagnostics for real-device GNSS runs. Silent unless switched on for a
         * session with `adb shell setprop log.tag.TaxiGnss DEBUG`, since a tag defaults to
         * INFO. Never logs coordinates -- only the quality of a fix, never its position.
         */
        const val FIELD_TAG = "TaxiGnss"

        /**
         * A fix the mapper refused leaves no other trace, so without this a dropped fix and a
         * fix that never arrived look identical from the log.
         */
        fun logDroppedFix(location: Location) {
            if (!Log.isLoggable(FIELD_TAG, Log.DEBUG)) return
            Log.d(
                FIELD_TAG,
                "dropped fix provider=${location.provider} " +
                    "hasAccuracy=${location.hasAccuracy()} " +
                    "elapsedRealtimeNanos=${location.elapsedRealtimeNanos}",
            )
        }

        fun logFieldQuality(sample: LocationSample) {
            if (!Log.isLoggable(FIELD_TAG, Log.DEBUG)) return
            Log.d(
                FIELD_TAG,
                "fix band=${sample.band} accuracy=${sample.accuracyMeters}m " +
                    "speed=${sample.speedMetersPerSecond} " +
                    "speedAccuracy=${sample.speedAccuracyMetersPerSecond} " +
                    "mock=${sample.isMock}",
            )
        }
    }
}

/** Reports how many satellites are visible and the carrier frequencies used in the fix. */
internal fun interface GnssStatusListener {
    fun onSatelliteStatus(satellitesInView: Int, carrierFrequenciesUsedInFix: List<Float>)
}

internal interface GpsLocationSource {
    fun isGpsProviderEnabled(): Boolean

    fun requestGpsUpdates(
        minTimeMillis: Long,
        minDistanceMeters: Float,
        listener: LocationListener,
    )

    fun removeUpdates(listener: LocationListener)

    fun registerGnssStatus(listener: GnssStatusListener)

    fun removeGnssStatus(listener: GnssStatusListener)
}

private class LocationManagerGpsLocationSource(
    private val locationManager: LocationManager,
) : GpsLocationSource {
    private val gnssCallbacks = ConcurrentHashMap<GnssStatusListener, GnssStatus.Callback>()

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

    @SuppressLint("MissingPermission")
    override fun registerGnssStatus(listener: GnssStatusListener) {
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                listener.onSatelliteStatus(
                    satellitesInView = status.satelliteCount,
                    carrierFrequenciesUsedInFix = status.carrierFrequenciesUsedInFix(),
                )
            }
        }
        gnssCallbacks[listener] = callback
        locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
    }

    override fun removeGnssStatus(listener: GnssStatusListener) {
        gnssCallbacks.remove(listener)?.let(locationManager::unregisterGnssStatusCallback)
    }
}

/**
 * Carrier frequency is only readable from API 26; on older devices this is empty and the
 * band stays Unknown, which the engine treats exactly as single-band.
 */
private fun GnssStatus.carrierFrequenciesUsedInFix(): List<Float> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()

    val frequencies = ArrayList<Float>(satelliteCount)
    for (index in 0 until satelliteCount) {
        if (!usedInFix(index)) continue
        if (!hasCarrierFrequencyHz(index)) continue
        frequencies += getCarrierFrequencyHz(index)
    }
    return frequencies
}

private fun Location.toDomainSample(
    receivedElapsedMillis: Long,
    band: LocationSample.Band,
): LocationSample? {
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
        band = band,
        speedAccuracyMetersPerSecond = readSpeedAccuracy(),
        isMock = readIsMock(),
    )
}

/** Available from API 26; an implausible value is reported as unknown rather than trusted. */
private fun Location.readSpeedAccuracy(): Double? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
    if (!hasSpeedAccuracy()) return null
    return speedAccuracyMetersPerSecond.toDouble().takeIf { it.isFinite() && it >= 0.0 }
}

@Suppress("DEPRECATION")
private fun Location.readIsMock(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider
