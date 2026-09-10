package com.taxiinspector.trace

import java.util.Locale

/**
 * ISO-8601 UTC, computed rather than formatted. `java.time` needs API 26 or core library
 * desugaring and this app targets API 24 without it, while `SimpleDateFormat` is not safe to
 * share between threads. The arithmetic is Howard Hinnant's `civil_from_days`.
 */
internal fun isoUtcMillis(epochMillis: Long): String {
    val days = Math.floorDiv(epochMillis, MILLIS_PER_DAY)
    val millisOfDay = Math.floorMod(epochMillis, MILLIS_PER_DAY)

    val shifted = days + DAYS_FROM_YEAR_ZERO_TO_EPOCH
    val era = Math.floorDiv(shifted, DAYS_PER_ERA)
    val dayOfEra = shifted - era * DAYS_PER_ERA
    val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val eraYear = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthPosition = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthPosition + 2) / 5 + 1
    val month = if (monthPosition < 10) monthPosition + 3 else monthPosition - 9
    val year = if (month <= 2) eraYear + 1 else eraYear

    return String.format(
        Locale.ROOT,
        "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
        year,
        month,
        day,
        millisOfDay / 3_600_000,
        millisOfDay / 60_000 % 60,
        millisOfDay / 1_000 % 60,
        millisOfDay % 1_000,
    )
}

private const val MILLIS_PER_DAY = 86_400_000L
private const val DAYS_PER_ERA = 146_097L

/** 1970-01-01 is day 719468 of the proleptic Gregorian calendar counted from 0000-03-01. */
private const val DAYS_FROM_YEAR_ZERO_TO_EPOCH = 719_468L
