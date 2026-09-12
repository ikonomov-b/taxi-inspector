package com.taxiinspector.data.rides

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.MotionState
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import com.taxiinspector.ride.TrackingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the version-1 to version-2 forward migration is non-destructive: the singleton tariff
 * becomes one selected placeholder company, and existing rides keep their own locked tariff with
 * no invented company identity.
 */
@RunWith(AndroidJUnit4::class)
class TaxiInspectorMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TaxiInspectorDatabase::class.java,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun versionOneTariffBecomesTheOneSelectedPlaceholderCompanyWithItsExactValues() = runBlocking {
        seedVersionOne { insertSettingsTariff(initialTax = "1.25", perKm = "2.5", perMinute = "0.75") }

        val database = migrateToVersionTwo()
        try {
            val companies = database.rideDao().observeCompanies().first()
            val company = companies.single()
            assertEquals(TaxiCompanyEntity.MIGRATED_TARIFF_ID, company.id)
            assertEquals(TaxiCompanyEntity.MIGRATED_TARIFF_NAME, company.name)
            assertEquals(TaxiCompany.nameKey(TaxiCompanyEntity.MIGRATED_TARIFF_NAME), company.nameKey)
            assertEquals("1.25", company.initialTax)
            assertEquals("2.5", company.perKmRate)
            assertEquals("0.75", company.perMinuteStillRate)

            val repository = RoomRideRepository(database.rideDao())
            assertEquals(company.toDomain(), repository.selectedCompany())
            assertEquals(company.toDomain(), repository.observeSelectedCompany().first())
        } finally {
            database.close()
        }
    }

    @Test
    fun theMigratedSelectionCanStartARideThatLocksItsNameAndTariff() = runBlocking {
        seedVersionOne { insertSettingsTariff(initialTax = "1.25", perKm = "2.5", perMinute = "0.75") }

        val database = migrateToVersionTwo()
        try {
            val started = RoomRideRepository(database.rideDao()).startRide("ride-after-migration", 1_000)
            assertEquals(TaxiCompanyEntity.MIGRATED_TARIFF_NAME, started.companyName)
            assertEquals("1.25", started.tariff.initialTax.value.toPlainString())
            assertEquals("2.5", started.tariff.perKmRate.value.toPlainString())
            assertEquals("0.75", started.tariff.perMinuteStillRate.value.toPlainString())
        } finally {
            database.close()
        }
    }

    @Test
    fun theMigratedCompanyNameKeyRejectsACaseInsensitiveDuplicate() = runBlocking {
        seedVersionOne { insertSettingsTariff(initialTax = "1.25", perKm = "2.5", perMinute = "0.75") }

        val database = migrateToVersionTwo()
        try {
            val duplicate = TaxiCompany(
                id = "other-company",
                name = TaxiCompanyEntity.MIGRATED_TARIFF_NAME.uppercase(),
                tariff = tariffOf("3", "4", "5"),
            ).toEntity()

            assertThrows(SQLiteConstraintException::class.java) {
                runBlocking { database.rideDao().insertCompany(duplicate) }
            }
            assertEquals(1, database.rideDao().observeCompanies().first().size)
        } finally {
            database.close()
        }
    }

    @Test
    fun aVersionOneActiveRideKeepsItsOwnLockedTariffAndCarriesNoCompanyLabel() = runBlocking {
        seedVersionOne {
            insertSettingsTariff(initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
            insertActiveRide(initialTax = "9.99", perKm = "8.5", perMinute = "0.4")
        }

        val database = migrateToVersionTwo()
        try {
            val active = requireNotNull(RoomRideRepository(database.rideDao()).currentActiveRide())
            assertNull(active.companyName)
            assertEquals("legacy-ride", active.id)
            assertEquals(RidePhase.Running, active.phase)
            assertEquals(TrackingStatus.Good, active.trackingStatus)
            assertEquals(MotionState.Idle, active.motionState)
            assertEquals("9.99", active.tariff.initialTax.value.toPlainString())
            assertEquals("8.5", active.tariff.perKmRate.value.toPlainString())
            assertEquals("0.4", active.tariff.perMinuteStillRate.value.toPlainString())
            assertEquals("1234.5", active.distanceMeters.toPlainString())
            assertEquals(7_000L, active.timeTariffMillis)
            val point = requireNotNull(active.lastBillablePoint)
            assertEquals(42.6977, point.latitude, 0.0)
            assertEquals(4.5, point.accuracyMeters, 0.0)
        } finally {
            database.close()
        }
    }

    @Test
    fun versionOneSummariesSurviveInOrderWithLegacyCompanyLabels() = runBlocking {
        seedVersionOne {
            insertSettingsTariff(initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
            insertSummary(id = "oldest", total = "6.05", endedAtUtcMillis = 1_000)
            insertSummary(id = "newest", total = "12.5", endedAtUtcMillis = 3_000)
            insertSummary(id = "middle", total = "9.125", endedAtUtcMillis = 2_000)
        }

        val database = migrateToVersionTwo()
        try {
            val history = RoomRideRepository(database.rideDao()).observeHistory().first()
            assertEquals(listOf("newest", "middle", "oldest"), history.map { it.summary.id })
            assertEquals(
                listOf("12.5", "9.125", "6.05"),
                history.map { it.summary.total.value.toPlainString() },
            )
            assertTrue(history.all { it.summary.companyName == null })
            assertTrue(history.all { it.summary.status == RideSummary.Status.Completed })
            assertEquals("1.25", history.first().summary.tariff.initialTax.value.toPlainString())
        } finally {
            database.close()
        }
    }

    @Test
    fun aVersionOneDatabaseWithoutATariffMigratesToNoCompanyAndBlocksStart() = runBlocking {
        seedVersionOne { }

        val database = migrateToVersionTwo()
        try {
            val repository = RoomRideRepository(database.rideDao())
            assertTrue(database.rideDao().observeCompanies().first().isEmpty())
            assertNull(repository.selectedCompany())
            assertNull(repository.observeSelectedCompany().first())

            assertThrows(IllegalStateException::class.java) {
                runBlocking { repository.startRide("ride-without-company", 1_000) }
            }
            assertNull(repository.currentActiveRide())
        } finally {
            database.close()
        }
    }

    private fun seedVersionOne(seed: SupportSQLiteDatabase.() -> Unit) {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            seed()
            close()
        }
    }

    /**
     * Validates the migrated schema against the exported version-2 schema, then opens the
     * database at the current version. The builder must carry `MIGRATION_2_3` as well: the file
     * on disk stops at version 2 here, while `@Database` has since moved on, so opening it with
     * only the 1-to-2 migration registered fails with "a migration from 2 to 3 was required but
     * not found". Nothing this test asserts is changed by the later migration, which only adds
     * columns.
     */
    private fun migrateToVersionTwo(): TaxiInspectorDatabase {
        migrationHelper.runMigrationsAndValidate(TEST_DATABASE, 2, true, MIGRATION_1_2).close()
        return Room.databaseBuilder(context, TaxiInspectorDatabase::class.java, TEST_DATABASE)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    private fun SupportSQLiteDatabase.insertSettingsTariff(
        initialTax: String,
        perKm: String,
        perMinute: String,
    ) = execSQL(
        "INSERT INTO app_settings (id, initialTax, perKmRate, perMinuteStillRate) " +
            "VALUES (1, '$initialTax', '$perKm', '$perMinute')",
    )

    private fun SupportSQLiteDatabase.insertActiveRide(
        initialTax: String,
        perKm: String,
        perMinute: String,
    ) = execSQL(
        "INSERT INTO active_ride (" +
            "id, initialTax, perKmRate, perMinuteStillRate, phase, trackingStatus, " +
            "distanceMeters, idleMillis, motionState, startedElapsedMillis, lastTickElapsedMillis, " +
            "lastAcceptedFixElapsedMillis, lastFreshBillableReceivedElapsedMillis, " +
            "pointLatitude, pointLongitude, pointAccuracyMeters, pointProvider, " +
            "pointSpeedMetersPerSecond, pointFixElapsedMillis, pointReceivedElapsedMillis, " +
            "lastSpeedMetersPerSecond, lastSpeedReceivedElapsedMillis, " +
            "lowSpeedCandidateMillis, highSpeedCandidateMillis) VALUES (" +
            "'legacy-ride', '$initialTax', '$perKm', '$perMinute', 'Running', 'Good', " +
            "'1234.5', 7000, 'Idle', 1000, 60000, " +
            "59000, 59000, " +
            "42.6977, 23.3219, 4.5, 'Gps', " +
            "0.2, 59000, 59000, " +
            "0.2, 59000, " +
            "5000, 0)",
    )

    private fun SupportSQLiteDatabase.insertSummary(
        id: String,
        total: String,
        endedAtUtcMillis: Long,
    ) = execSQL(
        "INSERT INTO ride_summary (" +
            "id, initialTax, perKmRate, perMinuteStillRate, total, distanceMeters, idleMillis, " +
            "elapsedMillis, endedElapsedMillis, endedAtUtcMillis, status) VALUES (" +
            "'$id', '1.25', '2.5', '0.75', '$total', '2500.75', 30000, " +
            "600000, 601000, $endedAtUtcMillis, 'Completed')",
    )

    private fun tariffOf(initialTax: String, perKm: String, perMinute: String) = Tariff(
        initialTax = DecimalAmount.parse(initialTax)!!,
        perKmRate = DecimalAmount.parse(perKm)!!,
        perMinuteStillRate = DecimalAmount.parse(perMinute)!!,
        waitingCrossoverKilometersPerHour = DecimalAmount.parse("8")!!,
    )

    private companion object {
        const val TEST_DATABASE = "taxi-inspector-migration-test.db"
    }
}
