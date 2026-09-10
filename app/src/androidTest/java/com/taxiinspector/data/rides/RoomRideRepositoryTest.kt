package com.taxiinspector.data.rides

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.MotionState
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.SavedRideSummary
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TrackingStatus
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRideRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: TaxiInspectorDatabase
    private lateinit var repository: RoomRideRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun startLocksTheCompanyNameAndTariffAndFinishAtomicallyMovesRideToHistory() = runBlocking {
        val lockedTariff = tariff("1.25", "2.50", "0.75")
        saveCompany("City Taxi", lockedTariff)

        val active = repository.startRide("ride-1", 1_000)
        assertEquals(lockedTariff, active.tariff)
        // The name and the rates are copied together, in one transaction.
        assertEquals("City Taxi", active.companyName)
        assertEquals(
            CompanySaveResult.RideActive,
            repository.createCompany("Night Cabs", tariff("9", "9", "9")),
        )

        val summary = RideEngine.finish(active, 6_000)
        repository.finishCompleted(summary, endedAtUtcMillis = 100_000)

        assertNull(database.rideDao().activeRide())
        assertEquals(summary, database.rideDao().summary("ride-1")?.toDomain()?.summary)
    }

    @Test
    fun failedFinishRollsBackActiveRideDeletion() = runBlocking {
        saveCompany("City Taxi", tariff("1", "2", "3"))
        val existing = repository.startRide("duplicate-id", 1_000)
        val summary = RideEngine.finish(existing, 2_000)
        database.rideDao().insertSummary(
            SavedRideSummary(summary, endedAtUtcMillis = 10_000).toEntity(),
        )

        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking { repository.finishCompleted(summary, endedAtUtcMillis = 20_000) }
        }

        assertNotNull(database.rideDao().activeRide())
        assertEquals(10_000L, database.rideDao().summary("duplicate-id")?.endedAtUtcMillis)
    }

    @Test
    fun elevenSavedRidesKeepNewestTenWithTheirLockedTariffs() = runBlocking {
        val companyId = saveCompany("City Taxi", tariff("1", "1", "1"))
        for (number in 1..11) {
            val rideTariff = tariff(number.toString(), "$number.25", "$number.5")
            repository.updateCompany(companyId, "City Taxi", rideTariff)
            val active = repository.startRide("ride-$number", number * 1_000L)
            repository.finishCompleted(
                RideEngine.finish(active, number * 1_000L + 500),
                endedAtUtcMillis = number * 10_000L,
            )
        }

        val history = repository.observeHistory().first()
        assertEquals((11 downTo 2).map { "ride-$it" }, history.map { it.summary.id })
        history.forEach { saved ->
            val number = saved.summary.id.removePrefix("ride-")
            assertEquals(amount(number), saved.summary.tariff.initialTax)
            assertEquals(amount("$number.25"), saved.summary.tariff.perKmRate)
            assertEquals(amount("$number.5"), saved.summary.tariff.perMinuteStillRate)
        }
    }

    @Test
    fun concurrentInterruptedSavesAreIdempotent() = runBlocking {
        saveCompany("City Taxi", tariff("1", "2", "3"))
        val active = repository.startRide("interrupted", 1_000)
        val summary = RideEngine.finish(active, 5_000)

        listOf(
            async(Dispatchers.Default) { repository.saveInterrupted(summary, 50_000) },
            async(Dispatchers.Default) { repository.saveInterrupted(summary, 50_000) },
        ).awaitAll()

        val history = repository.observeHistory().first()
        assertEquals(1, history.size)
        assertEquals(RideSummary.Status.Interrupted, history.single().summary.status)
        assertNull(database.rideDao().activeRide())
    }

    @Test
    fun interruptingARunningRideKeepsTheTimeItHadAlreadyBilled() = runBlocking {
        saveCompany("City Taxi", tariff("1", "2", "3"))
        val running = repository.startRide("interrupted", 1_000)
        repository.updateActiveRide(
            running.copy(distanceMeters = BigDecimal("250"), timeTariffMillis = 42_000),
        )

        val interrupted = requireNotNull(repository.markRunningRideInterrupted("interrupted"))

        assertEquals(RidePhase.PendingInterrupted, interrupted.phase)
        assertEquals(TrackingStatus.GpsLost, interrupted.trackingStatus)
        assertEquals(42_000L, interrupted.billedTimeMillis)
        assertEquals("250", interrupted.distanceMeters.toPlainString())
        assertNull(interrupted.lastBillablePoint)
    }

    @Test
    fun companySelectionAndCompleteActiveSnapshotSurviveDatabaseRecreation() = runBlocking {
        val savedTariff = tariff("1.250000", "2.75", "0.500001")
        val companyId = saveCompany("City Taxi", savedTariff)
        val baseline = LocationSample(
            latitude = 42.6977,
            longitude = 23.3219,
            accuracyMeters = 7.5,
            provider = LocationSample.Provider.Gps,
            speedMetersPerSecond = 0.25,
            fixElapsedMillis = 8_500,
            receivedElapsedMillis = 8_750,
        )
        val stored = repository.startRide("recreated", 1_000).copy(
            phase = RidePhase.Paused,
            trackingStatus = TrackingStatus.Weak,
            distanceMeters = BigDecimal("123.450"),
            timeTariffMillis = 6_789,
            provisionalTimeMillis = 1_211,
            motionState = MotionState.Idle,
            lastTickElapsedMillis = 9_000,
            lastAcceptedFix = baseline.copy(fixElapsedMillis = 8_900),
            lastFreshBillableReceivedElapsedMillis = 8_750,
            lastBillablePoint = baseline,
            pendingOutlier = baseline.copy(fixElapsedMillis = 8_950),
            outlierStreak = 1,
        )
        // The stored row carries one time figure, so the observed hold is committed into it, and
        // the transient plausibility state is not stored at all: a baseline restored without the
        // fix clock it was measured against must not be able to form a chord across the gap.
        val expected = stored.copy(
            timeTariffMillis = 8_000,
            provisionalTimeMillis = 0,
            lastAcceptedFix = null,
            pendingOutlier = null,
            outlierStreak = 0,
        )
        repository.updateActiveRide(stored)

        database.close()
        openDatabase()

        val selected = requireNotNull(repository.observeSelectedCompany().first())
        assertEquals(companyId, selected.id)
        assertEquals("City Taxi", selected.name)
        assertEquals(savedTariff, selected.tariff)
        assertEquals(expected, repository.observeActiveRide().first())
    }

    @Test
    fun deletingSummaryLeavesOtherHistoryUntouched() = runBlocking {
        saveCompany("City Taxi", tariff("1", "2", "3"))
        val first = repository.startRide("first", 1_000)
        repository.finishCompleted(RideEngine.finish(first, 2_000), 10_000)
        val second = repository.startRide("second", 3_000)
        repository.finishCompleted(RideEngine.finish(second, 4_000), 20_000)

        repository.deleteSummary("first")

        assertNull(database.rideDao().summary("first"))
        assertNotNull(database.rideDao().summary("second"))
    }

    @Test
    fun onlyTenCompaniesSurviveConcurrentAddsAndNoneIsEvicted() = runBlocking {
        val results = (1..14).map { number ->
            async(Dispatchers.Default) {
                repository.createCompany("Company $number", tariff("$number", "1", "1"))
            }
        }.awaitAll()

        assertEquals(10, results.count { it == CompanySaveResult.Saved })
        assertEquals(4, results.count { it == CompanySaveResult.LimitReached })
        val companies = repository.observeCompanies().first()
        assertEquals(10, companies.size)
        assertEquals(10, companies.map { it.name }.toSet().size)
        // The first insert to win selects itself, and nothing later overwrote that.
        assertNotNull(repository.selectedCompany())
    }

    @Test
    fun aDuplicateNameIsRejectedAfterTrimmingAndCaseFolding() = runBlocking {
        saveCompany("City Taxi", tariff("1", "2", "3"))

        assertEquals(
            CompanySaveResult.DuplicateName,
            repository.createCompany("  CITY taxi ", tariff("9", "9", "9")),
        )
        assertEquals(1, repository.observeCompanies().first().size)
    }

    @Test
    fun deletingTheSelectedCompanyClearsTheSelectionAndBlocksStart() = runBlocking {
        val city = saveCompany("City Taxi", tariff("1", "2", "3"))
        saveCompany("Night Cabs", tariff("5", "2", "0.5"))

        assertEquals(CompanyChangeResult.Done, repository.deleteCompany(city))

        assertNull(repository.selectedCompany())
        assertEquals(1, repository.observeCompanies().first().size)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.startRide("no-selection", 1_000) }
        }
        assertNull(repository.currentActiveRide())
    }

    @Test
    fun editingOrDeletingACompanyLeavesActiveAndHistoricSnapshotsUnchanged() = runBlocking {
        val lockedTariff = tariff("1.25", "2.50", "0.75")
        val companyId = saveCompany("City Taxi", lockedTariff)
        val finished = repository.startRide("saved-ride", 1_000)
        repository.finishCompleted(RideEngine.finish(finished, 2_000), endedAtUtcMillis = 10_000)
        val active = repository.startRide("active-ride", 3_000)

        // Both writes are refused while a ride is active, so the lock holds first.
        assertEquals(
            CompanySaveResult.RideActive,
            repository.updateCompany(companyId, "Renamed", tariff("9", "9", "9")),
        )
        assertEquals(CompanyChangeResult.RideActive, repository.deleteCompany(companyId))
        assertEquals("City Taxi", repository.currentActiveRide()?.companyName)

        repository.finishCompleted(RideEngine.finish(active, 4_000), endedAtUtcMillis = 20_000)
        assertEquals(
            CompanySaveResult.Saved,
            repository.updateCompany(companyId, "Renamed", tariff("9", "9", "9")),
        )
        assertEquals(CompanyChangeResult.Done, repository.deleteCompany(companyId))

        // Every saved ride still reproduces the name and the exact rates it recorded.
        val history = repository.observeHistory().first()
        assertEquals(2, history.size)
        history.forEach { saved ->
            assertEquals("City Taxi", saved.summary.companyName)
            assertEquals(lockedTariff, saved.summary.tariff)
        }
    }

    /** Returns the new company's id; the first one saved selects itself. */
    private suspend fun saveCompany(name: String, tariff: Tariff): String {
        assertEquals(CompanySaveResult.Saved, repository.createCompany(name, tariff))
        return repository.observeCompanies().first().single { it.name == name }.id
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(
            context,
            TaxiInspectorDatabase::class.java,
            TEST_DATABASE,
        ).build()
        repository = RoomRideRepository(database.rideDao())
    }

    private fun tariff(initial: String, distance: String, waiting: String): Tariff = Tariff(
        initialTax = amount(initial),
        perKmRate = amount(distance),
        perMinuteStillRate = amount(waiting),
    )

    private fun amount(value: String): DecimalAmount = DecimalAmount.of(BigDecimal(value))

    private companion object {
        const val TEST_DATABASE = "room-ride-repository-test.db"
    }
}
