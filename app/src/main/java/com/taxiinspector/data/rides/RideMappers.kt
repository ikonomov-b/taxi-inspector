package com.taxiinspector.data.rides

import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.LocationSample
import com.taxiinspector.ride.MotionState
import com.taxiinspector.ride.RidePhase
import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.SavedRideSummary
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TrackingStatus
import java.math.BigDecimal

internal fun AppSettingsEntity.toDomainTariff(): Tariff = Tariff(
    initialTax = initialTax.toDecimalAmount(),
    perKmRate = perKmRate.toDecimalAmount(),
    perMinuteStillRate = perMinuteStillRate.toDecimalAmount(),
)

internal fun Tariff.toEntity(): AppSettingsEntity = AppSettingsEntity(
    initialTax = initialTax.value.toPlainString(),
    perKmRate = perKmRate.value.toPlainString(),
    perMinuteStillRate = perMinuteStillRate.value.toPlainString(),
)

internal fun ActiveRide.toEntity(): ActiveRideEntity = ActiveRideEntity(
    id = id,
    initialTax = tariff.initialTax.value.toPlainString(),
    perKmRate = tariff.perKmRate.value.toPlainString(),
    perMinuteStillRate = tariff.perMinuteStillRate.value.toPlainString(),
    phase = phase.name,
    trackingStatus = trackingStatus.name,
    distanceMeters = distanceMeters.toPlainString(),
    idleMillis = idleMillis,
    motionState = motionState.name,
    startedElapsedMillis = startedElapsedMillis,
    lastTickElapsedMillis = lastTickElapsedMillis,
    lastAcceptedFixElapsedMillis = lastAcceptedFixElapsedMillis,
    lastFreshBillableReceivedElapsedMillis = lastFreshBillableReceivedElapsedMillis,
    pointLatitude = lastBillablePoint?.latitude,
    pointLongitude = lastBillablePoint?.longitude,
    pointAccuracyMeters = lastBillablePoint?.accuracyMeters,
    pointProvider = lastBillablePoint?.provider?.name,
    pointSpeedMetersPerSecond = lastBillablePoint?.speedMetersPerSecond,
    pointFixElapsedMillis = lastBillablePoint?.fixElapsedMillis,
    pointReceivedElapsedMillis = lastBillablePoint?.receivedElapsedMillis,
    lastSpeedMetersPerSecond = lastSpeedMetersPerSecond,
    lastSpeedReceivedElapsedMillis = lastSpeedReceivedElapsedMillis,
    lowSpeedCandidateMillis = lowSpeedCandidateMillis,
    highSpeedCandidateMillis = highSpeedCandidateMillis,
)

internal fun ActiveRideEntity.toDomain(): ActiveRide = ActiveRide(
    id = id,
    tariff = Tariff(initialTax.toDecimalAmount(), perKmRate.toDecimalAmount(), perMinuteStillRate.toDecimalAmount()),
    phase = RidePhase.valueOf(phase),
    trackingStatus = TrackingStatus.valueOf(trackingStatus),
    distanceMeters = BigDecimal(distanceMeters),
    idleMillis = idleMillis,
    motionState = MotionState.valueOf(motionState),
    startedElapsedMillis = startedElapsedMillis,
    lastTickElapsedMillis = lastTickElapsedMillis,
    lastAcceptedFixElapsedMillis = lastAcceptedFixElapsedMillis,
    lastFreshBillableReceivedElapsedMillis = lastFreshBillableReceivedElapsedMillis,
    lastBillablePoint = pointOrNull(),
    lastSpeedMetersPerSecond = lastSpeedMetersPerSecond,
    lastSpeedReceivedElapsedMillis = lastSpeedReceivedElapsedMillis,
    lowSpeedCandidateMillis = lowSpeedCandidateMillis,
    highSpeedCandidateMillis = highSpeedCandidateMillis,
)

internal fun SavedRideSummary.toEntity(): RideSummaryEntity = RideSummaryEntity(
    id = summary.id,
    initialTax = summary.tariff.initialTax.value.toPlainString(),
    perKmRate = summary.tariff.perKmRate.value.toPlainString(),
    perMinuteStillRate = summary.tariff.perMinuteStillRate.value.toPlainString(),
    total = summary.total.value.toPlainString(),
    distanceMeters = summary.distanceMeters.toPlainString(),
    idleMillis = summary.idleMillis,
    elapsedMillis = summary.elapsedMillis,
    endedElapsedMillis = summary.endedElapsedMillis,
    endedAtUtcMillis = endedAtUtcMillis,
    status = summary.status.name,
)

internal fun RideSummaryEntity.toDomain(): SavedRideSummary = SavedRideSummary(
    summary = RideSummary(
        id = id,
        tariff = Tariff(initialTax.toDecimalAmount(), perKmRate.toDecimalAmount(), perMinuteStillRate.toDecimalAmount()),
        total = total.toDecimalAmount(),
        distanceMeters = BigDecimal(distanceMeters),
        idleMillis = idleMillis,
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
