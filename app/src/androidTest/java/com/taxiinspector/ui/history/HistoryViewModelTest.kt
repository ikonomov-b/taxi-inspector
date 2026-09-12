package com.taxiinspector.ui.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.Tariff
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that History is a formatted, live projection of durable Room state. */
@RunWith(AndroidJUnit4::class)
class HistoryViewModelTest {
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository
    private lateinit var viewModel: HistoryViewModel

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRideRepository(database.rideDao())
        viewModel = HistoryViewModel(repository, formatter())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun completedAndInterruptedRidesAppearNewestFirstWithFinalValues() = runBlocking {
        saveRide(
            id = "older-interrupted",
            endedAtUtcMillis = 10_000,
            status = RideSummary.Status.Interrupted,
            tariff = tariff("1", "2", "0.5"),
            distanceMeters = "750",
            timeTariffMillis = 30_000,
            elapsedMillis = 90_000,
        )
        saveRide(
            id = "newest-completed",
            endedAtUtcMillis = 20_000,
            status = RideSummary.Status.Completed,
            tariff = tariff("2.4", "1.2", "0.35"),
            distanceMeters = "2500",
            timeTariffMillis = 180_000,
            elapsedMillis = 3_849_000,
        )

        val state = awaitState { it.rides.size == 2 }

        assertEquals(listOf("newest-completed", "older-interrupted"), state.rides.map { it.id })
        assertEquals("6.45", state.rides.first().total)
        assertEquals("2.50", state.rides.first().distanceKilometres)
        assertEquals(HistoryRideStatus.Completed, state.rides.first().status)
        assertEquals(HistoryRideStatus.Interrupted, state.rides.last().status)
        assertTrue(state.rides.all { it.endedAt.isNotBlank() })
    }

    @Test
    fun trimmingIsImmediatelyReflectedAsTheNewestTenRows() = runBlocking {
        for (number in 1..11) {
            saveRide(
                id = "ride-$number",
                endedAtUtcMillis = number * 1_000L,
                tariff = tariff("1", "0", "0"),
            )
        }

        val state = awaitState { it.rides.size == 10 && it.rides.first().id == "ride-11" }

        assertEquals((11 downTo 2).map { "ride-$it" }, state.rides.map { it.id })
    }

    @Test
    fun retryingAnInterruptedSaveStillProducesOneVisibleRow() = runBlocking {
        val rideTariff = tariff("1", "2", "3")
        useCompany(rideTariff)
        val active = repository.startRide("interrupted", 1_000)
        val summary = RideEngine.finish(active, 5_000)

        repository.saveInterrupted(summary, 10_000)
        repository.saveInterrupted(summary, 10_000)

        val state = awaitState { !it.isLoading && it.rides.isNotEmpty() }
        assertEquals(listOf("interrupted"), state.rides.map { it.id })
        assertEquals(HistoryRideStatus.Interrupted, state.rides.single().status)
    }

    private suspend fun saveRide(
        id: String,
        endedAtUtcMillis: Long,
        status: RideSummary.Status = RideSummary.Status.Completed,
        tariff: Tariff,
        distanceMeters: String = "0",
        timeTariffMillis: Long = 0,
        elapsedMillis: Long = 1_000,
    ) {
        useCompany(tariff)
        val active = repository.startRide(id, 1_000).copy(
            distanceMeters = BigDecimal(distanceMeters),
            travelledDistanceMeters = BigDecimal(distanceMeters),
            timeTariffMillis = timeTariffMillis,
        )
        val summary = RideEngine.finish(active, 1_000 + elapsedMillis)
        when (status) {
            RideSummary.Status.Completed -> repository.finishCompleted(summary, endedAtUtcMillis)
            RideSummary.Status.Interrupted -> repository.saveInterrupted(summary, endedAtUtcMillis)
        }
    }

    private suspend fun awaitState(predicate: (HistoryUiState) -> Boolean): HistoryUiState =
        withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }

    private fun tariff(initial: String, perKm: String, perMinute: String) = Tariff(
        initialTax = requireNotNull(DecimalAmount.parse(initial)),
        perKmRate = requireNotNull(DecimalAmount.parse(perKm)),
        perMinuteStillRate = requireNotNull(DecimalAmount.parse(perMinute)),
        waitingCrossoverKilometersPerHour = requireNotNull(DecimalAmount.parse("8")),
    )

    private fun formatter() = RideHistoryFormatter(Locale.US, TimeZone.getTimeZone("UTC"))

    /** Ensures exactly one selected company holds this tariff, whatever is already stored. */
    private suspend fun useCompany(tariff: Tariff, name: String = "City Taxi") {
        val existing = repository.observeCompanies().first().firstOrNull { it.name == name }
        val id = if (existing == null) {
            repository.createCompany(name, tariff)
            repository.observeCompanies().first().single { it.name == name }.id
        } else {
            repository.updateCompany(existing.id, name, tariff)
            existing.id
        }
        repository.selectCompany(id)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
