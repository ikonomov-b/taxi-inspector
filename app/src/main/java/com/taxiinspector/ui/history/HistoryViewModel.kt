package com.taxiinspector.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.data.rides.RoomRideRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Presents the repository's newest-first durable history without retaining ride data itself. */
class HistoryViewModel internal constructor(
    repository: RoomRideRepository,
    formatter: RideHistoryFormatter = RideHistoryFormatter(),
) : ViewModel() {
    val state: StateFlow<HistoryUiState> = repository.observeHistory()
        .map { savedRides ->
            HistoryUiState(
                isLoading = false,
                rides = savedRides.map(formatter::historyItem),
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    companion object {
        fun factory(repository: RoomRideRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HistoryViewModel(repository) }
        }
    }
}
