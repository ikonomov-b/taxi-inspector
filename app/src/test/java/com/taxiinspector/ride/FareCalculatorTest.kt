package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FareCalculatorTest {
    private val tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
    )

    @Test
    fun `calculates the documented deterministic fare`() {
        val total = FareCalculator.total(
            tariff = tariff,
            trackedDistanceMeters = BigDecimal("2500"),
            idleMillis = 180_000,
        )

        assertEquals("6.45", total.formatTotal(Locale.US))
    }

    @Test
    fun `retains configured precision until display rounding`() {
        val preciseTariff = tariff.copy(perKmRate = DecimalAmount.parse("0.333333")!!)

        val total = FareCalculator.total(preciseTariff, BigDecimal("1000"), 0)

        assertEquals("2.73", total.formatTotal(Locale.US))
    }
}
