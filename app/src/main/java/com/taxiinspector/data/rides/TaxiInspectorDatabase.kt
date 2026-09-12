package com.taxiinspector.data.rides

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaxiCompanyEntity::class,
        AppSettingsEntity::class,
        ActiveRideEntity::class,
        RideSummaryEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class TaxiInspectorDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
}
