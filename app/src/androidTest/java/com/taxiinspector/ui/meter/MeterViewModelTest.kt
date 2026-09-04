package com.taxiinspector.ui.meter

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.Tariff
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.tracking.RideCommand
import com.taxiinspector.tracking.RideRecoveryCoordinator
import com.taxiinspector.ui.tariff.TariffSummary
import java.math.BigDecimal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the meter state holder against a real in-memory Room database, so the durable
 * state, the tariff lock, the permission/GPS gate, and interrupted recovery are verified
 * without a live foreground service.
 */
@RunWith(AndroidJUnit4::class)
class MeterViewModelTest {
    private lateinit var context: Context
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository
    private lateinit var viewModel: MeterViewModel

    private val collected = Channel<MeterEffect>(Channel.UNLIMITED)
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRideRepository(database.rideDao())
        viewModel = MeterViewModel(repository, RideRecoveryCoordinator(repository))
        collectorScope.launch { viewModel.effects.collect { collected.send(it) } }
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        database.close()
    }

    @Test
    fun startWithoutASavedTariffAsksForOneAndSendsNoCommand() = runBlocking {
        viewModel.onAction(MeterAction.EnvironmentChanged(readyEnvironment()))
        viewModel.onAction(MeterAction.StartRide)

        awaitState { it.message == MeterMessage.TariffNeededToStart }
        assertNull(nextEffectOrNull())
    }

    @Test
    fun startRequestsPreciseLocationFirstAndCommandsTheServiceOnceGranted() = runBlocking {
        saveTariff()
        viewModel.onAction(MeterAction.EnvironmentChanged(MeterEnvironment()))
        viewModel.onAction(MeterAction.StartRide)

        assertEquals(MeterEffect.RequestPreciseLocationPermission, nextEffect())
        viewModel.onAction(MeterAction.PermissionResult(readyEnvironment(), isGranted = true))
        assertEquals(MeterEffect.SendCommand(RideCommand.Start), nextEffect())
    }

    @Test
    fun aDeniedPermissionStopsTheStartAndOffersASettingsRecovery() = runBlocking {
        saveTariff()
        viewModel.onAction(MeterAction.EnvironmentChanged(MeterEnvironment()))
        viewModel.onAction(MeterAction.StartRide)
        assertEquals(MeterEffect.RequestPreciseLocationPermission, nextEffect())

        viewModel.onAction(MeterAction.PermissionResult(MeterEnvironment(), isGranted = false))

        val state = awaitState { it.recovery != null }
        assertEquals(MeterRecovery.GrantPreciseLocation, state.recovery)
        assertNull(nextEffectOrNull())

        viewModel.onAction(MeterAction.RecoveryRequested)
        assertEquals(MeterEffect.OpenAppSettings, nextEffect())
    }

    @Test
    fun aDisabledGpsProviderBlocksStartUntilItIsTurnedOn() = runBlocking {
        saveTariff()
        viewModel.onAction(
            MeterAction.EnvironmentChanged(readyEnvironment().copy(isGpsProviderEnabled = false)),
        )
        viewModel.onAction(MeterAction.StartRide)

        assertEquals(MeterRecovery.EnableGps, awaitState { it.recovery != null }.recovery)
        assertEquals(MeterStatus.GpsDisabled, awaitState { it.status == MeterStatus.GpsDisabled }.status)
        assertNull(nextEffectOrNull())

        viewModel.onAction(MeterAction.RecoveryRequested)
        assertEquals(MeterEffect.OpenLocationSettings, nextEffect())

        viewModel.onAction(MeterAction.EnvironmentChanged(readyEnvironment()))
        awaitState { it.recovery == null }
        viewModel.onAction(MeterAction.StartRide)
        assertEquals(MeterEffect.SendCommand(RideCommand.Start), nextEffect())
    }

    @Test
    fun anActiveRideWithdrawsTariffEditingAndStarting() = runBlocking {
        saveTariff()
        assertTrue(awaitState { it.canStart }.canEditTariff)

        repository.startRide("ride-lock", 1_000)

        val locked = awaitState { !it.canEditTariff }
        assertEquals(false, locked.canStart)
        assertEquals(
            TariffSummary("2.4", "1.2", "0.35"),
            locked.savedTariff,
        )
    }

    @Test
    fun theMeterPresentsTheDocumentedFareExampleWithoutACurrencyLabel() = runBlocking {
        saveTariff()
        val ride = repository.startRide("ride-fare", 1_000)
        repository.updateActiveRide(
            ride.copy(distanceMeters = BigDecimal("2500"), idleMillis = 180_000),
        )

        val presentation = awaitState { it.presentation.distance != "0.00" }.presentation
        assertEquals("6.45", presentation.total)
        assertEquals("2.50", presentation.distance)
        assertEquals("03:00", presentation.waitTime)
        assertEquals(MeterPhaseLabel.Running, presentation.phase)
    }

    @Test
    fun discardRequiresConfirmationBeforeAnyCommandIsSent() = runBlocking {
        saveTariff()
        repository.startRide("ride-discard", 1_000)
        awaitState { !it.canEditTariff }
        drainEffects()

        viewModel.onAction(MeterAction.DiscardRequested)
        assertTrue(awaitState { it.isDiscardConfirmationVisible }.isDiscardConfirmationVisible)
        assertNull(nextEffectOrNull())

        viewModel.onAction(MeterAction.DiscardDismissed)
        awaitState { !it.isDiscardConfirmationVisible }
        assertNull(nextEffectOrNull())

        viewModel.onAction(MeterAction.DiscardRequested)
        viewModel.onAction(MeterAction.DiscardConfirmed)
        assertEquals(MeterEffect.SendCommand(RideCommand.Discard), nextEffect())
    }

    @Test
    fun pauseAndStopReachTheServiceAsExplicitCommands() = runBlocking {
        saveTariff()
        repository.startRide("ride-commands", 1_000)
        awaitState { !it.canEditTariff }
        drainEffects()

        viewModel.onAction(MeterAction.PauseRide)
        assertEquals(MeterEffect.SendCommand(RideCommand.Pause), nextEffect())
        viewModel.onAction(MeterAction.StopAndSave)
        assertEquals(MeterEffect.SendCommand(RideCommand.Stop), nextEffect())
    }

    @Test
    fun aRunningSnapshotIsCheckedOnceAndRecoveredOnlyWhenNoServiceOwnsIt() = runBlocking {
        saveTariff()
        val ride = repository.startRide("ride-orphan", 1_000)

        assertEquals(MeterEffect.CheckServiceOwnership(ride.id), nextEffect())
        viewModel.onAction(
            MeterAction.ServiceOwnershipChecked(ride.id, isOwnedByLiveService = false),
        )

        val recovered = withTimeout(TIMEOUT_MILLIS) {
            repository.observeActiveRide().first { it?.phase == RidePhase.PendingInterrupted }
        }
        assertEquals(ride.id, recovered?.id)
        assertEquals(MeterPhaseLabel.Interrupted, awaitState {
            it.presentation.phase == MeterPhaseLabel.Interrupted
        }.presentation.phase)
        // The check is never repeated for the same ride, so recovery cannot run twice.
        assertNull(nextEffectOrNull())
    }

    @Test
    fun aRunningSnapshotOwnedByALiveServiceIsLeftRunning() = runBlocking {
        saveTariff()
        val ride = repository.startRide("ride-owned", 1_000)

        assertEquals(MeterEffect.CheckServiceOwnership(ride.id), nextEffect())
        viewModel.onAction(
            MeterAction.ServiceOwnershipChecked(ride.id, isOwnedByLiveService = true),
        )

        awaitState { it.presentation.phase == MeterPhaseLabel.Running }
        assertEquals(RidePhase.Running, repository.currentActiveRide()?.phase)
    }

    /** The tariff is owned by its own destination, so this writes it directly. */
    private suspend fun saveTariff() {
        repository.saveTariff(tariff("2.40", "1.20", "0.35"))
        awaitState { it.canStart }
        drainEffects()
    }

    private suspend fun awaitState(predicate: (MeterUiState) -> Boolean): MeterUiState =
        withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }

    private suspend fun nextEffect(): MeterEffect =
        withTimeout(TIMEOUT_MILLIS) { collected.receive() }

    private suspend fun nextEffectOrNull(): MeterEffect? =
        withTimeoutOrNull(QUIET_MILLIS) { collected.receive() }

    private suspend fun drainEffects() {
        while (withTimeoutOrNull(QUIET_MILLIS) { collected.receive() } != null) {
            // Discard set-up effects so each assertion sees only what its action produced.
        }
    }

    private fun readyEnvironment() = MeterEnvironment(
        hasPreciseLocationPermission = true,
        hasNotificationPermission = true,
        isGpsProviderEnabled = true,
    )

    private fun tariff(initialTax: String, perKm: String, perMinute: String) = Tariff(
        initialTax = requireNotNull(DecimalAmount.parse(initialTax)),
        perKmRate = requireNotNull(DecimalAmount.parse(perKm)),
        perMinuteStillRate = requireNotNull(DecimalAmount.parse(perMinute)),
    )

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
        const val QUIET_MILLIS = 400L
    }
}
