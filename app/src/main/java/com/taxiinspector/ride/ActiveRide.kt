package com.taxiinspector.ride

import java.math.BigDecimal

/**
 * The complete deterministic state required to resume an active fare calculation.
 * The one stored point is a temporary billable baseline, never route history.
 *
 * [companyName] is the label locked at Start, copied together with [tariff] so the ride never
 * depends on a mutable company row. It is null only for a ride that began before saved
 * companies existed, which display renders as an explicit legacy label.
 */
data class ActiveRide(
    val id: String,
    val companyName: String?,
    val tariff: Tariff,
    val phase: RidePhase,
    val trackingStatus: TrackingStatus,
    val distanceMeters: BigDecimal,
    val idleMillis: Long,
    val motionState: MotionState,
    val startedElapsedMillis: Long,
    val lastTickElapsedMillis: Long,
    val lastAcceptedFixElapsedMillis: Long?,
    val lastFreshBillableReceivedElapsedMillis: Long?,
    val lastBillablePoint: LocationSample?,
    val lastSpeedMetersPerSecond: Double?,
    val lastSpeedReceivedElapsedMillis: Long?,
    val lowSpeedCandidateMillis: Long,
    val highSpeedCandidateMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "Ride id cannot be blank." }
        require(companyName == null || companyName.isNotBlank()) {
            "A locked company name cannot be blank."
        }
        require(distanceMeters.signum() >= 0) { "Distance cannot be negative." }
        require(idleMillis >= 0) { "Idle time cannot be negative." }
        require(startedElapsedMillis >= 0 && lastTickElapsedMillis >= 0) {
            "Elapsed timestamps cannot be negative."
        }
        require(lowSpeedCandidateMillis >= 0 && highSpeedCandidateMillis >= 0) {
            "Speed candidates cannot be negative."
        }
    }
}
