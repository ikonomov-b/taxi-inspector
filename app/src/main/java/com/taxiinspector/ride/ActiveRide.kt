package com.taxiinspector.ride

import java.math.BigDecimal

/**
 * The complete deterministic state required to resume an active fare calculation.
 * The stored points are temporary billing references, never route history.
 *
 * [companyName] is the label locked at Start, copied together with [tariff] so the ride never
 * depends on a mutable company row. It is null only for a ride that began before saved
 * companies existed, which display renders as an explicit legacy label.
 *
 * Time accrues in two parts. [timeTariffMillis] is committed: intervals the engine has already
 * closed on the time tariff. [provisionalTimeMillis] is the hold observed since the current
 * [lastBillablePoint] was set, measured on the fix clock, which the next closed interval either
 * replaces with its own attribution or commits. [billedTimeMillis] is their sum and the only
 * time figure a fare, a notification or a summary may read.
 */
data class ActiveRide(
    val id: String,
    val companyName: String?,
    val tariff: Tariff,
    val phase: RidePhase,
    val trackingStatus: TrackingStatus,
    val distanceMeters: BigDecimal,
    val timeTariffMillis: Long,
    val provisionalTimeMillis: Long,
    val motionState: MotionState,
    val startedElapsedMillis: Long,
    val lastTickElapsedMillis: Long,
    val lastAcceptedFix: LocationSample?,
    val lastFreshBillableReceivedElapsedMillis: Long?,
    val lastBillablePoint: LocationSample?,
    val pendingOutlier: LocationSample?,
    val outlierStreak: Int,
) {
    /** Committed plus provisional time-tariff duration; the figure every fare consumes. */
    val billedTimeMillis: Long get() = timeTariffMillis + provisionalTimeMillis

    init {
        require(id.isNotBlank()) { "Ride id cannot be blank." }
        require(companyName == null || companyName.isNotBlank()) {
            "A locked company name cannot be blank."
        }
        require(distanceMeters.signum() >= 0) { "Distance cannot be negative." }
        require(timeTariffMillis >= 0) { "Committed tariff time cannot be negative." }
        require(provisionalTimeMillis >= 0) { "Provisional tariff time cannot be negative." }
        require(lastBillablePoint != null || provisionalTimeMillis == 0L) {
            "Provisional time cannot outlive its baseline."
        }
        require(pendingOutlier == null || lastAcceptedFix != null) {
            "A pending outlier needs an accepted fix to be implausible against."
        }
        require(outlierStreak >= 0) { "Outlier streak cannot be negative." }
        require(startedElapsedMillis >= 0 && lastTickElapsedMillis >= 0) {
            "Elapsed timestamps cannot be negative."
        }
    }
}
