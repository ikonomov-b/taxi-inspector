package com.taxiinspector.ui.history

import com.taxiinspector.ride.RideSummary
import com.taxiinspector.ride.SavedRideSummary
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DateFormat
import java.text.DecimalFormatSymbols
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Converts durable ride values into locale-aware display strings without recalculating fare. */
internal class RideHistoryFormatter(
    private val locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault(),
) {
    private val dateTimeFormat = DateFormat.getDateTimeInstance(
        DateFormat.MEDIUM,
        DateFormat.SHORT,
        locale,
    ).apply { this.timeZone = timeZone }

    fun historyItem(saved: SavedRideSummary): HistoryRideItem = HistoryRideItem(
        id = saved.summary.id,
        endedAt = formatTimestamp(saved.endedAtUtcMillis),
        total = saved.summary.total.formatTotal(locale),
        distanceKilometres = formatKilometres(saved.summary.distanceMeters),
        status = saved.summary.status.toPresentation(),
    )

    fun detail(saved: SavedRideSummary): RideDetailPresentation = with(saved.summary) {
        RideDetailPresentation(
            id = id,
            endedAt = formatTimestamp(saved.endedAtUtcMillis),
            total = total.formatTotal(locale),
            distanceKilometres = formatKilometres(distanceMeters),
            waitTime = formatDuration(idleMillis),
            elapsedTime = formatDuration(elapsedMillis),
            initialTax = tariff.initialTax.formatConfigured(locale),
            perKmRate = tariff.perKmRate.formatConfigured(locale),
            perMinuteStillRate = tariff.perMinuteStillRate.formatConfigured(locale),
            status = status.toPresentation(),
        )
    }

    private fun formatTimestamp(epochMillis: Long): String = dateTimeFormat.format(Date(epochMillis))

    private fun formatKilometres(meters: BigDecimal): String = localize(
        meters.divide(METERS_PER_KILOMETRE, 2, RoundingMode.HALF_UP).toPlainString(),
    )

    private fun localize(value: String): String {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        return if (separator == '.') value else value.replace('.', separator)
    }

    private fun formatDuration(durationMillis: Long): String {
        val totalSeconds = durationMillis / MILLIS_PER_SECOND
        val hours = totalSeconds / SECONDS_PER_HOUR
        val minutes = (totalSeconds / SECONDS_PER_MINUTE) % SECONDS_PER_MINUTE
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    private fun RideSummary.Status.toPresentation(): HistoryRideStatus = when (this) {
        RideSummary.Status.Completed -> HistoryRideStatus.Completed
        RideSummary.Status.Interrupted -> HistoryRideStatus.Interrupted
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val SECONDS_PER_MINUTE = 60L
        const val SECONDS_PER_HOUR = 3_600L
        val METERS_PER_KILOMETRE: BigDecimal = BigDecimal("1000")
    }
}
