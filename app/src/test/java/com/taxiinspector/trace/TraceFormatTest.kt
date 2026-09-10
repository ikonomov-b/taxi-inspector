package com.taxiinspector.trace

import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.MotionState
import com.taxiinspector.ride.RideDecision
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TrackingStatus
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceFormatTest {
    private val tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
    )

    private val meta = TraceMeta(
        rideId = "ride-1",
        companyName = "City \"Taxi\" & Co",
        tariff = tariff,
        engineConstants = mapOf("gpsLossMillis" to "15000", "fareModel" to "modeS"),
        appVersionName = "1.0",
        deviceModel = "Google Pixel 8 Pro",
        androidRelease = "17",
        startedUtcMillis = 1_789_043_696_789,
    )

    private val fix = LocationSample(
        latitude = 42.6977123,
        longitude = 23.3219456,
        accuracyMeters = 4.5,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = 8.25,
        fixElapsedMillis = 12_000,
        receivedElapsedMillis = 12_050,
        band = LocationSample.Band.Dual,
        speedAccuracyMetersPerSecond = 0.5,
        utcMillis = 1_789_043_708_789,
        bearingDegrees = 271.5,
        altitudeMeters = 550.25,
        satellitesUsedInFix = 14,
        l5SignalCount = 6,
    )

    private fun row(
        type: TraceRow.Type = TraceRow.Type.Fix,
        sample: LocationSample? = fix,
        decision: RideDecision? = RideDecision(
            reason = RideDecision.Reason.ClosedDistance,
            chordMeters = 24.125,
            significantMeters = 9.0,
            baselineAgeMillis = 3_000,
            excessSpeedMetersPerSecond = 0.0,
            billedAs = RideDecision.BilledAs.Distance,
        ),
        commandLabel: String? = null,
    ) = TraceRow(
        sequence = 7,
        type = type,
        distanceMeters = BigDecimal("124.125"),
        billedTimeMillis = 4_000,
        trackingStatus = TrackingStatus.Good,
        motionState = MotionState.Moving,
        total = "2.57",
        sample = sample,
        decision = decision,
        commandLabel = commandLabel,
    )

    @Test
    fun `every csv row has one field per header column`() {
        val columns = TraceCsv.HEADER.split(",").size
        val rows = listOf(
            row(),
            row(type = TraceRow.Type.Tick, sample = null, decision = null),
            row(type = TraceRow.Type.Command, sample = null, decision = null, commandLabel = "Start"),
            row(sample = fix.copy(speedMetersPerSecond = null, utcMillis = null, altitudeMeters = null)),
        )

        rows.forEach { assertEquals(columns, TraceCsv.row(it).split(",").size) }
    }

    @Test
    fun `a csv row keeps the coordinates and the verdict together`() {
        val fields = TraceCsv.row(row()).split(",")
        val header = TraceCsv.HEADER.split(",")

        assertEquals("42.6977123", fields[header.indexOf("lat")])
        assertEquals("23.3219456", fields[header.indexOf("lon")])
        assertEquals("1789043708789", fields[header.indexOf("utcMillis")])
        assertEquals("4.500", fields[header.indexOf("accuracyM")])
        assertEquals("Dual", fields[header.indexOf("band")])
        assertEquals("6", fields[header.indexOf("l5")])
        assertEquals("ClosedDistance", fields[header.indexOf("reason")])
        assertEquals("Distance", fields[header.indexOf("billedAs")])
        assertEquals("24.125", fields[header.indexOf("chordM")])
        assertEquals("124.125", fields[header.indexOf("distanceM")])
        assertEquals("2.57", fields[header.indexOf("total")])
    }

    @Test
    fun `the gpx is well formed xml that a strict parser accepts`() {
        val document = TraceGpx.header(meta) + TraceGpx.trackPoint(row()) +
            TraceGpx.trackPoint(row(sample = fix.copy(latitude = 42.70, utcMillis = null))) +
            TraceGpx.footer()

        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val parsed = factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(document.toByteArray()))

        assertEquals("gpx", parsed.documentElement.tagName)
        assertEquals("1.1", parsed.documentElement.getAttribute("version"))
        // The name a company gave itself must not be able to break the file.
        assertTrue(document.contains("City &quot;Taxi&quot; &amp; Co"))
        val points = parsed.getElementsByTagName("trkpt")
        assertEquals(2, points.length)
        assertEquals(
            "42.6977123",
            points.item(0).attributes.getNamedItem("lat").nodeValue,
        )
        // Extensions carry this app's own namespace, so Locus ignores what it does not know
        // instead of refusing the file.
        assertEquals(
            2,
            parsed.getElementsByTagNameNS("urn:taxi-inspector:gpx:1", "reason").length,
        )
        // A fix whose wall clock was unusable is still a place, just one Locus cannot align
        // by time.
        assertEquals(1, (points.item(0) as Element).getElementsByTagName("time").length)
        assertEquals(0, (points.item(1) as Element).getElementsByTagName("time").length)
    }

    @Test
    fun `a tick or a command is not a place`() {
        assertNull(TraceGpx.trackPoint(row(type = TraceRow.Type.Tick, sample = null)))
        assertNotNull(TraceGpx.trackPoint(row()))
    }

    @Test
    fun `the metadata records the tariff and the constants that produced the trace`() {
        val json = meta.toJson()

        assertTrue(json.contains(""""rideId": "ride-1""""))
        assertTrue(json.contains(""""perKmRate": "1.20""""))
        assertTrue(json.contains(""""crossoverSpeedMetersPerSecond": 4.861111"""))
        assertTrue(json.contains(""""startedUtc": "2026-09-10T12:34:56.789Z""""))
        assertTrue(json.contains(""""gpsLossMillis": "15000""""))
        // A name with a quote in it must not produce invalid JSON.
        assertTrue(json.contains("""City \"Taxi\" & Co"""))
    }
}
