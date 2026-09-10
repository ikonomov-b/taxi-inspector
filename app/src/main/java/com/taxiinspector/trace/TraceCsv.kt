package com.taxiinspector.trace

import java.util.Locale

/**
 * The decision log: one row per recorded event, including the fixes the engine refused. A fix
 * that was dropped and a fix that never arrived look identical without this.
 */
object TraceCsv {
    const val HEADER: String =
        "seq,type,utcMillis,fixElapsedMillis,receivedElapsedMillis,lat,lon,altM,accuracyM," +
            "speedMps,speedAccMps,bearingDeg,band,l5,usedInFix,inView,cn0Used,cn0View," +
            "mock,command,reason,chordM," +
            "significantM,excessSpeedMps,baselineAgeMs,deltaMs,billedAs,distanceM,timeMs," +
            "status,motion,total"

    fun row(row: TraceRow): String {
        val sample = row.sample
        val decision = row.decision
        return listOf(
            row.sequence.toString(),
            row.type.name,
            sample?.utcMillis.orEmpty(),
            sample?.fixElapsedMillis.orEmpty(),
            sample?.receivedElapsedMillis.orEmpty(),
            // Six decimal places is about 0.1 m at this latitude, finer than any fix.
            sample?.latitude.coordinate(),
            sample?.longitude.coordinate(),
            sample?.altitudeMeters.threeDecimals(),
            sample?.accuracyMeters.threeDecimals(),
            sample?.speedMetersPerSecond.threeDecimals(),
            sample?.speedAccuracyMetersPerSecond.threeDecimals(),
            sample?.bearingDegrees.threeDecimals(),
            sample?.band?.name.orEmpty(),
            sample?.signal?.l5SignalCount.orEmpty(),
            // The count the receiver used, not the subset whose carrier frequency is readable.
            sample?.signal?.satellitesUsedInFix.orEmpty(),
            sample?.signal?.satellitesInView.orEmpty(),
            // Carrier-to-noise density in dB-Hz: the number that tells one placement from another.
            sample?.signal?.medianCn0UsedDbHz.threeDecimals(),
            sample?.signal?.medianCn0InViewDbHz.threeDecimals(),
            sample?.isMock?.toString().orEmpty(),
            row.commandLabel.orEmpty(),
            decision?.reason?.name.orEmpty(),
            decision?.chordMeters.threeDecimals(),
            decision?.significantMeters.threeDecimals(),
            decision?.excessSpeedMetersPerSecond.threeDecimals(),
            decision?.baselineAgeMillis.orEmpty(),
            decision?.deltaMillis.orEmpty(),
            decision?.billedAs?.name.orEmpty(),
            row.distanceMeters.toPlainString(),
            row.billedTimeMillis.toString(),
            row.trackingStatus.name,
            row.motionState.name,
            row.total,
        ).joinToString(",")
    }

    private fun Any?.orEmpty(): String = this?.toString() ?: ""

    private fun Double?.coordinate(): String =
        if (this == null) "" else String.format(Locale.ROOT, "%.7f", this)

    private fun Double?.threeDecimals(): String =
        if (this == null) "" else String.format(Locale.ROOT, "%.3f", this)
}
