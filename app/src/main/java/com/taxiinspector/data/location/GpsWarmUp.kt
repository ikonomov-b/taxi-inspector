package com.taxiinspector.data.location

/**
 * Keeps the GNSS receiver tracking while a ride is about to be started, and nothing more.
 *
 * The tracking service subscribes only once a ride starts, so until then the chip is idle and
 * every ride begins with a cold acquisition. Measured on a Pixel 8 Pro: ten minutes at a window
 * border without one fix inside the 20 m billing gate, and over three minutes indoors before
 * the first position of any quality arrived. A car pulling away immediately would bill nothing
 * for the first minute or more of the trip — a fare loss, and one that looks in a trace exactly
 * like bad reception rather than like a cold start.
 *
 * Nothing collected here can reach a fare. The samples are dropped, and the foreground service
 * remains the only subscription whose fixes are given to `RideEngine`. This holds no state: the
 * caller's coroutine owns the subscription's lifetime, so it ends when the meter stops being
 * visible or a ride takes over.
 */
class GpsWarmUp internal constructor(private val locationClient: LocationClient) {
    /** Collects and discards until cancelled. */
    suspend fun keepWarm() {
        // A revoked permission or a provider switched off mid-collection is not an error worth
        // surfacing: the meter reports both states itself, and warming up is best effort by
        // definition. It must never be able to disturb the screen that started it.
        runCatching {
            locationClient.locationSamples().collect { }
        }
    }
}
