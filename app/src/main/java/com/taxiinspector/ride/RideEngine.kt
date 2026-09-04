package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure active-ride reducer. Android adapters decide how samples arrive; this class
 * decides whether a sample may affect distance, waiting time, or visible status.
 */
object RideEngine {
    private const val BILLING_ACCURACY_METERS = 20.0
    private const val WEAK_ACCURACY_METERS = 60.0
    private const val MINIMUM_SIGNIFICANT_MOVEMENT_METERS = 5.0
    private const val DUAL_BAND_SIGNIFICANT_MOVEMENT_METERS = 2.5
    private const val MAXIMUM_SEGMENT_METERS = 1_500.0
    private const val FRESH_SAMPLE_MILLIS = 5_000L
    private const val GPS_LOSS_MILLIS = 15_000L
    private const val IDLE_ENTRY_MILLIS = 5_000L
    private const val MOVING_EXIT_MILLIS = 3_000L
    private const val IDLE_ENTRY_SPEED = 0.8
    private const val MOVING_EXIT_SPEED = 1.3

    fun start(id: String, tariff: Tariff, nowElapsedMillis: Long): ActiveRide = ActiveRide(
        id = id,
        tariff = tariff,
        phase = RidePhase.Running,
        trackingStatus = TrackingStatus.Searching,
        distanceMeters = BigDecimal.ZERO,
        idleMillis = 0,
        motionState = MotionState.Moving,
        startedElapsedMillis = nowElapsedMillis,
        lastTickElapsedMillis = nowElapsedMillis,
        lastAcceptedFixElapsedMillis = null,
        lastFreshBillableReceivedElapsedMillis = null,
        lastBillablePoint = null,
        lastSpeedMetersPerSecond = null,
        lastSpeedReceivedElapsedMillis = null,
        lowSpeedCandidateMillis = 0,
        highSpeedCandidateMillis = 0,
    )

    fun reduce(ride: ActiveRide, input: RideInput): ActiveRide = when (input) {
        RideInput.Pause -> pause(ride)
        is RideInput.Resume -> resume(ride, input.nowElapsedMillis)
        is RideInput.LocationReceived -> onLocation(ride, input.sample, input.nowElapsedMillis)
        is RideInput.Tick -> onTick(ride, input.nowElapsedMillis)
        is RideInput.GpsTimedOut -> timeout(ride, input.nowElapsedMillis)
        RideInput.PermissionRevoked -> permissionRevoked(ride)
    }

    fun finish(ride: ActiveRide, endedElapsedMillis: Long): RideSummary {
        require(endedElapsedMillis >= ride.startedElapsedMillis) { "End cannot precede start." }
        return RideSummary(
            id = ride.id,
            tariff = ride.tariff,
            total = FareCalculator.total(ride.tariff, ride.distanceMeters, ride.idleMillis),
            distanceMeters = ride.distanceMeters,
            idleMillis = ride.idleMillis,
            elapsedMillis = endedElapsedMillis - ride.startedElapsedMillis,
            endedElapsedMillis = endedElapsedMillis,
            status = RideSummary.Status.Completed,
        )
    }

    private fun pause(ride: ActiveRide): ActiveRide = when (ride.phase) {
        RidePhase.Running -> ride.copy(
            phase = RidePhase.Paused,
            lowSpeedCandidateMillis = 0,
            highSpeedCandidateMillis = 0,
        )
        else -> ride
    }

    private fun resume(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide {
        if (ride.phase != RidePhase.Paused) return ride
        return ride.copy(
            phase = RidePhase.Running,
            trackingStatus = TrackingStatus.Searching,
            lastTickElapsedMillis = nowElapsedMillis,
            lastAcceptedFixElapsedMillis = null,
            lastFreshBillableReceivedElapsedMillis = null,
            lastBillablePoint = null,
            lastSpeedMetersPerSecond = null,
            lastSpeedReceivedElapsedMillis = null,
            lowSpeedCandidateMillis = 0,
            highSpeedCandidateMillis = 0,
            motionState = MotionState.Moving,
        )
    }

    private fun onLocation(
        ride: ActiveRide,
        sample: LocationSample,
        nowElapsedMillis: Long,
    ): ActiveRide {
        if (ride.phase != RidePhase.Running) return ride
        // A synthetic fix must never reach the fare: this app's output is meant to be
        // evidence, and a mock provider can manufacture any distance it likes.
        if (sample.isMock) return ride.copy(trackingStatus = TrackingStatus.Weak)
        if (sample.provider != LocationSample.Provider.Gps || sample.accuracyMeters > WEAK_ACCURACY_METERS) {
            return ride.copy(trackingStatus = TrackingStatus.Weak)
        }
        if (nowElapsedMillis - sample.fixElapsedMillis !in 0..FRESH_SAMPLE_MILLIS) {
            return ride.copy(trackingStatus = TrackingStatus.Weak)
        }
        if (sample.accuracyMeters > BILLING_ACCURACY_METERS) {
            return ride.copy(trackingStatus = TrackingStatus.Weak)
        }
        if (ride.lastAcceptedFixElapsedMillis != null &&
            sample.fixElapsedMillis <= ride.lastAcceptedFixElapsedMillis
        ) {
            return ride
        }

        val previousBaseline = ride.lastBillablePoint
        val gapMillis = previousBaseline?.let { sample.fixElapsedMillis - it.fixElapsedMillis }
        val segmentMeters = previousBaseline?.let { distanceBetweenMeters(it, sample) }
        val canUseSegment = previousBaseline != null &&
            gapMillis != null && gapMillis in 1..GPS_LOSS_MILLIS &&
            segmentMeters != null && segmentMeters <= MAXIMUM_SEGMENT_METERS
        val significantMeters = previousBaseline?.let {
            maxOf(significantMovementFloorMeters(it, sample), it.accuracyMeters, sample.accuracyMeters)
        }
        val isSignificantSegment = canUseSegment && segmentMeters!! >= significantMeters!!
        // Distance and waiting time are mutually exclusive: a vehicle the engine considers
        // Idle is billed for time, so its movement advances the baseline without billing.
        val distanceToAdd = if (isSignificantSegment && ride.motionState == MotionState.Moving) {
            BigDecimal.valueOf(segmentMeters!!)
        } else {
            BigDecimal.ZERO
        }

        val canDeriveSpeed = previousBaseline != null &&
            gapMillis != null && gapMillis in 1..FRESH_SAMPLE_MILLIS && segmentMeters != null
        val derivedSpeed = if (canDeriveSpeed) {
            segmentMeters!! / (gapMillis!!.toDouble() / 1_000.0)
        } else {
            null
        }
        val usableSpeed = sample.trustedSpeedMetersPerSecond() ?: derivedSpeed
        val nextBaseline = when {
            previousBaseline == null -> sample
            gapMillis == null || gapMillis > GPS_LOSS_MILLIS -> sample
            segmentMeters != null && segmentMeters > MAXIMUM_SEGMENT_METERS -> sample
            // Advances on any significant segment, billed or not, so that leaving Idle
            // never measures back across an interval that was already billed as waiting.
            isSignificantSegment -> sample
            else -> previousBaseline
        }

        return ride.copy(
            trackingStatus = TrackingStatus.Good,
            distanceMeters = ride.distanceMeters.add(distanceToAdd),
            lastAcceptedFixElapsedMillis = sample.fixElapsedMillis,
            lastFreshBillableReceivedElapsedMillis = nowElapsedMillis,
            lastBillablePoint = nextBaseline,
            lastSpeedMetersPerSecond = usableSpeed,
            lastSpeedReceivedElapsedMillis = if (usableSpeed == null) null else nowElapsedMillis,
        )
    }

    private fun onTick(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide {
        if (ride.phase != RidePhase.Running || nowElapsedMillis <= ride.lastTickElapsedMillis) return ride
        if (isGpsLost(ride, nowElapsedMillis)) return markGpsLost(ride, nowElapsedMillis)

        val elapsedMillis = nowElapsedMillis - ride.lastTickElapsedMillis
        val speed = ride.lastSpeedMetersPerSecond
        val speedFresh = ride.lastSpeedReceivedElapsedMillis?.let {
            nowElapsedMillis - it <= FRESH_SAMPLE_MILLIS
        } == true
        if (speed == null || !speedFresh) {
            return ride.copy(lastTickElapsedMillis = nowElapsedMillis)
        }

        return applySpeedInterval(ride, speed, elapsedMillis).copy(lastTickElapsedMillis = nowElapsedMillis)
    }

    private fun applySpeedInterval(
        ride: ActiveRide,
        speedMetersPerSecond: Double,
        elapsedMillis: Long,
    ): ActiveRide = when (ride.motionState) {
        MotionState.Moving -> when {
            speedMetersPerSecond <= IDLE_ENTRY_SPEED -> {
                val candidate = ride.lowSpeedCandidateMillis + elapsedMillis
                if (candidate >= IDLE_ENTRY_MILLIS) {
                    ride.copy(
                        motionState = MotionState.Idle,
                        lowSpeedCandidateMillis = 0,
                        highSpeedCandidateMillis = 0,
                    )
                } else {
                    ride.copy(lowSpeedCandidateMillis = candidate, highSpeedCandidateMillis = 0)
                }
            }
            else -> ride.copy(lowSpeedCandidateMillis = 0, highSpeedCandidateMillis = 0)
        }

        MotionState.Idle -> when {
            speedMetersPerSecond >= MOVING_EXIT_SPEED -> {
                val candidate = ride.highSpeedCandidateMillis + elapsedMillis
                val chargedRide = ride.copy(idleMillis = ride.idleMillis + elapsedMillis)
                if (candidate >= MOVING_EXIT_MILLIS) {
                    chargedRide.copy(
                        motionState = MotionState.Moving,
                        lowSpeedCandidateMillis = 0,
                        highSpeedCandidateMillis = 0,
                    )
                } else {
                    chargedRide.copy(highSpeedCandidateMillis = candidate, lowSpeedCandidateMillis = 0)
                }
            }
            else -> ride.copy(
                idleMillis = ride.idleMillis + elapsedMillis,
                highSpeedCandidateMillis = 0,
            )
        }
    }

    private fun timeout(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide =
        if (ride.phase == RidePhase.Running && isGpsLost(ride, nowElapsedMillis)) {
            markGpsLost(ride, nowElapsedMillis)
        } else {
            ride
        }

    private fun permissionRevoked(ride: ActiveRide): ActiveRide =
        if (ride.phase == RidePhase.Running) {
            ride.copy(
                phase = RidePhase.Paused,
                trackingStatus = TrackingStatus.PermissionNeeded,
                lowSpeedCandidateMillis = 0,
                highSpeedCandidateMillis = 0,
                lastSpeedMetersPerSecond = null,
                lastSpeedReceivedElapsedMillis = null,
            )
        } else {
            ride
        }

    private fun isGpsLost(ride: ActiveRide, nowElapsedMillis: Long): Boolean =
        ride.lastFreshBillableReceivedElapsedMillis?.let {
            nowElapsedMillis - it >= GPS_LOSS_MILLIS
        } ?: false

    private fun markGpsLost(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide = ride.copy(
        trackingStatus = TrackingStatus.GpsLost,
        lastTickElapsedMillis = nowElapsedMillis,
        lastAcceptedFixElapsedMillis = null,
        lastFreshBillableReceivedElapsedMillis = null,
        lastBillablePoint = null,
        lastSpeedMetersPerSecond = null,
        lastSpeedReceivedElapsedMillis = null,
        lowSpeedCandidateMillis = 0,
        highSpeedCandidateMillis = 0,
        motionState = MotionState.Moving,
    )

    /**
     * L5-class signals resolve movement a single-band fix cannot, so a dual-band segment may
     * bill smaller steps. Both endpoints must be dual-band: the deadband covers noise at each
     * end of the segment, and a baseline restored from persistence comes back as Unknown.
     */
    private fun significantMovementFloorMeters(
        baseline: LocationSample,
        sample: LocationSample,
    ): Double =
        if (baseline.band == LocationSample.Band.Dual && sample.band == LocationSample.Band.Dual) {
            DUAL_BAND_SIGNIFICANT_MOVEMENT_METERS
        } else {
            MINIMUM_SIGNIFICANT_MOVEMENT_METERS
        }

    /**
     * A reported speed whose own accuracy is wider than the Idle/Moving hysteresis band cannot
     * place the vehicle on either side of it. Such a speed is treated as no reported speed at
     * all, which drops the engine onto its existing derived-speed fallback.
     */
    private fun LocationSample.trustedSpeedMetersPerSecond(): Double? =
        speedMetersPerSecond?.takeIf {
            val accuracy = speedAccuracyMetersPerSecond
            accuracy == null || accuracy <= MOVING_EXIT_SPEED - IDLE_ENTRY_SPEED
        }

    private fun distanceBetweenMeters(first: LocationSample, second: LocationSample): Double {
        val latitudeRadians = Math.toRadians(second.latitude - first.latitude)
        val longitudeRadians = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeRadians / 2).pow(2) +
            cos(Math.toRadians(first.latitude)) * cos(Math.toRadians(second.latitude)) *
            sin(longitudeRadians / 2).pow(2)
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(a))
    }

    private const val EARTH_RADIUS_METERS = 6_371_000.0
}
