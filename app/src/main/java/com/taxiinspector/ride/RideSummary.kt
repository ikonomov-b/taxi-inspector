package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal

data class RideSummary(
    val id: String,
    val tariff: Tariff,
    val total: DecimalAmount,
    val distanceMeters: BigDecimal,
    val idleMillis: Long,
    val elapsedMillis: Long,
    val endedElapsedMillis: Long,
    val status: Status,
) {
    enum class Status { Completed, Interrupted }
}
