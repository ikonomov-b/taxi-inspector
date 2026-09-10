package com.taxiinspector.data.trace

import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RideInput
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import com.taxiinspector.trace.TraceCsv
import com.taxiinspector.trace.TraceEnvironment
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRideTraceRecorderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
    )
    private val company = TaxiCompany(id = "company-1", name = "City Taxi", tariff = tariff)

    private lateinit var store: RideTraceStore
    private lateinit var recorder: FileRideTraceRecorder

    private fun newRecorder(): FileRideTraceRecorder {
        store = RideTraceStore(temporaryFolder.root)
        // Unconfined runs each queued write eagerly, in order, on the calling thread, so a test
        // sees exactly the file the device would end up with.
        return FileRideTraceRecorder(
            store = store,
            environment = TraceEnvironment("1.0", "Google Pixel 8 Pro", "17"),
            dispatcher = Dispatchers.Unconfined,
        ).also { recorder = it }
    }

    @Test
    fun `tracing is off until it is switched on`() {
        newRecorder()
        driveOneFix()

        assertFalse(store.isTracingEnabled())
        assertTrue(store.filesFor(RIDE_ID).isEmpty())
        assertFalse(store.hasTrace(RIDE_ID))
    }

    @Test
    fun `the switch survives being read back and can be turned off again`() {
        newRecorder()

        store.setTracingEnabled(true)
        assertTrue(store.isTracingEnabled())
        assertTrue(RideTraceStore(temporaryFolder.root).isTracingEnabled())

        store.setTracingEnabled(false)
        assertFalse(store.isTracingEnabled())
    }

    @Test
    fun `a traced ride records every point with its coordinates and the verdict on it`() {
        newRecorder()
        store.setTracingEnabled(true)
        driveOneFix()

        val track = File(store.directoryFor(RIDE_ID), RideTraceStore.TRACK_FILE).readText()
        val decisions = File(store.directoryFor(RIDE_ID), RideTraceStore.DECISIONS_FILE).readText()
        val meta = File(store.directoryFor(RIDE_ID), RideTraceStore.META_FILE).readText()

        assertTrue(track.contains("""lat="42.6977000" lon="23.3219000""""))
        assertTrue(track.contains("<ti:reason>Seeded</ti:reason>"))
        assertTrue(track.contains("<time>1970-01-01T00:00:12.000Z</time>"))
        assertTrue(track.trimEnd().endsWith("</gpx>"))

        val rows = decisions.trim().lines()
        assertEquals(TraceCsv.HEADER, rows.first())
        // One accepted fix, one refused fix, and the End marker.
        assertEquals(4, rows.size)
        assertTrue(rows[1].contains("Seeded"))
        assertTrue(rows[2].contains("RejectedAccuracy"))
        assertTrue(rows[3].contains("End"))

        assertTrue(meta.contains(""""perKmRate": "1.20""""))
        assertTrue(meta.contains(""""deviceModel": "Google Pixel 8 Pro""""))
        assertTrue(meta.contains(""""fareModel": "modeS-perClosedInterval""""))
    }

    @Test
    fun `a refused fix is recorded but is not a place on the track`() {
        newRecorder()
        store.setTracingEnabled(true)
        driveOneFix()

        val track = File(store.directoryFor(RIDE_ID), RideTraceStore.TRACK_FILE).readText()
        // Both fixes are on the track, so a dropout is visible rather than absent.
        assertEquals(2, Regex("<trkpt ").findAll(track).count())
        assertTrue(track.contains("<ti:reason>RejectedAccuracy</ti:reason>"))
    }

    @Test
    fun `resuming a ride appends to the trace it already has`() {
        newRecorder()
        store.setTracingEnabled(true)
        var ride = RideEngine.start(RIDE_ID, company, 0)
        recorder.open(ride, startedUtcMillis = 12_000)
        ride = record(ride, fix(0))
        recorder.close(ride)

        recorder.open(ride, startedUtcMillis = 30_000, continuing = true)
        ride = record(ride, fix(20_000, north = 40.0))
        recorder.close(ride)

        val track = File(store.directoryFor(RIDE_ID), RideTraceStore.TRACK_FILE).readText()
        val rows = File(store.directoryFor(RIDE_ID), RideTraceStore.DECISIONS_FILE)
            .readText().trim().lines()

        // One header, one closing tag, and a sequence that carried on rather than restarting.
        assertEquals(1, Regex("<gpx ").findAll(track).count())
        assertEquals(1, Regex("</gpx>").findAll(track).count())
        assertEquals(2, Regex("<trkpt ").findAll(track).count())
        assertEquals(listOf("0", "1", "2", "3"), rows.drop(1).map { it.substringBefore(',') })
    }

    @Test
    fun `discarding a ride deletes its route`() {
        newRecorder()
        store.setTracingEnabled(true)
        driveOneFix()
        assertTrue(store.hasTrace(RIDE_ID))

        recorder.delete(RIDE_ID)

        assertFalse(store.hasTrace(RIDE_ID))
        assertFalse(store.directoryFor(RIDE_ID).exists())
    }

    @Test
    fun `an unterminated track is closed before it is read`() {
        newRecorder()
        store.setTracingEnabled(true)
        val ride = RideEngine.start(RIDE_ID, company, 0)
        recorder.open(ride, startedUtcMillis = 12_000)
        record(ride, fix(0))
        // No close: the process died mid-ride, as a service kill would.

        val files = store.filesFor(RIDE_ID)

        assertEquals(3, files.size)
        assertTrue(
            File(store.directoryFor(RIDE_ID), RideTraceStore.TRACK_FILE)
                .readText().trimEnd().endsWith("</gpx>"),
        )
    }

    @Test
    fun `pruning keeps only the newest traces`() {
        newRecorder()
        store.setTracingEnabled(true)
        repeat(5) { index ->
            store.directoryFor("ride-$index").mkdirs()
            File(store.directoryFor("ride-$index"), RideTraceStore.TRACK_FILE)
                .writeText("<gpx></gpx>")
            store.directoryFor("ride-$index").setLastModified(1_000L + index * 1_000L)
        }

        store.prune(keepNewest = 2)

        assertFalse(store.directoryFor("ride-0").exists())
        assertFalse(store.directoryFor("ride-2").exists())
        assertTrue(store.directoryFor("ride-3").exists())
        assertTrue(store.directoryFor("ride-4").exists())
        // The switch is a file, not a trace, so pruning must never take it out.
        assertTrue(store.isTracingEnabled())
    }

    private fun driveOneFix() {
        var ride = RideEngine.start(RIDE_ID, company, 0)
        recorder.open(ride, startedUtcMillis = 12_000)
        ride = record(ride, fix(0))
        ride = record(ride, fix(1_000, accuracy = 40.0))
        recorder.close(ride)
    }

    private fun record(ride: ActiveRide, sample: LocationSample): ActiveRide {
        val input = RideInput.LocationReceived(sample, sample.receivedElapsedMillis)
        val (updated, decision) = RideEngine.step(ride, input)
        recorder.record(input, ride, updated, decision)
        return updated
    }

    private fun fix(
        elapsedMillis: Long,
        north: Double = 0.0,
        accuracy: Double = 5.0,
    ) = LocationSample(
        latitude = 42.6977 + north / 111_086.86,
        longitude = 23.3219,
        accuracyMeters = accuracy,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = 8.0,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
        band = LocationSample.Band.Dual,
        speedAccuracyMetersPerSecond = 0.5,
        utcMillis = 12_000 + elapsedMillis,
        altitudeMeters = 550.0,
        signal = com.taxiinspector.ride.SignalQuality(
            satellitesInView = 20,
            satellitesUsedInFix = 14,
            l5SignalCount = 6,
            medianCn0UsedDbHz = 40.0,
            medianCn0InViewDbHz = 33.0,
        ),
    )

    private companion object {
        const val RIDE_ID = "ride-1"
    }
}
