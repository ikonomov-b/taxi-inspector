package com.taxiinspector.tracking

import android.app.Service

internal interface ForegroundSession {
    fun startPreparing()

    fun stop()
}

internal fun interface ServiceTerminator {
    fun stopService()
}

/** Keeps foreground promotion synchronous with onStartCommand while controller work stays serialized. */
internal class RideServiceCommandRouter(
    private val foregroundSession: ForegroundSession,
    private val commandSink: RideCommandSink,
    private val terminator: ServiceTerminator,
) {
    fun onStartCommand(action: String?): Int {
        val command = RideCommand.fromAction(action) ?: return Service.START_NOT_STICKY
        if (command.requiresForegroundStart) {
            try {
                foregroundSession.startPreparing()
            } catch (_: RuntimeException) {
                commandSink.rejectForegroundStart()
                runCatching { foregroundSession.stop() }
                terminator.stopService()
                return Service.START_NOT_STICKY
            }
        }
        commandSink.dispatch(command)
        return Service.START_NOT_STICKY
    }
}
