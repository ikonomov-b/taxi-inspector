package com.taxiinspector.trace

import com.taxiinspector.ride.Tariff
import java.util.Locale

/**
 * What produced a trace. Without it a captured ride cannot be replayed or compared later: the
 * fare depends on the locked tariff, and every distance and timing verdict depends on the engine
 * constants in force when it was recorded.
 */
data class TraceMeta(
    val rideId: String,
    val companyName: String?,
    val tariff: Tariff,
    val engineConstants: Map<String, String>,
    val appVersionName: String,
    val deviceModel: String,
    val androidRelease: String,
    val startedUtcMillis: Long,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("""  "rideId": ${rideId.json()},""")
        appendLine("""  "companyName": ${companyName.json()},""")
        appendLine("""  "initialTax": ${tariff.initialTax.value.toPlainString().json()},""")
        appendLine("""  "perKmRate": ${tariff.perKmRate.value.toPlainString().json()},""")
        appendLine("""  "perMinuteStillRate": ${tariff.perMinuteStillRate.value.toPlainString().json()},""")
        appendLine(
            """  "waitingCrossoverKilometersPerHour": """ +
                tariff.waitingCrossoverKilometersPerHour.value.toPlainString().json() + ",",
        )
        appendLine("""  "appVersionName": ${appVersionName.json()},""")
        appendLine("""  "deviceModel": ${deviceModel.json()},""")
        appendLine("""  "androidRelease": ${androidRelease.json()},""")
        appendLine("""  "startedUtc": ${isoUtcMillis(startedUtcMillis).json()},""")
        appendLine("""  "startedUtcMillis": $startedUtcMillis,""")
        appendLine("""  "engineConstants": {""")
        engineConstants.entries.forEachIndexed { index, (key, value) ->
            val comma = if (index == engineConstants.size - 1) "" else ","
            appendLine("""    ${key.json()}: ${value.json()}$comma""")
        }
        appendLine("  }")
        append("}")
    }

    private fun String?.json(): String {
        if (this == null) return "null"
        val escaped = StringBuilder(length + 2)
        escaped.append('"')
        for (character in this) {
            when (character) {
                '"' -> escaped.append("\\\"")
                '\\' -> escaped.append("\\\\")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                else -> if (character < ' ') {
                    escaped.append(String.format(Locale.ROOT, "\\u%04x", character.code))
                } else {
                    escaped.append(character)
                }
            }
        }
        escaped.append('"')
        return escaped.toString()
    }
}
