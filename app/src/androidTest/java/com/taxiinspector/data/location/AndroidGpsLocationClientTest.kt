package com.taxiinspector.data.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
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
import org.junit.Assert.assertNotSame
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

        // Both callbacks share one thread that is not the main looper: the received timestamp a
        // fix is stamped with decides whether it may bill, so UI work must not be able to delay
        // it, and sharing the thread is what lets the band carry-forward skip synchronisation.
        val callbackLooper = requireNotNull(source.requestedLooper)
        assertNotSame(Looper.getMainLooper(), callbackLooper)
        assertSame(callbackLooper, source.gnssLooper)

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

    @Test
    fun l5SignalsSeenJustBeforeAFixMarkItDualBand() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emitGnssStatus(List(6) { L5_HZ } + List(10) { L1_HZ })
        source.emit(gpsFix())

        val sample = withTimeout(1_000) { received.receive() }
        assertEquals(LocationSample.Band.Dual, sample.band)

        collection.cancelAndJoin()
    }

    @Test
    fun theUsedInFixCountIsTheReceiversOwnAndNotTheReadableFrequencySubset() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        // Nine satellites solved the fix; only two of them report a carrier frequency. Counting
        // the frequency list said two, and on the hardware this was measured on it said zero
        // for positions that plainly existed, since none can be solved from no satellites.
        source.emitGnssStatus(
            carrierFrequenciesHz = List(2) { L5_HZ },
            satellitesInView = 21,
            satellitesUsedInFix = 9,
            cn0UsedDbHz = listOf(30f, 34f, 38f, 44f),
            cn0InViewDbHz = listOf(18f, 22f, 30f, 34f, 38f, 44f),
        )
        source.emit(gpsFix())

        val signal = requireNotNull(withTimeout(1_000) { received.receive() }.signal)
        assertEquals(9, signal.satellitesUsedInFix)
        assertEquals(21, signal.satellitesInView)
        assertEquals(2, signal.l5SignalCount)
        // Medians, so one strong or one dead satellite cannot move the placement metric far.
        assertEquals(36.0, requireNotNull(signal.medianCn0UsedDbHz), 0.001)
        assertEquals(32.0, requireNotNull(signal.medianCn0InViewDbHz), 0.001)

        collection.cancelAndJoin()
    }

    @Test
    fun aReceiverThatReportsNoSignalStrengthLeavesTheMedianAbsentRatherThanZero() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        // A missing carrier-to-noise reading is not a signal of zero strength, and averaging it
        // in as one would make a placement look worse than it is.
        source.emitGnssStatus(
            carrierFrequenciesHz = List(6) { L1_HZ },
            satellitesInView = 8,
            satellitesUsedInFix = 6,
        )
        source.emit(gpsFix())

        val signal = requireNotNull(withTimeout(1_000) { received.receive() }.signal)
        assertEquals(6, signal.satellitesUsedInFix)
        assertNull(signal.medianCn0UsedDbHz)
        assertNull(signal.medianCn0InViewDbHz)

        collection.cancelAndJoin()
    }

    @Test
    fun aBandObservedTooLongBeforeAFixIsNotAttachedToIt() = runBlocking {
        val source = FakeGpsLocationSource()
        var elapsedMillis = 0L
        val client = AndroidGpsLocationClient(source) { elapsedMillis }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emitGnssStatus(List(6) { L5_HZ })
        elapsedMillis = 6_000L
        source.emit(gpsFix())

        val sample = withTimeout(1_000) { received.receive() }
        assertEquals(LocationSample.Band.Unknown, sample.band)

        collection.cancelAndJoin()
    }

    @Test
    fun aFixWithNoSatelliteStatusStaysUnknownRatherThanSingleBand() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emit(gpsFix())

        val sample = withTimeout(1_000) { received.receive() }
        assertEquals(LocationSample.Band.Unknown, sample.band)

        collection.cancelAndJoin()
    }

    @Test
    fun cancellationRemovesTheGnssStatusListenerAsWellAsTheLocationListener() = runBlocking {
        val source = FakeGpsLocationSource()
        val client = AndroidGpsLocationClient(source) { 0 }

        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect()
        }
        source.awaitSubscription()
        val registered = source.gnssListener

        collection.cancelAndJoin()

        assertSame(registered, source.removedGnssListener)
    }

    @Test
    fun aRefusedSatelliteStatusSubscriptionStillDeliversFixes() = runBlocking {
        val source = FakeGpsLocationSource(failGnssRegistration = true)
        val client = AndroidGpsLocationClient(source) { 2_000L }
        val received = Channel<LocationSample>(capacity = 1)
        val collection = launch(start = CoroutineStart.UNDISPATCHED) {
            client.locationSamples().collect { received.send(it) }
        }
        source.awaitSubscription()

        source.emit(gpsFix())

        // Knowing the band is a refinement; losing it must never cost us the ride.
        val sample = withTimeout(1_000) { received.receive() }
        assertEquals(LocationSample.Band.Unknown, sample.band)

        collection.cancelAndJoin()
    }

    private fun gpsFix(): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 42.6977
        longitude = 23.3219
        accuracy = 4f
        speed = 3f
        elapsedRealtimeNanos = 1_000_000_000L
    }

    private class FakeGpsLocationSource(
        var isEnabled: Boolean = true,
        private val failGnssRegistration: Boolean = false,
    ) : GpsLocationSource {
        var requestedMinTimeMillis: Long? = null
        var requestedMinDistanceMeters: Float? = null
        var listener: LocationListener? = null
        var removedListener: LocationListener? = null
        var gnssListener: GnssStatusListener? = null
        var removedGnssListener: GnssStatusListener? = null
        var requestedLooper: Looper? = null
        var gnssLooper: Looper? = null
        private val subscriptionRequested = CompletableDeferred<Unit>()

        override fun isGpsProviderEnabled(): Boolean = isEnabled

        override fun requestGpsUpdates(
            minTimeMillis: Long,
            minDistanceMeters: Float,
            listener: LocationListener,
            looper: Looper,
        ) {
            requestedMinTimeMillis = minTimeMillis
            requestedMinDistanceMeters = minDistanceMeters
            this.listener = listener
            // Which thread the platform would deliver on is the adapter's business; the fake
            // calls back on whichever thread the test emits from.
            requestedLooper = looper
            subscriptionRequested.complete(Unit)
        }

        override fun removeUpdates(listener: LocationListener) {
            removedListener = listener
        }

        override fun registerGnssStatus(listener: GnssStatusListener, looper: Looper) {
            if (failGnssRegistration) throw IllegalStateException("no satellite status here")
            gnssListener = listener
            gnssLooper = looper
        }

        override fun removeGnssStatus(listener: GnssStatusListener) {
            removedGnssListener = listener
        }

        /**
         * [carrierFrequenciesHz] is deliberately allowed to be shorter than
         * [satellitesUsedInFix]: on real hardware the frequency is readable for only some of
         * the satellites a fix used, which is what made the old count untrustworthy.
         */
        fun emitGnssStatus(
            carrierFrequenciesHz: List<Float>,
            satellitesInView: Int = carrierFrequenciesHz.size,
            satellitesUsedInFix: Int = carrierFrequenciesHz.size,
            cn0UsedDbHz: List<Float> = emptyList(),
            cn0InViewDbHz: List<Float> = emptyList(),
        ) {
            checkNotNull(gnssListener).onSatelliteStatus(
                GnssObservation(
                    satellitesInView = satellitesInView,
                    satellitesUsedInFix = satellitesUsedInFix,
                    carrierFrequenciesUsedInFix = carrierFrequenciesHz,
                    cn0UsedDbHz = cn0UsedDbHz,
                    cn0InViewDbHz = cn0InViewDbHz,
                ),
            )
        }

        fun emit(location: Location) {
            checkNotNull(listener).onLocationChanged(location)
        }

        suspend fun awaitSubscription() {
            withTimeout(1_000) { subscriptionRequested.await() }
        }
    }

    private companion object {
        const val L1_HZ = 1_575_420_000f
        const val L5_HZ = 1_176_450_000f
    }
}
