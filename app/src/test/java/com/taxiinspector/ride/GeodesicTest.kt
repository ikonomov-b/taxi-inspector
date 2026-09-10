package com.taxiinspector.ride

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeodesicTest {
    @Test
    fun `matches the WGS84 degree lengths at the equator`() {
        assertEquals(
            111_319.49,
            Geodesic.distanceMeters(0.0, 0.0, 0.0, 1.0),
            0.01,
        )
        assertEquals(
            110_574.39,
            Geodesic.distanceMeters(0.0, 0.0, 1.0, 0.0),
            0.01,
        )
    }

    @Test
    fun `matches Vincenty's own published test line`() {
        // Flinders Peak to Buninyong, the reference pair from Vincenty's 1975 paper, converted
        // from its degrees-minutes-seconds coordinates: truncating them to five decimal places
        // moves Flinders Peak by 0.4 m and the answer with it.
        val flindersPeakLatitude = -(37 + 57 / 60.0 + 3.72030 / 3600.0)
        val flindersPeakLongitude = 144 + 25 / 60.0 + 29.52440 / 3600.0
        val buninyongLatitude = -(37 + 39 / 60.0 + 10.15610 / 3600.0)
        val buninyongLongitude = 143 + 55 / 60.0 + 35.38390 / 3600.0

        assertEquals(
            54_972.271,
            Geodesic.distanceMeters(
                flindersPeakLatitude,
                flindersPeakLongitude,
                buninyongLatitude,
                buninyongLongitude,
            ),
            0.001,
        )
    }

    @Test
    fun `the ellipsoid is shorter than the mean-radius sphere at the test latitude`() {
        // A thousandth of a degree of latitude at Sofia. The mean-radius haversine this replaced
        // reads 111.195 m for the same step: 0.109 m, or 0.098 %, long. MI-007 allows 0.2 % in
        // total, so half of it was being spent before any GPS noise was counted.
        assertEquals(
            111.0869,
            Geodesic.distanceMeters(42.6977, 23.3219, 42.6987, 23.3219),
            0.0005,
        )
    }

    @Test
    fun `coincident points are zero apart`() {
        assertEquals(0.0, Geodesic.distanceMeters(42.6977, 23.3219, 42.6977, 23.3219), 0.0)
    }

    @Test
    fun `a near-antipodal pair falls back instead of failing to converge`() {
        // Vincenty's iteration does not converge here. No ride segment can reach this, but the
        // engine must never see a NaN from a geodesic.
        val distance = Geodesic.distanceMeters(0.0, 0.0, 0.5, 179.5)
        val haversine = 6_371_008.8 * 2 * asin(
            sqrt(
                sin(Math.toRadians(0.5) / 2).pow(2) +
                    cos(0.0) * cos(Math.toRadians(0.5)) * sin(Math.toRadians(179.5) / 2).pow(2),
            ).coerceAtMost(1.0),
        )

        assertTrue(distance.isFinite())
        assertTrue(abs(distance - haversine) / haversine < 0.005)
    }
}
