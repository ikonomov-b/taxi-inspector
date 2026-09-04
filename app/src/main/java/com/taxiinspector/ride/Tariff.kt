package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount

data class Tariff(
    val initialTax: DecimalAmount,
    val perKmRate: DecimalAmount,
    val perMinuteStillRate: DecimalAmount,
)
