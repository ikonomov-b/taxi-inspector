package com.taxiinspector.ride

/**
 * The GNSS constellation as it stood when a fix was produced.
 *
 * Diagnostics only. No billing rule may read any of it, and the engine's single signal input
 * remains [LocationSample.band].
 *
 * [satellitesUsedInFix] is the count the receiver actually used, which is not the same as the
 * number whose carrier frequency happens to be readable. An earlier version conflated the two
 * and so reported zero satellites for positions that plainly existed, since no position can be
 * solved from none.
 *
 * [medianCn0UsedDbHz] and [medianCn0InViewDbHz] are carrier-to-noise densities in dB-Hz, over
 * the satellites used in the fix and over everything in view. They are the metric for comparing
 * one phone placement with another: a cradle behind a windscreen's transponder patch beats the
 * middle of an athermic screen by several dB, and nothing else a trace records shows that. Both
 * are recorded because the populations differ, and a single number whose meaning changed with
 * the fix state would be a trap.
 */
data class SignalQuality(
    val satellitesInView: Int,
    val satellitesUsedInFix: Int,
    val l5SignalCount: Int,
    val medianCn0UsedDbHz: Double?,
    val medianCn0InViewDbHz: Double?,
) {
    init {
        require(satellitesInView >= 0) { "Satellites in view cannot be negative." }
        require(satellitesUsedInFix >= 0) { "Satellites used in fix cannot be negative." }
        require(l5SignalCount >= 0) { "Signal count cannot be negative." }
    }
}
