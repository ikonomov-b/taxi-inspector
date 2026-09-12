package com.taxiinspector.data.rides

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.taxiinspector.ride.Tariff
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the version-2 to version-3 forward migration is non-destructive: an existing company or
 * ride gets the product-default waiting crossover, an in-flight active ride's travelled distance
 * is backfilled from its own billed distance, and an existing saved summary's travelled distance
 * is left unrecorded rather than guessed.
 */
@RunWith(AndroidJUnit4::class)
class TaxiInspectorMigration2To3Test {
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
    fun anExistingCompanyGetsExactlyTheProductDefaultCrossoverAndNothingElseChanges() = runBlocking {
        seedVersionTwo {
            insertCompany(id = "city", name = "City Taxi", initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
        }

        val database = migrateToVersionThree()
        try {
            val company = database.rideDao().observeCompanies().first().single()
            assertEquals("City Taxi", company.name)
            assertEquals("1.25", company.initialTax)
            assertEquals("2.5", company.perKmRate)
            assertEquals("0.75", company.perMinuteStillRate)
            assertEquals(
                Tariff.DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR,
                company.waitingCrossoverKilometersPerHour,
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun anEditedCompanyStoresAndLocksInANonDefaultCrossover() = runBlocking {
        seedVersionTwo {
            insertCompany(id = "city", name = "City Taxi", initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
            selectCompany("city")
        }

        val database = migrateToVersionThree()
        try {
            val repository = RoomRideRepository(database.rideDao())
            val edited = repository.selectedCompany()!!.copy(
                tariff = repository.selectedCompany()!!.tariff.copy(
                    waitingCrossoverKilometersPerHour =
                        com.taxiinspector.core.decimal.DecimalAmount.parse("5")!!,
                ),
            )
            repository.updateCompany(edited.id, edited.name, edited.tariff)

            val started = repository.startRide("ride-after-edit", 1_000)
            assertEquals("5", started.tariff.waitingCrossoverKilometersPerHour.value.toPlainString())
        } finally {
            database.close()
        }
    }

    @Test
    fun anExistingActiveRideBackfillsTravelledDistanceFromItsBilledDistance() = runBlocking {
        seedVersionTwo {
            insertCompany(id = "city", name = "City Taxi", initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
            selectCompany("city")
            insertActiveRide(distanceMeters = "1234.5")
        }

        val database = migrateToVersionThree()
        try {
            val active = requireNotNull(RoomRideRepository(database.rideDao()).currentActiveRide())
            assertEquals("1234.5", active.distanceMeters.toPlainString())
            assertEquals(active.distanceMeters, active.travelledDistanceMeters)
        } finally {
            database.close()
        }
    }

    @Test
    fun anExistingSummaryReadsBackWithNoRecordedTravelledDistance() = runBlocking {
        seedVersionTwo {
            insertCompany(id = "city", name = "City Taxi", initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
            selectCompany("city")
            insertSummary(id = "before-upgrade", total = "6.05")
        }

        val database = migrateToVersionThree()
        try {
            val history = RoomRideRepository(database.rideDao()).observeHistory().first()
            val summary = history.single { it.summary.id == "before-upgrade" }
            assertNull(summary.summary.travelledDistanceMeters)
        } finally {
            database.close()
        }
    }

    @Test
    fun aNewRideAfterTheUpgradeRecordsBothDistancesIndependently() = runBlocking {
        seedVersionTwo {
            insertCompany(id = "city", name = "City Taxi", initialTax = "1.25", perKm = "2.5", perMinute = "0.75")
            selectCompany("city")
        }

        val database = migrateToVersionThree()
        try {
            val repository = RoomRideRepository(database.rideDao())
            repository.startRide("ride-new", 1_000)
            val running = requireNotNull(repository.currentActiveRide())
                .copy(distanceMeters = java.math.BigDecimal("500"), travelledDistanceMeters = java.math.BigDecimal("800"))
            repository.updateActiveRide(running)

            repository.finishCompleted(com.taxiinspector.ride.RideEngine.finish(running, 60_000), 60_000)
            val saved = repository.observeHistory().first().single { it.summary.id == "ride-new" }
            assertEquals("500", saved.summary.distanceMeters.toPlainString())
            assertEquals("800", saved.summary.travelledDistanceMeters?.toPlainString())
        } finally {
            database.close()
        }
    }

    @Test
    fun theExportedVersionThreeSchemaValidatesAcrossBothMigrationsFromVersionOne() {
        seedVersionOne()

        migrationHelper.runMigrationsAndValidate(
            TEST_DATABASE,
            3,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
        ).close()
    }

    private fun seedVersionOne() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).close()
    }

    private fun seedVersionTwo(seed: SupportSQLiteDatabase.() -> Unit) {
        migrationHelper.createDatabase(TEST_DATABASE, 2).apply {
            seed()
            close()
        }
    }

    /** Validates the migrated schema against the exported version-3 schema before reading it. */
    private fun migrateToVersionThree(): TaxiInspectorDatabase {
        migrationHelper.runMigrationsAndValidate(TEST_DATABASE, 3, true, MIGRATION_2_3).close()
        return Room.databaseBuilder(context, TaxiInspectorDatabase::class.java, TEST_DATABASE)
            .addMigrations(MIGRATION_2_3)
            .build()
    }

    private fun SupportSQLiteDatabase.insertCompany(
        id: String,
        name: String,
        initialTax: String,
        perKm: String,
        perMinute: String,
    ) = execSQL(
        "INSERT INTO taxi_company (id, name, nameKey, initialTax, perKmRate, perMinuteStillRate) " +
            "VALUES ('$id', '$name', '${name.lowercase()}', '$initialTax', '$perKm', '$perMinute')",
    )

    private fun SupportSQLiteDatabase.selectCompany(id: String) = execSQL(
        "INSERT INTO app_settings (id, selectedCompanyId) VALUES (1, '$id')",
    )

    private fun SupportSQLiteDatabase.insertActiveRide(distanceMeters: String) = execSQL(
        "INSERT INTO active_ride (" +
            "id, companyName, initialTax, perKmRate, perMinuteStillRate, phase, trackingStatus, " +
            "distanceMeters, idleMillis, motionState, startedElapsedMillis, lastTickElapsedMillis, " +
            "lastAcceptedFixElapsedMillis, lastFreshBillableReceivedElapsedMillis, " +
            "pointLatitude, pointLongitude, pointAccuracyMeters, pointProvider, " +
            "pointSpeedMetersPerSecond, pointFixElapsedMillis, pointReceivedElapsedMillis, " +
            "lastSpeedMetersPerSecond, lastSpeedReceivedElapsedMillis, " +
            "lowSpeedCandidateMillis, highSpeedCandidateMillis) VALUES (" +
            "'legacy-active', 'City Taxi', '1.25', '2.5', '0.75', 'Running', 'Good', " +
            "'$distanceMeters', 7000, 'Idle', 1000, 60000, " +
            "59000, 59000, " +
            "42.6977, 23.3219, 4.5, 'Gps', " +
            "0.2, 59000, 59000, " +
            "0.2, 59000, " +
            "5000, 0)",
    )

    private fun SupportSQLiteDatabase.insertSummary(id: String, total: String) = execSQL(
        "INSERT INTO ride_summary (" +
            "id, companyName, initialTax, perKmRate, perMinuteStillRate, total, distanceMeters, " +
            "idleMillis, elapsedMillis, endedElapsedMillis, endedAtUtcMillis, status) VALUES (" +
            "'$id', 'City Taxi', '1.25', '2.5', '0.75', '$total', '2500.75', 30000, " +
            "600000, 601000, 1000, 'Completed')",
    )

    private companion object {
        const val TEST_DATABASE = "taxi-inspector-migration-2-3-test.db"
    }
}
