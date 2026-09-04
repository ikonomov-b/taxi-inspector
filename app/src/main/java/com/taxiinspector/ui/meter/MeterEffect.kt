package com.taxiinspector.ui.meter

import com.taxiinspector.tracking.RideCommand

/**
 * One-off Android work the route performs on the ViewModel's behalf. Keeping these out
 * of [MeterUiState] means a recreated screen never re-requests a permission or re-sends
 * a service command.
 */
sealed interface MeterEffect {
    data object RequestPreciseLocationPermission : MeterEffect

    data object RequestNotificationPermission : MeterEffect

    data object OpenAppSettings : MeterEffect

    data object OpenLocationSettings : MeterEffect

    /** The UI never owns the ride; it only asks the foreground service to act. */
    data class SendCommand(val command: RideCommand) : MeterEffect

    /**
     * Asks the route to bind to any live tracking service before a persisted Running
     * snapshot may be treated as interrupted.
     */
    data class CheckServiceOwnership(val rideId: String) : MeterEffect
}
