package com.taxiinspector.ride

/**
 * Android-free location input used by the fare engine.
 *
 * The engine reads only position, accuracy, provider, mock flag, band, speed and the two elapsed
 * timestamps. [utcMillis], [bearingDegrees], [altitudeMeters] and [signal] are carried for the
 * debug ride trace, which has to be comparable with an independent GPX recording of the same
 * trip and has to be able to tell one phone placement from another; no billing rule may read
 * them.
 */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val provider: Provider,
    val speedMetersPerSecond: Double?,
    val fixElapsedMillis: Long,
    val receivedElapsedMillis: Long,
    val band: Band = Band.Unknown,
    val speedAccuracyMetersPerSecond: Double? = null,
    val isMock: Boolean = false,
    val utcMillis: Long? = null,
    val bearingDegrees: Double? = null,
    val altitudeMeters: Double? = null,
    val signal: SignalQuality? = null,
) {
    enum class Provider { Gps, Network, Other }

    /**
     * Which GNSS carrier frequencies produced the fix. [Dual] means L5-class signals
     * (L5/E5a/B2a) contributed, which resolves smaller movement than a single L1-only
     * solution; [Unknown] is the conservative default for devices or API levels that
     * cannot report carrier frequency, and is treated exactly like [Single].
     */
    enum class Band { Dual, Single, Unknown }

    init {
        require(latitude in -90.0..90.0) { "Latitude must be in range." }
        require(longitude in -180.0..180.0) { "Longitude must be in range." }
        require(accuracyMeters >= 0.0) { "Accuracy cannot be negative." }
        require(speedMetersPerSecond == null || speedMetersPerSecond >= 0.0) {
            "Speed cannot be negative."
        }
        require(speedAccuracyMetersPerSecond == null || speedAccuracyMetersPerSecond >= 0.0) {
            "Speed accuracy cannot be negative."
        }
        require(fixElapsedMillis >= 0 && receivedElapsedMillis >= 0) {
            "Elapsed timestamps cannot be negative."
        }
        require(bearingDegrees == null || bearingDegrees in 0.0..360.0) {
            "Bearing must be in range."
        }
    }
}
