package com.taxiinspector.ui.companies

import com.taxiinspector.ride.TaxiCompany
import com.taxiinspector.ui.TariffSummary
import com.taxiinspector.ui.toSummary

/** A saved company as the UI shows it: a user label plus its formatted tariff. */
data class CompanySummary(
    val id: String,
    val name: String,
    val tariff: TariffSummary,
)

/** The three rate inputs. The name is validated separately, with its own messages. */
enum class CompanyRateField { InitialTax, PerKmRate, PerMinuteStillRate }

// region Company list

/** The Taxi companies destination. */
data class CompanyListUiState(
    val isLoading: Boolean = true,
    val companies: List<CompanySummary> = emptyList(),
    val selectedCompanyId: String? = null,
    /** False while any ride is active, because a ride locks its company for its whole life. */
    val canManage: Boolean = true,
    /** True at ten companies: Add is unavailable and says why, and nothing is evicted. */
    val isAtLimit: Boolean = false,
    val pendingDeletion: CompanySummary? = null,
)

sealed interface CompanyListAction {
    data object Back : CompanyListAction

    data object AddCompany : CompanyListAction

    data class EditCompany(val id: String) : CompanyListAction

    /** Selects the whole profile: its name and all three rates together. */
    data class SelectCompany(val id: String) : CompanyListAction

    data class DeleteRequested(val id: String) : CompanyListAction

    data object DeleteDismissed : CompanyListAction

    data object DeleteConfirmed : CompanyListAction
}

// endregion

// region Company editor

/** The company editor, used for both creating and editing one profile. */
data class CompanyEditorUiState(
    val mode: CompanyEditorMode = CompanyEditorMode.Create,
    val isLoading: Boolean = false,
    val form: CompanyFormState = CompanyFormState(),
    /** A ride locks its company at Start, so saving is refused until the ride ends. */
    val isLocked: Boolean = false,
    val error: CompanyEditorError? = null,
)

enum class CompanyEditorMode { Create, Edit }

/**
 * The three save outcomes the user can act on. A refusal caused by a ride starting or by the
 * company being deleted elsewhere is reported as [SaveFailed]: the screen's locked notice and
 * the list itself already explain those, so they need no message of their own.
 */
enum class CompanyEditorError { DuplicateName, LimitReached, SaveFailed }

enum class CompanyNameError { Blank, TooLong }

/**
 * The waiting-crossover field is asked a second question the three rates are not: not just "is
 * this a well-formed non-negative decimal" ([Format], shared with [CompanyRateField]) but also
 * "is this a plausible speed" ([OutOfRange]) — regulated crossovers run 5-20 km/h, so this catches
 * a mistyped per-km rate or road speed limit without asserting the app knows the local rule.
 */
enum class CompanyCrossoverError { Format, OutOfRange }

/** The entry fields; while [isPristine] they mirror the saved company exactly. */
data class CompanyFormState(
    val name: String = "",
    val initialTax: String = "",
    val perKmRate: String = "",
    val perMinuteStillRate: String = "",
    val waitingCrossoverKmh: String = "",
    val nameError: CompanyNameError? = null,
    val invalidRates: Set<CompanyRateField> = emptySet(),
    val crossoverError: CompanyCrossoverError? = null,
    val isPristine: Boolean = true,
) {
    fun valueOf(field: CompanyRateField): String = when (field) {
        CompanyRateField.InitialTax -> initialTax
        CompanyRateField.PerKmRate -> perKmRate
        CompanyRateField.PerMinuteStillRate -> perMinuteStillRate
    }

    fun withValue(field: CompanyRateField, value: String): CompanyFormState = when (field) {
        CompanyRateField.InitialTax -> copy(initialTax = value)
        CompanyRateField.PerKmRate -> copy(perKmRate = value)
        CompanyRateField.PerMinuteStillRate -> copy(perMinuteStillRate = value)
    }
}

sealed interface CompanyEditorAction {
    data class NameChanged(val value: String) : CompanyEditorAction

    data class RateChanged(val field: CompanyRateField, val value: String) : CompanyEditorAction

    data class CrossoverChanged(val value: String) : CompanyEditorAction

    data object Save : CompanyEditorAction
}

// endregion

internal fun TaxiCompany.toSummary(): CompanySummary = CompanySummary(
    id = id,
    name = name,
    tariff = tariff.toSummary(),
)
