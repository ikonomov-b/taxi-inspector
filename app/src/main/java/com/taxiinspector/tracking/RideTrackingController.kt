package com.taxiinspector.tracking

import com.taxiinspector.core.time.Clock
import com.taxiinspector.data.location.LocationClient
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RideInput
import com.taxiinspector.ride.RidePhase
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface TrackingHost {
    fun updateForegroundNotification(ride: ActiveRide)

    fun stopForegroundAndService()
}

internal interface RideCommandSink {
    fun dispatch(command: RideCommand)

    fun rejectForegroundStart()
}

/** Owns the only in-memory active ride and serializes commands, locations, and ticks. */
internal class RideTrackingController(
    private val repository: RoomRideRepository,
    private val locationClient: LocationClient,
    private val prerequisites: TrackingPrerequisites,
    private val clock: Clock,
    private val host: TrackingHost,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val rideIdFactory: () -> String = { UUID.randomUUID().toString() },
) : RideCommandSink {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val ownedRideId = AtomicReference<String?>(null)
    private val mutableState = MutableStateFlow<RideTrackingState>(RideTrackingState.Idle)

    private var activeRide: ActiveRide? = null
    private var locationJob: Job? = null
    private var tickerJob: Job? = null
    private var lastNotificationElapsedMillis: Long? = null

    val state: StateFlow<RideTrackingState> = mutableState.asStateFlow()

    init {
        scope.launch {
            for (event in events) {
                try {
                    handle(event)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    failSafely(TrackingFailure.StorageFailure)
                }
            }
        }
    }

    override fun dispatch(command: RideCommand) {
        events.trySend(Event.Command(command))
    }

    override fun rejectForegroundStart() {
        mutableState.value = RideTrackingState.Failed(TrackingFailure.ForegroundStartFailed)
    }

    fun ownsRide(rideId: String): Boolean = ownedRideId.get() == rideId

    fun close() {
        ownedRideId.set(null)
        locationJob?.cancel()
        tickerJob?.cancel()
        events.close()
        scope.cancel()
    }

    private suspend fun handle(event: Event) {
        when (event) {
            is Event.Command -> when (event.command) {
                RideCommand.Start -> start()
                RideCommand.Pause -> pause()
                RideCommand.Resume -> resume()
                RideCommand.Stop -> stopAndSave()
                RideCommand.Discard -> discard()
            }
            is Event.Location -> updateRide(RideInput.LocationReceived(event.sample, event.sample.receivedElapsedMillis))
            is Event.Tick -> updateRide(RideInput.Tick(event.elapsedRealtimeMillis))
            Event.PermissionRevoked -> permissionRevoked()
            Event.LocationFailed -> locationFailed()
        }
    }

    private suspend fun start() {
        if (activeRide != null) return
        prerequisiteRejection()?.let {
            rejectAndStop(it)
            return
        }
        if (repository.currentActiveRide() != null) {
            rejectAndStop(StartRejection.ActiveRideExists)
            return
        }
        if (repository.currentTariff() == null) {
            rejectAndStop(StartRejection.TariffMissing)
            return
        }

        val ride = repository.startRide(rideIdFactory(), clock.elapsedRealtimeMillis())
        activate(ride)
    }

    private suspend fun resume() {
        if (activeRide != null) return
        prerequisiteRejection()?.let {
            rejectAndStop(it)
            return
        }
        val persisted = repository.currentActiveRide()
        if (persisted?.phase != RidePhase.Paused) {
            rejectAndStop(StartRejection.NoPausedRide)
            return
        }

        val resumed = RideEngine.reduce(
            persisted,
            RideInput.Resume(clock.elapsedRealtimeMillis()),
        )
        repository.updateActiveRide(resumed)
        activate(resumed)
    }

    private suspend fun pause() {
        val current = activeRide ?: return
        val paused = RideEngine.reduce(current, RideInput.Pause)
        repository.updateActiveRide(paused)
        activeRide = paused
        mutableState.value = paused.toTrackingState()
        finishOwnership()
    }

    private suspend fun stopAndSave() {
        val current = activeRide ?: repository.currentActiveRide()
            ?.takeIf { it.phase == RidePhase.Paused }
            ?: return
        val summary = RideEngine.finish(current, clock.elapsedRealtimeMillis())
        repository.finishCompleted(summary, clock.utcMillis())
        finishOwnership()
    }

    private suspend fun discard() {
        val current = activeRide ?: repository.currentActiveRide() ?: return
        repository.discardActiveRide(current.id)
        finishOwnership()
    }

    private suspend fun permissionRevoked() {
        val current = activeRide ?: return
        val paused = RideEngine.reduce(current, RideInput.PermissionRevoked)
        repository.updateActiveRide(paused)
        activeRide = paused
        mutableState.value = RideTrackingState.Failed(TrackingFailure.PermissionRevoked)
        finishOwnership(keepState = true)
    }

    private suspend fun locationFailed() {
        val current = activeRide ?: return
        val paused = RideEngine.reduce(current, RideInput.Pause)
        repository.updateActiveRide(paused)
        activeRide = paused
        mutableState.value = RideTrackingState.Failed(TrackingFailure.LocationUnavailable)
        finishOwnership(keepState = true)
    }

    private suspend fun updateRide(input: RideInput) {
        val current = activeRide ?: return
        val updated = RideEngine.reduce(current, input)
        if (updated == current) return

        repository.updateActiveRide(updated)
        activeRide = updated
        mutableState.value = updated.toTrackingState()
        updateNotificationIfDue(updated)
    }

    private fun activate(ride: ActiveRide) {
        activeRide = ride
        ownedRideId.set(ride.id)
        mutableState.value = ride.toTrackingState()
        lastNotificationElapsedMillis = clock.elapsedRealtimeMillis()
        host.updateForegroundNotification(ride)
        startLocationAndTicks()
    }

    private fun startLocationAndTicks() {
        locationJob?.cancel()
        tickerJob?.cancel()
        locationJob = scope.launch {
            try {
                locationClient.locationSamples().collect { events.send(Event.Location(it)) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SecurityException) {
                events.send(Event.PermissionRevoked)
            } catch (_: Throwable) {
                events.send(Event.LocationFailed)
            }
        }
        tickerJob = scope.launch {
            while (isActive) {
                delay(TICK_INTERVAL_MILLIS)
                events.send(Event.Tick(clock.elapsedRealtimeMillis()))
            }
        }
    }

    private fun updateNotificationIfDue(ride: ActiveRide) {
        val now = clock.elapsedRealtimeMillis()
        val lastUpdate = lastNotificationElapsedMillis
        if (lastUpdate == null || now - lastUpdate >= NOTIFICATION_INTERVAL_MILLIS) {
            host.updateForegroundNotification(ride)
            lastNotificationElapsedMillis = now
        }
    }

    private fun prerequisiteRejection(): StartRejection? = when {
        !prerequisites.hasPreciseLocationPermission() -> StartRejection.PreciseLocationMissing
        !locationClient.isGpsProviderEnabled() -> StartRejection.GpsDisabled
        !prerequisites.hasNotificationPermission() -> StartRejection.NotificationPermissionMissing
        else -> null
    }

    private fun rejectAndStop(rejection: StartRejection) {
        mutableState.value = RideTrackingState.Rejected(rejection)
        host.stopForegroundAndService()
    }

    private fun finishOwnership(keepState: Boolean = false) {
        locationJob?.cancel()
        tickerJob?.cancel()
        locationJob = null
        tickerJob = null
        activeRide = null
        ownedRideId.set(null)
        lastNotificationElapsedMillis = null
        if (!keepState) mutableState.value = RideTrackingState.Idle
        host.stopForegroundAndService()
    }

    private suspend fun failSafely(failure: TrackingFailure) {
        val current = activeRide
        if (current != null) {
            val paused = RideEngine.reduce(current, RideInput.Pause)
            runCatching { repository.updateActiveRide(paused) }
        }
        mutableState.value = RideTrackingState.Failed(failure)
        finishOwnership(keepState = true)
    }

    private fun ActiveRide.toTrackingState(): RideTrackingState.Active = RideTrackingState.Active(
        rideId = id,
        phase = phase,
        trackingStatus = trackingStatus,
    )

    private sealed interface Event {
        data class Command(val command: RideCommand) : Event
        data class Location(val sample: com.taxiinspector.ride.LocationSample) : Event
        data class Tick(val elapsedRealtimeMillis: Long) : Event
        data object PermissionRevoked : Event
        data object LocationFailed : Event
    }

    private companion object {
        const val TICK_INTERVAL_MILLIS = 1_000L
        const val NOTIFICATION_INTERVAL_MILLIS = 1_000L
    }
}
