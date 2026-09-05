package com.taxiinspector.data.rides

import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.SavedRideSummary
import com.taxiinspector.ride.Tariff
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The only storage API used by UI and tracking code. */
class RoomRideRepository(private val dao: RideDao) {
    fun observeTariff(): Flow<Tariff?> = dao.observeSettings().map { it?.toDomainTariff() }

    fun observeActiveRide(): Flow<ActiveRide?> = dao.observeActiveRide().map { it?.toDomain() }

    fun observeHistory(): Flow<List<SavedRideSummary>> = dao.observeHistory().map { rows ->
        rows.map(RideSummaryEntity::toDomain)
    }

    fun observeSummary(id: String): Flow<SavedRideSummary?> =
        dao.observeSummary(id).map { it?.toDomain() }

    suspend fun currentTariff(): Tariff? = dao.settings()?.toDomainTariff()

    suspend fun currentActiveRide(): ActiveRide? = dao.activeRide()?.toDomain()

    suspend fun saveTariff(tariff: Tariff) {
        check(dao.activeRide() == null) { "Tariffs cannot change during a ride." }
        dao.upsertSettings(tariff.toEntity())
    }

    /** Atomically locks the current saved tariff into a new active session. */
    suspend fun startRide(id: String, nowElapsedMillis: Long): ActiveRide =
        dao.startRide(id, nowElapsedMillis).toDomain()

    suspend fun updateActiveRide(ride: ActiveRide) {
        check(dao.activeRide()?.id == ride.id) { "Only the active ride may be updated." }
        dao.upsertActiveRide(ride.toEntity())
    }

    suspend fun finishCompleted(summary: RideSummary, endedAtUtcMillis: Long) {
        dao.finishRide(SavedRideSummary(summary, endedAtUtcMillis).toEntity())
    }

    /** Saving recovery is idempotent; a prior successful save is left unchanged. */
    suspend fun saveInterrupted(summary: RideSummary, endedAtUtcMillis: Long) {
        dao.saveInterruptedRide(
            SavedRideSummary(
                summary.copy(status = RideSummary.Status.Interrupted),
                endedAtUtcMillis,
            ).toEntity(),
        )
    }

    suspend fun deleteSummary(id: String) {
        dao.deleteSummary(id)
    }

    suspend fun discardActiveRide(id: String) {
        dao.deleteActiveRide(id)
    }

    suspend fun markRunningRideInterrupted(id: String): ActiveRide? =
        dao.markRunningRideInterrupted(id)?.toDomain()
}
