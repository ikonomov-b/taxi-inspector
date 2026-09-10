package com.taxiinspector.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class TraceTimeTest {
    @Test
    fun `formats the epoch and a leap day in ISO-8601 UTC`() {
        assertEquals("1970-01-01T00:00:00.000Z", isoUtcMillis(0))
        assertEquals("1970-01-01T00:00:00.001Z", isoUtcMillis(1))
        // A GPX time that disagrees with Locus by a day would silently misalign a comparison.
        assertEquals("2024-02-29T23:59:59.999Z", isoUtcMillis(1_709_251_199_999))
        assertEquals("2026-09-10T12:34:56.789Z", isoUtcMillis(1_789_043_696_789))
    }

    @Test
    fun `handles times before the epoch without drifting a day`() {
        assertEquals("1969-12-31T23:59:59.999Z", isoUtcMillis(-1))
        assertEquals("1969-12-31T00:00:00.000Z", isoUtcMillis(-86_400_000))
    }
}
