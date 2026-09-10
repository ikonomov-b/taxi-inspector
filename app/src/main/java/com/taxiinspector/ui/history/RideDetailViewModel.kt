package com.taxiinspector.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.data.trace.RideTraceStore
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
    private val traceStore: RideTraceStore? = null,
) : ViewModel() {
    private val localState = MutableStateFlow(LocalState())
    private val deletedEvents = Channel<Unit>(Channel.BUFFERED)
    private val effects = Channel<RideDetailEffect>(Channel.BUFFERED)

    /** Emitted only after Room has durably deleted the selected summary. */
    val deleted: Flow<Unit> = deletedEvents.receiveAsFlow()
    val effect: Flow<RideDetailEffect> = effects.receiveAsFlow()

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
            hasTrace = savedRide != null && traceStore?.hasTrace(rideId) == true,
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
            RideDetailAction.ShareTrack -> share(justTheTrack = true)
            RideDetailAction.ShareFullTrace -> share(justTheTrack = false)
        }
    }

    private fun share(justTheTrack: Boolean) {
        val store = traceStore ?: return
        val files = store.filesFor(rideId)
        val chosen = if (justTheTrack) {
            files.filter { it.name.endsWith(".gpx") }
        } else {
            files
        }
        if (chosen.isEmpty()) return
        // A single GPX is offered as one, so Locus Map recognises it and offers to import the
        // track; a mixed set has no honest shared type.
        val mimeType = if (chosen.size == 1 && justTheTrack) GPX_MIME_TYPE else ANY_MIME_TYPE
        effects.trySend(RideDetailEffect.ShareFiles(chosen, mimeType))
    }

    private fun deleteRide() {
        if (savedRide == null || localState.value.isDeleting) return
        localState.value = LocalState(isDeleting = true)
        viewModelScope.launch {
            runCatching { repository.deleteSummary(rideId) }
                .onSuccess {
                    // Deleting the record deletes its route with it.
                    runCatching { traceStore?.delete(rideId) }
                    deletedEvents.send(Unit)
                }
                .onFailure { localState.value = LocalState(deleteFailed = true) }
        }
    }

    companion object {
        fun factory(
            rideId: String,
            repository: RoomRideRepository,
            traceStore: RideTraceStore? = null,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { RideDetailViewModel(rideId, repository, traceStore = traceStore) }
        }

        private const val GPX_MIME_TYPE = "application/gpx+xml"
        private const val ANY_MIME_TYPE = "*/*"
    }

    private data class LocalState(
        val isDeleteConfirmationVisible: Boolean = false,
        val isDeleting: Boolean = false,
        val deleteFailed: Boolean = false,
    )
}
