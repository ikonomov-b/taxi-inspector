package com.taxiinspector.ui.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.rides.TaxiInspectorDatabase
import com.taxiinspector.data.trace.RideTraceStore
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.Tariff
import java.io.File
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies detail fidelity and confirmed individual deletion against real Room. */
@RunWith(AndroidJUnit4::class)
class RideDetailViewModelTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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

    @Test
    fun aRideWithATraceOffersToShareItAndARideWithoutOneDoesNot() = runBlocking {
        val store = RideTraceStore(temporaryFolder.root)
        saveCompleted("traced", endedAtUtcMillis = 1_000)
        val viewModel = RideDetailViewModel("traced", repository, traceStore = store)

        // No trace: a build that never recorded one, or a ride from before it was switched on.
        assertFalse(awaitState(viewModel) { it.ride != null }.hasTrace)

        store.directoryFor("traced").mkdirs()
        File(store.directoryFor("traced"), RideTraceStore.TRACK_FILE).writeText("<gpx></gpx>")
        val withTrace = RideDetailViewModel("traced", repository, traceStore = store)

        assertTrue(awaitState(withTrace) { it.ride != null }.hasTrace)

        withTrace.onAction(RideDetailAction.ShareTrack)
        val effect = withTimeout(3_000) { withTrace.effect.first() }
        effect as RideDetailEffect.ShareFiles
        assertEquals(1, effect.files.size)
        assertEquals(RideTraceStore.TRACK_FILE, effect.files.single().name)
        assertEquals("application/gpx+xml", effect.mimeType)
    }

    @Test
    fun deletingARideDeletesItsRoute() = runBlocking {
        val store = RideTraceStore(temporaryFolder.root)
        saveCompleted("traced", endedAtUtcMillis = 1_000)
        store.directoryFor("traced").mkdirs()
        File(store.directoryFor("traced"), RideTraceStore.TRACK_FILE).writeText("<gpx></gpx>")
        val viewModel = RideDetailViewModel("traced", repository, traceStore = store)
        awaitState(viewModel) { it.ride != null }

        viewModel.onAction(RideDetailAction.DeleteConfirmed)
        withTimeout(3_000) { viewModel.deleted.first() }

        assertFalse(store.hasTrace("traced"))
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
        useCompany(tariff())
        val active = repository.startRide(id, 1_000).copy(
            distanceMeters = BigDecimal("2500"),
            timeTariffMillis = 180_000,
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
