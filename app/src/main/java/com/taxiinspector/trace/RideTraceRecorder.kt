package com.taxiinspector.trace

import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.RideDecision
import com.taxiinspector.ride.RideInput

/** The build and device a trace came from; recorded so a captured ride is attributable. */
data class TraceEnvironment(
    val appVersionName: String,
    val deviceModel: String,
    val androidRelease: String,
)

/**
 * Records what the engine saw and what it decided, for one ride at a time.
 *
 * Implementations must not throw: a trace is a diagnostic, and losing it must never disturb a
 * ride. Calls arrive from the tracking controller's single event loop, in order.
 */
interface RideTraceRecorder {
    /**
     * Begins a trace. [continuing] is true when a paused or interrupted ride is picked up again,
     * where the earlier part of the same trip must be kept and appended to rather than replaced.
     */
    fun open(ride: ActiveRide, startedUtcMillis: Long, continuing: Boolean = false)

    fun record(input: RideInput, before: ActiveRide, after: ActiveRide, decision: RideDecision?)

    fun command(label: String, ride: ActiveRide?)

    /** Flushes and terminates the files. A trace left unterminated is closed when it is read. */
    fun close(ride: ActiveRide)

    /** Removes a discarded ride's trace: a ride the user threw away leaves no route behind. */
    fun delete(rideId: String)
}

/** What a release build gets: the trace facility is compiled out, so nothing is written. */
object NoOpRideTraceRecorder : RideTraceRecorder {
    override fun open(ride: ActiveRide, startedUtcMillis: Long, continuing: Boolean) = Unit

    override fun record(
        input: RideInput,
        before: ActiveRide,
        after: ActiveRide,
        decision: RideDecision?,
    ) = Unit

    override fun command(label: String, ride: ActiveRide?) = Unit

    override fun close(ride: ActiveRide) = Unit

    override fun delete(rideId: String) = Unit
}
