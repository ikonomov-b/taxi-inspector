package com.taxiinspector.data.location

import com.taxiinspector.ride.LocationSample
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsWarmUpTest {
    @Test
    fun `subscribes until cancelled and gives the samples to nobody`() = runBlocking {
        val client = RecordingLocationClient()
        val warmUp = GpsWarmUp(client)

        val job = launch(start = CoroutineStart.UNDISPATCHED) { warmUp.keepWarm() }
        withTimeout(1_000) { client.subscribed.await() }
        client.emit(sample(1_000))
        client.emit(sample(2_000))
        withTimeout(1_000) { while (client.emitted.get() < 2) yield() }

        // Collected and dropped: the point is to keep the receiver tracking, not to measure.
        assertEquals(2, client.emitted.get())
        assertEquals(0, client.cancellations.get())

        job.cancelAndJoin()

        assertEquals(1, client.cancellations.get())
    }

    @Test
    fun `a receiver that refuses the subscription does not fail the caller`() = runBlocking {
        // A revoked permission throws from the platform request. Warming up is best effort, and
        // it must never be able to take down the screen that started it.
        val warmUp = GpsWarmUp(
            object : LocationClient {
                override fun isGpsProviderEnabled(): Boolean = true

                override fun locationSamples(): Flow<LocationSample> =
                    flow { throw SecurityException("permission revoked") }
            },
        )

        warmUp.keepWarm()

        assertTrue("keepWarm returned instead of throwing", true)
    }

    private class RecordingLocationClient : LocationClient {
        val subscribed = CompletableDeferred<Unit>()
        val emitted = AtomicInteger()
        val cancellations = AtomicInteger()
        private val samples = kotlinx.coroutines.channels.Channel<LocationSample>(
            kotlinx.coroutines.channels.Channel.UNLIMITED,
        )

        override fun isGpsProviderEnabled(): Boolean = true

        override fun locationSamples(): Flow<LocationSample> = flow {
            subscribed.complete(Unit)
            try {
                for (sample in samples) {
                    emitted.incrementAndGet()
                    emit(sample)
                }
            } finally {
                cancellations.incrementAndGet()
            }
        }

        suspend fun emit(sample: LocationSample) {
            samples.send(sample)
        }
    }

    private fun sample(elapsedMillis: Long) = LocationSample(
        latitude = 42.6977,
        longitude = 23.3219,
        accuracyMeters = 5.0,
        provider = LocationSample.Provider.Gps,
        speedMetersPerSecond = null,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
    )
}
