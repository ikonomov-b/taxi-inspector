package com.taxiinspector.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.SavedRideSummary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Observes one durable summary and owns only the local delete-confirmation state. */
class RideDetailViewModel internal constructor(
    private val rideId: String,
    private val repository: RoomRideRepository,
    private val formatter: RideHistoryFormatter = RideHistoryFormatter(),
) : ViewModel() {
    private val localState = MutableStateFlow(LocalState())
    private val deletedEvents = Channel<Unit>(Channel.BUFFERED)

    /** Emitted only after Room has durably deleted the selected summary. */
    val deleted: Flow<Unit> = deletedEvents.receiveAsFlow()

    private var savedRide: SavedRideSummary? = null
    private val savedRideFlow = repository.observeSummary(rideId).onEach { savedRide = it }

    val state: StateFlow<RideDetailUiState> = combine(
        savedRideFlow,
        localState,
    ) { savedRide, local ->
        RideDetailUiState(
            isLoading = false,
            ride = savedRide?.let(formatter::detail),
            isDeleteConfirmationVisible = local.isDeleteConfirmationVisible && savedRide != null,
            isDeleting = local.isDeleting,
            deleteFailed = local.deleteFailed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RideDetailUiState())

    fun onAction(action: RideDetailAction) {
        when (action) {
            RideDetailAction.Back -> Unit // The route navigates; nothing here changes.
            RideDetailAction.DeleteRequested -> {
                if (savedRide != null && !localState.value.isDeleting) {
                    localState.update {
                        it.copy(isDeleteConfirmationVisible = true, deleteFailed = false)
                    }
                }
            }
            RideDetailAction.DeleteDismissed -> localState.update {
                it.copy(isDeleteConfirmationVisible = false)
            }
            RideDetailAction.DeleteConfirmed -> deleteRide()
        }
    }

    private fun deleteRide() {
        if (savedRide == null || localState.value.isDeleting) return
        localState.value = LocalState(isDeleting = true)
        viewModelScope.launch {
            runCatching { repository.deleteSummary(rideId) }
                .onSuccess { deletedEvents.send(Unit) }
                .onFailure { localState.value = LocalState(deleteFailed = true) }
        }
    }

    companion object {
        fun factory(
            rideId: String,
            repository: RoomRideRepository,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RideDetailViewModel(rideId, repository) }
        }
    }

    private data class LocalState(
        val isDeleteConfirmationVisible: Boolean = false,
        val isDeleting: Boolean = false,
        val deleteFailed: Boolean = false,
    )
}
