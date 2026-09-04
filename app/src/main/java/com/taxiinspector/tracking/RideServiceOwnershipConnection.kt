package com.taxiinspector.tracking

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Answers "does a live service still own this ride?" for the UI before a persisted Running
 * snapshot may be converted to an interrupted one.
 *
 * It deliberately binds without `BIND_AUTO_CREATE`: creating the service here would answer
 * the question with a service this call itself started, and tracking must never resume
 * silently after process death.
 */
class RideServiceOwnershipConnection(private val context: Context) {
    suspend fun <T> withOwnership(
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        block: suspend (RideOwnership?) -> T,
    ): T {
        val connected = CompletableDeferred<RideOwnership?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete(binder as? RideOwnership)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                connected.complete(null)
            }
        }

        val isBinding = runCatching {
            context.bindService(Intent(context, RideTrackingService::class.java), connection, 0)
        }.getOrDefault(false)
        if (!isBinding) return block(null)

        return try {
            // No live service answers within the timeout, which counts as "not owned".
            block(withTimeoutOrNull(timeoutMillis) { connected.await() })
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 2_000L
    }
}
