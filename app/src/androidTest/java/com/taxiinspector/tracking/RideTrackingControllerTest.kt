package com.taxiinspector.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.core.time.Clock
import com.taxiinspector.data.location.LocationClient
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RideInput
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TrackingStatus
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideTrackingControllerTest {
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository
    private var controller: RideTrackingController? = null

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java).build()
        repository = RoomRideRepository(database.rideDao())
    }

    @After
    fun tearDown() {
        controller?.close()
        database.close()
    }

    @Test
    fun startRejectsEveryMissingPrerequisiteWithoutCreatingAnActiveRide() = runBlocking {
        repository.saveTariff(tariff())
        val scenarios = listOf(
            Scenario(false, true, true, StartRejection.PreciseLocationMissing),
            Scenario(true, false, true, StartRejection.GpsDisabled),
            Scenario(true, true, false, StartRejection.NotificationPermissionMissing),
        )

        scenarios.forEach { scenario ->
            val host = FakeTrackingHost()
            val candidate = createController(
                locationClient = FakeLocationClient(isGpsEnabled = scenario.gpsEnabled),
                prerequisites = FakePrerequisites(scenario.precise, scenario.notifications),
                host = host,
            )
            candidate.dispatch(RideCommand.Start)

            assertEquals(
                RideTrackingState.Rejected(scenario.rejection),
                candidate.awaitState { it is RideTrackingState.Rejected },
            )
            assertNull(repository.currentActiveRide())
            assertEquals(1, host.stopCount.get())
            candidate.close()
        }
        controller = null
    }

    @Test
    fun pausePersistsBeforeStoppingAndCancelsLocation() = runBlocking {
        repository.saveTariff(tariff())
        val locationClient = FakeLocationClient()
        val host = FakeTrackingHost(repository)
        val candidate = createController(locationClient = locationClient, host = host)

        candidate.dispatch(RideCommand.Start)
        val running = candidate.awaitActive()
        locationClient.awaitSubscription()
        locationClient.emit(gpsSample(1_000, accuracy = 8.0))
        repository.observeActiveRide().first { it?.trackingStatus == TrackingStatus.Good }

        candidate.dispatch(RideCommand.Pause)
        host.awaitStop()

        val persisted = repository.currentActiveRide()
        assertEquals(RidePhase.Paused, persisted?.phase)
        assertEquals(running.rideId, persisted?.id)
        assertEquals(RidePhase.Paused, host.phaseObservedWhenStopped)
        locationClient.awaitCancellation()
        assertFalse(candidate.ownsRide(running.rideId))
    }

    @Test
    fun stopIsSerializedAheadOfALaterLocationAndSavesExactlyOnce() = runBlocking {
        repository.saveTariff(tariff())
        val locationClient = FakeLocationClient()
        val host = FakeTrackingHost()
        val candidate = createController(locationClient = locationClient, host = host)

        candidate.dispatch(RideCommand.Start)
        val running = candidate.awaitActive()
        locationClient.awaitSubscription()
        locationClient.emit(gpsSample(1_000, longitude = 23.0))
        repository.observeActiveRide().first { it?.trackingStatus == TrackingStatus.Good }

        candidate.dispatch(RideCommand.Stop)
        locationClient.emit(gpsSample(2_000, longitude = 23.01))
        host.awaitStop()

        assertNull(repository.currentActiveRide())
        val history = repository.observeHistory().first()
        assertEquals(1, history.size)
        assertEquals(running.rideId, history.single().summary.id)
        assertEquals(RideSummary.Status.Completed, history.single().summary.status)
        assertEquals(0, history.single().summary.distanceMeters.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun permissionLossFreezesAndPersistsARecoverablePausedRide() = runBlocking {
        repository.saveTariff(tariff())
        val locationClient = FakeLocationClient()
        val host = FakeTrackingHost()
        val candidate = createController(locationClient = locationClient, host = host)

        candidate.dispatch(RideCommand.Start)
        val running = candidate.awaitActive()
        locationClient.awaitSubscription()
        locationClient.fail(SecurityException("revoked"))

        assertEquals(
            RideTrackingState.Failed(TrackingFailure.PermissionRevoked),
            candidate.awaitState { it == RideTrackingState.Failed(TrackingFailure.PermissionRevoked) },
        )
        host.awaitStop()
        val persisted = repository.currentActiveRide()
        assertEquals(running.rideId, persisted?.id)
        assertEquals(RidePhase.Paused, persisted?.phase)
        assertEquals(TrackingStatus.PermissionNeeded, persisted?.trackingStatus)
        assertFalse(candidate.ownsRide(running.rideId))
    }

    @Test
    fun resumeUsesTheLockedPausedRideAndDiscardDeletesItWithoutHistory() = runBlocking {
        repository.saveTariff(tariff())
        val started = repository.startRide("paused-ride", 100)
        val withBaseline = RideEngine.reduce(
            started,
            RideInput.LocationReceived(gpsSample(200), nowElapsedMillis = 200),
        )
        repository.updateActiveRide(RideEngine.reduce(withBaseline, RideInput.Pause))
        val locationClient = FakeLocationClient()
        val host = FakeTrackingHost()
        val clock = FakeClock(elapsed = 1_000)
        val candidate = createController(locationClient = locationClient, host = host, clock = clock)

        candidate.dispatch(RideCommand.Resume)
        candidate.awaitState { it is RideTrackingState.Active && it.phase == RidePhase.Running }
        locationClient.awaitSubscription()
        val resumed = repository.currentActiveRide()
        assertEquals(RidePhase.Running, resumed?.phase)
        assertEquals(TrackingStatus.Searching, resumed?.trackingStatus)
        assertNull(resumed?.lastBillablePoint)

        candidate.dispatch(RideCommand.Discard)
        host.awaitStop()
        assertNull(repository.currentActiveRide())
        assertTrue(repository.observeHistory().first().isEmpty())
    }

    @Test
    fun stopCanSaveAPersistedPausedRideWithoutResumingGps() = runBlocking {
        repository.saveTariff(tariff())
        val started = repository.startRide("paused-stop", 100)
        repository.updateActiveRide(RideEngine.reduce(started, RideInput.Pause))
        val locationClient = FakeLocationClient()
        val host = FakeTrackingHost()
        val candidate = createController(locationClient = locationClient, host = host)

        candidate.dispatch(RideCommand.Stop)
        host.awaitStop()

        assertNull(repository.currentActiveRide())
        val saved = repository.observeHistory().first().single()
        assertEquals("paused-stop", saved.summary.id)
        assertEquals(RideSummary.Status.Completed, saved.summary.status)
        assertFalse(locationClient.wasSubscribed())
    }

    @Test
    fun meaningfulNotificationUpdatesAreBoundedToOncePerSecond() = runBlocking {
        repository.saveTariff(tariff())
        val locationClient = FakeLocationClient()
        val host = FakeTrackingHost()
        val clock = FakeClock(elapsed = 1_000)
        val candidate = createController(locationClient = locationClient, host = host, clock = clock)

        candidate.dispatch(RideCommand.Start)
        candidate.awaitActive()
        locationClient.awaitSubscription()
        assertEquals(1, host.notifications.size)

        locationClient.emit(gpsSample(1_000, accuracy = 8.0))
        candidate.awaitState { it is RideTrackingState.Active && it.trackingStatus == TrackingStatus.Good }
        clock.elapsed = 1_500
        locationClient.emit(gpsSample(1_500, accuracy = 30.0))
        candidate.awaitState { it is RideTrackingState.Active && it.trackingStatus == TrackingStatus.Weak }
        assertEquals(1, host.notifications.size)

        clock.elapsed = 2_000
        locationClient.emit(gpsSample(2_000, accuracy = 8.0))
        candidate.awaitState { it is RideTrackingState.Active && it.trackingStatus == TrackingStatus.Good }
        awaitCondition { host.notifications.size == 2 }
        assertEquals(2, host.notifications.size)
    }

    @Test
    fun unownedRunningRideBecomesInterruptedOnceWhileLiveOwnershipPreservesIt() = runBlocking {
        repository.saveTariff(tariff())
        val locationClient = FakeLocationClient()
        val candidate = createController(locationClient = locationClient)
        candidate.dispatch(RideCommand.Start)
        val running = candidate.awaitActive()
        locationClient.awaitSubscription()
        locationClient.emit(gpsSample(1_000))
        repository.observeActiveRide().first { it?.lastBillablePoint != null }
        val recovery = RideRecoveryCoordinator(repository)

        val stillOwned = recovery.recoverRunningRideAfterOwnershipCheck(running.rideId, candidate::ownsRide)
        assertEquals(RidePhase.Running, stillOwned?.phase)

        candidate.close()
        controller = null
        val interrupted = recovery.recoverRunningRideAfterOwnershipCheck(running.rideId, null)
        assertEquals(RidePhase.PendingInterrupted, interrupted?.phase)
        assertNull(interrupted?.lastBillablePoint)
        assertNull(interrupted?.lastSpeedMetersPerSecond)

        val repeated = recovery.recoverRunningRideAfterOwnershipCheck(running.rideId, null)
        assertEquals(interrupted, repeated)
    }

    private fun createController(
        locationClient: FakeLocationClient = FakeLocationClient(),
        prerequisites: FakePrerequisites = FakePrerequisites(),
        host: FakeTrackingHost = FakeTrackingHost(),
        clock: FakeClock = FakeClock(),
    ): RideTrackingController = RideTrackingController(
        repository = repository,
        locationClient = locationClient,
        prerequisites = prerequisites,
        clock = clock,
        host = host,
        rideIdFactory = { "ride-under-test" },
    ).also { controller = it }

    private suspend fun RideTrackingController.awaitActive(): RideTrackingState.Active =
        awaitState { it is RideTrackingState.Active } as RideTrackingState.Active

    private suspend fun RideTrackingController.awaitState(
        predicate: (RideTrackingState) -> Boolean,
    ): RideTrackingState = withTimeout(3_000) { state.first(predicate) }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(3_000) {
            while (!predicate()) delay(10)
        }
    }

    private fun tariff(): Tariff = Tariff(
        initialTax = amount("1.25"),
        perKmRate = amount("2.50"),
        perMinuteStillRate = amount("0.75"),
    )

    private fun amount(value: String): DecimalAmount = DecimalAmount.of(BigDecimal(value))

    private fun gpsSample(
        elapsedMillis: Long,
        longitude: Double = 23.0,
        accuracy: Double = 8.0,
    ): LocationSample = LocationSample(
        latitude = 42.0,
        longitude = longitude,
        accuracyMeters = accuracy,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = 2.0,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
    )

    private data class Scenario(
        val precise: Boolean,
        val gpsEnabled: Boolean,
        val notifications: Boolean,
        val rejection: StartRejection,
    )

    private class FakePrerequisites(
        private val precise: Boolean = true,
        private val notifications: Boolean = true,
    ) : TrackingPrerequisites {
        override fun hasPreciseLocationPermission(): Boolean = precise

        override fun hasNotificationPermission(): Boolean = notifications
    }

    private class FakeClock(
        @Volatile var elapsed: Long = 1_000,
        @Volatile var utc: Long = 100_000,
    ) : Clock {
        override fun elapsedRealtimeMillis(): Long = elapsed

        override fun utcMillis(): Long = utc
    }

    private class FakeLocationClient(
        private val isGpsEnabled: Boolean = true,
    ) : LocationClient {
        private val samples = Channel<LocationSample>(Channel.UNLIMITED)
        private val subscribed = CompletableDeferred<Unit>()
        private val cancellationCount = AtomicInteger()

        override fun isGpsProviderEnabled(): Boolean = isGpsEnabled

        override fun locationSamples(): Flow<LocationSample> = flow {
            subscribed.complete(Unit)
            try {
                for (sample in samples) emit(sample)
            } finally {
                cancellationCount.incrementAndGet()
            }
        }

        suspend fun awaitSubscription() {
            withTimeout(3_000) { subscribed.await() }
        }

        suspend fun emit(sample: LocationSample) {
            samples.send(sample)
        }

        fun fail(error: Throwable) {
            samples.close(error)
        }

        suspend fun awaitCancellation() {
            withTimeout(3_000) {
                while (cancellationCount.get() == 0) delay(10)
            }
        }

        fun wasSubscribed(): Boolean = subscribed.isCompleted
    }

    private class FakeTrackingHost(
        private val repository: RoomRideRepository? = null,
    ) : TrackingHost {
        val notifications = CopyOnWriteArrayList<ActiveRide>()
        val stopCount = AtomicInteger()
        private val stopped = CompletableDeferred<Unit>()
        @Volatile var phaseObservedWhenStopped: RidePhase? = null

        override fun updateForegroundNotification(ride: ActiveRide) {
            notifications += ride
        }

        override fun stopForegroundAndService() {
            phaseObservedWhenStopped = repository?.let { runBlocking { it.currentActiveRide()?.phase } }
            stopCount.incrementAndGet()
            stopped.complete(Unit)
        }

        suspend fun awaitStop() {
            withTimeout(3_000) { stopped.await() }
        }
    }
}
