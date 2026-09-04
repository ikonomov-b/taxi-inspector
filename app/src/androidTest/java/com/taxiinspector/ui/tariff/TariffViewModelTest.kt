package com.taxiinspector.ui.tariff

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.Tariff
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

/** Verifies exact tariff parsing, the ride lock, and durable storage against real Room. */
@RunWith(AndroidJUnit4::class)
class TariffViewModelTest {
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository
    private lateinit var viewModel: TariffViewModel

    private val savedEvents = Channel<Unit>(Channel.UNLIMITED)
    private val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRideRepository(database.rideDao())
        viewModel = TariffViewModel(repository)
        collectorScope.launch { viewModel.saved.collect { savedEvents.send(Unit) } }
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        database.close()
    }

    @Test
    fun aValidTariffIsStoredExactlyAndReportsThatItWasSaved() = runBlocking {
        enter("2.40", "1.20", "0.35")
        viewModel.onAction(TariffAction.Save)

        assertEquals(Unit, withTimeout(TIMEOUT_MILLIS) { savedEvents.receive() })
        assertEquals(tariff("2.40", "1.20", "0.35"), repository.currentTariff())
        assertEquals(
            TariffSummary("2.4", "1.2", "0.35"),
            awaitState { it.savedTariff != null }.savedTariff,
        )
    }

    @Test
    fun aDecimalCommaIsAcceptedWhileGroupingIsRejectedOnItsOwnField() = runBlocking {
        enter("2,40", "1 200", "0.35")
        viewModel.onAction(TariffAction.Save)

        val rejected = awaitState { it.form.invalidFields.isNotEmpty() }
        assertEquals(setOf(TariffField.PerKmRate), rejected.form.invalidFields)
        assertNull(repository.currentTariff())
        assertNull(withTimeoutOrNull(QUIET_MILLIS) { savedEvents.receive() })

        viewModel.onAction(TariffAction.FieldChanged(TariffField.PerKmRate, "1200"))
        viewModel.onAction(TariffAction.Save)

        withTimeout(TIMEOUT_MILLIS) { savedEvents.receive() }
        assertEquals(tariff("2.40", "1200", "0.35"), repository.currentTariff())
    }

    @Test
    fun sixFractionalDigitsAndZeroValuesAreAccepted() = runBlocking {
        enter("0", "0.123456", "0")
        viewModel.onAction(TariffAction.Save)

        withTimeout(TIMEOUT_MILLIS) { savedEvents.receive() }
        assertEquals(tariff("0", "0.123456", "0"), repository.currentTariff())
    }

    @Test
    fun anActiveRideRefusesTheSaveAndLeavesTheLockedTariffUntouched() = runBlocking {
        repository.saveTariff(tariff("2.40", "1.20", "0.35"))
        repository.startRide("ride-lock", 1_000)
        awaitState { it.isLocked }

        viewModel.onAction(TariffAction.FieldChanged(TariffField.InitialTax, "99"))
        viewModel.onAction(TariffAction.Save)

        assertNull(withTimeoutOrNull(QUIET_MILLIS) { savedEvents.receive() })
        assertEquals(tariff("2.40", "1.20", "0.35"), repository.currentTariff())
        assertEquals(tariff("2.40", "1.20", "0.35"), repository.currentActiveRide()?.tariff)
    }

    @Test
    fun resetClearsUnsavedEditsWithoutTouchingTheStoredTariff() = runBlocking {
        repository.saveTariff(tariff("2.40", "1.20", "0.35"))
        awaitState { it.form.initialTax == "2.4" }

        viewModel.onAction(TariffAction.FieldChanged(TariffField.InitialTax, "1,2,3"))
        awaitState { it.form.invalidFields.isNotEmpty() }
        viewModel.onAction(TariffAction.Reset)

        val state = awaitState { it.form.initialTax == "2.4" }
        assertTrue(state.form.invalidFields.isEmpty())
        assertEquals(tariff("2.40", "1.20", "0.35"), repository.currentTariff())
    }

    private fun enter(initialTax: String, perKm: String, perMinute: String) {
        viewModel.onAction(TariffAction.FieldChanged(TariffField.InitialTax, initialTax))
        viewModel.onAction(TariffAction.FieldChanged(TariffField.PerKmRate, perKm))
        viewModel.onAction(TariffAction.FieldChanged(TariffField.PerMinuteStillRate, perMinute))
    }

    private suspend fun awaitState(predicate: (TariffUiState) -> Boolean): TariffUiState =
        withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }

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
