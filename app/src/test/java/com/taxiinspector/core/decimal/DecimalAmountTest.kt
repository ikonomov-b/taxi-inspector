package com.taxiinspector.core.decimal

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecimalAmountTest {
    @Test
    fun `parses whole numbers and either decimal separator`() {
        assertEquals("2", DecimalAmount.parse("2")?.formatConfigured(Locale.US))
        assertEquals("2.4", DecimalAmount.parse("2.4")?.formatConfigured(Locale.US))
        assertEquals("2.4", DecimalAmount.parse(" 2,40 ")?.formatConfigured(Locale.US))
    }

    @Test
    fun `rejects ambiguous and invalid tariff input`() {
        listOf("", " ", "-2", "+2", "1.2.3", ".5", "2.", "1.1234567")
            .forEach { assertNull("Expected $it to be rejected", DecimalAmount.parse(it)) }
    }

    @Test
    fun `formats totals half up using device decimal separator`() {
        val amount = DecimalAmount.parse("2.345")!!

        assertEquals("2.35", amount.formatTotal(Locale.US))
        assertEquals("2,35", amount.formatTotal(Locale.GERMANY))
    }
}
