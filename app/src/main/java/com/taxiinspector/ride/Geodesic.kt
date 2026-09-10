package com.taxiinspector.ride

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * WGS84 distance between two fixes.
 *
 * MI-007 allows 0.2 % distance error, and the spherical mean-radius haversine this replaced is
 * already about 0.3 % off at mid latitudes, so the ellipsoid matters before any GPS noise does.
 * Vincenty's inverse formula is exact to the millimetre but does not converge for near-antipodal
 * pairs; those fall back to the haversine, which no ride segment can reach anyway.
 */
object Geodesic {
    fun distanceMeters(from: LocationSample, to: LocationSample): Double =
        distanceMeters(from.latitude, from.longitude, to.latitude, to.longitude)

    fun distanceMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        if (fromLatitude == toLatitude && fromLongitude == toLongitude) return 0.0
        return vincentyMeters(fromLatitude, fromLongitude, toLatitude, toLongitude)
            ?: haversineMeters(fromLatitude, fromLongitude, toLatitude, toLongitude)
    }

    /** Returns null when the iteration does not converge, which only near-antipodal pairs do. */
    private fun vincentyMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double? {
        val longitudeDifference = Math.toRadians(toLongitude - fromLongitude)
        val reducedFromTangent = (1 - FLATTENING) * tan(Math.toRadians(fromLatitude))
        val reducedToTangent = (1 - FLATTENING) * tan(Math.toRadians(toLatitude))
        val cosFrom = 1.0 / sqrt(1 + reducedFromTangent * reducedFromTangent)
        val sinFrom = reducedFromTangent * cosFrom
        val cosTo = 1.0 / sqrt(1 + reducedToTangent * reducedToTangent)
        val sinTo = reducedToTangent * cosTo

        var lambda = longitudeDifference
        var sinSigma = 0.0
        var cosSigma = 0.0
        var sigma = 0.0
        var cosSquaredAlpha = 0.0
        var cosTwiceSigmaMidpoint = 0.0
        var iterations = 0
        var converged = false

        while (!converged && iterations < MAX_ITERATIONS) {
            iterations++
            val sinLambda = sin(lambda)
            val cosLambda = cos(lambda)
            sinSigma = sqrt(
                (cosTo * sinLambda).pow(2) + (cosFrom * sinTo - sinFrom * cosTo * cosLambda).pow(2),
            )
            cosSigma = sinFrom * sinTo + cosFrom * cosTo * cosLambda
            // Coincident points give a zero distance; antipodal ones give the same sine and must
            // fall back rather than be reported as zero.
            if (sinSigma == 0.0) return if (cosSigma > 0) 0.0 else null
            sigma = atan2(sinSigma, cosSigma)
            val sinAlpha = cosFrom * cosTo * sinLambda / sinSigma
            cosSquaredAlpha = 1 - sinAlpha * sinAlpha
            cosTwiceSigmaMidpoint = if (cosSquaredAlpha == 0.0) {
                0.0
            } else {
                cosSigma - 2 * sinFrom * sinTo / cosSquaredAlpha
            }
            val c = FLATTENING / 16 * cosSquaredAlpha *
                (4 + FLATTENING * (4 - 3 * cosSquaredAlpha))
            val previousLambda = lambda
            lambda = longitudeDifference + (1 - c) * FLATTENING * sinAlpha * (
                sigma + c * sinSigma * (
                    cosTwiceSigmaMidpoint + c * cosSigma *
                        (-1 + 2 * cosTwiceSigmaMidpoint * cosTwiceSigmaMidpoint)
                    )
                )
            converged = abs(lambda - previousLambda) < CONVERGENCE_TOLERANCE
        }
        if (!converged) return null

        val uSquared = cosSquaredAlpha *
            (SEMI_MAJOR_AXIS_METERS.pow(2) - SEMI_MINOR_AXIS_METERS.pow(2)) /
            SEMI_MINOR_AXIS_METERS.pow(2)
        val a = 1 + uSquared / 16384 *
            (4096 + uSquared * (-768 + uSquared * (320 - 175 * uSquared)))
        val b = uSquared / 1024 * (256 + uSquared * (-128 + uSquared * (74 - 47 * uSquared)))
        val deltaSigma = b * sinSigma * (
            cosTwiceSigmaMidpoint + b / 4 * (
                cosSigma * (-1 + 2 * cosTwiceSigmaMidpoint * cosTwiceSigmaMidpoint) -
                    b / 6 * cosTwiceSigmaMidpoint * (-3 + 4 * sinSigma * sinSigma) *
                    (-3 + 4 * cosTwiceSigmaMidpoint * cosTwiceSigmaMidpoint)
                )
            )
        return SEMI_MINOR_AXIS_METERS * a * (sigma - deltaSigma)
    }

    private fun haversineMeters(
        fromLatitude: Double,
        fromLongitude: Double,
        toLatitude: Double,
        toLongitude: Double,
    ): Double {
        val latitudeRadians = Math.toRadians(toLatitude - fromLatitude)
        val longitudeRadians = Math.toRadians(toLongitude - fromLongitude)
        val a = sin(latitudeRadians / 2).pow(2) +
            cos(Math.toRadians(fromLatitude)) * cos(Math.toRadians(toLatitude)) *
            sin(longitudeRadians / 2).pow(2)
        return MEAN_RADIUS_METERS * 2 * asin(sqrt(a.coerceAtMost(1.0)))
    }

    private const val SEMI_MAJOR_AXIS_METERS = 6_378_137.0
    private const val FLATTENING = 1 / 298.257223563
    private const val SEMI_MINOR_AXIS_METERS = SEMI_MAJOR_AXIS_METERS * (1 - FLATTENING)
    private const val MEAN_RADIUS_METERS = 6_371_008.8
    private const val CONVERGENCE_TOLERANCE = 1e-12
    private const val MAX_ITERATIONS = 200
}
