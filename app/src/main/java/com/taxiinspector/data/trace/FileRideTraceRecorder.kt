package com.taxiinspector.data.trace

import android.util.Log
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.FareCalculator
import com.taxiinspector.ride.RideDecision
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RideInput
import com.taxiinspector.trace.RideTraceRecorder
import com.taxiinspector.trace.TraceEnvironment
import com.taxiinspector.trace.TraceCsv
import com.taxiinspector.trace.TraceGpx
import com.taxiinspector.trace.TraceMeta
import com.taxiinspector.trace.TraceRow
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes a ride's trace to disk, off the tracking controller's event loop.
 *
 * Every call is queued on one consumer, so rows land in the order the engine produced them.
 * Nothing here may throw into the ride: a failed write loses a diagnostic, and that must never
 * cost a fare. Writes are flushed every few rows so a service kill loses only the last moment of
 * a trace -- which is itself evidence about the kill.
 */
class FileRideTraceRecorder(
    private val store: RideTraceStore,
    private val environment: TraceEnvironment,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RideTraceRecorder {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val work = Channel<() -> Unit>(Channel.UNLIMITED)

    private var openRideId: String? = null
    private var track: File? = null
    private var decisions: File? = null
    private var sequence = 0L
    private var rowsSinceFlush = 0
    private val trackBuffer = StringBuilder()
    private val decisionBuffer = StringBuilder()

    init {
        scope.launch {
            for (task in work) {
                // A trace must never throw into a ride, but a swallowed failure looks exactly
                // like "no trace appeared", which costs a whole field trip to diagnose. So the
                // ride carries on and the reason is logged. Never a coordinate, only paths.
                runCatching { task() }.onFailure {
                    Log.w(TAG, "ride trace write failed: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        }
    }

    override fun open(ride: ActiveRide, startedUtcMillis: Long, continuing: Boolean) {
        submit {
            // Checked once per ride, so a switch flipped mid-trip cannot leave half a trace.
            // Nothing else in here does anything while no ride is open.
            if (!store.isTracingEnabled()) {
                // Logged so that "why is there no trace" has an answer without reading code.
                Log.i(TAG, "ride tracing is off; raise it with scripts/trace-toggle.sh on (${store.switchPath})")
                openRideId = null
                track = null
                decisions = null
                return@submit
            }
            store.prune()
            val directory = store.directoryFor(ride.id)
            val trackFile = File(directory, RideTraceStore.TRACK_FILE)
            val decisionsFile = File(directory, RideTraceStore.DECISIONS_FILE)
            // A paused ride resumed in a new service session is the same trip, so its earlier
            // half must survive; only a genuinely new ride starts from an empty directory.
            val append = continuing && trackFile.isFile && decisionsFile.isFile

            openRideId = ride.id
            rowsSinceFlush = 0
            trackBuffer.setLength(0)
            decisionBuffer.setLength(0)

            if (append) {
                // A clean close left a footer; appending track points after it would be invalid
                // XML, so it is taken back off and rewritten at the next close.
                val existing = trackFile.readText()
                val footer = TraceGpx.footer()
                if (existing.trimEnd().endsWith("</gpx>")) {
                    trackFile.writeText(existing.substring(0, existing.length - footer.length))
                }
                sequence = decisionsFile.useLines { lines -> lines.count().toLong() - 1 }
                    .coerceAtLeast(0)
            } else {
                directory.deleteRecursively()
                if (!directory.mkdirs() && !directory.isDirectory) {
                    // External storage unmounted, or the directory owned by something this app
                    // cannot write. Silence here would be indistinguishable from tracing being
                    // switched off.
                    Log.w(TAG, "cannot create trace directory $directory; this ride is not traced")
                    openRideId = null
                    track = null
                    decisions = null
                    return@submit
                }
                sequence = 0
                val meta = TraceMeta(
                    rideId = ride.id,
                    companyName = ride.companyName,
                    tariff = ride.tariff,
                    engineConstants = RideEngine.constantsForTrace(),
                    appVersionName = environment.appVersionName,
                    deviceModel = environment.deviceModel,
                    androidRelease = environment.androidRelease,
                    startedUtcMillis = startedUtcMillis,
                )
                File(directory, RideTraceStore.META_FILE).writeText(meta.toJson())
                trackFile.writeText(TraceGpx.header(meta))
                decisionsFile.writeText(TraceCsv.HEADER + "\n")
            }
            track = trackFile
            decisions = decisionsFile
        }
    }

    override fun record(
        input: RideInput,
        before: ActiveRide,
        after: ActiveRide,
        decision: RideDecision?,
    ) {
        val type = when (input) {
            is RideInput.LocationReceived -> TraceRow.Type.Fix
            is RideInput.Tick -> TraceRow.Type.Tick
            else -> TraceRow.Type.Command
        }
        // A tick that changed nothing visible is noise in a ten-minute file; one that moved the
        // status or the label is the only record that it happened at all.
        if (type == TraceRow.Type.Tick &&
            before.trackingStatus == after.trackingStatus &&
            before.motionState == after.motionState
        ) {
            return
        }
        val sample = (input as? RideInput.LocationReceived)?.sample
        val label = if (type == TraceRow.Type.Command) input::class.simpleName else null
        submit { append(after, type, sample, decision, label) }
    }

    override fun command(label: String, ride: ActiveRide?) {
        if (ride == null) return
        submit { append(ride, TraceRow.Type.Command, sample = null, decision = null, label = label) }
    }

    override fun close(ride: ActiveRide) {
        submit {
            if (openRideId != ride.id) return@submit
            append(ride, TraceRow.Type.Command, sample = null, decision = null, label = "End")
            trackBuffer.append(TraceGpx.footer())
            flush()
            openRideId = null
            track = null
            decisions = null
        }
    }

    override fun delete(rideId: String) {
        submit {
            if (openRideId == rideId) {
                openRideId = null
                track = null
                decisions = null
                trackBuffer.setLength(0)
                decisionBuffer.setLength(0)
            }
            store.delete(rideId)
        }
    }

    private fun append(
        ride: ActiveRide,
        type: TraceRow.Type,
        sample: com.taxiinspector.ride.LocationSample?,
        decision: RideDecision?,
        label: String?,
    ) {
        if (openRideId != ride.id) return
        val row = TraceRow(
            sequence = sequence++,
            type = type,
            distanceMeters = ride.distanceMeters,
            billedTimeMillis = ride.billedTimeMillis,
            trackingStatus = ride.trackingStatus,
            motionState = ride.motionState,
            total = FareCalculator
                .total(ride.tariff, ride.distanceMeters, ride.billedTimeMillis)
                .formatTotal(Locale.ROOT),
            sample = sample,
            decision = decision,
            commandLabel = label,
        )
        decisionBuffer.append(TraceCsv.row(row)).append('\n')
        TraceGpx.trackPoint(row)?.let(trackBuffer::append)
        if (++rowsSinceFlush >= FLUSH_EVERY_ROWS) flush()
    }

    private fun flush() {
        rowsSinceFlush = 0
        if (decisionBuffer.isNotEmpty()) {
            decisions?.appendText(decisionBuffer.toString())
            decisionBuffer.setLength(0)
        }
        if (trackBuffer.isNotEmpty()) {
            track?.appendText(trackBuffer.toString())
            trackBuffer.setLength(0)
        }
    }

    private fun submit(task: () -> Unit) {
        work.trySend(task)
    }

    private companion object {
        const val FLUSH_EVERY_ROWS = 10

        /** Field diagnostics for the trace itself; never a coordinate, only paths and reasons. */
        const val TAG = "TaxiTrace"
    }
}
