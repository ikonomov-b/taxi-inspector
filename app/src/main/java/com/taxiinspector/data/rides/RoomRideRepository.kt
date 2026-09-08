package com.taxiinspector.data.rides

import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.SavedRideSummary
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** The only storage API used by UI and tracking code. */
class RoomRideRepository(private val dao: RideDao) {
    fun observeCompanies(): Flow<List<TaxiCompany>> = dao.observeCompanies().map { rows ->
        rows.map(TaxiCompanyEntity::toDomain)
    }

    /**
     * Composed from two single-table flows so Room invalidates it on a change to either the
     * selection or the company itself, and so a stale selection reads as no selection.
     */
    fun observeSelectedCompany(): Flow<TaxiCompany?> =
        combine(dao.observeSettings(), dao.observeCompanies()) { settings, companies ->
            companies.firstOrNull { it.id == settings?.selectedCompanyId }?.toDomain()
        }

    suspend fun selectedCompany(): TaxiCompany? = dao.selectedCompany()?.toDomain()

    suspend fun companyCount(): Int = dao.companyCount()

    /**
     * Creates a company under a generated stable id. Names arrive as the user typed them and
     * are stored trimmed; [TaxiCompany] then enforces the nonblank and length invariants.
     */
    suspend fun createCompany(name: String, tariff: Tariff): CompanySaveResult =
        dao.createCompany(TaxiCompany(UUID.randomUUID().toString(), name.trim(), tariff).toEntity())

    suspend fun updateCompany(id: String, name: String, tariff: Tariff): CompanySaveResult =
        dao.updateCompanyDetails(TaxiCompany(id, name.trim(), tariff).toEntity())

    suspend fun selectCompany(id: String): CompanyChangeResult = dao.selectCompany(id)

    /** Confirmed by the UI before it is called; an active or saved ride keeps its snapshot. */
    suspend fun deleteCompany(id: String): CompanyChangeResult = dao.deleteCompany(id)

    /** Interim: Phase 7A.4 replaces these two with the company selector and editor. */
    fun observeTariff(): Flow<Tariff?> = observeSelectedCompany().map { it?.tariff }

    fun observeActiveRide(): Flow<ActiveRide?> = dao.observeActiveRide().map { it?.toDomain() }

    fun observeHistory(): Flow<List<SavedRideSummary>> = dao.observeHistory().map { rows ->
        rows.map(RideSummaryEntity::toDomain)
    }

    fun observeSummary(id: String): Flow<SavedRideSummary?> =
        dao.observeSummary(id).map { it?.toDomain() }

    suspend fun currentTariff(): Tariff? = selectedCompany()?.tariff

    suspend fun currentActiveRide(): ActiveRide? = dao.activeRide()?.toDomain()

    suspend fun saveTariff(tariff: Tariff) {
        check(dao.activeRide() == null) { "Tariffs cannot change during a ride." }
        dao.saveSelectedCompanyTariff(tariff)
    }

    /** Atomically locks the selected company's name and exact tariff into a new active session. */
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
