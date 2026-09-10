package com.taxiinspector.trace

import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.MotionState
import com.taxiinspector.ride.RideDecision
import com.taxiinspector.ride.TrackingStatus
import java.math.BigDecimal

/**
 * One recorded moment of a ride: the input, what the engine decided about it, and the running
 * totals immediately afterwards. Pure data, and the only shape the trace writers consume.
 *
 * [sample] carries coordinates. That is the whole point of a trace and the reason it exists only
 * in debug builds: a release build stores no route at all.
 */
data class TraceRow(
    val sequence: Long,
    val type: Type,
    val distanceMeters: BigDecimal,
    val billedTimeMillis: Long,
    val trackingStatus: TrackingStatus,
    val motionState: MotionState,
    val total: String,
    val sample: LocationSample? = null,
    val decision: RideDecision? = null,
    val commandLabel: String? = null,
) {
    enum class Type { Fix, Tick, Command }
}
