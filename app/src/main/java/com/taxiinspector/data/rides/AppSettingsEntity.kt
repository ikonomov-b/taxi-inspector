package com.taxiinspector.data.rides

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The single settings row. It holds the durable company selection only; the tariff itself lives
 * in [TaxiCompanyEntity], so a selection can never disagree with the tariff it names.
 *
 * No foreign key is declared. A selection can go stale when its company row is deleted, and the
 * repository must already reject Start on a stale selection with an actionable message, so the
 * deleting transaction clears the selection instead of the database doing it invisibly.
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val selectedCompanyId: String?,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
