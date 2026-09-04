package com.taxiinspector.data.rides

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.taxiinspector.ride.RideEngine
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RideDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    abstract fun observeSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    abstract suspend fun settings(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSettings(settings: AppSettingsEntity)

    @Query("SELECT * FROM active_ride LIMIT 1")
    abstract fun observeActiveRide(): Flow<ActiveRideEntity?>

    @Query("SELECT * FROM active_ride LIMIT 1")
    abstract suspend fun activeRide(): ActiveRideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertActiveRide(ride: ActiveRideEntity)

    @Query("DELETE FROM active_ride WHERE id = :id")
    abstract suspend fun deleteActiveRide(id: String)

    @Query("SELECT * FROM ride_summary ORDER BY endedAtUtcMillis DESC, id DESC")
    abstract fun observeHistory(): Flow<List<RideSummaryEntity>>

    @Query("SELECT * FROM ride_summary WHERE id = :id")
    abstract suspend fun summary(id: String): RideSummaryEntity?

    @Query("DELETE FROM ride_summary WHERE id = :id")
    abstract suspend fun deleteSummary(id: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSummary(summary: RideSummaryEntity)

    @Query(
        "DELETE FROM ride_summary WHERE id NOT IN " +
            "(SELECT id FROM ride_summary ORDER BY endedAtUtcMillis DESC, id DESC LIMIT 10)",
    )
    abstract suspend fun trimHistoryToTen()

    @Transaction
    open suspend fun finishRide(summary: RideSummaryEntity) {
        insertSummary(summary)
        deleteActiveRide(summary.id)
        trimHistoryToTen()
    }

    @Transaction
    open suspend fun startRide(id: String, nowElapsedMillis: Long): ActiveRideEntity {
        check(activeRide() == null) { "A ride is already active." }
        val tariff = checkNotNull(settings()) { "Save a tariff before starting a ride." }.toDomainTariff()
        val activeRide = RideEngine.start(id, tariff, nowElapsedMillis).toEntity()
        upsertActiveRide(activeRide)
        return activeRide
    }
}
