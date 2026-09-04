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
    fun startLocksTariffAndFinishAtomicallyMovesRideToHistory() = runBlocking {
        val lockedTariff = tariff("1.25", "2.50", "0.75")
        repository.saveTariff(lockedTariff)

        val active = repository.startRide("ride-1", 1_000)
        assertEquals(lockedTariff, active.tariff)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.saveTariff(tariff("9", "9", "9")) }
        }

        val summary = RideEngine.finish(active, 6_000)
        repository.finishCompleted(summary, endedAtUtcMillis = 100_000)

        assertNull(database.rideDao().activeRide())
        assertEquals(summary, database.rideDao().summary("ride-1")?.toDomain()?.summary)
    }

    @Test
    fun failedFinishRollsBackActiveRideDeletion() = runBlocking {
        repository.saveTariff(tariff("1", "2", "3"))
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
        for (number in 1..11) {
            val rideTariff = tariff(number.toString(), "$number.25", "$number.5")
            repository.saveTariff(rideTariff)
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
        repository.saveTariff(tariff("1", "2", "3"))
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
    fun tariffAndCompleteActiveSnapshotSurviveDatabaseRecreation() = runBlocking {
        val savedTariff = tariff("1.250000", "2.75", "0.500001")
        repository.saveTariff(savedTariff)
        val expected = repository.startRide("recreated", 1_000).copy(
            phase = RidePhase.Paused,
            trackingStatus = TrackingStatus.Weak,
            distanceMeters = BigDecimal("123.450"),
            idleMillis = 6_789,
            motionState = MotionState.Idle,
            lastTickElapsedMillis = 9_000,
            lastAcceptedFixElapsedMillis = 8_500,
            lastFreshBillableReceivedElapsedMillis = 8_750,
            lastBillablePoint = LocationSample(
                latitude = 42.6977,
                longitude = 23.3219,
                accuracyMeters = 7.5,
                provider = LocationSample.Provider.Gps,
                speedMetersPerSecond = 0.25,
                fixElapsedMillis = 8_500,
                receivedElapsedMillis = 8_750,
            ),
            lastSpeedMetersPerSecond = 0.25,
            lastSpeedReceivedElapsedMillis = 8_750,
            lowSpeedCandidateMillis = 4_000,
            highSpeedCandidateMillis = 0,
        )
        repository.updateActiveRide(expected)

        database.close()
        openDatabase()

        assertEquals(savedTariff, repository.observeTariff().first())
        assertEquals(expected, repository.observeActiveRide().first())
    }

    @Test
    fun deletingSummaryLeavesOtherHistoryUntouched() = runBlocking {
        repository.saveTariff(tariff("1", "2", "3"))
        val first = repository.startRide("first", 1_000)
        repository.finishCompleted(RideEngine.finish(first, 2_000), 10_000)
        val second = repository.startRide("second", 3_000)
        repository.finishCompleted(RideEngine.finish(second, 4_000), 20_000)

        repository.deleteSummary("first")

        assertNull(database.rideDao().summary("first"))
        assertNotNull(database.rideDao().summary("second"))
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
