package com.taxiinspector.ui.tariff

/** The tariff destination's complete display state. */
data class TariffUiState(
    val form: TariffFormState = TariffFormState(),
    val savedTariff: TariffSummary? = null,
    /** A ride locks its tariff at Start, so editing is refused until the ride ends. */
    val isLocked: Boolean = false,
)

enum class TariffField { InitialTax, PerKmRate, PerMinuteStillRate }

/** The entry fields; while [isPristine] they mirror the saved tariff exactly. */
data class TariffFormState(
    val initialTax: String = "",
    val perKmRate: String = "",
    val perMinuteStillRate: String = "",
    val invalidFields: Set<TariffField> = emptySet(),
    val isPristine: Boolean = true,
) {
    fun valueOf(field: TariffField): String = when (field) {
        TariffField.InitialTax -> initialTax
        TariffField.PerKmRate -> perKmRate
        TariffField.PerMinuteStillRate -> perMinuteStillRate
    }

    fun withValue(field: TariffField, value: String): TariffFormState = when (field) {
        TariffField.InitialTax -> copy(initialTax = value)
        TariffField.PerKmRate -> copy(perKmRate = value)
        TariffField.PerMinuteStillRate -> copy(perMinuteStillRate = value)
    }
}

/** The saved tariff, formatted without trailing zeroes and without a currency label. */
data class TariffSummary(
    val initialTax: String,
    val perKmRate: String,
    val perMinuteStillRate: String,
)

/** The single entry point through which the tariff screen reports user intent. */
sealed interface TariffAction {
    data class FieldChanged(val field: TariffField, val value: String) : TariffAction

    data object Save : TariffAction

    /** Clears unsaved edits back to the saved tariff; it deletes nothing. */
    data object Reset : TariffAction
}
