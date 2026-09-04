package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
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
}
