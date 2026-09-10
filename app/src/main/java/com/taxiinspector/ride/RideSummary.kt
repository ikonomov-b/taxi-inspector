package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal

/**
 * [companyName] is the label locked at Start; null marks a ride saved before companies existed.
 * [timeTariffMillis] is the duration billed on the time tariff, committed and provisional
 * together, as it stood when the ride ended.
 */
data class RideSummary(
    val id: String,
    val companyName: String?,
    val tariff: Tariff,
    val total: DecimalAmount,
    val distanceMeters: BigDecimal,
    val timeTariffMillis: Long,
    val elapsedMillis: Long,
    val endedElapsedMillis: Long,
    val status: Status,
) {
    enum class Status { Completed, Interrupted }
}
