package com.taxiinspector.ride

/** Describes whether received location data is permitted to change the fare. */
enum class TrackingStatus {
    Searching,
    Good,
    Weak,
    GpsLost,
    PermissionNeeded,
}
