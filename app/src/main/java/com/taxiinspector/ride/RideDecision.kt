package com.taxiinspector.ride

/**
 * What the engine did with one input and why. Pure data: the engine's own state transitions do
 * not read it, and nothing here is persisted. It exists so a debug ride trace can attribute
 * every billed and every discarded metre to a named rule.
 *
 * [baselineAgeMillis] is the open interval from the deadband baseline to this fix.
 * [deltaMillis] is the duration this decision attributed to the time tariff: the whole interval
 * on [Reason.ClosedTime], the newly observed hold on [Reason.Held], the hold committed on
 * [Reason.Relocated] and [Reason.GpsLostReset], and zero when distance won or nothing happened.
 */
data class RideDecision(
    val reason: Reason,
    val chordMeters: Double? = null,
    val significantMeters: Double? = null,
    val baselineAgeMillis: Long? = null,
    val excessSpeedMetersPerSecond: Double? = null,
    val billedAs: BilledAs = BilledAs.None,
    val deltaMillis: Long = 0,
) {
    enum class Reason {
        /** First accepted fix of a ride, or the first after a reset; becomes the baseline. */
        Seeded,

        /** Inside the deadband: the baseline is kept and the observed hold accrues provisionally. */
        Held,
        ClosedDistance,
        ClosedTime,
        RejectedMock,
        RejectedNonGps,
        RejectedStale,
        RejectedAccuracy,
        RejectedOutOfOrder,

        /** Implied speed beyond the plausibility bound; held pending confirmation. */
        Outlier,

        /** An implausible position confirmed by a second fix or by the streak cap. */
        Relocated,
        GpsLostReset,
    }

    enum class BilledAs { Distance, Time, None }
}
