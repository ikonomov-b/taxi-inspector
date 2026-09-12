package com.taxiinspector.ui

import com.taxiinspector.ride.Tariff

/**
 * Tariff values formatted for display: no trailing zeroes, no currency label. Shared by the
 * meter, the company flow, and the anonymous tariff editor, so all three read alike.
 */
data class TariffSummary(
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
    val waitingCrossoverKilometersPerHour: String,
)

internal fun Tariff.toSummary(): TariffSummary = TariffSummary(
    initialTax = initialTax.formatConfigured(),
    perKmRate = perKmRate.formatConfigured(),
    perMinuteStillRate = perMinuteStillRate.formatConfigured(),
    waitingCrossoverKilometersPerHour = waitingCrossoverKilometersPerHour.formatConfigured(),
)
