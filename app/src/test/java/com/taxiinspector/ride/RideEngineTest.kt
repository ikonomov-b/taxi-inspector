package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideEngineTest {
    private val tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
    )

    @Test
    fun `wait billing starts after five qualifying seconds without back billing`() {
        var ride = RideEngine.start("ride-1", tariff, 0)
        ride = accept(ride, elapsedMillis = 0, speed = 0.8)

        repeat(5) { second ->
            ride = RideEngine.reduce(ride, RideInput.Tick((second + 1) * 1_000L))
        }

        assertEquals(MotionState.Idle, ride.motionState)
        assertEquals(0, ride.idleMillis)

        ride = accept(ride, elapsedMillis = 5_000, speed = 0.8)
        ride = RideEngine.reduce(ride, RideInput.Tick(6_000))

        assertEquals(1_000, ride.idleMillis)
    }

    @Test
    fun `gps timeout freezes fare and removes baseline`() {
        var ride = RideEngine.start("ride-1", tariff, 0)
        ride = accept(ride, elapsedMillis = 0, speed = 0.0)
        ride = RideEngine.reduce(ride, RideInput.GpsTimedOut(15_000))

        assertEquals(TrackingStatus.GpsLost, ride.trackingStatus)
        assertNull(ride.lastBillablePoint)
        assertEquals(0, ride.idleMillis)
    }

    @Test
    fun `weak fixes never become a billable baseline`() {
        var ride = RideEngine.start("ride-1", tariff, 0)
        ride = RideEngine.reduce(
            ride,
            RideInput.LocationReceived(
                sample = sample(elapsedMillis = 0, accuracyMeters = 25.0, speed = 0.0),
                nowElapsedMillis = 0,
            ),
        )

        assertEquals(TrackingStatus.Weak, ride.trackingStatus)
        assertNull(ride.lastBillablePoint)
    }

    @Test
    fun `crawling below the idle threshold bills waiting time and no distance`() {
        var ride = drive(seconds = 60) { 0.7 }

        assertEquals(MotionState.Idle, ride.motionState)
        assertEquals(BigDecimal.ZERO.compareTo(ride.distanceMeters), 0)
        assertEquals(55_000, ride.idleMillis)
    }

    @Test
    fun `speed inside the hysteresis band never bills distance while idle`() {
        // 1.0 m/s sits between the 0.8 entry and 1.3 exit speeds, so Idle is retained.
        val ride = drive(seconds = 60) { if (it < 8) 0.5 else 1.0 }

        assertEquals(MotionState.Idle, ride.motionState)
        assertEquals(BigDecimal.ZERO.compareTo(ride.distanceMeters), 0)
        assertEquals(55_000, ride.idleMillis)
    }

    @Test
    fun `moving bills distance and no waiting time`() {
        val ride = drive(seconds = 20) { 10.0 }

        assertEquals(MotionState.Moving, ride.motionState)
        assertEquals(0, ride.idleMillis)
        assertEquals(190.0, ride.distanceMeters.toDouble(), 1.0)
    }

    @Test
    fun `leaving idle bills the exit interval as waiting and never back bills its distance`() {
        // Stopped for 10 s, then away at 6 m/s: the three-second exit confirmation is
        // billed as waiting, and the distance covered during it is not charged.
        var ride = RideEngine.start("ride-1", tariff, 0)
        var travelled = 0.0
        var distanceAtExit = BigDecimal.ZERO
        for (second in 0 until 30) {
            val now = second * 1_000L
            val speed = if (second < 10) 0.0 else 6.0
            ride = RideEngine.reduce(
                ride,
                RideInput.LocationReceived(movedSample(travelled, now, speed), now),
            )
            val wasIdle = ride.motionState == MotionState.Idle
            ride = RideEngine.reduce(ride, RideInput.Tick(now + 1_000))
            if (wasIdle && ride.motionState == MotionState.Moving) {
                distanceAtExit = ride.distanceMeters
            }
            travelled += speed
        }

        // Five qualifying seconds are not back billed; five stopped seconds plus the
        // three-second exit confirmation are.
        assertEquals(8_000, ride.idleMillis)
        assertEquals(MotionState.Moving, ride.motionState)
        // Nothing was billed while Idle, including the 18 m covered while exiting.
        assertEquals(BigDecimal.ZERO.compareTo(distanceAtExit), 0)
        // Only the seconds after the exit are charged, measured from a recent baseline.
        assertEquals(102.0, ride.distanceMeters.toDouble(), 12.0)
    }

    @Test
    fun `distance covered while crawling is not billed when the ride leaves idle`() {
        // Pulling away at 2 m/s keeps each one-second segment under the five-metre
        // significance threshold, so the baseline only stays current if Idle advances it.
        var ride = RideEngine.start("ride-1", tariff, 0)
        var travelled = 0.0
        var positionAtExit: Double? = null
        var lastFixPosition = 0.0
        for (second in 0 until 60) {
            val now = second * 1_000L
            val speed = if (second < 30) 0.7 else 2.0
            lastFixPosition = travelled
            ride = RideEngine.reduce(
                ride,
                RideInput.LocationReceived(movedSample(travelled, now, speed), now),
            )
            val wasIdle = ride.motionState == MotionState.Idle
            ride = RideEngine.reduce(ride, RideInput.Tick(now + 1_000))
            if (wasIdle && ride.motionState == MotionState.Moving) positionAtExit = travelled
            travelled += speed
        }

        val movingMeters = lastFixPosition - requireNotNull(positionAtExit)
        assertEquals(MotionState.Moving, ride.motionState)
        assertEquals(movingMeters, ride.distanceMeters.toDouble(), 1.0)
    }

    @Test
    fun `a mock fix never becomes a billable baseline`() {
        var ride = RideEngine.start("ride-1", tariff, 0)
        ride = RideEngine.reduce(
            ride,
            RideInput.LocationReceived(
                sample = sample(elapsedMillis = 0, accuracyMeters = 5.0, speed = 0.0)
                    .copy(isMock = true),
                nowElapsedMillis = 0,
            ),
        )

        assertEquals(TrackingStatus.Weak, ride.trackingStatus)
        assertNull(ride.lastBillablePoint)
    }

    @Test
    fun `a dual band segment bills movement a single band segment discards as noise`() {
        fun distanceAfterThreeMetreStep(band: LocationSample.Band): Double {
            var ride = RideEngine.start("ride-1", tariff, 0)
            ride = RideEngine.reduce(
                ride,
                RideInput.LocationReceived(bandedSample(0.0, 0, speed = 3.0, band = band), 0),
            )
            ride = RideEngine.reduce(
                ride,
                RideInput.LocationReceived(bandedSample(3.0, 1_000, speed = 3.0, band = band), 1_000),
            )
            return ride.distanceMeters.toDouble()
        }

        assertEquals(0.0, distanceAfterThreeMetreStep(LocationSample.Band.Single), 0.0)
        assertEquals(0.0, distanceAfterThreeMetreStep(LocationSample.Band.Unknown), 0.0)
        assertEquals(3.0, distanceAfterThreeMetreStep(LocationSample.Band.Dual), 0.1)
    }

    @Test
    fun `a reported speed too coarse to resolve the hysteresis band is replaced by derived speed`() {
        // The vehicle really covers 5 m per second while the provider insists it is stopped.
        fun motionAfterEightSeconds(speedAccuracy: Double): MotionState {
            var ride = RideEngine.start("ride-1", tariff, 0)
            var travelled = 0.0
            for (second in 0 until 8) {
                val now = second * 1_000L
                ride = RideEngine.reduce(
                    ride,
                    RideInput.LocationReceived(
                        bandedSample(
                            meters = travelled,
                            elapsedMillis = now,
                            speed = 0.0,
                            band = LocationSample.Band.Single,
                            speedAccuracy = speedAccuracy,
                        ),
                        now,
                    ),
                )
                ride = RideEngine.reduce(ride, RideInput.Tick(now + 1_000))
                travelled += 5.0
            }
            return ride.motionState
        }

        // Confident enough to place the vehicle below the idle threshold, so it is believed.
        assertEquals(MotionState.Idle, motionAfterEightSeconds(speedAccuracy = 0.1))
        // Wider than the 0.8-1.3 m/s band, so the engine derives 5 m/s from the fixes instead.
        assertEquals(MotionState.Moving, motionAfterEightSeconds(speedAccuracy = 2.0))
    }

    private fun bandedSample(
        meters: Double,
        elapsedMillis: Long,
        speed: Double,
        band: LocationSample.Band,
        speedAccuracy: Double? = null,
    ) = LocationSample(
        latitude = 42.6977 + meters / METERS_PER_DEGREE_LATITUDE,
        longitude = 23.3219,
        accuracyMeters = 2.0,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = speed,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
        band = band,
        speedAccuracyMetersPerSecond = speedAccuracy,
    )

    /** Drives one 1 Hz fix and one tick per second at the given speed profile. */
    private fun drive(seconds: Int, speedAt: (Int) -> Double): ActiveRide {
        var ride = RideEngine.start("ride-1", tariff, 0)
        var travelled = 0.0
        for (second in 0 until seconds) {
            val now = second * 1_000L
            val speed = speedAt(second)
            ride = RideEngine.reduce(
                ride,
                RideInput.LocationReceived(movedSample(travelled, now, speed), now),
            )
            ride = RideEngine.reduce(ride, RideInput.Tick(now + 1_000))
            travelled += speed
        }
        return ride
    }

    private fun movedSample(meters: Double, elapsedMillis: Long, speed: Double) = LocationSample(
        latitude = 42.6977 + meters / METERS_PER_DEGREE_LATITUDE,
        longitude = 23.3219,
        accuracyMeters = 5.0,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = speed,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
    )

    private fun accept(ride: ActiveRide, elapsedMillis: Long, speed: Double): ActiveRide =
        RideEngine.reduce(
            ride,
            RideInput.LocationReceived(sample(elapsedMillis, accuracyMeters = 5.0, speed = speed), elapsedMillis),
        )

    private fun sample(
        elapsedMillis: Long,
        accuracyMeters: Double,
        speed: Double,
    ) = LocationSample(
        latitude = 42.6977,
        longitude = 23.3219,
        accuracyMeters = accuracyMeters,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = speed,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
    )

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }
}
