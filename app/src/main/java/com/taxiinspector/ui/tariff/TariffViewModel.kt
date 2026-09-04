package com.taxiinspector.ui.tariff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.Tariff
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Validates and persists the editable tariff. Amounts stay exact decimals in one
 * user-chosen unit: no currency code, symbol, or conversion exists anywhere here.
 */
class TariffViewModel(private val repository: RoomRideRepository) : ViewModel() {
    private val formState = MutableStateFlow(TariffFormState())
    private val savedEvents = Channel<Unit>(Channel.BUFFERED)

    /** Emitted after the tariff is durably stored, so the route can leave the screen. */
    val saved: Flow<Unit> = savedEvents.receiveAsFlow()

    val state: StateFlow<TariffUiState> = combine(
        repository.observeTariff(),
        repository.observeActiveRide(),
        formState,
    ) { tariff, ride, form ->
        TariffUiState(
            form = form,
            savedTariff = tariff?.toSummary(),
            isLocked = ride != null,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TariffUiState())

    // Mirrored so an action reads what the user is looking at, not a lagging derived state.
    private var savedTariff: Tariff? = null
    private var activeRide: ActiveRide? = null

    init {
        viewModelScope.launch {
            repository.observeTariff().collect { tariff ->
                savedTariff = tariff
                if (formState.value.isPristine) formState.value = pristineFormOf(tariff)
            }
        }
        viewModelScope.launch {
            repository.observeActiveRide().collect { activeRide = it }
        }
    }

    fun onAction(action: TariffAction) {
        when (action) {
            is TariffAction.FieldChanged -> onFieldChanged(action.field, action.value)
            TariffAction.Save -> save()
            TariffAction.Reset -> formState.value = pristineFormOf(savedTariff)
        }
    }

    private fun onFieldChanged(field: TariffField, value: String) {
        formState.update { current ->
            val isAcceptable = value.isBlank() || DecimalAmount.parse(value) != null
            current.withValue(field, value).copy(
                isPristine = false,
                invalidFields = if (isAcceptable) {
                    current.invalidFields - field
                } else {
                    current.invalidFields + field
                },
            )
        }
    }

    private fun save() {
        if (activeRide != null) return

        val form = formState.value
        val parsed = TariffField.entries.associateWith { DecimalAmount.parse(form.valueOf(it)) }
        val invalidFields = parsed.filterValues { it == null }.keys
        if (invalidFields.isNotEmpty()) {
            formState.value = form.copy(isPristine = false, invalidFields = invalidFields)
            return
        }

        val tariff = Tariff(
            initialTax = requireNotNull(parsed[TariffField.InitialTax]),
            perKmRate = requireNotNull(parsed[TariffField.PerKmRate]),
            perMinuteStillRate = requireNotNull(parsed[TariffField.PerMinuteStillRate]),
        )
        viewModelScope.launch {
            // A ride may have started between the check above and this write.
            if (runCatching { repository.saveTariff(tariff) }.isSuccess) {
                formState.value = pristineFormOf(tariff)
                savedEvents.trySend(Unit)
            }
        }
    }

    companion object {
        fun factory(repository: RoomRideRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { TariffViewModel(repository) }
        }
    }
}

internal fun Tariff.toSummary(): TariffSummary = TariffSummary(
    initialTax = initialTax.formatConfigured(),
    perKmRate = perKmRate.formatConfigured(),
    perMinuteStillRate = perMinuteStillRate.formatConfigured(),
)

private fun pristineFormOf(tariff: Tariff?): TariffFormState = TariffFormState(
    initialTax = tariff?.initialTax?.formatConfigured().orEmpty(),
    perKmRate = tariff?.perKmRate?.formatConfigured().orEmpty(),
    perMinuteStillRate = tariff?.perMinuteStillRate?.formatConfigured().orEmpty(),
    isPristine = true,
)
