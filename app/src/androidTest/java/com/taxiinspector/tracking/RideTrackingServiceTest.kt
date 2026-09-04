package com.taxiinspector.tracking

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import com.taxiinspector.MainActivity
import com.taxiinspector.TaxiInspectorApplication
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.RidePhase
import java.math.BigDecimal
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideTrackingServiceTest {
    private val permissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )
    private val serviceRule = ServiceTestRule()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissionRule).around(serviceRule)

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val repository: RoomRideRepository
        get() = (context.applicationContext as TaxiInspectorApplication).appContainer.rideRepository
    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() = runBlocking {
        repository.currentActiveRide()?.let { repository.discardActiveRide(it.id) }
        repository.saveTariff(
            com.taxiinspector.ride.Tariff(
                DecimalAmount.of(BigDecimal("1.25")),
                DecimalAmount.of(BigDecimal("2.50")),
                DecimalAmount.of(BigDecimal("0.75")),
            ),
        )
        shell("cmd location set-location-enabled true")
    }

    @After
    fun tearDown() = runBlocking {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        repository.currentActiveRide()?.let { repository.discardActiveRide(it.id) }
        context.stopService(Intent(context, RideTrackingService::class.java))
        notificationManager.cancel(RideNotificationFactory.NOTIFICATION_ID)
    }

    @Test
    fun serviceCreatesAnIdleOwnershipBinder() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val binder = serviceRule.bindService(
            Intent(context, RideTrackingService::class.java),
        ) as RideTrackingService.LocalBinder

        assertFalse(binder.ownsRide("not-running"))
        assertEquals(RideTrackingState.Idle, binder.state.value)
    }

    @Test
    fun visibleStartSurvivesScreenRecreationAndNotificationPausePersistsFirst() = runBlocking {
        val screen = startFromVisibleActivity()
        try {
            val active = withTimeout(5_000) {
                repository.observeActiveRide().first { it?.phase == RidePhase.Running }
            }
            assertNotNull(active)
            val binder = serviceRule.bindService(
                Intent(context, RideTrackingService::class.java),
            ) as RideTrackingService.LocalBinder
            assertTrue(binder.ownsRide(requireNotNull(active).id))

            screen.recreate()
            assertTrue(binder.ownsRide(active.id))
            shell("input keyevent KEYCODE_SLEEP")
            delay(1_200)
            assertTrue(binder.ownsRide(active.id))
            assertEquals(RidePhase.Running, repository.currentActiveRide()?.phase)
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            val notification = awaitTrackingNotification()
            val platformNotification = notification.notification
            assertEquals(2, platformNotification.actions.size)
            assertEquals("Pause", platformNotification.actions[0].title.toString())
            assertEquals("Stop & save", platformNotification.actions[1].title.toString())
            assertTrue(platformNotification.flags and Notification.FLAG_ONGOING_EVENT != 0)

            platformNotification.actions[0].actionIntent.send()

            val paused = withTimeout(5_000) {
                repository.observeActiveRide().first { it?.phase == RidePhase.Paused }
            }
            assertEquals(active.id, paused?.id)
            withTimeout(5_000) {
                while (activeTrackingNotification() != null) delay(10)
            }
        } finally {
            screen.close()
        }
    }

    @Test
    fun notificationStopSavesBeforeRemovingTheActiveRide() = runBlocking {
        val screen = startFromVisibleActivity()
        try {
            val active = withTimeout(5_000) {
                repository.observeActiveRide().first { it?.phase == RidePhase.Running }
            }
            val notification = awaitTrackingNotification()

            notification.notification.actions[1].actionIntent.send()

            withTimeout(5_000) {
                repository.observeActiveRide().first { it == null }
            }
            val saved = withTimeout(5_000) {
                repository.observeHistory().first { history ->
                    history.any { it.summary.id == active?.id }
                }
            }.single { it.summary.id == active?.id }
            assertEquals(active?.tariff, saved.summary.tariff)
            assertNull(repository.currentActiveRide())
            withTimeout(5_000) {
                while (activeTrackingNotification() != null) delay(10)
            }
        } finally {
            screen.close()
        }
    }

    private fun startFromVisibleActivity(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario ->
            scenario.onActivity { activity ->
                RideTrackingCommands.sendFromVisibleActivity(activity, RideCommand.Start)
            }
        }

    private suspend fun awaitTrackingNotification(): android.service.notification.StatusBarNotification =
        withTimeout(5_000) {
            while (true) {
                activeTrackingNotification()
                    ?.takeIf { it.notification.actions?.size == 2 }
                    ?.let { return@withTimeout it }
                delay(10)
            }
            error("Unreachable")
        }

    private fun activeTrackingNotification(): android.service.notification.StatusBarNotification? =
        notificationManager.activeNotifications.firstOrNull {
            it.id == RideNotificationFactory.NOTIFICATION_ID
        }

    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
}
