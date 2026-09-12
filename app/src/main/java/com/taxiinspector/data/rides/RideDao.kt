package com.taxiinspector.data.rides

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import com.taxiinspector.ride.TrackingStatus
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RideDao {
    @Query("SELECT * FROM taxi_company ORDER BY nameKey ASC")
    abstract fun observeCompanies(): Flow<List<TaxiCompanyEntity>>

    @Query("SELECT * FROM taxi_company WHERE id = :id")
    abstract suspend fun company(id: String): TaxiCompanyEntity?

    @Query("SELECT * FROM taxi_company WHERE nameKey = :nameKey")
    abstract suspend fun companyByNameKey(nameKey: String): TaxiCompanyEntity?

    @Query("SELECT COUNT(*) FROM taxi_company")
    abstract suspend fun companyCount(): Int

    /**
     * One query is safe here because a one-shot read needs no invalidation tracking; the
     * observable form is composed from the two single-table flows instead.
     */
    @Query(
        "SELECT * FROM taxi_company WHERE id = " +
            "(SELECT selectedCompanyId FROM app_settings WHERE id = 1)",
    )
    abstract suspend fun selectedCompany(): TaxiCompanyEntity?

    /**
     * Deliberately aborts rather than replaces: a duplicate `nameKey` belongs to a different
     * company, and REPLACE would silently evict it, which the ten-company rule forbids.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertCompany(company: TaxiCompanyEntity)

    @Update
    abstract suspend fun updateCompany(company: TaxiCompanyEntity)

    @Query("DELETE FROM taxi_company WHERE id = :id")
    abstract suspend fun deleteCompanyRow(id: String)

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

    @Query("SELECT * FROM ride_summary WHERE id = :id")
    abstract fun observeSummary(id: String): Flow<RideSummaryEntity?>

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

    /** Atomically makes interrupted recovery safe to retry or invoke concurrently. */
    @Transaction
    open suspend fun saveInterruptedRide(summary: RideSummaryEntity) {
        if (summary(summary.id) != null) return
        insertSummary(summary)
        deleteActiveRide(summary.id)
        trimHistoryToTen()
    }

    /**
     * Every guard runs inside the transaction so a concurrent add cannot slip past the
     * ten-company limit or the duplicate-name rule between the check and the insert.
     */
    @Transaction
    open suspend fun createCompany(company: TaxiCompanyEntity): CompanySaveResult {
        if (activeRide() != null) return CompanySaveResult.RideActive
        val existingCount = companyCount()
        if (existingCount >= TaxiCompany.MAX_COMPANIES) return CompanySaveResult.LimitReached
        if (companyByNameKey(company.nameKey) != null) return CompanySaveResult.DuplicateName

        insertCompany(company)
        // Only the very first company selects itself. Adding one after deleting the selected
        // company must not silently choose for the user; the product rule makes them pick.
        if (existingCount == 0) {
            upsertSettings(AppSettingsEntity(selectedCompanyId = company.id))
        }
        return CompanySaveResult.Saved
    }

    @Transaction
    open suspend fun updateCompanyDetails(company: TaxiCompanyEntity): CompanySaveResult {
        if (activeRide() != null) return CompanySaveResult.RideActive
        if (company(company.id) == null) return CompanySaveResult.CompanyMissing
        val sameName = companyByNameKey(company.nameKey)
        if (sameName != null && sameName.id != company.id) return CompanySaveResult.DuplicateName

        updateCompany(company)
        return CompanySaveResult.Saved
    }

    @Transaction
    open suspend fun selectCompany(id: String): CompanyChangeResult {
        if (activeRide() != null) return CompanyChangeResult.RideActive
        if (company(id) == null) return CompanyChangeResult.CompanyMissing

        upsertSettings(AppSettingsEntity(selectedCompanyId = id))
        return CompanyChangeResult.Done
    }

    /** Deleting the selected company clears the selection, so Start stays unavailable. */
    @Transaction
    open suspend fun deleteCompany(id: String): CompanyChangeResult {
        if (activeRide() != null) return CompanyChangeResult.RideActive
        if (company(id) == null) return CompanyChangeResult.CompanyMissing

        val wasSelected = settings()?.selectedCompanyId == id
        deleteCompanyRow(id)
        if (wasSelected) {
            upsertSettings(AppSettingsEntity(selectedCompanyId = null))
        }
        return CompanyChangeResult.Done
    }

    /**
     * Interim bridge for the single-tariff editor that Phase 7A.4 replaces with the company
     * editor: it retariffs the selected company, or creates the placeholder company when the
     * database holds no selection yet.
     */
    @Transaction
    open suspend fun saveSelectedCompanyTariff(tariff: Tariff) {
        val existing = selectedCompany() ?: company(TaxiCompanyEntity.MIGRATED_TARIFF_ID)
        val company = if (existing == null) {
            TaxiCompany(
                id = TaxiCompanyEntity.MIGRATED_TARIFF_ID,
                name = TaxiCompanyEntity.MIGRATED_TARIFF_NAME,
                tariff = tariff,
            ).toEntity().also { insertCompany(it) }
        } else {
            existing.copy(
                initialTax = tariff.initialTax.value.toPlainString(),
                perKmRate = tariff.perKmRate.value.toPlainString(),
                perMinuteStillRate = tariff.perMinuteStillRate.value.toPlainString(),
                waitingCrossoverKilometersPerHour =
                    tariff.waitingCrossoverKilometersPerHour.value.toPlainString(),
            ).also { updateCompany(it) }
        }

        upsertSettings(AppSettingsEntity(selectedCompanyId = company.id))
    }

    /** Resolves the selection and locks its name and exact tariff together, or creates nothing. */
    @Transaction
    open suspend fun startRide(id: String, nowElapsedMillis: Long): ActiveRideEntity {
        check(activeRide() == null) { "A ride is already active." }
        val company = checkNotNull(selectedCompany()) {
            "Select a saved taxi company before starting a ride."
        }
        val activeRide = RideEngine.start(id, company.toDomain(), nowElapsedMillis).toEntity()
        upsertActiveRide(activeRide)
        return activeRide
    }

    /** Called only after binding has confirmed that no live service owns this Running ride. */
    @Transaction
    open suspend fun markRunningRideInterrupted(id: String): ActiveRideEntity? {
        val current = activeRide() ?: return null
        if (current.id != id || current.phase != RidePhase.Running.name) return current

        // The engine owns the transition: it commits the hold observed before the process died,
        // which a field-by-field copy here would silently drop.
        val interrupted = RideEngine.interrupt(current.toDomain()).toEntity()
        upsertActiveRide(interrupted)
        return interrupted
    }
}
