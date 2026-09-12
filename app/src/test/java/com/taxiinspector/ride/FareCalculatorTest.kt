package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FareCalculatorTest {
    private val tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
        waitingCrossoverKilometersPerHour = DecimalAmount.parse("8")!!,
    )

    @Test
    fun `calculates the documented deterministic fare`() {
        val total = FareCalculator.total(
            tariff = tariff,
            trackedDistanceMeters = BigDecimal("2500"),
            timeTariffMillis = 180_000,
        )

        assertEquals("6.45", total.formatTotal(Locale.US))
    }

    @Test
    fun `retains configured precision until display rounding`() {
        val preciseTariff = tariff.copy(perKmRate = DecimalAmount.parse("0.333333")!!)

        val total = FareCalculator.total(preciseTariff, BigDecimal("1000"), 0)

        assertEquals("2.73", total.formatTotal(Locale.US))
    }

    @Test
    fun `compares the two components exactly where the rounded fares do not tie`() {
        // 0.36 per minute against 1.20 per km makes five metres in one second worth exactly the
        // same on either tariff.
        val tied = tariff.copy(perMinuteStillRate = DecimalAmount.parse("0.36")!!)
        val fiveMetres = BigDecimal.valueOf(5.0)

        // Dividing at a fixed internal scale leaves a sixtieth a hair too large, so the two
        // rounded fares disagree in the eighteenth decimal.
        assertTrue(FareCalculator.timeFare(tied, 1_000) > FareCalculator.distanceFare(tied, fiveMetres))

        // Cross-multiplying sees the tie the tariff actually defines, which is what decides
        // which tariff a closed interval is billed on.
        assertEquals(0, FareCalculator.compareDistanceToTimeFare(tied, fiveMetres, 1_000))
    }
}
