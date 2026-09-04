package com.taxiinspector.core.decimal

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * An exact, non-negative number expressed in the tariff unit chosen by the user.
 * It deliberately has no currency code, symbol, or conversion behaviour.
 */
@JvmInline
value class DecimalAmount private constructor(val value: BigDecimal) : Comparable<DecimalAmount> {
    init {
        require(value.signum() >= 0) { "A tariff amount cannot be negative." }
    }

    override fun compareTo(other: DecimalAmount): Int = value.compareTo(other.value)

    operator fun plus(other: DecimalAmount): DecimalAmount = of(value.add(other.value))

    operator fun times(multiplier: BigDecimal): DecimalAmount = of(value.multiply(multiplier))

    /** Formats a fare total to exactly two places, without a currency label. */
    fun formatTotal(locale: Locale = Locale.getDefault()): String =
        localize(value.setScale(2, RoundingMode.HALF_UP).toPlainString(), locale)

    /** Formats a configured tariff value without redundant fractional zeroes. */
    fun formatConfigured(locale: Locale = Locale.getDefault()): String {
        val normalized = if (value.compareTo(BigDecimal.ZERO) == 0) {
            BigDecimal.ZERO
        } else {
            value.stripTrailingZeros()
        }
        return localize(normalized.toPlainString(), locale)
    }

    companion object {
        val ZERO: DecimalAmount = DecimalAmount(BigDecimal.ZERO)

        fun of(value: BigDecimal): DecimalAmount = DecimalAmount(value)

        /**
         * Parses only the deliberately small tariff-input grammar: ASCII digits with
         * an optional single decimal point or comma and at most six fraction digits.
         */
        fun parse(input: String): DecimalAmount? {
            val normalized = input.trim()
            if (!INPUT_PATTERN.matches(normalized)) return null

            return of(BigDecimal(normalized.replace(',', '.')))
        }

        private fun localize(plainNumber: String, locale: Locale): String {
            val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            return if (separator == '.') plainNumber else plainNumber.replace('.', separator)
        }

        private val INPUT_PATTERN = Regex("[0-9]+(?:[.,][0-9]{1,6})?")
    }
}
