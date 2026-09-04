package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import java.math.RoundingMode

/** Pure fare arithmetic; GPS and UI code must not duplicate this calculation. */
object FareCalculator {
    fun total(
        tariff: Tariff,
        trackedDistanceMeters: BigDecimal,
        idleMillis: Long,
    ): DecimalAmount {
        require(trackedDistanceMeters.signum() >= 0) { "Distance cannot be negative." }
        require(idleMillis >= 0) { "Idle time cannot be negative." }

        val distanceFare = tariff.perKmRate.value.multiply(
            trackedDistanceMeters.divide(METERS_PER_KILOMETRE, INTERNAL_SCALE, RoundingMode.HALF_UP),
        )
        val idleFare = tariff.perMinuteStillRate.value.multiply(
            BigDecimal.valueOf(idleMillis).divide(MILLIS_PER_MINUTE, INTERNAL_SCALE, RoundingMode.HALF_UP),
        )

        return DecimalAmount.of(tariff.initialTax.value.add(distanceFare).add(idleFare))
    }

    private val METERS_PER_KILOMETRE = BigDecimal("1000")
    private val MILLIS_PER_MINUTE = BigDecimal("60000")
    private const val INTERNAL_SCALE = 18
}
