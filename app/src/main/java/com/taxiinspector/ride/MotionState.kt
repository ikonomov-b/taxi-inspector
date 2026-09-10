package com.taxiinspector.ride

/**
 * Which tariff the engine currently believes applies. A label only: billing attributes a closed
 * interval by comparing the two fares over it, never by reading this.
 *
 * The names are retained so a snapshot stored by an earlier version still resolves. [Idle] now
 * means "the time tariff applies", which includes a crawl below the cross-over speed, not only
 * a stationary vehicle.
 */
enum class MotionState {
    Moving,
    Idle,
}
