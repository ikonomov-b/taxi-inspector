package com.taxiinspector.data.rides

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
