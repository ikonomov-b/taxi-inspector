package com.taxiinspector.data.rides

import android.content.Context
import androidx.room.Room
import com.taxiinspector.core.time.AndroidClock
import com.taxiinspector.core.time.Clock
import com.taxiinspector.data.location.AndroidGpsLocationClient
import com.taxiinspector.data.location.LocationClient

/** Explicit composition root; this small app does not need a dependency-injection framework. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        TaxiInspectorDatabase::class.java,
        "taxi-inspector.db",
    ).build()

    val rideRepository: RoomRideRepository = RoomRideRepository(database.rideDao())
    val locationClient: LocationClient = AndroidGpsLocationClient(context.applicationContext)
    val clock: Clock = AndroidClock
}
