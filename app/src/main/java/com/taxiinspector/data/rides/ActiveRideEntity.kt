package com.taxiinspector.data.rides

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.taxiinspector.ride.Tariff

/** A compact active snapshot; point columns represent one temporary baseline, not a route. */
@Entity(tableName = "active_ride")
data class ActiveRideEntity(
    @PrimaryKey val id: String,
    val companyName: String?,
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
    @ColumnInfo(defaultValue = "'${Tariff.DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR}'")
    val waitingCrossoverKilometersPerHour: String,
    val phase: String,
    val trackingStatus: String,
    val distanceMeters: String,
    @ColumnInfo(defaultValue = "'0'")
    val travelledDistanceMeters: String,
    val idleMillis: Long,
    val motionState: String,
    val startedElapsedMillis: Long,
    val lastTickElapsedMillis: Long,
    val lastAcceptedFixElapsedMillis: Long?,
    val lastFreshBillableReceivedElapsedMillis: Long?,
    val pointLatitude: Double?,
    val pointLongitude: Double?,
    val pointAccuracyMeters: Double?,
    val pointProvider: String?,
    val pointSpeedMetersPerSecond: Double?,
    val pointFixElapsedMillis: Long?,
    val pointReceivedElapsedMillis: Long?,
    val lastSpeedMetersPerSecond: Double?,
    val lastSpeedReceivedElapsedMillis: Long?,
    val lowSpeedCandidateMillis: Long,
    val highSpeedCandidateMillis: Long,
)
