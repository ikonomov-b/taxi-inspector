package com.taxiinspector.data.rides

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany

/**
 * Version 1 kept one editable tariff in `app_settings`. Version 2 keeps up to ten named
 * companies, leaves only the selected company id in `app_settings`, and snapshots the locked
 * company label onto active and saved rides.
 *
 * The migration is non-destructive:
 * - the version-1 tariff becomes one selected placeholder company with its exact decimal
 *   strings unchanged, and a version-1 database that never saved a tariff produces no company
 *   and no selection;
 * - the active ride and every summary keep their own locked tariff and gain a null company
 *   name, which display renders as an explicit legacy label rather than an invented identity.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `taxi_company` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
                "`initialTax` TEXT NOT NULL, `perKmRate` TEXT NOT NULL, " +
                "`perMinuteStillRate` TEXT NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_taxi_company_nameKey` " +
                "ON `taxi_company` (`nameKey`)",
        )

        db.execSQL(
            "INSERT INTO `taxi_company` " +
                "(`id`, `name`, `nameKey`, `initialTax`, `perKmRate`, `perMinuteStillRate`) " +
                "SELECT ?, ?, ?, `initialTax`, `perKmRate`, `perMinuteStillRate` " +
                "FROM `app_settings` WHERE `id` = ${AppSettingsEntity.SINGLETON_ID}",
            arrayOf(
                TaxiCompanyEntity.MIGRATED_TARIFF_ID,
                TaxiCompanyEntity.MIGRATED_TARIFF_NAME,
                TaxiCompany.nameKey(TaxiCompanyEntity.MIGRATED_TARIFF_NAME),
            ),
        )

        // The settings row is rebuilt rather than altered because its three tariff columns go away.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `app_settings_v2` (" +
                "`id` INTEGER NOT NULL, `selectedCompanyId` TEXT, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "INSERT INTO `app_settings_v2` (`id`, `selectedCompanyId`) VALUES " +
                "(${AppSettingsEntity.SINGLETON_ID}, " +
                "(SELECT `id` FROM `taxi_company` WHERE `id` = ?))",
            arrayOf(TaxiCompanyEntity.MIGRATED_TARIFF_ID),
        )
        db.execSQL("DROP TABLE `app_settings`")
        db.execSQL("ALTER TABLE `app_settings_v2` RENAME TO `app_settings`")

        db.execSQL("ALTER TABLE `active_ride` ADD COLUMN `companyName` TEXT")
        db.execSQL("ALTER TABLE `ride_summary` ADD COLUMN `companyName` TEXT")
    }
}

/**
 * Version 3 adds two independent, additive components: a per-company waiting-crossover speed
 * (`RideEngine` used to derive this from the two money rates; field evidence showed that formula
 * billed ordinary slow city driving entirely as waiting time, so it became stored, user-edited
 * data instead) and a second distance aggregate that records every closed interval's chord
 * regardless of which tariff won it, so a saved ride shows both what was billed and what was
 * actually driven.
 *
 * Both are non-destructive additive columns on the same three tables in one migration, since
 * shipping two versions this session for two changes landing together would leave a version no
 * build ever released, untested on its own. Existing companies and rides get the product-default
 * crossover; an in-flight active ride's travelled distance is backfilled from its own billed
 * distance, since that is the true lower bound for a ride that has not yet closed an interval on
 * the time tariff since the upgrade. A saved summary's travelled distance is left null: it is the
 * true historic figure that is unknown, not zero, so inventing a number would misrepresent a
 * ride the app never measured this way.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val defaultCrossover = Tariff.DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR

        db.execSQL(
            "ALTER TABLE `taxi_company` ADD COLUMN `waitingCrossoverKilometersPerHour` " +
                "TEXT NOT NULL DEFAULT '$defaultCrossover'",
        )
        db.execSQL(
            "ALTER TABLE `active_ride` ADD COLUMN `waitingCrossoverKilometersPerHour` " +
                "TEXT NOT NULL DEFAULT '$defaultCrossover'",
        )
        db.execSQL(
            "ALTER TABLE `ride_summary` ADD COLUMN `waitingCrossoverKilometersPerHour` " +
                "TEXT NOT NULL DEFAULT '$defaultCrossover'",
        )

        db.execSQL(
            "ALTER TABLE `active_ride` ADD COLUMN `travelledDistanceMeters` TEXT NOT NULL DEFAULT '0'",
        )
        db.execSQL("UPDATE `active_ride` SET `travelledDistanceMeters` = `distanceMeters`")

        // Nullable and defaultless: an existing summary's true travelled distance is unknown,
        // not zero, so it is left unrecorded rather than backfilled with a guess.
        db.execSQL("ALTER TABLE `ride_summary` ADD COLUMN `travelledDistanceMeters` TEXT")
    }
}
