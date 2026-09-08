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
import com.taxiinspector.ui.TariffSummary
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
 * state, company selection and its ride lock, the permission/GPS gate, and interrupted
 * recovery are verified without a live foreground service.
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
    fun startWithoutASelectedCompanyAsksForOneAndSendsNoCommand() = runBlocking {
        viewModel.onAction(MeterAction.EnvironmentChanged(readyEnvironment()))
        viewModel.onAction(MeterAction.StartRide)

        val state = awaitState { it.message == MeterMessage.CompanyNeededToStart }
        assertEquals(MeterStatus.CompanyNeeded, state.status)
        assertNull(state.company)
        assertNull(nextEffectOrNull())
    }

    @Test
    fun theSelectorDurablySelectsAWholeProfileAndEnablesStart() = runBlocking {
        saveCompany("City Taxi", "2.40", "1.20", "0.35")
        repository.createCompany("Night Cabs", tariff("5", "2", "0.5"))
        val night = awaitState { it.companies.size == 2 }.companies.single { it.name == "Night Cabs" }

        viewModel.onAction(MeterAction.CompanySelectorOpened)
        assertTrue(awaitState { it.isCompanySelectorVisible }.isCompanySelectorVisible)

        viewModel.onAction(MeterAction.CompanySelected(night.id))

        val state = awaitState { it.company?.name == "Night Cabs" }
        assertEquals(false, state.isCompanySelectorVisible)
        assertEquals(TariffSummary("5", "2", "0.5"), state.company?.tariff)
        assertEquals(night.id, state.selectedCompanyId)
        // Selecting a name selects all three of its rates together, durably.
        assertEquals("Night Cabs", repository.selectedCompany()?.name)
        assertTrue(state.canStart)
    }

    @Test
    fun startRequestsPreciseLocationFirstAndCommandsTheServiceOnceGranted() = runBlocking {
        saveCompany()
        viewModel.onAction(MeterAction.EnvironmentChanged(MeterEnvironment()))
        viewModel.onAction(MeterAction.StartRide)

        assertEquals(MeterEffect.RequestPreciseLocationPermission, nextEffect())
        viewModel.onAction(MeterAction.PermissionResult(readyEnvironment(), isGranted = true))
        assertEquals(MeterEffect.SendCommand(RideCommand.Start), nextEffect())
    }

    @Test
    fun aDeniedPermissionStopsTheStartAndOffersASettingsRecovery() = runBlocking {
        saveCompany()
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
        saveCompany()
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
    fun anActiveRideWithdrawsCompanyManagementAndStarting() = runBlocking {
        saveCompany()
        assertTrue(awaitState { it.canStart }.canManageCompanies)

        repository.startRide("ride-lock", 1_000)

        val locked = awaitState { !it.canManageCompanies }
        assertEquals(false, locked.canStart)
        // The ride's own locked snapshot, and nothing selectable while it holds it.
        assertEquals(MeterCompany("City Taxi", TariffSummary("2.4", "1.2", "0.35")), locked.company)
        assertTrue(locked.companies.isEmpty())
        assertNull(locked.selectedCompanyId)
    }

    @Test
    fun aRideLockedBeforeCompaniesExistedShowsNoInventedName() = runBlocking {
        saveCompany()
        val ride = repository.startRide("ride-legacy", 1_000)
        repository.updateActiveRide(ride.copy(companyName = null))

        val state = awaitState { it.company != null && it.company?.name == null }
        assertNull(state.company?.name)
        // Its own locked rates still show, so the fare stays explainable.
        assertEquals(TariffSummary("2.4", "1.2", "0.35"), state.company?.tariff)
    }

    @Test
    fun theMeterPresentsTheDocumentedFareExampleWithoutACurrencyLabel() = runBlocking {
        saveCompany()
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
        saveCompany()
        repository.startRide("ride-discard", 1_000)
        awaitState { !it.canManageCompanies }
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
        saveCompany()
        repository.startRide("ride-commands", 1_000)
        awaitState { !it.canManageCompanies }
        drainEffects()

        viewModel.onAction(MeterAction.PauseRide)
        assertEquals(MeterEffect.SendCommand(RideCommand.Pause), nextEffect())
        viewModel.onAction(MeterAction.StopAndSave)
        assertEquals(MeterEffect.SendCommand(RideCommand.Stop), nextEffect())
    }

    @Test
    fun aRunningSnapshotIsCheckedOnceAndRecoveredOnlyWhenNoServiceOwnsIt() = runBlocking {
        saveCompany()
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
        saveCompany()
        val ride = repository.startRide("ride-owned", 1_000)

        assertEquals(MeterEffect.CheckServiceOwnership(ride.id), nextEffect())
        viewModel.onAction(
            MeterAction.ServiceOwnershipChecked(ride.id, isOwnedByLiveService = true),
        )

        awaitState { it.presentation.phase == MeterPhaseLabel.Running }
        assertEquals(RidePhase.Running, repository.currentActiveRide()?.phase)
    }

    /** Companies are owned by their own destination, so this writes one directly. */
    private suspend fun saveCompany(
        name: String = "City Taxi",
        initialTax: String = "2.40",
        perKm: String = "1.20",
        perMinute: String = "0.35",
    ) {
        // The first saved company selects itself, so the meter becomes startable.
        repository.createCompany(name, tariff(initialTax, perKm, perMinute))
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
