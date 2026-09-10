package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import org.junit.Assert.assertEquals
import org.junit.Test

class TariffTest {
    @Test
    fun `derives the cross-over speed from the two rates`() {
        // 0.35 per minute against 1.20 per km is 17.5 km/h.
        assertEquals(4.8611, crossoverOf(perKm = "1.20", perMinute = "0.35"), 0.0001)
        assertEquals(5.0, crossoverOf(perKm = "1.20", perMinute = "0.36"), 0.0001)
    }

    @Test
    fun `a missing rate puts the cross-over out of reach`() {
        // With no distance rate no speed can ever earn more on distance, and with no time rate
        // every speed does.
        assertEquals(
            Double.POSITIVE_INFINITY,
            crossoverOf(perKm = "0", perMinute = "0.35"),
            0.0,
        )
        assertEquals(0.0, crossoverOf(perKm = "1.20", perMinute = "0"), 0.0)
    }

    private fun crossoverOf(perKm: String, perMinute: String): Double = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse(perKm)!!,
        perMinuteStillRate = DecimalAmount.parse(perMinute)!!,
    ).crossoverSpeedMetersPerSecond
}
