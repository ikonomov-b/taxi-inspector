package com.taxiinspector.ride

/** Android-free location input used by the fare engine. */
data class LocationSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val provider: Provider,
    val speedMetersPerSecond: Double?,
    val fixElapsedMillis: Long,
    val receivedElapsedMillis: Long,
) {
    enum class Provider { Gps, Network, Other }

    init {
        require(latitude in -90.0..90.0) { "Latitude must be in range." }
        require(longitude in -180.0..180.0) { "Longitude must be in range." }
        require(accuracyMeters >= 0.0) { "Accuracy cannot be negative." }
        require(speedMetersPerSecond == null || speedMetersPerSecond >= 0.0) {
            "Speed cannot be negative."
        }
        require(fixElapsedMillis >= 0 && receivedElapsedMillis >= 0) {
            "Elapsed timestamps cannot be negative."
        }
    }
}
