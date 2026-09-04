package com.taxiinspector.data.location

import com.taxiinspector.ride.LocationSample
import kotlin.math.abs

/**
 * Decides whether a fix was produced with help from L5-class signals.
 *
 * Android exposes no "this fix was dual-band" flag, so the band is inferred from the
 * carrier frequencies of the signals the receiver reported as used in the fix. GPS L5,
 * Galileo E5a, BeiDou B2a, QZSS L5 and NavIC L5 all centre on 1176.45 MHz, so a single
 * comparison covers every constellation the phone might be tracking.
 *
 * Counting L5-class signals is deliberate rather than checking whether one satellite is
 * tracked on two frequencies at once. The urban benefit this app cares about comes mostly
 * from L5's higher chipping rate rejecting multipath, which applies per signal; needing a
 * dual-tracked satellite would answer a different question about ionospheric correction.
 */
internal object GnssBandClassifier {
    /** GPS L5 / Galileo E5a / BeiDou B2a / QZSS L5 / NavIC L5 all share this centre. */
    private const val L5_CENTRE_HZ = 1_176_450_000.0

    /** Matches the +/-1 MHz tolerance the GPSTest project uses to label carrier frequencies. */
    private const val TOLERANCE_HZ = 1_000_000.0

    /**
     * Below this, the handful of L5 signals present are unlikely to have moved the
     * solution enough to justify a tighter movement threshold.
     */
    private const val MINIMUM_L5_SIGNALS = 4

    /**
     * @param carrierFrequenciesHz carrier frequencies of the signals used in the fix, for
     *   those signals that report one. Empty means the band cannot be determined -- either
     *   nothing is used in the fix yet, or the device does not report carrier frequency.
     */
    fun classify(carrierFrequenciesHz: List<Float>): LocationSample.Band = when {
        carrierFrequenciesHz.isEmpty() -> LocationSample.Band.Unknown
        l5SignalCount(carrierFrequenciesHz) >= MINIMUM_L5_SIGNALS -> LocationSample.Band.Dual
        else -> LocationSample.Band.Single
    }

    /** Exposed so field logging can show how much margin a fix had over [MINIMUM_L5_SIGNALS]. */
    fun l5SignalCount(carrierFrequenciesHz: List<Float>): Int =
        carrierFrequenciesHz.count { isL5Class(it) }

    private fun isL5Class(carrierFrequencyHz: Float): Boolean =
        abs(carrierFrequencyHz.toDouble() - L5_CENTRE_HZ) <= TOLERANCE_HZ
}
