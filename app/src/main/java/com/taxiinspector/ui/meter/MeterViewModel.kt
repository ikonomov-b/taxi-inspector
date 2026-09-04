package com.taxiinspector.ui.meter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.FareCalculator
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TrackingStatus
import com.taxiinspector.tracking.RideCommand
import com.taxiinspector.tracking.RideOwnership
import com.taxiinspector.tracking.RideRecoveryCoordinator
import com.taxiinspector.ui.tariff.toSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Presents durable repository state and turns user intent into explicit service commands.
 *
 * It never owns a ride: it holds no `Context`, `Location`, service reference, or mutable
 * fare value, and every Android side effect leaves through [effects] for the route to run.
 */
class MeterViewModel(
    private val repository: RoomRideRepository,
    private val recoveryCoordinator: RideRecoveryCoordinator,
) : ViewModel() {
    private val localState = MutableStateFlow(LocalState())
    private val effectChannel = Channel<MeterEffect>(Channel.BUFFERED)

    /** One-off Android work; a recreated screen replays no permission request or command. */
    val effects: Flow<MeterEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<MeterUiState> = combine(
        repository.observeTariff(),
        repository.observeActiveRide(),
        localState,
    ) { tariff, ride, local ->
        buildState(tariff, ride, local)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MeterUiState())

    /** Start or Resume was issued from this screen, so a live service already owns the ride. */
    private var hasLaunchedTrackingThisSession = false
    private var pendingLaunch: RideCommand? = null
    private var isAwaitingPermissionResult = false
    private var ownershipCheckedRideId: String? = null

    /**
     * Durable state is mirrored here as well as in [state] so that an action reads the
     * value the user is looking at: the derived state flow may not have recomposed yet
     * when a fast typist taps Save tariff.
     */
    private var savedTariff: Tariff? = null
    private var activeRide: ActiveRide? = null

    init {
        viewModelScope.launch {
            repository.observeTariff().collect { savedTariff = it }
        }
        viewModelScope.launch {
            repository.observeActiveRide().collect { ride ->
                activeRide = ride
                requestOwnershipCheckIfNeeded(ride)
            }
        }
    }

    fun onAction(action: MeterAction) {
        when (action) {
            MeterAction.EditTariff -> Unit // The route navigates; nothing here changes.
            MeterAction.Reset -> reset()
            MeterAction.StartRide -> requestLaunch(RideCommand.Start)
            MeterAction.ResumeRide -> requestLaunch(RideCommand.Resume)
            MeterAction.PauseRide -> emit(MeterEffect.SendCommand(RideCommand.Pause))
            MeterAction.StopAndSave -> emit(MeterEffect.SendCommand(RideCommand.Stop))
            MeterAction.DiscardRequested -> localState.update { it.copy(isDiscardConfirmationVisible = true) }
            MeterAction.DiscardDismissed -> localState.update { it.copy(isDiscardConfirmationVisible = false) }
            MeterAction.DiscardConfirmed -> {
                localState.update { it.copy(isDiscardConfirmationVisible = false) }
                emit(MeterEffect.SendCommand(RideCommand.Discard))
            }
            MeterAction.RecoveryRequested -> openRecovery()
            MeterAction.MessageShown -> localState.update { it.copy(message = null) }
            is MeterAction.EnvironmentChanged -> onEnvironmentChanged(action.environment)
            is MeterAction.PermissionResult -> onPermissionResult(action)
            is MeterAction.ServiceOwnershipChecked -> onOwnershipChecked(action)
        }
    }

    /** Pre-ride Reset clears the display and any pending notice; it deletes nothing saved. */
    private fun reset() {
        if (activeRide != null) return
        localState.update { it.copy(message = null, recovery = null) }
    }

    // region Start, resume, and recovery

    private fun requestLaunch(command: RideCommand) {
        pendingLaunch = command
        evaluateLaunch()
    }

    /**
     * Checks the same prerequisites the service rechecks, requesting one missing permission
     * at a time so the user is never asked for two dialogs at once.
     */
    private fun evaluateLaunch() {
        val command = pendingLaunch ?: return
        val environment = localState.value.environment
        when {
            command == RideCommand.Start && savedTariff == null -> {
                pendingLaunch = null
                localState.update { it.copy(message = MeterMessage.TariffNeededToStart) }
            }
            !environment.hasPreciseLocationPermission ->
                requestPermission(MeterEffect.RequestPreciseLocationPermission)
            !environment.hasNotificationPermission ->
                requestPermission(MeterEffect.RequestNotificationPermission)
            !environment.isGpsProviderEnabled -> {
                pendingLaunch = null
                localState.update { it.copy(recovery = MeterRecovery.EnableGps) }
            }
            else -> {
                pendingLaunch = null
                hasLaunchedTrackingThisSession = true
                localState.update { it.copy(recovery = null) }
                emit(MeterEffect.SendCommand(command))
            }
        }
    }

    private fun requestPermission(effect: MeterEffect) {
        if (isAwaitingPermissionResult) return
        isAwaitingPermissionResult = true
        emit(effect)
    }

    private fun onEnvironmentChanged(environment: MeterEnvironment) {
        localState.update { current ->
            current.copy(
                environment = environment,
                recovery = current.recovery?.takeIf { it.isStillNeeded(environment) },
            )
        }
        evaluateLaunch()
    }

    private fun onPermissionResult(result: MeterAction.PermissionResult) {
        isAwaitingPermissionResult = false
        localState.update { it.copy(environment = result.environment) }
        if (result.isGranted) {
            evaluateLaunch()
            return
        }
        pendingLaunch = null
        localState.update {
            it.copy(
                recovery = if (!result.environment.hasPreciseLocationPermission) {
                    MeterRecovery.GrantPreciseLocation
                } else {
                    MeterRecovery.GrantNotifications
                },
            )
        }
    }

    private fun openRecovery() {
        when (localState.value.recovery) {
            MeterRecovery.GrantPreciseLocation,
            MeterRecovery.GrantNotifications,
            -> emit(MeterEffect.OpenAppSettings)
            MeterRecovery.EnableGps -> emit(MeterEffect.OpenLocationSettings)
            null -> Unit
        }
    }

    /**
     * A Running snapshot may only be treated as interrupted once the route has confirmed
     * that no live service owns it. A ride launched from this screen is never re-checked,
     * because binding could still race the service that is starting up.
     */
    private fun requestOwnershipCheckIfNeeded(ride: ActiveRide?) {
        if (ride == null || ride.phase != RidePhase.Running) return
        if (hasLaunchedTrackingThisSession || ride.id == ownershipCheckedRideId) return
        ownershipCheckedRideId = ride.id
        emit(MeterEffect.CheckServiceOwnership(ride.id))
    }

    private fun onOwnershipChecked(result: MeterAction.ServiceOwnershipChecked) {
        viewModelScope.launch {
            runCatching {
                recoveryCoordinator.recoverRunningRideAfterOwnershipCheck(
                    rideId = result.rideId,
                    serviceOwner = RideOwnership { result.isOwnedByLiveService },
                )
            }
        }
    }

    // endregion

    private fun emit(effect: MeterEffect) {
        effectChannel.trySend(effect)
    }

    private fun buildState(
        tariff: Tariff?,
        ride: ActiveRide?,
        local: LocalState,
    ): MeterUiState {
        return MeterUiState(
            presentation = presentationOf(ride),
            savedTariff = tariff?.toSummary(),
            status = statusOf(tariff, ride, local.environment),
            canStart = tariff != null && ride == null,
            canEditTariff = ride == null,
            isDiscardConfirmationVisible = local.isDiscardConfirmationVisible && ride != null,
            recovery = local.recovery,
            message = local.message,
        )
    }

    private fun presentationOf(ride: ActiveRide?): MeterPresentation {
        if (ride == null) return MeterPresentation.EMPTY
        val totalSeconds = ride.idleMillis / MILLIS_PER_SECOND
        return MeterPresentation(
            // The fare engine remains the only place a total is calculated.
            total = FareCalculator.total(ride.tariff, ride.distanceMeters, ride.idleMillis).formatTotal(),
            distance = formatKilometres(ride.distanceMeters),
            waitTime = formatWaitTime(totalSeconds),
            waitMinutes = totalSeconds / SECONDS_PER_MINUTE,
            waitSeconds = totalSeconds % SECONDS_PER_MINUTE,
            phase = when (ride.phase) {
                RidePhase.Running -> MeterPhaseLabel.Running
                RidePhase.Paused -> MeterPhaseLabel.Paused
                RidePhase.PendingInterrupted -> MeterPhaseLabel.Interrupted
            },
        )
    }

    private fun statusOf(
        tariff: Tariff?,
        ride: ActiveRide?,
        environment: MeterEnvironment,
    ): MeterStatus = when {
        ride == null -> when {
            tariff == null -> MeterStatus.TariffNeeded
            !environment.hasPreciseLocationPermission -> MeterStatus.PermissionNeeded
            !environment.isGpsProviderEnabled -> MeterStatus.GpsDisabled
            !environment.hasNotificationPermission -> MeterStatus.NotificationsNeeded
            else -> MeterStatus.ReadyToStart
        }
        ride.phase == RidePhase.Paused -> MeterStatus.Paused
        ride.phase == RidePhase.PendingInterrupted -> MeterStatus.PendingInterrupted
        else -> when (ride.trackingStatus) {
            TrackingStatus.Searching -> MeterStatus.Searching
            TrackingStatus.Good -> MeterStatus.Good
            TrackingStatus.Weak -> MeterStatus.Weak
            TrackingStatus.GpsLost -> MeterStatus.GpsLost
            TrackingStatus.PermissionNeeded -> MeterStatus.PermissionNeeded
        }
    }

    private data class LocalState(
        val environment: MeterEnvironment = MeterEnvironment(),
        val recovery: MeterRecovery? = null,
        val message: MeterMessage? = null,
        val isDiscardConfirmationVisible: Boolean = false,
    )

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L
        private const val SECONDS_PER_MINUTE = 60L
        private const val SECONDS_PER_HOUR = 3_600L
        private val METERS_PER_KILOMETRE = BigDecimal("1000")

        fun factory(repository: RoomRideRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { MeterViewModel(repository, RideRecoveryCoordinator(repository)) }
        }

        private fun formatKilometres(meters: BigDecimal, locale: Locale = Locale.getDefault()): String {
            val kilometres = meters.divide(METERS_PER_KILOMETRE, 2, RoundingMode.HALF_UP).toPlainString()
            val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            return if (separator == '.') kilometres else kilometres.replace('.', separator)
        }

        private fun formatWaitTime(totalSeconds: Long): String {
            val minutes = totalSeconds / SECONDS_PER_MINUTE
            val seconds = totalSeconds % SECONDS_PER_MINUTE
            return if (totalSeconds >= SECONDS_PER_HOUR) {
                "%d:%02d:%02d".format(totalSeconds / SECONDS_PER_HOUR, minutes % SECONDS_PER_MINUTE, seconds)
            } else {
                "%02d:%02d".format(minutes, seconds)
            }
        }
    }
}

private fun MeterRecovery.isStillNeeded(environment: MeterEnvironment): Boolean = when (this) {
    MeterRecovery.GrantPreciseLocation -> !environment.hasPreciseLocationPermission
    MeterRecovery.GrantNotifications -> !environment.hasNotificationPermission
    MeterRecovery.EnableGps -> !environment.isGpsProviderEnabled
}

