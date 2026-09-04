package com.taxiinspector.ui.meter

/** The single entry point through which the meter screen reports user intent. */
sealed interface MeterAction {
    /** Opens the tariff destination; refused while a ride holds a locked tariff. */
    data object EditTariff : MeterAction

    /** Pre-ride Reset: clears the display and any pending notice, without confirmation. */
    data object Reset : MeterAction

    data object StartRide : MeterAction

    data object PauseRide : MeterAction

    data object ResumeRide : MeterAction

    /** Stop & save for a live ride, and Save as interrupted for a recovered one. */
    data object StopAndSave : MeterAction

    data object DiscardRequested : MeterAction

    data object DiscardConfirmed : MeterAction

    data object DiscardDismissed : MeterAction

    data object RecoveryRequested : MeterAction

    data object MessageShown : MeterAction

    /** Reported by the route whenever the screen becomes visible. */
    data class EnvironmentChanged(val environment: MeterEnvironment) : MeterAction

    /** Reported by the route after a runtime-permission dialog closes. */
    data class PermissionResult(
        val environment: MeterEnvironment,
        val isGranted: Boolean,
    ) : MeterAction

    /** Reported by the route after binding to any live tracking service. */
    data class ServiceOwnershipChecked(
        val rideId: String,
        val isOwnedByLiveService: Boolean,
    ) : MeterAction
}

/**
 * The Android facts the meter needs but must not read for itself: the route inspects
 * permissions and the GPS provider and reports them here.
 */
data class MeterEnvironment(
    val hasPreciseLocationPermission: Boolean = false,
    val hasNotificationPermission: Boolean = false,
    val isGpsProviderEnabled: Boolean = false,
)
