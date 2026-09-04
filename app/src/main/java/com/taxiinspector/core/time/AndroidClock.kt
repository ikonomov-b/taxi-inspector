package com.taxiinspector.core.time

import android.os.SystemClock

object AndroidClock : Clock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()

    override fun utcMillis(): Long = System.currentTimeMillis()
}
