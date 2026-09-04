package com.taxiinspector.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.taxiinspector.MainActivity
import com.taxiinspector.R
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.FareCalculator
import com.taxiinspector.ride.TrackingStatus

internal class RideNotificationFactory(
    private val context: Context,
) {
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.tracking_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.tracking_channel_description)
                    setShowBadge(false)
                },
            )
        }
    }

    fun starting(): Notification = baseBuilder()
        .setContentTitle(context.getString(R.string.tracking_notification_title))
        .setContentText(context.getString(R.string.tracking_notification_starting))
        .build()

    fun active(ride: ActiveRide): Notification {
        val total = FareCalculator.total(
            ride.tariff,
            ride.distanceMeters,
            ride.idleMillis,
        ).formatTotal()
        return baseBuilder()
            .setContentTitle(context.getString(R.string.tracking_notification_total, total))
            .setContentText(context.getString(ride.trackingStatus.statusTextResource()))
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_pause),
                    serviceAction(RideCommand.Pause, PAUSE_REQUEST_CODE),
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    context.getString(R.string.action_stop_save),
                    serviceAction(RideCommand.Stop, STOP_REQUEST_CODE),
                ).build(),
            )
            .build()
    }

    @Suppress("DEPRECATION")
    private fun baseBuilder(): Notification.Builder {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        return builder
            .setSmallIcon(R.drawable.ic_taxi_notification)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    CONTENT_REQUEST_CODE,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
    }

    private fun serviceAction(command: RideCommand, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            command.intent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun TrackingStatus.statusTextResource(): Int = when (this) {
        TrackingStatus.Searching -> R.string.gps_status_searching
        TrackingStatus.Good -> R.string.gps_status_good
        TrackingStatus.Weak -> R.string.gps_status_weak
        TrackingStatus.GpsLost -> R.string.gps_status_lost
        TrackingStatus.PermissionNeeded -> R.string.gps_status_permission_needed
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "active_ride_tracking"
        private const val CONTENT_REQUEST_CODE = 100
        private const val PAUSE_REQUEST_CODE = 101
        private const val STOP_REQUEST_CODE = 102
    }
}
