package com.taxiinspector.ride

import java.math.BigDecimal
import kotlin.math.min

/**
 * Pure active-ride reducer. Android adapters decide how samples arrive; this class decides
 * whether a sample may affect distance, tariff time, or visible status.
 *
 * Billing attributes each closed interval between two billing-quality fixes whole, to distance
 * or to waiting time, by comparing its average speed to the locked tariff's own
 * [Tariff.waitingCrossoverKilometersPerHour] — a fourth, user-edited component of the company
 * profile, not one derived from the other three rates. This departs from EU taximeter
 * calculation mode S as OIML R21 defines it, whose cross-over is tariff-derived; a stored,
 * per-company value was chosen 2026-09-12 because the regulated crossover itself varies by
 * jurisdiction (5 km/h in Bulgaria, up to 20 elsewhere), so no single formula or constant is
 * right for every profile (see `project-memory.md`). No speed measurement or hysteresis takes
 * part in billing: the comparison is exact, between a chord and the tariff's own crossover.
 *
 * A fix that cannot bill is a non-observation, not a boundary. The chord between two billing
 * quality fixes is a lower bound on the path whatever happened between them, so a Weak fix
 * clears nothing and the next good fix settles the whole held interval at once. Only the
 * fifteen-second loss rule ends an interval unbilled.
 */
object RideEngine {
    private const val BILLING_ACCURACY_METERS = 20.0
    private const val MINIMUM_SIGNIFICANT_MOVEMENT_METERS = 5.0
    private const val DUAL_BAND_SIGNIFICANT_MOVEMENT_METERS = 2.5
    private const val FRESH_SAMPLE_MILLIS = 5_000L
    private const val GPS_LOSS_MILLIS = 15_000L

    /** No road vehicle exceeds this, so a faster implied speed is a positioning error. */
    private const val MAX_PLAUSIBLE_SPEED = 55.0

    /** Headroom over a reported speed and its own uncertainty before a fix is doubted. */
    private const val SPEED_MARGIN = 15.0

    /** Consecutive implausible fixes after which the receiver, not the position, is doubted. */
    private const val OUTLIER_STREAK_LIMIT = 3

    private val LABEL_FALLBACK_METERS = BigDecimal.valueOf(BILLING_ACCURACY_METERS)
    private val SECONDS_PER_HOUR: BigDecimal = BigDecimal.valueOf(3_600)

    /** Which tariff wins a closed interval. */
    enum class Attribution { Distance, Time }

    /** The new state, plus what the engine did and why, for the debug ride trace. */
    data class Step(val ride: ActiveRide, val decision: RideDecision?)

    /** Locks the company's name and exact tariff together, so neither can be taken from elsewhere. */
    fun start(id: String, company: TaxiCompany, nowElapsedMillis: Long): ActiveRide = ActiveRide(
        id = id,
        companyName = company.name,
        tariff = company.tariff,
        phase = RidePhase.Running,
        trackingStatus = TrackingStatus.Searching,
        distanceMeters = BigDecimal.ZERO,
        travelledDistanceMeters = BigDecimal.ZERO,
        timeTariffMillis = 0,
        provisionalTimeMillis = 0,
        motionState = MotionState.Idle,
        startedElapsedMillis = nowElapsedMillis,
        lastTickElapsedMillis = nowElapsedMillis,
        lastAcceptedFix = null,
        lastFreshBillableReceivedElapsedMillis = null,
        lastBillablePoint = null,
        pendingOutlier = null,
        outlierStreak = 0,
    )

    fun reduce(ride: ActiveRide, input: RideInput): ActiveRide = step(ride, input).ride

    fun step(ride: ActiveRide, input: RideInput): Step = when (input) {
        RideInput.Pause -> Step(pause(ride), null)
        is RideInput.Resume -> Step(resume(ride, input.nowElapsedMillis), null)
        is RideInput.LocationReceived -> onLocation(ride, input.sample, input.nowElapsedMillis)
        is RideInput.Tick -> onTick(ride, input.nowElapsedMillis)
        RideInput.PermissionRevoked -> Step(permissionRevoked(ride), null)
    }

    fun finish(ride: ActiveRide, endedElapsedMillis: Long): RideSummary {
        require(endedElapsedMillis >= ride.startedElapsedMillis) { "End cannot precede start." }
        return RideSummary(
            id = ride.id,
            companyName = ride.companyName,
            tariff = ride.tariff,
            total = FareCalculator.total(ride.tariff, ride.distanceMeters, ride.billedTimeMillis),
            distanceMeters = ride.distanceMeters,
            travelledDistanceMeters = ride.travelledDistanceMeters,
            timeTariffMillis = ride.billedTimeMillis,
            elapsedMillis = endedElapsedMillis - ride.startedElapsedMillis,
            endedElapsedMillis = endedElapsedMillis,
            status = RideSummary.Status.Completed,
        )
    }

    /**
     * Freezes a ride whose owning process died. The observed hold is committed here rather than
     * copied field by field elsewhere, so an interruption cannot silently drop billed time.
     */
    fun interrupt(ride: ActiveRide): ActiveRide = ride.copy(
        phase = RidePhase.PendingInterrupted,
        trackingStatus = TrackingStatus.GpsLost,
        timeTariffMillis = ride.billedTimeMillis,
        provisionalTimeMillis = 0,
        lastAcceptedFix = null,
        lastFreshBillableReceivedElapsedMillis = null,
        lastBillablePoint = null,
        pendingOutlier = null,
        outlierStreak = 0,
    )

    /**
     * The thresholds in force, for a trace to record beside the ride it explains. A captured
     * ride replayed against a different set of constants is not the same measurement, and
     * without these written down there is no way to tell afterwards which set produced it.
     */
    fun constantsForTrace(): Map<String, String> = mapOf(
        "billingAccuracyMeters" to BILLING_ACCURACY_METERS.toString(),
        "minimumSignificantMovementMeters" to MINIMUM_SIGNIFICANT_MOVEMENT_METERS.toString(),
        "dualBandSignificantMovementMeters" to DUAL_BAND_SIGNIFICANT_MOVEMENT_METERS.toString(),
        "deadbandRule" to "max(floor, baselineAccuracy + sampleAccuracy)",
        "freshSampleMillis" to FRESH_SAMPLE_MILLIS.toString(),
        "gpsLossMillis" to GPS_LOSS_MILLIS.toString(),
        "maxPlausibleSpeed" to MAX_PLAUSIBLE_SPEED.toString(),
        "speedMargin" to SPEED_MARGIN.toString(),
        "outlierStreakLimit" to OUTLIER_STREAK_LIMIT.toString(),
        "fareModel" to "perCompanyCrossover-perClosedInterval",
    )

    /**
     * One closed interval, one tariff: distance wins once the average speed over it clears the
     * locked tariff's own waiting crossover; time wins otherwise. An exact tie goes to distance,
     * matching the direction a meter switches at its cross-over.
     */
    internal fun reconcile(tariff: Tariff, meters: BigDecimal, millis: Long): Attribution =
        if (clearsWaitingCrossover(tariff, meters, millis)) Attribution.Distance else Attribution.Time

    /**
     * Cross-multiplied instead of divided, for the same exactness reason as
     * [FareCalculator.compareDistanceToTimeFare]: km/h = metres × 3600 / (1000 × millis), so
     * comparing to the tariff's crossover needs only `metres × 3600` against `kmh × millis`.
     */
    private fun clearsWaitingCrossover(tariff: Tariff, meters: BigDecimal, millis: Long): Boolean =
        meters.multiply(SECONDS_PER_HOUR) >=
            tariff.waitingCrossoverKilometersPerHour.value.multiply(BigDecimal.valueOf(millis))

    private fun onLocation(
        ride: ActiveRide,
        sample: LocationSample,
        nowElapsedMillis: Long,
    ): Step {
        if (ride.phase != RidePhase.Running) return Step(ride, null)

        rejection(sample, nowElapsedMillis)?.let { reason ->
            // A non-observation. The baseline is kept, the provisional hold does not grow, and
            // the loss timer is not refreshed, so "GPS weak -- fare frozen" stays literally true
            // and a good fix within fifteen seconds still bridges the whole interval.
            return Step(markWeak(ride, nowElapsedMillis), RideDecision(reason))
        }

        val previousFix = ride.lastAcceptedFix
        if (previousFix != null && sample.fixElapsedMillis <= previousFix.fixElapsedMillis) {
            return Step(ride, RideDecision(RideDecision.Reason.RejectedOutOfOrder))
        }

        // Exactly fifteen seconds is already GPS Lost. Reset before accepting the returning fix
        // so location-before-tick and tick-before-location produce the same state and fare.
        val lost = previousFix != null &&
            sample.fixElapsedMillis - previousFix.fixElapsedMillis >= GPS_LOSS_MILLIS
        val current = if (lost) markGpsLost(ride, nowElapsedMillis) else ride
        val tickElapsedMillis = if (current.trackingStatus != TrackingStatus.Good) {
            maxOf(current.lastTickElapsedMillis, nowElapsedMillis)
        } else {
            current.lastTickElapsedMillis
        }

        fun ActiveRide.accepted(): ActiveRide = copy(
            trackingStatus = TrackingStatus.Good,
            lastFreshBillableReceivedElapsedMillis = nowElapsedMillis,
            lastTickElapsedMillis = tickElapsedMillis,
        )

        val baseline = current.lastBillablePoint
        val acceptedFix = current.lastAcceptedFix
        if (baseline == null || acceptedFix == null) {
            // Searching, returning after a loss, or a snapshot reloaded without its fix clock:
            // a chord needs two fixes this ride actually observed.
            return Step(
                current.accepted().copy(
                    provisionalTimeMillis = 0,
                    lastBillablePoint = sample,
                    lastAcceptedFix = sample,
                    pendingOutlier = null,
                    outlierStreak = 0,
                    motionState = MotionState.Idle,
                ),
                RideDecision(RideDecision.Reason.Seeded),
            )
        }

        val excessSpeed = excessSpeedMetersPerSecond(acceptedFix, sample)
        if (excessSpeed > plausibilityBound(sample)) return outlier(
            current,
            sample,
            excessSpeed,
        ) { it.accepted() }

        val chordMeters = Geodesic.distanceMeters(baseline, sample)
        val baselineAgeMillis = sample.fixElapsedMillis - baseline.fixElapsedMillis
        // Both endpoints get their full accuracy, the same budget the plausibility rule above
        // spends. Taking only the larger of the two let a chord be simultaneously plausible as
        // noise and significant as movement, which is a contradiction the fare paid for: at
        // 20 m accuracy a standing vehicle's fixes differ by more than 20 m about half the time,
        // and each difference used to be billed as distance and then move the baseline, so the
        // next one billed too. Holding instead of closing always bills less, because a close
        // only wins on distance when distance out-earns the whole interval as time.
        val significantMeters = maxOf(
            significantMovementFloorMeters(baseline, sample),
            baseline.accuracyMeters + sample.accuracyMeters,
        )

        if (chordMeters < significantMeters) {
            // Held inside the deadband: the position is unresolved but the wait is observed.
            // Accruing it from the fix clock, not from ticks, keeps it at or below the interval
            // the next close attributes, so no ordering of ticks and fixes can lower a total.
            return Step(
                current.accepted().copy(
                    lastAcceptedFix = sample,
                    pendingOutlier = null,
                    outlierStreak = 0,
                    provisionalTimeMillis = baselineAgeMillis,
                    motionState = labelOnHold(
                        current.tariff,
                        current.motionState,
                        baselineAgeMillis,
                        significantMeters,
                        sample,
                    ),
                ),
                RideDecision(
                    reason = RideDecision.Reason.Held,
                    chordMeters = chordMeters,
                    significantMeters = significantMeters,
                    baselineAgeMillis = baselineAgeMillis,
                    excessSpeedMetersPerSecond = excessSpeed,
                    deltaMillis = baselineAgeMillis,
                ),
            )
        }

        return when (reconcile(current.tariff, BigDecimal.valueOf(chordMeters), baselineAgeMillis)) {
            Attribution.Distance -> Step(
                current.accepted().copy(
                    distanceMeters = current.distanceMeters.add(BigDecimal.valueOf(chordMeters)),
                    travelledDistanceMeters =
                        current.travelledDistanceMeters.add(BigDecimal.valueOf(chordMeters)),
                    provisionalTimeMillis = 0,
                    lastBillablePoint = sample,
                    lastAcceptedFix = sample,
                    pendingOutlier = null,
                    outlierStreak = 0,
                    motionState = MotionState.Moving,
                ),
                RideDecision(
                    reason = RideDecision.Reason.ClosedDistance,
                    chordMeters = chordMeters,
                    significantMeters = significantMeters,
                    baselineAgeMillis = baselineAgeMillis,
                    excessSpeedMetersPerSecond = excessSpeed,
                    billedAs = RideDecision.BilledAs.Distance,
                ),
            )

            Attribution.Time -> Step(
                current.accepted().copy(
                    timeTariffMillis = current.timeTariffMillis + baselineAgeMillis,
                    travelledDistanceMeters =
                        current.travelledDistanceMeters.add(BigDecimal.valueOf(chordMeters)),
                    provisionalTimeMillis = 0,
                    lastBillablePoint = sample,
                    lastAcceptedFix = sample,
                    pendingOutlier = null,
                    outlierStreak = 0,
                    motionState = labelOnTimeClose(current.tariff, acceptedFix, sample),
                ),
                RideDecision(
                    reason = RideDecision.Reason.ClosedTime,
                    chordMeters = chordMeters,
                    significantMeters = significantMeters,
                    baselineAgeMillis = baselineAgeMillis,
                    excessSpeedMetersPerSecond = excessSpeed,
                    billedAs = RideDecision.BilledAs.Time,
                    deltaMillis = baselineAgeMillis,
                ),
            )
        }
    }

    /**
     * An implausible fix moves nothing until a second fix agrees with it, so a multipath jump and
     * the return from it are never both billed. A genuine relocation is confirmed either by a
     * second mutually plausible fix or by a streak, which bounds the freeze to three seconds at
     * 1 Hz. The hold observed before the jump is committed; the jump itself is never billed.
     */
    private fun outlier(
        ride: ActiveRide,
        sample: LocationSample,
        excessSpeed: Double,
        accept: (ActiveRide) -> ActiveRide,
    ): Step {
        val streak = ride.outlierStreak + 1
        val confirmedByPendingFix = ride.pendingOutlier?.let {
            excessSpeedMetersPerSecond(it, sample) <= plausibilityBound(sample)
        } == true

        return if (confirmedByPendingFix || streak >= OUTLIER_STREAK_LIMIT) {
            Step(
                accept(ride).copy(
                    timeTariffMillis = ride.billedTimeMillis,
                    provisionalTimeMillis = 0,
                    lastBillablePoint = sample,
                    lastAcceptedFix = sample,
                    pendingOutlier = null,
                    outlierStreak = 0,
                    motionState = MotionState.Idle,
                ),
                RideDecision(
                    reason = RideDecision.Reason.Relocated,
                    excessSpeedMetersPerSecond = excessSpeed,
                    deltaMillis = ride.provisionalTimeMillis,
                ),
            )
        } else {
            Step(
                accept(ride).copy(pendingOutlier = sample, outlierStreak = streak),
                RideDecision(
                    reason = RideDecision.Reason.Outlier,
                    excessSpeedMetersPerSecond = excessSpeed,
                ),
            )
        }
    }

    private fun onTick(ride: ActiveRide, nowElapsedMillis: Long): Step {
        if (ride.phase != RidePhase.Running || nowElapsedMillis <= ride.lastTickElapsedMillis) {
            return Step(ride, null)
        }
        if (isGpsLost(ride, nowElapsedMillis)) {
            return Step(
                markGpsLost(ride, nowElapsedMillis),
                RideDecision(
                    reason = RideDecision.Reason.GpsLostReset,
                    deltaMillis = ride.provisionalTimeMillis,
                ),
            )
        }

        // Ticks never bill. They only let the label fall back to Idle across a stretch with no
        // billing quality fix at all, using the widest deadband as the distance it stands for.
        val baseline = ride.lastBillablePoint
        val label = if (
            baseline != null &&
            ride.motionState == MotionState.Moving &&
            !clearsWaitingCrossover(
                ride.tariff,
                LABEL_FALLBACK_METERS,
                (nowElapsedMillis - baseline.fixElapsedMillis).coerceAtLeast(0),
            )
        ) {
            MotionState.Idle
        } else {
            ride.motionState
        }
        return Step(ride.copy(lastTickElapsedMillis = nowElapsedMillis, motionState = label), null)
    }

    private fun pause(ride: ActiveRide): ActiveRide = when (ride.phase) {
        RidePhase.Running -> ride.copy(
            phase = RidePhase.Paused,
            timeTariffMillis = ride.billedTimeMillis,
            provisionalTimeMillis = 0,
        )

        else -> ride
    }

    private fun resume(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide {
        if (ride.phase != RidePhase.Paused) return ride
        return ride.copy(
            phase = RidePhase.Running,
            trackingStatus = TrackingStatus.Searching,
            lastTickElapsedMillis = nowElapsedMillis,
            provisionalTimeMillis = 0,
            lastAcceptedFix = null,
            lastFreshBillableReceivedElapsedMillis = null,
            lastBillablePoint = null,
            pendingOutlier = null,
            outlierStreak = 0,
            motionState = MotionState.Idle,
        )
    }

    private fun permissionRevoked(ride: ActiveRide): ActiveRide =
        if (ride.phase == RidePhase.Running) {
            ride.copy(
                phase = RidePhase.Paused,
                trackingStatus = TrackingStatus.PermissionNeeded,
                timeTariffMillis = ride.billedTimeMillis,
                provisionalTimeMillis = 0,
            )
        } else {
            ride
        }

    private fun rejection(sample: LocationSample, nowElapsedMillis: Long): RideDecision.Reason? =
        when {
            // A synthetic fix must never reach the fare: this app's output is meant to be
            // evidence, and a mock provider can manufacture any distance it likes.
            sample.isMock -> RideDecision.Reason.RejectedMock
            sample.provider != LocationSample.Provider.Gps -> RideDecision.Reason.RejectedNonGps
            sample.accuracyMeters > BILLING_ACCURACY_METERS -> RideDecision.Reason.RejectedAccuracy
            nowElapsedMillis - sample.fixElapsedMillis !in 0..FRESH_SAMPLE_MILLIS ->
                RideDecision.Reason.RejectedStale

            else -> null
        }

    private fun markWeak(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide = ride.copy(
        trackingStatus = TrackingStatus.Weak,
        lastTickElapsedMillis = maxOf(ride.lastTickElapsedMillis, nowElapsedMillis),
    )

    private fun isGpsLost(ride: ActiveRide, nowElapsedMillis: Long): Boolean =
        ride.lastFreshBillableReceivedElapsedMillis?.let {
            nowElapsedMillis - it >= GPS_LOSS_MILLIS
        } ?: false

    /**
     * The interval up to the last accepted fix is exactly the hold that was observed, so it is
     * committed. The tail after that fix was never observed and is never charged.
     */
    private fun markGpsLost(ride: ActiveRide, nowElapsedMillis: Long): ActiveRide = ride.copy(
        trackingStatus = TrackingStatus.GpsLost,
        lastTickElapsedMillis = nowElapsedMillis,
        timeTariffMillis = ride.billedTimeMillis,
        provisionalTimeMillis = 0,
        lastAcceptedFix = null,
        lastFreshBillableReceivedElapsedMillis = null,
        lastBillablePoint = null,
        pendingOutlier = null,
        outlierStreak = 0,
        motionState = MotionState.Idle,
    )

    /**
     * The implied speed a fix demands beyond what its own accuracy explains. Consecutive fixes at
     * 20 m accuracy routinely differ by tens of metres while standing still, so the accuracy
     * budget is subtracted before any speed is inferred.
     */
    private fun excessSpeedMetersPerSecond(from: LocationSample, to: LocationSample): Double {
        val seconds = (to.fixElapsedMillis - from.fixElapsedMillis) / 1_000.0
        if (seconds <= 0.0) return Double.POSITIVE_INFINITY
        val beyondAccuracy = Geodesic.distanceMeters(from, to) -
            (from.accuracyMeters + to.accuracyMeters)
        return maxOf(0.0, beyondAccuracy) / seconds
    }

    /**
     * A reported speed with its own uncertainty bounds the jump a fix may claim far more tightly
     * than the absolute limit. The relative bound applies only when speed accuracy is reported,
     * so a receiver publishing a bare zero speed cannot turn every fix into an outlier.
     */
    private fun plausibilityBound(sample: LocationSample): Double {
        val speed = sample.speedMetersPerSecond ?: return MAX_PLAUSIBLE_SPEED
        val accuracy = sample.speedAccuracyMetersPerSecond ?: return MAX_PLAUSIBLE_SPEED
        return min(MAX_PLAUSIBLE_SPEED, speed + accuracy + SPEED_MARGIN)
    }

    /**
     * A hold proves only that the average speed stayed under the deadband over the interval, so
     * once the deadband's own width would already be too slow to clear the waiting crossover in
     * the time elapsed, the vehicle cannot be above it either. A trusted Doppler speed answers
     * sooner, in either direction.
     */
    private fun labelOnHold(
        tariff: Tariff,
        current: MotionState,
        baselineAgeMillis: Long,
        significantMeters: Double,
        sample: LocationSample,
    ): MotionState {
        val crossover = tariff.waitingCrossoverMetersPerSecond
        val speed = sample.speedMetersPerSecond
        val speedAccuracy = sample.speedAccuracyMetersPerSecond
        val confidentlyAbove = speed != null && speedAccuracy != null &&
            speed - speedAccuracy >= crossover
        val confidentlyBelow = speed != null && speedAccuracy != null &&
            speed + speedAccuracy < crossover
        val deadbandOutEarned = !clearsWaitingCrossover(
            tariff,
            BigDecimal.valueOf(significantMeters),
            baselineAgeMillis,
        )

        return when {
            confidentlyAbove -> MotionState.Moving
            confidentlyBelow || deadbandOutEarned -> MotionState.Idle
            else -> current
        }
    }

    /**
     * The first close after a hold always goes to the time tariff, so the whole interval says
     * nothing about the vehicle now. The sub-interval since the previous fix does, and it labels
     * a car that has just pulled away correctly.
     */
    private fun labelOnTimeClose(
        tariff: Tariff,
        previousFix: LocationSample,
        sample: LocationSample,
    ): MotionState {
        val seconds = (sample.fixElapsedMillis - previousFix.fixElapsedMillis) / 1_000.0
        if (seconds <= 0.0) return MotionState.Idle
        val speed = Geodesic.distanceMeters(previousFix, sample) / seconds
        return if (speed >= tariff.waitingCrossoverMetersPerSecond) {
            MotionState.Moving
        } else {
            MotionState.Idle
        }
    }

    /**
     * L5-class signals resolve movement a single-band fix cannot, so a dual-band segment may
     * bill smaller steps. Both endpoints must be dual-band: the deadband covers noise at each
     * end of the segment, and a baseline restored from persistence comes back as Unknown.
     *
     * Since the deadband now spends both endpoints' accuracy, this floor only decides anything
     * once their sum is under 2.5 m, which a phone does not reach. It matters more on a company
     * with a low configured crossover, since more slow travel then bills as distance instead of
     * time, and a straight-line chord corner-cuts a curve most exactly at low speed. Whether to
     * tighten it is a Phase 8.3 question for the field trace.
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
}
