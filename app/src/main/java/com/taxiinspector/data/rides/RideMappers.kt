package com.taxiinspector.data.rides

import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.MotionState
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.SavedRideSummary
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import com.taxiinspector.ride.TrackingStatus
import java.math.BigDecimal

internal fun TaxiCompanyEntity.toDomain(): TaxiCompany = TaxiCompany(
    id = id,
    name = name,
    tariff = Tariff(
        initialTax = initialTax.toDecimalAmount(),
        perKmRate = perKmRate.toDecimalAmount(),
        perMinuteStillRate = perMinuteStillRate.toDecimalAmount(),
        waitingCrossoverKilometersPerHour = waitingCrossoverKilometersPerHour.toDecimalAmount(),
    ),
)

internal fun TaxiCompany.toEntity(): TaxiCompanyEntity = TaxiCompanyEntity(
    id = id,
    name = name,
    nameKey = nameKey,
    initialTax = tariff.initialTax.value.toPlainString(),
    perKmRate = tariff.perKmRate.value.toPlainString(),
    perMinuteStillRate = tariff.perMinuteStillRate.value.toPlainString(),
    waitingCrossoverKilometersPerHour = tariff.waitingCrossoverKilometersPerHour.value.toPlainString(),
)

internal fun ActiveRide.toEntity(): ActiveRideEntity = ActiveRideEntity(
    id = id,
    companyName = companyName,
    initialTax = tariff.initialTax.value.toPlainString(),
    perKmRate = tariff.perKmRate.value.toPlainString(),
    perMinuteStillRate = tariff.perMinuteStillRate.value.toPlainString(),
    waitingCrossoverKilometersPerHour = tariff.waitingCrossoverKilometersPerHour.value.toPlainString(),
    phase = phase.name,
    trackingStatus = trackingStatus.name,
    distanceMeters = distanceMeters.toPlainString(),
    travelledDistanceMeters = travelledDistanceMeters.toPlainString(),
    // The column keeps its version-1 name and holds committed plus provisional time together:
    // every path that persists a ride has either committed the hold or is storing a confirmed
    // total, so the sum is exact and no migration is needed.
    idleMillis = billedTimeMillis,
    motionState = motionState.name,
    startedElapsedMillis = startedElapsedMillis,
    lastTickElapsedMillis = lastTickElapsedMillis,
    lastAcceptedFixElapsedMillis = lastAcceptedFix?.fixElapsedMillis,
    lastFreshBillableReceivedElapsedMillis = lastFreshBillableReceivedElapsedMillis,
    pointLatitude = lastBillablePoint?.latitude,
    pointLongitude = lastBillablePoint?.longitude,
    pointAccuracyMeters = lastBillablePoint?.accuracyMeters,
    pointProvider = lastBillablePoint?.provider?.name,
    pointSpeedMetersPerSecond = lastBillablePoint?.speedMetersPerSecond,
    pointFixElapsedMillis = lastBillablePoint?.fixElapsedMillis,
    pointReceivedElapsedMillis = lastBillablePoint?.receivedElapsedMillis,
    // Speed hysteresis is gone; the columns stay so the schema does not change.
    lastSpeedMetersPerSecond = null,
    lastSpeedReceivedElapsedMillis = null,
    lowSpeedCandidateMillis = 0,
    highSpeedCandidateMillis = 0,
)

internal fun ActiveRideEntity.toDomain(): ActiveRide = ActiveRide(
    id = id,
    companyName = companyName,
    tariff = Tariff(
        initialTax.toDecimalAmount(),
        perKmRate.toDecimalAmount(),
        perMinuteStillRate.toDecimalAmount(),
        waitingCrossoverKilometersPerHour.toDecimalAmount(),
    ),
    phase = RidePhase.valueOf(phase),
    trackingStatus = TrackingStatus.valueOf(trackingStatus),
    distanceMeters = BigDecimal(distanceMeters),
    travelledDistanceMeters = BigDecimal(travelledDistanceMeters),
    timeTariffMillis = idleMillis,
    provisionalTimeMillis = 0,
    motionState = MotionState.valueOf(motionState),
    startedElapsedMillis = startedElapsedMillis,
    lastTickElapsedMillis = lastTickElapsedMillis,
    // A restored baseline has no fix clock to measure continuity or plausibility against, and
    // the engine treats a point without one exactly like no baseline at all, so a stale point
    // can never form a chord across a process death.
    lastAcceptedFix = null,
    lastFreshBillableReceivedElapsedMillis = lastFreshBillableReceivedElapsedMillis,
    lastBillablePoint = pointOrNull(),
    pendingOutlier = null,
    outlierStreak = 0,
)

internal fun SavedRideSummary.toEntity(): RideSummaryEntity = RideSummaryEntity(
    id = summary.id,
    companyName = summary.companyName,
    initialTax = summary.tariff.initialTax.value.toPlainString(),
    perKmRate = summary.tariff.perKmRate.value.toPlainString(),
    perMinuteStillRate = summary.tariff.perMinuteStillRate.value.toPlainString(),
    waitingCrossoverKilometersPerHour = summary.tariff.waitingCrossoverKilometersPerHour.value.toPlainString(),
    total = summary.total.value.toPlainString(),
    distanceMeters = summary.distanceMeters.toPlainString(),
    travelledDistanceMeters = summary.travelledDistanceMeters?.toPlainString(),
    idleMillis = summary.timeTariffMillis,
    elapsedMillis = summary.elapsedMillis,
    endedElapsedMillis = summary.endedElapsedMillis,
    endedAtUtcMillis = endedAtUtcMillis,
    status = summary.status.name,
)

internal fun RideSummaryEntity.toDomain(): SavedRideSummary = SavedRideSummary(
    summary = RideSummary(
        id = id,
        companyName = companyName,
        tariff = Tariff(
            initialTax.toDecimalAmount(),
            perKmRate.toDecimalAmount(),
            perMinuteStillRate.toDecimalAmount(),
            waitingCrossoverKilometersPerHour.toDecimalAmount(),
        ),
        total = total.toDecimalAmount(),
        distanceMeters = BigDecimal(distanceMeters),
        travelledDistanceMeters = travelledDistanceMeters?.let { BigDecimal(it) },
        timeTariffMillis = idleMillis,
        elapsedMillis = elapsedMillis,
        endedElapsedMillis = endedElapsedMillis,
        status = RideSummary.Status.valueOf(status),
    ),
    endedAtUtcMillis = endedAtUtcMillis,
)

private fun ActiveRideEntity.pointOrNull(): LocationSample? {
    val values = listOf(
        pointLatitude,
        pointLongitude,
        pointAccuracyMeters,
        pointProvider,
        pointFixElapsedMillis,
        pointReceivedElapsedMillis,
    )
    if (values.any { it == null }) return null

    return LocationSample(
        latitude = requireNotNull(pointLatitude),
        longitude = requireNotNull(pointLongitude),
        accuracyMeters = requireNotNull(pointAccuracyMeters),
        provider = LocationSample.Provider.valueOf(requireNotNull(pointProvider)),
        speedMetersPerSecond = pointSpeedMetersPerSecond,
        fixElapsedMillis = requireNotNull(pointFixElapsedMillis),
        receivedElapsedMillis = requireNotNull(pointReceivedElapsedMillis),
    )
}

private fun String.toDecimalAmount(): DecimalAmount = DecimalAmount.of(BigDecimal(this))
