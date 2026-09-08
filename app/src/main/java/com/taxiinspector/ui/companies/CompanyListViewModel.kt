package com.taxiinspector.ui.companies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.TaxiCompany
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Presents the saved companies and turns selection and deletion into repository transactions.
 *
 * A rejected transaction needs no message here: the repository is the only guard that matters,
 * and its refusals are already visible as durable state — a started ride disables management,
 * and a company deleted elsewhere simply leaves the list.
 */
class CompanyListViewModel(private val repository: RoomRideRepository) : ViewModel() {
    private val pendingDeletionId = MutableStateFlow<String?>(null)

    val state: StateFlow<CompanyListUiState> = combine(
        repository.observeCompanies(),
        repository.observeSelectedCompany(),
        repository.observeActiveRide(),
        pendingDeletionId,
    ) { companies, selected, ride, pendingId ->
        val summaries = companies.map(TaxiCompany::toSummary)
        CompanyListUiState(
            isLoading = false,
            companies = summaries,
            selectedCompanyId = selected?.id,
            canManage = ride == null,
            isAtLimit = summaries.size >= TaxiCompany.MAX_COMPANIES,
            // Resolved against the live list, so a row that disappears takes its dialog with it.
            pendingDeletion = summaries.firstOrNull { it.id == pendingId },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CompanyListUiState())

    fun onAction(action: CompanyListAction) {
        when (action) {
            CompanyListAction.Back,
            CompanyListAction.AddCompany,
            is CompanyListAction.EditCompany,
            -> Unit // The route navigates; nothing here changes.

            is CompanyListAction.SelectCompany -> viewModelScope.launch {
                repository.selectCompany(action.id)
            }

            // Deletion is destructive, so it always goes through the confirmation dialog.
            is CompanyListAction.DeleteRequested -> pendingDeletionId.value = action.id
            CompanyListAction.DeleteDismissed -> pendingDeletionId.value = null
            CompanyListAction.DeleteConfirmed -> {
                val id = pendingDeletionId.value ?: return
                pendingDeletionId.value = null
                viewModelScope.launch { repository.deleteCompany(id) }
            }
        }
    }

    companion object {
        fun factory(repository: RoomRideRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { CompanyListViewModel(repository) }
        }
    }
}
