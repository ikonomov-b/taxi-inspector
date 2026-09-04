package com.taxiinspector.tracking

import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.ActiveRide

fun interface RideOwnership {
    fun ownsRide(rideId: String): Boolean
}

/** Used only after the UI has attempted to bind to a possibly live tracking service. */
class RideRecoveryCoordinator(
    private val repository: RoomRideRepository,
) {
    suspend fun recoverRunningRideAfterOwnershipCheck(
        rideId: String,
        serviceOwner: RideOwnership?,
    ): ActiveRide? {
        if (serviceOwner?.ownsRide(rideId) == true) return repository.currentActiveRide()
        return repository.markRunningRideInterrupted(rideId)
    }
}
