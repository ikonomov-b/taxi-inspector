package com.taxiinspector.tracking

import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.TrackingStatus

sealed interface RideTrackingState {
    data object Idle : RideTrackingState

    data class Active(
        val rideId: String,
        val phase: RidePhase,
        val trackingStatus: TrackingStatus,
    ) : RideTrackingState

    data class Rejected(val reason: StartRejection) : RideTrackingState

    data class Failed(val reason: TrackingFailure) : RideTrackingState
}

enum class StartRejection {
    PreciseLocationMissing,
    NotificationPermissionMissing,
    GpsDisabled,
    TariffMissing,
    ActiveRideExists,
    NoPausedRide,
}

enum class TrackingFailure {
    ForegroundStartFailed,
    PermissionRevoked,
    LocationUnavailable,
    StorageFailure,
}
