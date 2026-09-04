package com.taxiinspector.data.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taxiinspector.ride.LocationSample
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidGpsLocationClientTest {
    @Test
    fun providerAvailabilityIsReadFromTheGpsSourceEachTime() {
        val source = FakeGpsLocationSource(isEnabled = false)
        val client = AndroidGpsLocationClient(source) { 0 }

        assertFalse(client.isGpsProviderEnabled())
        source.isEnabled = true
        assertTrue(client.isGpsProviderEnabled())
    }

    @Test
    fun collectionRequestsOneSecondGpsUpdatesAndCancellationRemovesTheSameListener() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 0 }

        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect()
        }
        source.awaitSubscription()

        assertEquals(1_000L, source.requestedMinTimeMillis)
        assertEquals(0f, source.requestedMinDistanceMeters)
        val registeredListener = source.listener

        collection.cancelAndJoin()

        assertSame(registeredListener, source.removedListener)
    }

    @Test
    fun gpsFixMapsCoordinatesAccuracySpeedAndElapsedRealtime() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 12_345L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emit(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 42.6977
                longitude = 23.3219
                accuracy = 7.5f
                speed = 3.25f
                elapsedRealtimeNanos = 9_876_543_210L
            },
        )

        val sample = withTimeout(1_000) { received.receive() }
        assertEquals(42.6977, sample.latitude, 0.0)
        assertEquals(23.3219, sample.longitude, 0.0)
        assertEquals(7.5, sample.accuracyMeters, 0.0)
        assertEquals(LocationSample.Provider.Gps, sample.provider)
        assertEquals(3.25, sample.speedMetersPerSecond ?: -1.0, 0.0)
        assertEquals(9_876L, sample.fixElapsedMillis)
        assertEquals(12_345L, sample.receivedElapsedMillis)

        collection.cancelAndJoin()
    }

    @Test
    fun absentSpeedRemainsAbsentAndNonGpsProviderCannotBecomeGps() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emit(
            Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 42.0
                longitude = 23.0
                accuracy = 10f
                elapsedRealtimeNanos = 1_000_000_000L
            },
        )

        val sample = withTimeout(1_000) { received.receive() }
        assertEquals(LocationSample.Provider.Network, sample.provider)
        assertNull(sample.speedMetersPerSecond)

        collection.cancelAndJoin()
    }

    @Test
    fun fixWithoutAccuracyIsDropped() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emit(
            Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 42.0
                longitude = 23.0
                elapsedRealtimeNanos = 1_000_000_000L
            },
        )

        assertNull(withTimeoutOrNull(100) { received.receive() })
        collection.cancelAndJoin()
    }

    private class FakeGpsLocationSource(
        var isEnabled: Boolean = true,
    ) : GpsLocationSource {
        var requestedMinTimeMillis: Long? = null
        var requestedMinDistanceMeters: Float? = null
        var listener: LocationListener? = null
        var removedListener: LocationListener? = null
        private val subscriptionRequested = CompletableDeferred<Unit>()

        override fun isGpsProviderEnabled(): Boolean = isEnabled

        override fun requestGpsUpdates(
            minTimeMillis: Long,
            minDistanceMeters: Float,
            listener: LocationListener,
        ) {
            requestedMinTimeMillis = minTimeMillis
            requestedMinDistanceMeters = minDistanceMeters
            this.listener = listener
            subscriptionRequested.complete(Unit)
        }

        override fun removeUpdates(listener: LocationListener) {
            removedListener = listener
        }

        fun emit(location: Location) {
            checkNotNull(listener).onLocationChanged(location)
        }

        suspend fun awaitSubscription() {
            withTimeout(1_000) { subscriptionRequested.await() }
        }
    }
}
