package com.taxiinspector.ui.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.Tariff
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies detail fidelity and confirmed individual deletion against real Room. */
@RunWith(AndroidJUnit4::class)
class RideDetailViewModelTest {
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, TaxiInspectorDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomRideRepository(database.rideDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun detailReproducesTheStoredSummaryAndLockedTariff() = runBlocking {
        saveCompleted("selected", endedAtUtcMillis = 20_000)
        val viewModel = RideDetailViewModel("selected", repository, formatter())

        val ride = awaitState(viewModel) { it.ride != null }.ride

        assertNotNull(ride)
        requireNotNull(ride)
        assertEquals("6.45", ride.total)
        assertEquals("2.50", ride.distanceKilometres)
        assertEquals("03:00", ride.waitTime)
        assertEquals("1:04:09", ride.elapsedTime)
        assertEquals("2.4", ride.initialTax)
        assertEquals("1.2", ride.perKmRate)
        assertEquals("0.35", ride.perMinuteStillRate)
        assertEquals(HistoryRideStatus.Completed, ride.status)
        assertFalse(ride.endedAt.isBlank())
    }

    @Test
    fun deletionRequiresConfirmationAndLeavesOtherHistoryUntouched() = runBlocking {
        saveCompleted("selected", endedAtUtcMillis = 10_000)
        saveCompleted("other", endedAtUtcMillis = 20_000)
        val viewModel = RideDetailViewModel("selected", repository, formatter())
        awaitState(viewModel) { it.ride != null }

        viewModel.onAction(RideDetailAction.DeleteRequested)
        awaitState(viewModel) { it.isDeleteConfirmationVisible }
        viewModel.onAction(RideDetailAction.DeleteDismissed)
        awaitState(viewModel) { !it.isDeleteConfirmationVisible }
        assertNotNull(repository.observeSummary("selected").first())

        viewModel.onAction(RideDetailAction.DeleteRequested)
        awaitState(viewModel) { it.isDeleteConfirmationVisible }
        viewModel.onAction(RideDetailAction.DeleteConfirmed)

        withTimeout(TIMEOUT_MILLIS) { viewModel.deleted.first() }
        assertNull(repository.observeSummary("selected").first())
        assertNotNull(repository.observeSummary("other").first())
    }

    private suspend fun saveCompleted(id: String, endedAtUtcMillis: Long) {
        repository.saveTariff(tariff())
        val active = repository.startRide(id, 1_000).copy(
            distanceMeters = BigDecimal("2500"),
            idleMillis = 180_000,
        )
        repository.finishCompleted(
            RideEngine.finish(active, 3_850_000),
            endedAtUtcMillis,
        )
    }

    private suspend fun awaitState(
        viewModel: RideDetailViewModel,
        predicate: (RideDetailUiState) -> Boolean,
    ): RideDetailUiState = withTimeout(TIMEOUT_MILLIS) { viewModel.state.first(predicate) }

    private fun tariff() = Tariff(
        initialTax = requireNotNull(DecimalAmount.parse("2.4")),
        perKmRate = requireNotNull(DecimalAmount.parse("1.2")),
        perMinuteStillRate = requireNotNull(DecimalAmount.parse("0.35")),
    )

    private fun formatter() = RideHistoryFormatter(Locale.US, TimeZone.getTimeZone("UTC"))

    private companion object {
        const val TIMEOUT_MILLIS = 5_000L
    }
}
