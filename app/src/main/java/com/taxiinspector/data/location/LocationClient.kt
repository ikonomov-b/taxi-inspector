package com.taxiinspector.data.location

import com.taxiinspector.ride.LocationSample
import kotlinx.coroutines.flow.Flow

/** Android-free boundary consumed by the future tracking service. */
interface LocationClient {
    /** Reads the current state; callers re-check before every Start or Resume. */
    fun isGpsProviderEnabled(): Boolean

    /** A cold stream that owns one GPS subscription for the lifetime of its collector. */
    fun locationSamples(): Flow<LocationSample>
}
