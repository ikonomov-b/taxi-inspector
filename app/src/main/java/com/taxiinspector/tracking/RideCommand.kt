package com.taxiinspector.tracking

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

enum class RideCommand(
    internal val action: String,
    internal val requiresForegroundStart: Boolean,
) {
    Start("com.taxiinspector.tracking.START", true),
    Pause("com.taxiinspector.tracking.PAUSE", false),
    Resume("com.taxiinspector.tracking.RESUME", true),
    Stop("com.taxiinspector.tracking.STOP", false),
    Discard("com.taxiinspector.tracking.DISCARD", false),
    ;

    fun intent(context: Context): Intent = Intent(context, RideTrackingService::class.java).setAction(action)

    companion object {
        internal fun fromAction(action: String?): RideCommand? = entries.firstOrNull { it.action == action }
    }
}

object RideTrackingCommands {
    /** Start and Resume must be invoked by the visible activity after its own prerequisite checks. */
    fun sendFromVisibleActivity(context: Context, command: RideCommand) {
        val intent = command.intent(context)
        if (command.requiresForegroundStart) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            context.startService(intent)
        }
    }
}
