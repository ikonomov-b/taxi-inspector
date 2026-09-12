package com.taxiinspector.ui.history

/** Everything rendered by the newest-first saved-ride list. */
data class HistoryUiState(
    val isLoading: Boolean = true,
    val rides: List<HistoryRideItem> = emptyList(),
)

data class HistoryRideItem(
    val id: String,
    val endedAt: String,
    val total: String,
    val distanceKilometres: String,
    val status: HistoryRideStatus,
)

enum class HistoryRideStatus { Completed, Interrupted }

sealed interface HistoryAction {
    data object Back : HistoryAction
    data class RideSelected(val id: String) : HistoryAction
}

/** The full persisted summary shown on the Ride Detail destination. */
data class RideDetailUiState(
    val isLoading: Boolean = true,
    val ride: RideDetailPresentation? = null,
    val isDeleteConfirmationVisible: Boolean = false,
    val isDeleting: Boolean = false,
    val deleteFailed: Boolean = false,
    /** A debug build that traced this trip; false in release, where no trace is ever written. */
    val hasTrace: Boolean = false,
)

data class RideDetailPresentation(
    val id: String,
    /** Null for a ride saved before companies existed; the screen labels it as unrecorded. */
    val companyName: String?,
    val endedAt: String,
    val total: String,
    val distanceKilometres: String,
    /** Null for a ride saved before this was recorded; the screen shows it as unrecorded. */
    val travelledDistanceKilometres: String?,
    val waitTime: String,
    val elapsedTime: String,
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
    val status: HistoryRideStatus,
)

sealed interface RideDetailAction {
    data object Back : RideDetailAction
    data object DeleteRequested : RideDetailAction
    data object DeleteDismissed : RideDetailAction
    data object DeleteConfirmed : RideDetailAction

    /** The GPX alone, which Locus Map offers to import directly. */
    data object ShareTrack : RideDetailAction

    /** Track, decision log and metadata together, for analysis off the phone. */
    data object ShareFullTrace : RideDetailAction
}

/** One-off effects the route performs; the state holder cannot touch Android intents. */
sealed interface RideDetailEffect {
    data class ShareFiles(val files: List<java.io.File>, val mimeType: String) : RideDetailEffect
}
