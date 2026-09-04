package com.taxiinspector.core.time

/** Separates fare/session elapsed time from history wall-clock timestamps. */
interface Clock {
    fun elapsedRealtimeMillis(): Long

    fun utcMillis(): Long
}
