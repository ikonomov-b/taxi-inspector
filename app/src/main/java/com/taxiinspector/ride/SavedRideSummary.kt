package com.taxiinspector.ride

/** A history row with the UTC end time used for stable chronological display. */
data class SavedRideSummary(
    val summary: RideSummary,
    val endedAtUtcMillis: Long,
)
