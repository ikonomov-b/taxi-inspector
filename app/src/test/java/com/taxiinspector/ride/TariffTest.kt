package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TariffTest {
    @Test
    fun `the waiting crossover is stored, not derived from the two rates`() {
        val tariff = tariffWithCrossover("5")

        assertEquals(5.0 / 3.6, tariff.waitingCrossoverMetersPerSecond, 0.0001)
    }

    @Test
    fun `a zero waiting crossover is refused`() {
        assertThrows(IllegalArgumentException::class.java) { tariffWithCrossover("0") }
    }

    private fun tariffWithCrossover(crossoverKmh: String): Tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
        waitingCrossoverKilometersPerHour = DecimalAmount.parse(crossoverKmh)!!,
    )
}
