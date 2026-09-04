package com.taxiinspector.data.rides

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    /** Establishes the exported-v1-schema fixture used by each future forward-migration test. */
    @Test
    fun exportedVersionOneSchemaOpensWithTheCurrentDatabase() {
        migrationHelper.createDatabase(TEST_DATABASE, 1).apply {
            execSQL(
                "INSERT INTO app_settings " +
                    "(id, initialTax, perKmRate, perMinuteStillRate) " +
                    "VALUES (1, '1.25', '2.5', '0.75')",
            )
            close()
        }

        val database = Room.databaseBuilder(
            context,
            TaxiInspectorDatabase::class.java,
            TEST_DATABASE,
        ).build()
        try {
            runBlocking {
                val settings = database.rideDao().settings()
                assertEquals("1.25", settings?.initialTax)
                assertEquals("2.5", settings?.perKmRate)
                assertEquals("0.75", settings?.perMinuteStillRate)
                assertNull(database.rideDao().activeRide())
            }
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TEST_DATABASE = "taxi-inspector-migration-test.db"
    }
}
