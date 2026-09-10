package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount

data class Tariff(
    val initialTax: DecimalAmount,
    val perKmRate: DecimalAmount,
    val perMinuteStillRate: DecimalAmount,
) {
    /**
     * The speed at which the distance and time tariffs earn the same money per second, which is
     * where an EU calculation-mode-S taximeter switches between them. It follows from the two
     * rates and is never user input.
     *
     * Billing does not read it: a closed interval is attributed by comparing the two fares over
     * that interval, which needs no speed at all. It is used for the Moving/Idle label and for
     * the debug trace.
     */
    val crossoverSpeedMetersPerSecond: Double
        get() {
            val perKilometre = perKmRate.value.toDouble()
            if (perKilometre == 0.0) return Double.POSITIVE_INFINITY
            val perMinute = perMinuteStillRate.value.toDouble()
            if (perMinute == 0.0) return 0.0
            return perMinute * METRES_PER_KILOMETRE / (perKilometre * SECONDS_PER_MINUTE)
        }

    private companion object {
        const val METRES_PER_KILOMETRE = 1_000.0
        const val SECONDS_PER_MINUTE = 60.0
    }
}
