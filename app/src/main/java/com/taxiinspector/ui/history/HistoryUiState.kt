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
)

data class RideDetailPresentation(
    val id: String,
    val endedAt: String,
    val total: String,
    val distanceKilometres: String,
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
}
