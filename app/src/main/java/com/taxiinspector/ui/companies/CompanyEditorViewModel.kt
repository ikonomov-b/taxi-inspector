package com.taxiinspector.ui.companies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.taxiinspector.core.decimal.DecimalAmount
import com.taxiinspector.data.rides.CompanySaveResult
import com.taxiinspector.data.rides.RoomRideRepository
import com.taxiinspector.ride.ActiveRide
import com.taxiinspector.ride.Tariff
import com.taxiinspector.ride.TaxiCompany
import java.math.BigDecimal
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Validates and persists one company: a trimmed nonblank label of at most 80 characters and
 * three exact decimal rates in one user-chosen unit. No currency code, symbol, or conversion
 * exists anywhere here.
 *
 * [companyId] is null when creating.
 */
class CompanyEditorViewModel(
    private val repository: RoomRideRepository,
    private val companyId: String?,
) : ViewModel() {
    // An edit starts loading, so the form is never briefly rendered blank over saved values.
    // Create mode pre-fills the crossover with the product default, so the field is never blank.
    private val localState = MutableStateFlow(
        LocalState(
            isLoading = companyId != null,
            form = if (companyId == null) {
                CompanyFormState(
                    waitingCrossoverKmh = Tariff.DEFAULT_WAITING_CROSSOVER_KILOMETERS_PER_HOUR,
                )
            } else {
                CompanyFormState()
            },
        ),
    )
    private val savedEvents = Channel<Unit>(Channel.BUFFERED)

    /** Emitted once the company is durably stored, so the route can leave the screen. */
    val saved: Flow<Unit> = savedEvents.receiveAsFlow()

    private val mode = if (companyId == null) CompanyEditorMode.Create else CompanyEditorMode.Edit

    val state: StateFlow<CompanyEditorUiState> = combine(
        repository.observeActiveRide(),
        localState,
    ) { ride, local ->
        CompanyEditorUiState(
            mode = mode,
            isLoading = local.isLoading,
            form = local.form,
            isLocked = ride != null,
            error = local.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        CompanyEditorUiState(mode = mode, isLoading = companyId != null),
    )

    // Mirrored so a save reads what the user is looking at, not a lagging derived state.
    private var activeRide: ActiveRide? = null

    init {
        viewModelScope.launch {
            repository.observeActiveRide().collect { activeRide = it }
        }
        if (companyId != null) {
            viewModelScope.launch {
                repository.observeCompanies()
                    .map { companies -> companies.firstOrNull { it.id == companyId } }
                    .collect { company ->
                        localState.update { current ->
                            if (current.form.isPristine && company != null) {
                                current.copy(isLoading = false, form = pristineFormOf(company))
                            } else {
                                current.copy(isLoading = false)
                            }
                        }
                    }
            }
        }
    }

    fun onAction(action: CompanyEditorAction) {
        when (action) {
            is CompanyEditorAction.NameChanged -> localState.update { current ->
                current.copy(
                    form = current.form.copy(name = action.value, nameError = null, isPristine = false),
                    error = null,
                )
            }

            is CompanyEditorAction.RateChanged -> onRateChanged(action.field, action.value)
            is CompanyEditorAction.CrossoverChanged -> onCrossoverChanged(action.value)
            CompanyEditorAction.Save -> save()
        }
    }

    private fun onRateChanged(field: CompanyRateField, value: String) {
        localState.update { current ->
            val isAcceptable = value.isBlank() || DecimalAmount.parse(value) != null
            current.copy(
                form = current.form.withValue(field, value).copy(
                    isPristine = false,
                    invalidRates = if (isAcceptable) {
                        current.form.invalidRates - field
                    } else {
                        current.form.invalidRates + field
                    },
                ),
                error = null,
            )
        }
    }

    private fun onCrossoverChanged(value: String) {
        localState.update { current ->
            val parsed = DecimalAmount.parse(value)
            val crossoverError = when {
                value.isBlank() -> null
                parsed == null -> CompanyCrossoverError.Format
                !isPlausibleCrossover(parsed) -> CompanyCrossoverError.OutOfRange
                else -> null
            }
            current.copy(
                form = current.form.copy(
                    waitingCrossoverKmh = value,
                    isPristine = false,
                    crossoverError = crossoverError,
                ),
                error = null,
            )
        }
    }

    private fun save() {
        if (activeRide != null) return

        val form = localState.value.form
        val name = form.name.trim()
        val nameError = when {
            name.isEmpty() -> CompanyNameError.Blank
            name.length > TaxiCompany.MAX_NAME_LENGTH -> CompanyNameError.TooLong
            else -> null
        }
        val parsed = CompanyRateField.entries.associateWith { DecimalAmount.parse(form.valueOf(it)) }
        val invalidRates = parsed.filterValues { it == null }.keys
        val parsedCrossover = DecimalAmount.parse(form.waitingCrossoverKmh)
        val crossoverError = when {
            parsedCrossover == null -> CompanyCrossoverError.Format
            !isPlausibleCrossover(parsedCrossover) -> CompanyCrossoverError.OutOfRange
            else -> null
        }

        if (nameError != null || invalidRates.isNotEmpty() || crossoverError != null) {
            localState.update {
                it.copy(
                    form = it.form.copy(
                        isPristine = false,
                        nameError = nameError,
                        invalidRates = invalidRates,
                        crossoverError = crossoverError,
                    ),
                )
            }
            return
        }

        val tariff = Tariff(
            initialTax = requireNotNull(parsed[CompanyRateField.InitialTax]),
            perKmRate = requireNotNull(parsed[CompanyRateField.PerKmRate]),
            perMinuteStillRate = requireNotNull(parsed[CompanyRateField.PerMinuteStillRate]),
            waitingCrossoverKilometersPerHour = requireNotNull(parsedCrossover),
        )
        viewModelScope.launch {
            // A ride may have started between the check above and this write.
            val result = if (companyId == null) {
                repository.createCompany(name, tariff)
            } else {
                repository.updateCompany(companyId, name, tariff)
            }
            when (result) {
                CompanySaveResult.Saved -> savedEvents.trySend(Unit)
                CompanySaveResult.DuplicateName -> report(CompanyEditorError.DuplicateName)
                CompanySaveResult.LimitReached -> report(CompanyEditorError.LimitReached)
                CompanySaveResult.RideActive,
                CompanySaveResult.CompanyMissing,
                -> report(CompanyEditorError.SaveFailed)
            }
        }
    }

    private fun report(error: CompanyEditorError) {
        localState.update { it.copy(error = error) }
    }

    private data class LocalState(
        val isLoading: Boolean = false,
        val form: CompanyFormState = CompanyFormState(),
        val error: CompanyEditorError? = null,
    )

    companion object {
        fun factory(
            repository: RoomRideRepository,
            companyId: String?,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { CompanyEditorViewModel(repository, companyId) }
        }
    }
}

private fun pristineFormOf(company: TaxiCompany): CompanyFormState = CompanyFormState(
    name = company.name,
    initialTax = company.tariff.initialTax.formatConfigured(),
    perKmRate = company.tariff.perKmRate.formatConfigured(),
    perMinuteStillRate = company.tariff.perMinuteStillRate.formatConfigured(),
    waitingCrossoverKmh = company.tariff.waitingCrossoverKilometersPerHour.formatConfigured(),
    isPristine = true,
)

/**
 * Regulated crossovers run 5 km/h (Bulgaria) to 20 km/h (elsewhere in the EU); 30 is comfortably
 * outside any plausible jurisdiction while still catching a mistyped per-km rate or road speed
 * limit. Zero is already refused by [Tariff]'s own invariant. This is a guard against an obvious
 * mistake, not a claim the app knows the locally regulated figure.
 */
private fun isPlausibleCrossover(amount: DecimalAmount): Boolean =
    amount.value.signum() > 0 && amount.value <= MAX_PLAUSIBLE_WAITING_CROSSOVER_KMH

private val MAX_PLAUSIBLE_WAITING_CROSSOVER_KMH: BigDecimal = BigDecimal("30")
