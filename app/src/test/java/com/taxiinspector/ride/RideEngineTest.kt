package com.taxiinspector.ride

import com.taxiinspector.core.decimal.DecimalAmount
import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Attribution expectations for the reducer. The fixture's waiting crossover is 17.5 km/h
 * (4.8611 m/s), the same figure `1.20`/`0.35` used to derive before the crossover became stored,
 * per-company data (2026-09-12) — chosen so every tuned expectation below stays valid under a
 * fixture edit rather than a re-derivation. Below the crossover the time tariff earns more over
 * any interval; above it the distance tariff does. Every number asserted here was derived from
 * the tariff, not measured from the implementation.
 */
class RideEngineTest {
    private val tariff = Tariff(
        initialTax = DecimalAmount.parse("2.40")!!,
        perKmRate = DecimalAmount.parse("1.20")!!,
        perMinuteStillRate = DecimalAmount.parse("0.35")!!,
        waitingCrossoverKilometersPerHour = DecimalAmount.parse("17.5")!!,
    )
    private val company = TaxiCompany(id = "company-1", name = "Test Taxi", tariff = tariff)

    // --- Kept contracts -----------------------------------------------------------------

    @Test
    fun `gps loss commits the observed hold and clears the baseline`() {
        val ride = driveProfile(fixes = 11) { 0.0 }.tick(25_000)

        assertEquals(TrackingStatus.GpsLost, ride.trackingStatus)
        assertEquals(10_000, ride.timeTariffMillis)
        assertEquals(0, ride.provisionalTimeMillis)
        assertNull(ride.lastBillablePoint)
        assertNull(ride.lastAcceptedFix)
    }

    @Test
    fun `a weak fix never becomes a billable baseline`() {
        val ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 25.0))

        assertEquals(TrackingStatus.Weak, ride.trackingStatus)
        assertNull(ride.lastBillablePoint)
    }

    @Test
    fun `a mock fix never becomes a billable baseline`() {
        val ride = newRide().receive(fix(elapsedMillis = 0, isMock = true))

        assertEquals(TrackingStatus.Weak, ride.trackingStatus)
        assertNull(ride.lastBillablePoint)
    }

    @Test
    fun `a weak fix is a non-observation that changes nothing but the status`() {
        var ride = newRide()
        for (second in 0..10) ride = ride.receive(fix(elapsedMillis = second * 1_000L))
        assertEquals(10_000, ride.billedTimeMillis)

        ride = ride.receive(fix(elapsedMillis = 11_000, accuracyMeters = 25.0))
        ride = ride.receive(fix(elapsedMillis = 12_000, accuracyMeters = 25.0))

        assertEquals(TrackingStatus.Weak, ride.trackingStatus)
        assertEquals(10_000, ride.billedTimeMillis)
        assertEquals(0L, ride.lastBillablePoint?.fixElapsedMillis)

        // The held interval is settled in full by the next billing-quality fix.
        ride = ride.receive(fix(elapsedMillis = 13_000))

        assertEquals(TrackingStatus.Good, ride.trackingStatus)
        assertEquals(13_000, ride.provisionalTimeMillis)
        assertEquals(0L, ride.lastBillablePoint?.fixElapsedMillis)
    }

    @Test
    fun `a good fix after a weak fix bills the whole bridged segment`() {
        var ride = newRide().receive(fix(elapsedMillis = 0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 10.0, accuracyMeters = 25.0))
        ride = ride.receive(fix(elapsedMillis = 2_000, northMeters = 20.0))

        assertEquals(TrackingStatus.Good, ride.trackingStatus)
        assertEquals(20.0, ride.distanceMeters.toDouble(), 0.05)
        assertEquals(2_000L, ride.lastBillablePoint?.fixElapsedMillis)
    }

    @Test
    fun `jitter under the deadband bills time and no distance`() {
        var ride = newRide().receive(fix(elapsedMillis = 0))
        for (second in 1..15) {
            val drift = if (second % 2 == 0) 2.0 else 0.0
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = drift))
        }
        ride = ride.receive(fix(elapsedMillis = 16_000, northMeters = 12.0))

        assertEquals(16_000, ride.billedTimeMillis)
        assertEquals(0, ride.distanceMeters.signum())
        assertEquals(16_000L, ride.lastBillablePoint?.fixElapsedMillis)
    }

    @Test
    fun `gap policy bills below fifteen seconds and freezes at and above it`() {
        val expectedByGap = mapOf(
            5_000L to true,
            14_000L to true,
            15_000L to false,
            16_000L to false,
            30_000L to false,
            60_000L to false,
            120_000L to false,
        )

        expectedByGap.forEach { (gapMillis, shouldBill) ->
            // 100 m over 14 s is 7.1 m/s, still above the cross-over, so distance wins whenever
            // the interval is billable at all.
            var ride = RideEngine.start("ride-$gapMillis", company, 0)
            ride = ride.receive(fix(elapsedMillis = 0))
            ride = ride.receive(fix(elapsedMillis = gapMillis, northMeters = 100.0))

            assertEquals("gap=$gapMillis", shouldBill, ride.distanceMeters.signum() > 0)
        }
    }

    @Test
    fun `location and tick order agree at the fifteen second loss boundary`() {
        val initial = newRide().receive(fix(elapsedMillis = 0))
        val returningFix = RideInput.LocationReceived(
            fix(elapsedMillis = 15_000, northMeters = 100.0),
            15_000,
        )

        val locationThenTick = RideEngine.reduce(
            RideEngine.reduce(initial, returningFix),
            RideInput.Tick(15_000),
        )
        val tickThenLocation = RideEngine.reduce(
            RideEngine.reduce(initial, RideInput.Tick(15_000)),
            returningFix,
        )

        assertEquals(tickThenLocation, locationThenTick)
        assertEquals(TrackingStatus.Good, locationThenTick.trackingStatus)
        assertEquals(0, locationThenTick.distanceMeters.signum())
        assertEquals(0, locationThenTick.billedTimeMillis)
        assertEquals(15_000L, locationThenTick.lastBillablePoint?.fixElapsedMillis)
    }

    @Test
    fun `dual band fixes bill a step a single band fix discards as noise`() {
        // 3 m per half second is 6 m/s, above the cross-over, so the step is distance when the
        // deadband is small enough to resolve it at all.
        //
        // The accuracy here is 1 m, which a phone does not report: now that the deadband spends
        // both endpoints' accuracy, the 2.5 m dual-band floor only decides anything when their
        // sum is under it. The rule is still tested, but see the note on
        // `significantMovementFloorMeters` for why it has stopped mattering in a vehicle.
        fun distanceAfterThreeMetreStep(band: LocationSample.Band): Double {
            var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 1.0, band = band))
            ride = ride.receive(
                fix(elapsedMillis = 500, northMeters = 3.0, accuracyMeters = 1.0, band = band),
            )
            return ride.distanceMeters.toDouble()
        }

        assertEquals(0.0, distanceAfterThreeMetreStep(LocationSample.Band.Single), 0.0)
        assertEquals(0.0, distanceAfterThreeMetreStep(LocationSample.Band.Unknown), 0.0)
        assertEquals(3.0, distanceAfterThreeMetreStep(LocationSample.Band.Dual), 0.05)
    }

    // --- Mode-S attribution -------------------------------------------------------------

    @Test
    fun `a slow crawl bills time and no distance`() {
        val ride = driveProfile(fixes = 60) { 2.0 }

        assertEquals(59_000, ride.billedTimeMillis)
        assertEquals(0, ride.distanceMeters.signum())
        assertEquals(MotionState.Idle, ride.motionState)
    }

    @Test
    fun `travel above the cross-over bills distance and no time`() {
        val ride = driveProfile(fixes = 20) { 12.0 }

        assertEquals(228.0, ride.distanceMeters.toDouble(), 0.1)
        assertEquals(0, ride.billedTimeMillis)
        assertEquals(MotionState.Moving, ride.motionState)
    }

    @Test
    fun `a stop then a departure bills the stop as time and the drive as distance`() {
        val ride = driveProfile(fixes = 30) { if (it < 10) 0.0 else 6.0 }

        // The interval spanning the stop and the pull-away closes on time, whole. At 5 m
        // accuracy the deadband is 10 m, so 6 m/s then closes every second second, 12 m at a
        // time, and the last part-second is absorbed into the hold as time.
        assertEquals(13_000, ride.billedTimeMillis)
        assertEquals(96.0, ride.distanceMeters.toDouble(), 0.1)
    }

    @Test
    fun `a crawl then fast travel bills each interval on its own terms`() {
        val ride = driveProfile(fixes = 60) { if (it < 30) 0.7 else 8.0 }

        assertEquals(31_000, ride.billedTimeMillis)
        assertEquals(224.0, ride.distanceMeters.toDouble(), 0.1)
    }

    @Test
    fun `speeds either side of the cross-over bill on the winning tariff`() {
        // 2 m dual-band fixes clear the 2.5 m floor every second, so every interval closes.
        val above = driveProfile(
            fixes = 60,
            accuracyMeters = 2.0,
            band = LocationSample.Band.Dual,
        ) { CROSSOVER_METERS_PER_SECOND * 1.1 }
        val below = driveProfile(
            fixes = 60,
            accuracyMeters = 2.0,
            band = LocationSample.Band.Dual,
        ) { CROSSOVER_METERS_PER_SECOND * 0.9 }

        assertEquals(315.5, above.distanceMeters.toDouble(), 0.1)
        assertEquals(0, above.billedTimeMillis)
        assertEquals(59_000, below.billedTimeMillis)
        assertEquals(0, below.distanceMeters.signum())
    }

    @Test
    fun `an exact tie bills distance`() {
        // 18 km/h is exactly 5 m/s, and 5 m in one second earns the same on either tariff.
        val tied = tariff.copy(waitingCrossoverKilometersPerHour = DecimalAmount.parse("18")!!)

        assertEquals(
            RideEngine.Attribution.Distance,
            RideEngine.reconcile(tied, BigDecimal.valueOf(5.0), 1_000),
        )
    }

    @Test
    fun `reconcile reads the interval's own tariff, not a shared constant`() {
        // The same 5 m-in-1 s geometry, above one company's crossover and below another's.
        val chord = BigDecimal.valueOf(5.0)
        val lowCrossover = tariff.copy(waitingCrossoverKilometersPerHour = DecimalAmount.parse("3")!!)
        val highCrossover = tariff.copy(waitingCrossoverKilometersPerHour = DecimalAmount.parse("30")!!)

        assertEquals(RideEngine.Attribution.Distance, RideEngine.reconcile(lowCrossover, chord, 1_000))
        assertEquals(RideEngine.Attribution.Time, RideEngine.reconcile(highCrossover, chord, 1_000))
    }

    @Test
    fun `the same crawl bills distance or time depending on the company's own crossover`() {
        // 7 km/h: the exact drive speed that motivated storing the crossover per company. Below
        // this fixture's 17.5 km/h crossover it bills entirely as time; below only a 3 km/h
        // crossover the same drive clears it and bills as distance instead.
        val lowCrossoverCompany = company.copy(
            tariff = tariff.copy(waitingCrossoverKilometersPerHour = DecimalAmount.parse("3")!!),
        )
        val sevenKmh = 7_000.0 / 3_600.0

        val underFixtureCrossover = driveAt(company, sevenKmh, fixes = 60)
        val underLowCrossover = driveAt(lowCrossoverCompany, sevenKmh, fixes = 60)

        assertEquals(0, underFixtureCrossover.distanceMeters.signum())
        assertTrue(underFixtureCrossover.billedTimeMillis > 0)
        assertTrue(underLowCrossover.distanceMeters.signum() > 0)
    }

    @Test
    fun `the default crossover separates a crawl from ordinary driving`() {
        val defaultCompany = company.copy(
            tariff = tariff.copy(
                waitingCrossoverKilometersPerHour =
                    DecimalAmount.parse(Tariff.DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR)!!,
            ),
        )

        val crawl = driveAt(defaultCompany, speedMetersPerSecond = 7_000.0 / 3_600.0, fixes = 60)
        val ordinary = driveAt(defaultCompany, speedMetersPerSecond = 12_000.0 / 3_600.0, fixes = 60)

        // 7 km/h never clears the crossover, so it can only ever bill as time.
        assertEquals(0, crawl.distanceMeters.signum())
        assertTrue(crawl.billedTimeMillis > 0)
        // 12 km/h clears it, so most of the drive bills as distance; a trailing partial hold can
        // still owe a little provisional time, which is why this does not assert an exact zero.
        assertTrue(ordinary.distanceMeters.signum() > 0)
    }

    // --- Travelled distance ---------------------------------------------------------------

    @Test
    fun `a distance-won interval increases both distance figures by the same chord`() {
        var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 20.0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 50.0, accuracyMeters = 20.0))

        assertEquals(50.0, ride.distanceMeters.toDouble(), 0.1)
        assertEquals(ride.distanceMeters, ride.travelledDistanceMeters)
    }

    @Test
    fun `a time-won interval increases travelled distance but not billed distance`() {
        val ride = driveProfile(fixes = 60) { 2.0 }

        assertEquals(0, ride.distanceMeters.signum())
        assertTrue(ride.travelledDistanceMeters.signum() > 0)
        assertTrue(ride.travelledDistanceMeters > ride.distanceMeters)
    }

    @Test
    fun `a held interval increases neither distance figure`() {
        var ride = newRide().receive(fix(elapsedMillis = 0))
        for (second in 1..5) {
            // Half a metre a second, well inside the 10 m deadband: never closes.
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = second * 0.5))
        }

        assertEquals(0, ride.distanceMeters.signum())
        assertEquals(0, ride.travelledDistanceMeters.signum())
    }

    @Test
    fun `an outlier and a relocation do not increase travelled distance`() {
        var ride = newRide()
        for (second in 0..4) {
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0))
        }
        val beforeJump = ride.travelledDistanceMeters
        ride = ride.receive(fix(elapsedMillis = 5_000, northMeters = 60.0, eastMeters = 200.0))
        ride = ride.receive(fix(elapsedMillis = 6_000, northMeters = 72.0, eastMeters = 200.0))

        // Two mutually plausible outliers relocate without billing the jump as travel either.
        assertEquals(beforeJump, ride.travelledDistanceMeters)
    }

    // --- Probe regressions --------------------------------------------------------------

    @Test
    fun `the ten minute jam bills as a mode S meter would at this company's crossover`() {
        // 7 km/h for ten minutes, below this fixture's 17.5 km/h crossover: bills ten minutes
        // of time, 5.90 in total. At a company configured with a lower crossover (below 7 km/h)
        // the same drive would bill as distance instead; see the crossover-dependent tests below.
        val ride = driveProfile(fixes = 601) { 7_000.0 / 3_600.0 }

        assertEquals(600_000, ride.billedTimeMillis)
        assertEquals(0, ride.distanceMeters.signum())
        assertEquals("5.90", total(ride))
    }

    @Test
    fun `a stationary hold bills time whatever the reported speed says`() {
        // An ambiguous Doppler speed and no speed at all must reach the same total: the hold is
        // observed geometrically, and speed takes no part in billing.
        val ambiguous = driveProfile(
            fixes = 61,
            accuracyMeters = 8.0,
            speedAccuracy = 1.0,
            reportedSpeed = { 0.3 },
        ) { 0.0 }
        val silent = driveProfile(fixes = 61, accuracyMeters = 8.0) { 0.0 }

        assertEquals(60_000, ambiguous.billedTimeMillis)
        assertEquals(60_000, silent.billedTimeMillis)
        assertEquals("2.75", total(ambiguous))
        assertEquals("2.75", total(silent))

        val paused = RideEngine.reduce(ambiguous, RideInput.Pause)
        assertEquals(60_000, paused.timeTariffMillis)
        assertEquals(0, paused.provisionalTimeMillis)
    }

    @Test
    fun `weak flicker no longer loses urban distance`() {
        val oneInFive = driveProfile(
            fixes = 61,
            accuracyAt = { if (it % 5 == 4) 25.0 else 12.0 },
        ) { 10.0 }
        val alternating = driveProfile(
            fixes = 61,
            accuracyAt = { if (it % 2 == 0) 12.0 else 25.0 },
        ) { 10.0 }

        assertEquals(600.0, oneInFive.distanceMeters.toDouble(), 0.1)
        assertEquals(600.0, alternating.distanceMeters.toDouble(), 0.1)
    }

    @Test
    fun `creep with three stops bills as a mode S meter would at this company's crossover`() {
        val stopped = { second: Int -> second in 10..29 || second in 50..69 || second in 90..109 }
        val ride = driveProfile(fixes = 121) { if (stopped(it)) 0.0 else 2.0 }

        assertEquals(120_000, ride.billedTimeMillis)
        assertEquals(0, ride.distanceMeters.signum())
        assertEquals("3.10", total(ride))
    }

    // --- Outliers and plausibility ------------------------------------------------------

    @Test
    fun `a single outlier is never billed twice`() {
        // 10 m/s with one fix thrown 200 m ahead: the jump waits for confirmation, the next fix
        // agrees with the track, and only the real 100 m is billed.
        var ride = newRide()
        val states = mutableListOf<ActiveRide>()
        for (second in 0..10) {
            val displaced = if (second == 5) 200.0 else 0.0
            ride = ride.receive(
                fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0 + displaced),
            )
            states += ride
        }

        assertNotNull(states[5].pendingOutlier)
        assertNull(states[6].pendingOutlier)
        assertTrue(states.all { it.trackingStatus == TrackingStatus.Good })
        assertEquals(120.0, ride.distanceMeters.toDouble(), 0.1)
        assertEquals(0, ride.billedTimeMillis)
    }

    @Test
    fun `two mutually plausible outliers relocate without billing the jump`() {
        var ride = newRide()
        var afterRelocation: ActiveRide? = null
        for (second in 0..10) {
            val east = if (second >= 5) 200.0 else 0.0
            ride = ride.receive(
                fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0, eastMeters = east),
            )
            if (second == 6) afterRelocation = ride
        }

        // The relocation itself is not distance: 48 m before the jump, 48 m after it.
        assertEquals(6_000L, afterRelocation?.lastBillablePoint?.fixElapsedMillis)
        assertEquals(48.0, afterRelocation!!.distanceMeters.toDouble(), 0.1)
        assertEquals(96.0, ride.distanceMeters.toDouble(), 0.1)
        assertEquals(0, ride.billedTimeMillis)
    }

    @Test
    fun `three mutually implausible fixes relocate at the streak cap`() {
        var ride = newRide()
        for (second in 0..4) {
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0))
        }
        // No two of these agree with each other, so only the streak cap can end the freeze.
        for ((index, east) in listOf(200.0, 400.0, 600.0).withIndex()) {
            ride = ride.receive(fix(elapsedMillis = (5 + index) * 1_000L, eastMeters = east))
        }

        assertEquals(7_000L, ride.lastBillablePoint?.fixElapsedMillis)
        assertEquals(0, ride.outlierStreak)
        assertNull(ride.pendingOutlier)
        assertEquals(48.0, ride.distanceMeters.toDouble(), 0.1)
    }

    @Test
    fun `an outlier burst refreshes the loss timer without moving the baseline`() {
        var ride = newRide()
        for (second in 0..4) {
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0))
        }
        ride = ride.receive(fix(elapsedMillis = 5_000, northMeters = 60.0, eastMeters = 200.0))
        ride = ride.receive(fix(elapsedMillis = 6_000, northMeters = 72.0, eastMeters = -200.0))

        assertEquals(4_000L, ride.lastBillablePoint?.fixElapsedMillis)
        assertEquals(2, ride.outlierStreak)

        for (second in 7..10) {
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0))
        }

        // The burst kept the ride alive and the real track was billed across it.
        assertEquals(TrackingStatus.Good, ride.trackingStatus)
        assertEquals(120.0, ride.distanceMeters.toDouble(), 0.1)
    }

    @Test
    fun `a long gap bills a plausible chord and doubts an implausible one`() {
        fun distanceOver14Seconds(meters: Double): ActiveRide {
            val ride = newRide().receive(fix(elapsedMillis = 0))
            return ride.receive(fix(elapsedMillis = 14_000, northMeters = meters))
        }

        assertEquals(700.0, distanceOver14Seconds(700.0).distanceMeters.toDouble(), 0.1)
        assertEquals(0, distanceOver14Seconds(900.0).distanceMeters.signum())
        assertNotNull(distanceOver14Seconds(900.0).pendingOutlier)
    }

    @Test
    fun `a reported speed tightens the plausibility bound`() {
        // 10 +/- 1 m/s allows 26 m/s of implied speed; 50 m in one second needs 40 after the
        // accuracy budget, 30 m needs only 20.
        fun distanceAfterOneSecond(meters: Double): ActiveRide {
            val ride = newRide().receive(fix(elapsedMillis = 0, speed = 10.0, speedAccuracy = 1.0))
            return ride.receive(
                fix(elapsedMillis = 1_000, northMeters = meters, speed = 10.0, speedAccuracy = 1.0),
            )
        }

        assertEquals(0, distanceAfterOneSecond(50.0).distanceMeters.signum())
        assertEquals(30.0, distanceAfterOneSecond(30.0).distanceMeters.toDouble(), 0.1)
    }

    @Test
    fun `loose accuracy with a trusted zero speed never makes a fix an outlier`() {
        // Standing still at 20 m accuracy: consecutive fixes differ by tens of metres and the
        // plausibility budget, which allows both endpoints their own accuracy, absorbs all of it.
        val ride = driveProfile(
            fixes = 20,
            accuracyMeters = 20.0,
            speedAccuracy = 0.5,
            reportedSpeed = { 0.0 },
            positionAt = { if (it % 2 == 0) 0.0 else 30.0 },
        )

        assertNull(ride.pendingOutlier)
        assertEquals(0, ride.outlierStreak)
        assertEquals(TrackingStatus.Good, ride.trackingStatus)
    }

    @Test
    fun `jitter inside the combined accuracy of two fixes is not billed as distance`() {
        // The deadband spends both endpoints' accuracy, so at 20 m accuracy a 30 m excursion
        // cannot be told from noise and bills as tariff time, not as 30 m/s of travel. Taking
        // only the larger accuracy billed each of these nineteen jumps and advanced the baseline
        // each time, so standing still cost 570 m.
        val ride = driveProfile(
            fixes = 20,
            accuracyMeters = 20.0,
            speedAccuracy = 0.5,
            reportedSpeed = { 0.0 },
            positionAt = { if (it % 2 == 0) 0.0 else 30.0 },
        )

        assertEquals(0, ride.distanceMeters.signum())
        assertEquals(19_000, ride.billedTimeMillis)
    }

    @Test
    fun `movement clear of the combined accuracy is still billed at the same accuracy`() {
        // The veto is a noise floor, not a suppression: 50 m in one second at 20 m accuracy is
        // 10 m beyond anything the two fixes can explain, so it bills.
        var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 20.0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 50.0, accuracyMeters = 20.0))

        assertEquals(50.0, ride.distanceMeters.toDouble(), 0.1)
        assertEquals(0, ride.billedTimeMillis)
    }

    // --- Label latency ------------------------------------------------------------------

    @Test
    fun `a hard stop bills every second and turns the label idle`() {
        // 8 m/s at 10 m accuracy clears the 20 m deadband on the third second; the close lands
        // on the stop fix.
        var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 10.0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 8.0, accuracyMeters = 10.0))
        ride = ride.receive(fix(elapsedMillis = 2_000, northMeters = 16.0, accuracyMeters = 10.0))
        assertEquals(0, ride.distanceMeters.signum())
        ride = ride.receive(fix(elapsedMillis = 3_000, northMeters = 24.0, accuracyMeters = 10.0))
        assertEquals(24.0, ride.distanceMeters.toDouble(), 0.05)
        assertEquals(MotionState.Moving, ride.motionState)

        val billed = mutableListOf<Long>()
        val labels = mutableListOf<MotionState>()
        for (second in 4..9) {
            ride = ride.receive(
                fix(elapsedMillis = second * 1_000L, northMeters = 24.0, accuracyMeters = 10.0),
            )
            billed += ride.billedTimeMillis
            labels += ride.motionState
        }

        // Billing latency is one fix from the stop. The label waits until the time tariff
        // out-earns the whole deadband, which is five seconds once the deadband is 20 m.
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L), billed)
        assertEquals(
            listOf(
                MotionState.Moving,
                MotionState.Moving,
                MotionState.Moving,
                MotionState.Moving,
                MotionState.Idle,
                MotionState.Idle,
            ),
            labels,
        )
        assertEquals(24.0, ride.distanceMeters.toDouble(), 0.05)
    }

    @Test
    fun `a tighter deadband settles the label sooner`() {
        // 5 m accuracy makes the deadband 10 m, which the time tariff out-earns in three
        // seconds instead of five.
        var ride = newRide().receive(fix(elapsedMillis = 0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 12.0))
        assertEquals(MotionState.Moving, ride.motionState)

        ride = ride.receive(fix(elapsedMillis = 2_000, northMeters = 12.0))
        assertEquals(MotionState.Moving, ride.motionState)
        ride = ride.receive(fix(elapsedMillis = 3_000, northMeters = 12.0))
        assertEquals(MotionState.Moving, ride.motionState)
        ride = ride.receive(fix(elapsedMillis = 4_000, northMeters = 12.0))
        assertEquals(MotionState.Idle, ride.motionState)
    }

    @Test
    fun `the tick fallback labels idle during a weak stretch`() {
        var ride = newRide()
        for (second in 0..5) {
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = second * 12.0))
        }
        assertEquals(MotionState.Moving, ride.motionState)

        ride = ride.receive(fix(elapsedMillis = 6_000, northMeters = 72.0, accuracyMeters = 25.0))
        assertEquals(TrackingStatus.Weak, ride.trackingStatus)

        val distanceBefore = ride.distanceMeters
        assertEquals(MotionState.Moving, ride.tick(9_000).motionState)
        val late = ride.tick(9_000).tick(10_000)

        assertEquals(MotionState.Idle, late.motionState)
        assertEquals(TrackingStatus.Weak, late.trackingStatus)
        assertEquals(distanceBefore, late.distanceMeters)
        assertEquals(0, late.billedTimeMillis)
    }

    @Test
    fun `a trusted slow speed labels idle in one fix`() {
        var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 10.0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 24.0, accuracyMeters = 10.0))
        assertEquals(MotionState.Moving, ride.motionState)

        val silent = ride.receive(
            fix(elapsedMillis = 2_000, northMeters = 24.0, accuracyMeters = 10.0),
        )
        val doppler = ride.receive(
            fix(
                elapsedMillis = 2_000,
                northMeters = 24.0,
                accuracyMeters = 10.0,
                speed = 0.3,
                speedAccuracy = 1.0,
            ),
        )

        // Geometry alone cannot separate a crawl from a stop this soon; a trusted speed can.
        assertEquals(MotionState.Moving, silent.motionState)
        assertEquals(MotionState.Idle, doppler.motionState)
    }

    @Test
    fun `a trusted fast speed labels moving before the first distance close`() {
        val seeded = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 10.0))
        val silent = seeded.receive(
            fix(elapsedMillis = 1_000, northMeters = 8.0, accuracyMeters = 10.0),
        )
        val doppler = seeded.receive(
            fix(
                elapsedMillis = 1_000,
                northMeters = 8.0,
                accuracyMeters = 10.0,
                speed = 15.0,
                speedAccuracy = 1.0,
            ),
        )

        assertEquals(MotionState.Idle, silent.motionState)
        assertEquals(MotionState.Moving, doppler.motionState)
        assertEquals(0, doppler.distanceMeters.signum())
    }

    @Test
    fun `a time close whose last sub-interval is fast labels moving`() {
        var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 10.0))
        for (second in 1..9) {
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, accuracyMeters = 10.0))
        }
        ride = ride.receive(fix(elapsedMillis = 10_000, northMeters = 30.0, accuracyMeters = 10.0))

        // The whole ten-second interval goes to time, but the vehicle is plainly moving now.
        assertEquals(10_000, ride.timeTariffMillis)
        assertEquals(0, ride.distanceMeters.signum())
        assertEquals(MotionState.Moving, ride.motionState)
    }

    @Test
    fun `a departure after a stop closes on the time tariff`() {
        // Five seconds stopped, then pulling away at 2 m/s squared, which covers t squared
        // metres. At 10 m accuracy the 20 m deadband is cleared five seconds after the car
        // moves, and the stop it is measured across makes the whole interval time.
        var ride = newRide().receive(fix(elapsedMillis = 0, accuracyMeters = 10.0))
        for (second in 1..10) {
            val moving = (second - 5).coerceAtLeast(0)
            ride = ride.receive(
                fix(
                    elapsedMillis = second * 1_000L,
                    northMeters = (moving * moving).toDouble(),
                    accuracyMeters = 10.0,
                ),
            )
        }

        assertEquals(10_000L, ride.lastBillablePoint?.fixElapsedMillis)
        assertEquals(10_000, ride.timeTariffMillis)
        assertEquals(0, ride.distanceMeters.signum())
    }

    // --- Commit rules -------------------------------------------------------------------

    @Test
    fun `permission loss commits the observed hold`() {
        val ride = RideEngine.reduce(driveProfile(fixes = 31) { 0.0 }, RideInput.PermissionRevoked)

        assertEquals(RidePhase.Paused, ride.phase)
        assertEquals(TrackingStatus.PermissionNeeded, ride.trackingStatus)
        assertEquals(30_000, ride.timeTariffMillis)
        assertEquals(0, ride.provisionalTimeMillis)
    }

    @Test
    fun `gps loss commits the hold and leaves the total unchanged`() {
        val holding = driveProfile(fixes = 31) { 0.0 }
        val lost = holding.tick(45_000)

        assertEquals(TrackingStatus.GpsLost, lost.trackingStatus)
        assertEquals(30_000, lost.timeTariffMillis)
        assertEquals(total(holding), total(lost))
    }

    @Test
    fun `finish and interrupt both include the provisional hold`() {
        val holding = driveProfile(fixes = 61, accuracyMeters = 8.0) { 0.0 }

        val summary = RideEngine.finish(holding, 60_000)
        assertEquals(60_000, summary.timeTariffMillis)
        assertEquals("2.75", summary.total.formatTotal(Locale.US))
        // Stationary the whole ride: no interval ever closes, so nothing was travelled either.
        assertEquals(0, summary.travelledDistanceMeters?.signum())

        val interrupted = RideEngine.interrupt(holding)
        assertEquals(RidePhase.PendingInterrupted, interrupted.phase)
        assertEquals(60_000, interrupted.timeTariffMillis)
        assertEquals(0, interrupted.provisionalTimeMillis)
        assertNull(interrupted.lastBillablePoint)
        assertNull(interrupted.lastAcceptedFix)
    }

    @Test
    fun `finish carries travelled distance into the summary`() {
        val ride = driveProfile(fixes = 60) { 2.0 }

        val summary = RideEngine.finish(ride, 59_000)

        assertEquals(ride.travelledDistanceMeters, summary.travelledDistanceMeters)
        assertTrue(summary.travelledDistanceMeters!!.signum() > 0)
    }

    @Test
    fun `resume starts a new baseline and keeps the committed total`() {
        val paused = RideEngine.reduce(driveProfile(fixes = 31) { 0.0 }, RideInput.Pause)
        val resumed = RideEngine.reduce(paused, RideInput.Resume(40_000))

        assertEquals(RidePhase.Running, resumed.phase)
        assertEquals(TrackingStatus.Searching, resumed.trackingStatus)
        assertEquals(30_000, resumed.billedTimeMillis)
        assertNull(resumed.lastBillablePoint)
        assertNull(resumed.lastAcceptedFix)
    }

    // --- Non-observations ---------------------------------------------------------------

    @Test
    fun `an out of order fix is ignored`() {
        var ride = newRide().receive(fix(elapsedMillis = 0))
        ride = ride.receive(fix(elapsedMillis = 5_000, northMeters = 50.0))

        assertEquals(ride, ride.receive(fix(elapsedMillis = 3_000, northMeters = 30.0)))
    }

    @Test
    fun `a stale fix is weak and keeps the baseline`() {
        var ride = newRide().receive(fix(elapsedMillis = 0))
        ride = ride.receive(fix(elapsedMillis = 1_000, northMeters = 100.0), nowElapsedMillis = 7_000)

        assertEquals(TrackingStatus.Weak, ride.trackingStatus)
        assertEquals(0L, ride.lastBillablePoint?.fixElapsedMillis)
        assertEquals(0, ride.distanceMeters.signum())
    }

    @Test
    fun `weak fixes do not reset the loss timer`() {
        var ride = newRide().receive(fix(elapsedMillis = 0))
        for (elapsed in listOf(5_000L, 10_000L, 14_000L)) {
            ride = ride.receive(fix(elapsedMillis = elapsed, accuracyMeters = 25.0))
        }

        assertEquals(TrackingStatus.GpsLost, ride.tick(15_000).trackingStatus)
    }

    // --- Properties ---------------------------------------------------------------------

    @Test
    fun `distance and tariff time partition the tracked interval`() {
        var ride = newRide()
        var distanceTimeMillis = 0L
        var firstFixElapsedMillis: Long? = null
        var travelled = 0.0

        for (second in 0 until 60) {
            val before = ride
            ride = ride.receive(fix(elapsedMillis = second * 1_000L, northMeters = travelled))
            travelled += mixedProfileSpeed(second)

            if (ride.distanceMeters > before.distanceMeters) {
                distanceTimeMillis += ride.lastBillablePoint!!.fixElapsedMillis -
                    before.lastBillablePoint!!.fixElapsedMillis
            }
            if (firstFixElapsedMillis == null) {
                firstFixElapsedMillis = ride.lastBillablePoint?.fixElapsedMillis
            }

            // Every millisecond between the first and the latest accepted fix is attributed to
            // exactly one tariff. Nothing is billed twice and nothing is silently dropped.
            assertEquals(
                "second=$second",
                ride.lastAcceptedFix!!.fixElapsedMillis - firstFixElapsedMillis!!,
                ride.billedTimeMillis + distanceTimeMillis,
            )

            // Travelled distance is a superset of billed distance: never smaller, and equal to
            // it plus whatever a closed interval redirected to the time tariff instead.
            assertTrue("second=$second", ride.travelledDistanceMeters >= ride.distanceMeters)
        }
    }

    @Test
    fun `the total never decreases across weak fixes an outlier a dropout and a pause`() {
        var ride = newRide()
        var previous = FareCalculator.total(tariff, BigDecimal.ZERO, 0).value
        var travelled = 0.0

        fun advance(next: ActiveRide, label: String) {
            val total = FareCalculator.total(next.tariff, next.distanceMeters, next.billedTimeMillis)
            assertTrue("$label lowered the total", total.value >= previous)
            previous = total.value
            ride = next
        }

        for (second in 0 until 60) {
            val elapsed = second * 1_000L
            val weak = second % 11 == 7
            val displaced = if (second == 23) 300.0 else 0.0
            if (second !in 30..49) {
                advance(
                    ride.receive(
                        fix(
                            elapsedMillis = elapsed,
                            northMeters = travelled + displaced,
                            accuracyMeters = if (weak) 25.0 else 5.0,
                        ),
                    ),
                    "fix at $second",
                )
            }
            advance(ride.tick(elapsed + 500), "tick at $second")
            if (second == 55) {
                advance(RideEngine.reduce(ride, RideInput.Pause), "pause")
                advance(RideEngine.reduce(ride, RideInput.Resume(elapsed + 700)), "resume")
            }
            travelled += mixedProfileSpeed(second)
        }
    }

    // --- Decisions ----------------------------------------------------------------------

    @Test
    fun `each input reports why it did what it did`() {
        fun reasonOf(ride: ActiveRide, sample: LocationSample, now: Long = sample.fixElapsedMillis) =
            RideEngine.step(ride, RideInput.LocationReceived(sample, now)).decision?.reason

        val seeded = newRide()
        assertEquals(
            RideDecision.Reason.Seeded,
            reasonOf(seeded, fix(elapsedMillis = 0)),
        )
        val running = seeded.receive(fix(elapsedMillis = 0))
        assertEquals(
            RideDecision.Reason.Held,
            reasonOf(running, fix(elapsedMillis = 1_000, northMeters = 1.0)),
        )
        assertEquals(
            RideDecision.Reason.ClosedDistance,
            reasonOf(running, fix(elapsedMillis = 1_000, northMeters = 20.0)),
        )
        assertEquals(
            RideDecision.Reason.ClosedTime,
            reasonOf(running, fix(elapsedMillis = 10_000, northMeters = 12.0)),
        )
        assertEquals(
            RideDecision.Reason.Outlier,
            reasonOf(running, fix(elapsedMillis = 1_000, northMeters = 300.0)),
        )
        assertEquals(
            RideDecision.Reason.RejectedAccuracy,
            reasonOf(running, fix(elapsedMillis = 1_000, accuracyMeters = 25.0)),
        )
        assertEquals(
            RideDecision.Reason.RejectedMock,
            reasonOf(running, fix(elapsedMillis = 1_000, isMock = true)),
        )
        assertEquals(
            RideDecision.Reason.RejectedNonGps,
            reasonOf(
                running,
                fix(elapsedMillis = 1_000, provider = LocationSample.Provider.Network),
            ),
        )
        assertEquals(
            RideDecision.Reason.RejectedStale,
            reasonOf(running, fix(elapsedMillis = 1_000), now = 7_000),
        )
        assertEquals(
            RideDecision.Reason.RejectedOutOfOrder,
            reasonOf(running, fix(elapsedMillis = 0)),
        )
        assertEquals(
            RideDecision.Reason.GpsLostReset,
            RideEngine.step(running, RideInput.Tick(20_000)).decision?.reason,
        )
    }

    // --- Helpers ------------------------------------------------------------------------

    private fun newRide() = RideEngine.start("ride-1", company, 0)

    /** Like [driveProfile], but for a company other than the class-level fixture. */
    private fun driveAt(
        drivingCompany: TaxiCompany,
        speedMetersPerSecond: Double,
        fixes: Int,
    ): ActiveRide {
        var ride = RideEngine.start("crossover-ride", drivingCompany, 0)
        var travelled = 0.0
        for (index in 0 until fixes) {
            ride = ride.receive(fix(elapsedMillis = index * 1_000L, northMeters = travelled))
            travelled += speedMetersPerSecond
        }
        return ride
    }

    private fun ActiveRide.receive(
        sample: LocationSample,
        nowElapsedMillis: Long = sample.receivedElapsedMillis,
    ): ActiveRide = RideEngine.reduce(this, RideInput.LocationReceived(sample, nowElapsedMillis))

    private fun ActiveRide.tick(nowElapsedMillis: Long): ActiveRide =
        RideEngine.reduce(this, RideInput.Tick(nowElapsedMillis))

    private fun total(ride: ActiveRide): String =
        FareCalculator.total(ride.tariff, ride.distanceMeters, ride.billedTimeMillis)
            .formatTotal(Locale.US)

    /** The mixed profile the two property tests share: still, fast, crawl, fast, still, fast. */
    private fun mixedProfileSpeed(second: Int): Double = when (second / 10) {
        0 -> 0.0
        1 -> 6.0
        2 -> 2.0
        3 -> 10.0
        4 -> 0.0
        else -> 8.0
    }

    /**
     * Drives [fixes] fixes at [intervalMillis], advancing the position by [metersPerSecondAt] or
     * placing it directly with [positionAt].
     */
    private fun driveProfile(
        fixes: Int,
        accuracyMeters: Double = 5.0,
        band: LocationSample.Band = LocationSample.Band.Unknown,
        speedAccuracy: Double? = null,
        intervalMillis: Long = 1_000,
        reportedSpeed: (Int) -> Double? = { null },
        accuracyAt: (Int) -> Double = { accuracyMeters },
        positionAt: ((Int) -> Double)? = null,
        metersPerSecondAt: (Int) -> Double = { 0.0 },
    ): ActiveRide {
        var ride = newRide()
        var travelled = 0.0
        for (index in 0 until fixes) {
            ride = ride.receive(
                fix(
                    elapsedMillis = index * intervalMillis,
                    northMeters = positionAt?.invoke(index) ?: travelled,
                    accuracyMeters = accuracyAt(index),
                    speed = reportedSpeed(index),
                    speedAccuracy = speedAccuracy,
                    band = band,
                ),
            )
            travelled += metersPerSecondAt(index) * intervalMillis / 1_000.0
        }
        return ride
    }

    /**
     * Metres are converted through the WGS84 scale at the test latitude, taken from [Geodesic]
     * itself, so a nominal step really is that many metres. The spherical 111 320 m per degree
     * is 0.19 % long here, which is enough to place a nominal five-metre step under a
     * five-metre deadband.
     */
    private fun fix(
        elapsedMillis: Long,
        northMeters: Double = 0.0,
        eastMeters: Double = 0.0,
        accuracyMeters: Double = 5.0,
        speed: Double? = null,
        speedAccuracy: Double? = null,
        band: LocationSample.Band = LocationSample.Band.Unknown,
        provider: LocationSample.Provider = LocationSample.Provider.Gps,
        isMock: Boolean = false,
    ) = LocationSample(
        latitude = BASE_LATITUDE + northMeters / METERS_PER_DEGREE_LATITUDE,
        longitude = BASE_LONGITUDE + eastMeters / METERS_PER_DEGREE_LONGITUDE,
        accuracyMeters = accuracyMeters,
        provider = provider,
        speedMetersPerSecond = speed,
        fixElapsedMillis = elapsedMillis,
        receivedElapsedMillis = elapsedMillis,
        band = band,
        speedAccuracyMetersPerSecond = speedAccuracy,
        isMock = isMock,
    )

    private companion object {
        const val BASE_LATITUDE = 42.6977
        const val BASE_LONGITUDE = 23.3219

        /** The fixture's own stored crossover, 17.5 km/h. */
        const val CROSSOVER_METERS_PER_SECOND = 17.5 / 3.6

        val METERS_PER_DEGREE_LATITUDE = Geodesic.distanceMeters(
            BASE_LATITUDE,
            BASE_LONGITUDE,
            BASE_LATITUDE + 0.001,
            BASE_LONGITUDE,
        ) * 1_000.0

        val METERS_PER_DEGREE_LONGITUDE = Geodesic.distanceMeters(
            BASE_LATITUDE,
            BASE_LONGITUDE,
            BASE_LATITUDE,
            BASE_LONGITUDE + 0.001,
        ) * 1_000.0
    }
}
