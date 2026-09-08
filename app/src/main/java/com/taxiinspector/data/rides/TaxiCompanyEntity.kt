package com.taxiinspector.data.rides

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One saved company label and its exact tariff, stored as canonical decimal strings.
 *
 * [nameKey] is the trimmed, case-folded name. It is stored rather than derived in SQL because a
 * unique index on it rejects duplicates for non-ASCII names too, which SQLite's ASCII-only
 * `NOCASE` collation would silently admit.
 */
@Entity(
    tableName = "taxi_company",
    indices = [Index(value = ["nameKey"], unique = true)],
)
data class TaxiCompanyEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
) {
    companion object {
        /**
         * The company the version-1 singleton tariff migrates into. It is a placeholder label the
         * user is expected to rename, not an invented business identity.
         */
        const val MIGRATED_TARIFF_ID = "migrated-v1-tariff"
        const val MIGRATED_TARIFF_NAME = "Unnamed company"
    }
}
