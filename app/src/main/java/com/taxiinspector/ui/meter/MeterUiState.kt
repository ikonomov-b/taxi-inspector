package com.taxiinspector.ui.meter

import com.taxiinspector.ui.tariff.TariffSummary

/**
 * Everything the meter screen renders. It holds formatted display values only: never a
 * `Location`, coordinates, a service reference, or live fare state.
 */
data class MeterUiState(
    val presentation: MeterPresentation = MeterPresentation.EMPTY,
    /** The locked tariff while a ride runs, otherwise the currently editable one. */
    val savedTariff: TariffSummary? = null,
    val status: MeterStatus = MeterStatus.TariffNeeded,
    val canStart: Boolean = false,
    /** False while any ride is active, because a ride locks its tariff at Start. */
    val canEditTariff: Boolean = true,
    val isDiscardConfirmationVisible: Boolean = false,
    val recovery: MeterRecovery? = null,
    val message: MeterMessage? = null,
)

/** Pre-formatted meter-face values in the user's own tariff unit; no currency label. */
data class MeterPresentation(
    /** The fare total, already rounded half-up to two places for display. */
    val total: String,
    /** Kilometres to two places; the screen adds the unit. */
    val distance: String,
    /** `mm:ss`, or `h:mm:ss` beyond an hour. */
    val waitTime: String,
    /** The same wait time spoken as whole minutes and seconds for screen readers. */
    val waitMinutes: Long,
    val waitSeconds: Long,
    val phase: MeterPhaseLabel,
) {
    companion object {
        val EMPTY = MeterPresentation(
            total = "0.00",
            distance = "0.00",
            waitTime = "00:00",
            waitMinutes = 0,
            waitSeconds = 0,
            phase = MeterPhaseLabel.Ready,
        )
    }
}

/** The ride phase as the meter face announces it, distinct from GPS quality. */
enum class MeterPhaseLabel { Ready, Running, Paused, Interrupted }

/**
 * Whether the displayed total is presently billable, in the plain language of the
 * design document's status table. It is never rendered inside the meter face.
 */
enum class MeterStatus {
    TariffNeeded,
    ReadyToStart,
    PermissionNeeded,
    NotificationsNeeded,
    GpsDisabled,
    Searching,
    Good,
    Weak,
    GpsLost,
    Paused,
    PendingInterrupted,
}

/** The single actionable step offered after a Start attempt could not proceed. */
enum class MeterRecovery { GrantPreciseLocation, GrantNotifications, EnableGps }

/** A transient, one-shot notice; it never carries fare state. */
enum class MeterMessage { TariffNeededToStart }
