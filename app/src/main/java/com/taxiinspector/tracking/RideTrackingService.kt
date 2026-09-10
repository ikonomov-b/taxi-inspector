package com.taxiinspector.tracking

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.taxiinspector.TaxiInspectorApplication
import com.taxiinspector.ride.ActiveRide
import kotlinx.coroutines.flow.StateFlow

class RideTrackingService : Service(), TrackingHost, ForegroundSession, ServiceTerminator {
    private lateinit var controller: RideTrackingController
    private lateinit var commandRouter: RideServiceCommandRouter
    private lateinit var notificationFactory: RideNotificationFactory
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        val container = (application as TaxiInspectorApplication).appContainer
        notificationFactory = RideNotificationFactory(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        controller = RideTrackingController(
            repository = container.rideRepository,
            locationClient = container.locationClient,
            prerequisites = AndroidTrackingPrerequisites(applicationContext),
            clock = container.clock,
            host = this,
            trace = container.traceRecorder,
        )
        commandRouter = RideServiceCommandRouter(this, controller, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        commandRouter.onStartCommand(intent?.action)

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        controller.close()
        stop()
        super.onDestroy()
    }

    override fun startPreparing() {
        acquireWakeLock()
        notificationFactory.createChannel()
        val notification = notificationFactory.starting()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                RideNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(RideNotificationFactory.NOTIFICATION_ID, notification)
        }
    }

    override fun updateForegroundNotification(ride: ActiveRide) {
        notificationManager.notify(
            RideNotificationFactory.NOTIFICATION_ID,
            notificationFactory.active(ride),
        )
    }

    override fun stopForegroundAndService() {
        stop()
        stopService()
    }

    override fun stop() {
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * A foreground service keeps the process alive but does not keep the processor awake, so with
     * the screen off the ticker and location delivery can both pause in doze. That no longer
     * changes a fare -- ticks do not bill and the hold is measured on the fix clock -- but it
     * does put gaps in a ride that came from the device sleeping rather than from reception, and
     * a field trace cannot tell those apart afterwards.
     *
     * Held without a timeout on purpose: its lifetime is bounded by the foreground session, and a
     * ride may legitimately last hours.
     */
    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(PowerManager::class.java) ?: return
        wakeLock = manager
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply {
                // Not reference counted, so releasing twice is safe and releasing once is enough.
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun stopService() {
        stopSelf()
    }

    private companion object {
        const val WAKE_LOCK_TAG = "TaxiInspector:ride"
    }

    inner class LocalBinder : Binder(), RideOwnership {
        val state: StateFlow<RideTrackingState>
            get() = controller.state

        override fun ownsRide(rideId: String): Boolean = controller.ownsRide(rideId)
    }
}
