package com.taxiinspector.ride

/** The durable lifecycle of a locally stored active ride. */
enum class RidePhase {
    Running,
    Paused,
    PendingInterrupted,
}
