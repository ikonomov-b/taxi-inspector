package com.taxiinspector.ui.meter

import com.taxiinspector.ui.TariffSummary
import com.taxiinspector.ui.companies.CompanySummary

/**
 * Everything the meter screen renders. It holds formatted display values only: never a
 * `Location`, coordinates, a service reference, or live fare state.
 */
data class MeterUiState(
    val presentation: MeterPresentation = MeterPresentation.EMPTY,
    /** During a ride this is its locked snapshot; before one, the durable selection. */
    val company: MeterCompany? = null,
    /** The selectable profiles. Empty during a ride, which cannot change its company. */
    val companies: List<CompanySummary> = emptyList(),
    /** Which of [companies] the selector marks; null during a ride and before any selection. */
    val selectedCompanyId: String? = null,
    val status: MeterStatus = MeterStatus.CompanyNeeded,
    val canStart: Boolean = false,
    /** True while a ride owns the meter, so the screen knows not to warm up the receiver. */
    val isRideActive: Boolean = false,
    /** False while any ride is active, because a ride locks its company at Start. */
    val canManageCompanies: Boolean = true,
    val isCompanySelectorVisible: Boolean = false,
    val isDiscardConfirmationVisible: Boolean = false,
    val recovery: MeterRecovery? = null,
    val message: MeterMessage? = null,
)

/**
 * What the meter shows beneath the face. While a ride is active this is the ride's own locked
 * snapshot rather than the current selection, so a later selection change cannot appear to
 * rewrite a running ride.
 */
data class MeterCompany(
    /** Null only for a ride locked before companies existed; the screen labels that case. */
    val name: String?,
    val tariff: TariffSummary,
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
    CompanyNeeded,
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
enum class MeterMessage { CompanyNeededToStart }
