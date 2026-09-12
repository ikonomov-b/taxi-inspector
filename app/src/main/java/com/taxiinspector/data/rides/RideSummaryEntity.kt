package com.taxiinspector.data.rides

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.taxiinspector.ride.Tariff

@Entity(tableName = "ride_summary")
data class RideSummaryEntity(
    @PrimaryKey val id: String,
    val companyName: String?,
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
    @ColumnInfo(defaultValue = "'${Tariff.DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR}'")
    val waitingCrossoverKilometersPerHour: String,
    val total: String,
    val distanceMeters: String,
    /** Null means "not recorded": a summary saved before this column existed. */
    val travelledDistanceMeters: String?,
    val idleMillis: Long,
    val elapsedMillis: Long,
    val endedElapsedMillis: Long,
    val endedAtUtcMillis: Long,
    val status: String,
)
