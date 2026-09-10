package com.taxiinspector.trace

import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.RideEngine
import com.taxiinspector.ride.RideInput
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Drives a real captured ride back through the engine, on a computer, with no device.
 *
 * A trace pulled off a phone is the only realistic input this engine has. Replaying it is how a
 * proposed rule change is measured against what actually happened on a road, rather than against
 * a synthetic profile that flatters it.
 *
 *     ./gradlew test --tests '*TraceReplayTest' -Dtaxi.trace=$PWD/traces/<rideId>/decisions.csv
 *
 * Skipped, not failed, when the property is absent, so a normal run costs nothing.
 */
class TraceReplayTest {
    @Test
    fun `replays a captured trace and reports what the engine would bill now`() {
        val path = System.getProperty("taxi.trace")
        assumeTrue("Set -Dtaxi.trace=<decisions.csv> to replay a captured ride", path != null)
        val file = File(requireNotNull(path))
        assumeTrue("No trace at $path", file.isFile)

        val rows = file.readLines().filter { it.isNotBlank() }
        val header = rows.first().split(",")
        val fixes = rows.drop(1)
            .map { header.zip(it.split(",")).toMap() }
            .filter { it["type"] == "Fix" }
            .mapNotNull(::sampleOf)

        assumeTrue("No usable fixes in $path", fixes.isNotEmpty())

        val tariff = tariffFrom(file.parentFile)
        var ride = RideEngine.start("replay", TaxiCompany("replay", "Replay", tariff), 0)
        val reasons = sortedMapOf<String, Int>()
        var nextTickMillis = fixes.first().receivedElapsedMillis + TICK_INTERVAL

        for (sample in fixes) {
            // The ticker ran at 1 Hz beside the fixes; ticks never bill, but they are what
            // detects a loss, so a replay without them would bridge gaps the ride did not.
            while (nextTickMillis <= sample.receivedElapsedMillis) {
                ride = RideEngine.reduce(ride, RideInput.Tick(nextTickMillis))
                nextTickMillis += TICK_INTERVAL
            }
            val step = RideEngine.step(
                ride,
                RideInput.LocationReceived(sample, sample.receivedElapsedMillis),
            )
            ride = step.ride
            step.decision?.let { reasons[it.reason.name] = (reasons[it.reason.name] ?: 0) + 1 }
        }

        val summary = RideEngine.finish(ride, fixes.last().receivedElapsedMillis)
        println("replayed ${fixes.size} fixes from ${file.absolutePath}")
        println("  distance   ${summary.distanceMeters.toPlainString()} m")
        println("  tariff time ${summary.timeTariffMillis / 1_000} s")
        println("  total      ${summary.total.value.toPlainString()}")
        println("  recorded total in the trace: ${rows.last().substringAfterLast(',')}")
        reasons.forEach { (reason, count) -> println("  %-20s %d".format(reason, count)) }
    }

    private fun sampleOf(row: Map<String, String>): LocationSample? {
        val latitude = row["lat"]?.toDoubleOrNull() ?: return null
        val longitude = row["lon"]?.toDoubleOrNull() ?: return null
        val accuracy = row["accuracyM"]?.toDoubleOrNull() ?: return null
        val fixElapsed = row["fixElapsedMillis"]?.toLongOrNull() ?: return null
        val received = row["receivedElapsedMillis"]?.toLongOrNull() ?: fixElapsed
        return LocationSample(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
            provider = LocationSample.Provider.Gps,
            speedMetersPerSecond = row["speedMps"]?.toDoubleOrNull(),
            fixElapsedMillis = fixElapsed,
            receivedElapsedMillis = received,
            band = row["band"]?.let { name ->
                LocationSample.Band.entries.firstOrNull { it.name == name }
            } ?: LocationSample.Band.Unknown,
            speedAccuracyMetersPerSecond = row["speedAccMps"]?.toDoubleOrNull(),
            isMock = row["mock"] == "true",
            utcMillis = row["utcMillis"]?.toLongOrNull(),
        )
    }

    /** The trace records the tariff it was billed with; a replay under another one proves nothing. */
    private fun tariffFrom(directory: File?): Tariff {
        val meta = directory?.resolve("meta.json")?.takeIf { it.isFile }?.readText()
        fun read(key: String, fallback: String): String =
            meta?.let { Regex("\"$key\":\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) } ?: fallback
        return Tariff(
            initialTax = DecimalAmount.parse(read("initialTax", "2.40"))!!,
            perKmRate = DecimalAmount.parse(read("perKmRate", "1.20"))!!,
            perMinuteStillRate = DecimalAmount.parse(read("perMinuteStillRate", "0.35"))!!,
        )
    }

    private companion object {
        const val TICK_INTERVAL = 1_000L
    }
}
