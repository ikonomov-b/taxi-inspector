package com.taxiinspector.tracking

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.taxiinspector.TaxiInspectorApplication
import com.taxiinspector.ride.ActiveRide
import kotlinx.coroutines.flow.StateFlow

class RideTrackingService : Service(), TrackingHost, ForegroundSession, ServiceTerminator {
    private lateinit var controller: RideTrackingController
    private lateinit var commandRouter: RideServiceCommandRouter
    private lateinit var notificationFactory: RideNotificationFactory
    private lateinit var notificationManager: NotificationManager
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
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun stopService() {
        stopSelf()
    }

    inner class LocalBinder : Binder(), RideOwnership {
        val state: StateFlow<RideTrackingState>
            get() = controller.state

        override fun ownsRide(rideId: String): Boolean = controller.ownsRide(rideId)
    }
}
