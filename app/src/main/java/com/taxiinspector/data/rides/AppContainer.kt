package com.taxiinspector.data.rides

import android.content.Context
import androidx.room.Room
import com.taxiinspector.BuildConfig
import com.taxiinspector.core.time.AndroidClock
import com.taxiinspector.core.time.Clock
import com.taxiinspector.data.location.AndroidGpsLocationClient
import com.taxiinspector.data.location.GpsWarmUp
import com.taxiinspector.data.location.LocationClient
import com.taxiinspector.data.trace.FileRideTraceRecorder
import com.taxiinspector.data.trace.RideTraceStore
import com.taxiinspector.trace.NoOpRideTraceRecorder
import com.taxiinspector.trace.RideTraceRecorder
import com.taxiinspector.trace.TraceEnvironment

/** Explicit composition root; this small app does not need a dependency-injection framework. */
class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context.applicationContext,
        TaxiInspectorDatabase::class.java,
        "taxi-inspector.db",
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()

    val rideRepository: RoomRideRepository = RoomRideRepository(database.rideDao())
    val locationClient: LocationClient = AndroidGpsLocationClient(context.applicationContext)
    val clock: Clock = AndroidClock

    /**
     * Held here rather than by the service, because its whole purpose is to run before a ride
     * exists. It gives the engine nothing; see [GpsWarmUp].
     */
    val gpsWarmUp: GpsWarmUp = GpsWarmUp(locationClient)

    val traceStore: RideTraceStore = RideTraceStore(context.applicationContext)

    /**
     * Debug builds trace every trip, coordinates included, so a ride can be compared with an
     * independent recording of it. Release builds get the no-op: the documented privacy
     * contract is that a released app stores no route at all.
     */
    val traceRecorder: RideTraceRecorder = if (BuildConfig.RIDE_TRACE_ENABLED) {
        FileRideTraceRecorder(
            store = traceStore,
            environment = TraceEnvironment(
                appVersionName = BuildConfig.VERSION_NAME,
                deviceModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                androidRelease = android.os.Build.VERSION.RELEASE,
            ),
        )
    } else {
        NoOpRideTraceRecorder
    }
}
