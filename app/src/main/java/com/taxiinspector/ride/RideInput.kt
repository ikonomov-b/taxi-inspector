package com.taxiinspector.ride

sealed interface RideInput {
    data object Pause : RideInput
    data class Resume(val nowElapsedMillis: Long) : RideInput
    data class LocationReceived(
        val sample: LocationSample,
        val nowElapsedMillis: Long,
    ) : RideInput

    data class Tick(val nowElapsedMillis: Long) : RideInput
    data class GpsTimedOut(val nowElapsedMillis: Long) : RideInput
    data object PermissionRevoked : RideInput
}
