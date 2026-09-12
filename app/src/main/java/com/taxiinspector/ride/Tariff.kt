package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount

/**
 * [waitingCrossoverKilometersPerHour] is the fourth locked component of a company's profile: the
 * speed below which a closed interval bills as waiting time rather than distance. It is stored,
 * user-edited data, not derived from the other three rates. EU taximeter regulation ties this
 * speed to a jurisdiction rather than to a formula on the money rates — Bulgaria specifies 5 km/h,
 * other EU states allow up to 20 — so this app asks for it directly instead of assuming one
 * derivation. See `project-memory.md` for the sourced background this decision replaced.
 */
data class Tariff(
    val initialTax: DecimalAmount,
    val perKmRate: DecimalAmount,
    val perMinuteStillRate: DecimalAmount,
    val waitingCrossoverKilometersPerHour: DecimalAmount,
) {
    init {
        require(waitingCrossoverKilometersPerHour.value.signum() > 0) {
            "A waiting crossover of zero would make the time tariff unreachable."
        }
    }

    /** Only [RideEngine.labelOnHold] compares this to a measured Doppler speed; billing itself
     * stays exact through [RideEngine.reconcile]. */
    val waitingCrossoverMetersPerSecond: Double
        get() = waitingCrossoverKilometersPerHour.value.toDouble() / SECONDS_PER_HOUR_OVER_1000

    companion object {
        /** A product default, not a regulatory value; the regulated figure varies by jurisdiction. */
        const val DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR = "8"

        private const val SECONDS_PER_HOUR_OVER_1000 = 3.6
    }
}
