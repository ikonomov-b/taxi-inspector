package com.taxiinspector.data.rides

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AppSettingsEntity::class, ActiveRideEntity::class, RideSummaryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TaxiInspectorDatabase : RoomDatabase() {
    abstract fun rideDao(): RideDao
}
