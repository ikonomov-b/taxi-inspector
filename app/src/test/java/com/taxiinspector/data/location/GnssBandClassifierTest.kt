package com.taxiinspector.data.location

import com.taxiinspector.ride.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Test

class GnssBandClassifierTest {
    @Test
    fun `enough L5 class signals report dual band`() {
        // GPS L5, Galileo E5a, BeiDou B2a and QZSS L5 all centre on 1176.45 MHz.
        val frequencies = List(3) { L5 } + List(8) { L1 }

        assertEquals(LocationSample.Band.Dual, GnssBandClassifier.classify(frequencies))
    }

    @Test
    fun `secondary frequencies that are not L5 class never count as dual band`() {
        // GPS L2, Galileo E5b, Galileo E6, BeiDou B3 and GLONASS L2 all sit below L1 without
        // being the L5-class signals this app cares about.
        val frequencies = listOf(
            1_227_600_000f,
            1_207_140_000f,
            1_278_750_000f,
            1_268_520_000f,
            1_246_000_000f,
        )

        assertEquals(LocationSample.Band.Single, GnssBandClassifier.classify(frequencies))
    }

    @Test
    fun `fewer L5 class signals than the minimum stays single band`() {
        val frequencies = List(2) { L5 } + List(9) { L1 }

        assertEquals(LocationSample.Band.Single, GnssBandClassifier.classify(frequencies))
    }

    @Test
    fun `a receiver that reports no carrier frequency is unknown rather than single band`() {
        assertEquals(LocationSample.Band.Unknown, GnssBandClassifier.classify(emptyList()))
    }

    @Test
    fun `a carrier just inside the one megahertz tolerance still counts`() {
        val frequencies = List(3) { L5 + 900_000f }

        assertEquals(LocationSample.Band.Dual, GnssBandClassifier.classify(frequencies))
    }

    private companion object {
        const val L1 = 1_575_420_000f
        const val L5 = 1_176_450_000f
    }
}
