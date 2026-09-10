package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import java.math.RoundingMode

/** Pure fare arithmetic; GPS and UI code must not duplicate this calculation. */
object FareCalculator {
    fun total(
        tariff: Tariff,
        trackedDistanceMeters: BigDecimal,
        timeTariffMillis: Long,
    ): DecimalAmount {
        require(trackedDistanceMeters.signum() >= 0) { "Distance cannot be negative." }
        require(timeTariffMillis >= 0) { "Tariff time cannot be negative." }

        return DecimalAmount.of(
            tariff.initialTax.value
                .add(distanceFare(tariff, trackedDistanceMeters))
                .add(timeFare(tariff, timeTariffMillis)),
        )
    }

    /** The exact, unrounded distance component of a fare. */
    fun distanceFare(tariff: Tariff, meters: BigDecimal): BigDecimal =
        tariff.perKmRate.value.multiply(
            meters.divide(METERS_PER_KILOMETRE, INTERNAL_SCALE, RoundingMode.HALF_UP),
        )

    /** The exact, unrounded time component of a fare. */
    fun timeFare(tariff: Tariff, millis: Long): BigDecimal =
        tariff.perMinuteStillRate.value.multiply(
            BigDecimal.valueOf(millis).divide(MILLIS_PER_MINUTE, INTERNAL_SCALE, RoundingMode.HALF_UP),
        )

    /**
     * Compares the distance and the time component of the same interval, exactly.
     *
     * [distanceFare] and [timeFare] divide at a fixed internal scale, which is invisible in a
     * displayed total but decides an exact mode-S tie by a rounding artefact in the eighteenth
     * decimal. So this cross-multiplies instead of dividing: `perKm x metres x 60` against
     * `perMinute x millis` is the same inequality with no division at all.
     *
     * Returns a positive number when distance earns more, zero on an exact tie, negative when
     * time earns more.
     */
    fun compareDistanceToTimeFare(tariff: Tariff, meters: BigDecimal, millis: Long): Int =
        tariff.perKmRate.value
            .multiply(meters)
            .multiply(SECONDS_PER_MINUTE)
            .compareTo(tariff.perMinuteStillRate.value.multiply(BigDecimal.valueOf(millis)))

    private val METERS_PER_KILOMETRE = BigDecimal("1000")
    private val MILLIS_PER_MINUTE = BigDecimal("60000")
    private val SECONDS_PER_MINUTE = BigDecimal("60")
    private const val INTERNAL_SCALE = 18
}
