package com.taxiinspector.data.rides

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ride_summary")
data class RideSummaryEntity(
    @PrimaryKey val id: String,
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
    val total: String,
    val distanceMeters: String,
    val idleMillis: Long,
    val elapsedMillis: Long,
    val endedElapsedMillis: Long,
    val endedAtUtcMillis: Long,
    val status: String,
)
