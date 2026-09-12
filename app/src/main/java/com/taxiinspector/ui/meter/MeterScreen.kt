package com.taxiinspector.ui.meter

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.taxiinspector.R
import com.taxiinspector.ui.companies.CompanySummary

/**
 * The meter screen. It renders [MeterUiState] and reports intent through [onAction]; it
 * calculates no fare, holds no ride state, and performs no Android side effect itself.
 */
@Composable
fun MeterScreen(
    state: MeterUiState,
    onAction: (MeterAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusHeader(state.status)

            val presentation = state.presentation
            TaximeterFace(
                phaseLabel = stringResource(presentation.phase.labelRes()),
                total = presentation.total,
                totalDescription = stringResource(R.string.meter_total_description, presentation.total),
                distanceLabel = stringResource(R.string.meter_distance),
                distance = stringResource(R.string.meter_distance_value, presentation.distance),
                distanceDescription = stringResource(
                    R.string.meter_distance_description,
                    presentation.distance,
                ),
                waitTimeLabel = stringResource(R.string.meter_wait_time),
                waitTime = presentation.waitTime,
                waitTimeDescription = stringResource(
                    R.string.meter_wait_description,
                    pluralStringResource(
                        R.plurals.meter_wait_minutes,
                        presentation.waitMinutes.toInt(),
                        presentation.waitMinutes,
                    ),
                    pluralStringResource(
                        R.plurals.meter_wait_seconds,
                        presentation.waitSeconds.toInt(),
                        presentation.waitSeconds,
                    ),
                ),
                isLampLit = presentation.phase == MeterPhaseLabel.Running,
            )

            RideControls(state, onAction)

            state.recovery?.let { recovery ->
                OutlinedButton(
                    onClick = { onAction(MeterAction.RecoveryRequested) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(recovery.labelRes()))
                }
            }

            state.message?.let { message ->
                Text(
                    text = stringResource(message.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SelectedCompany(state, onAction)

            OutlinedButton(
                onClick = { onAction(MeterAction.ViewHistory) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_history))
            }

            Text(
                text = stringResource(R.string.estimate_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (state.isDiscardConfirmationVisible) {
        DiscardConfirmationDialog(
            onConfirm = { onAction(MeterAction.DiscardConfirmed) },
            onDismiss = { onAction(MeterAction.DiscardDismissed) },
        )
    }

    if (state.isCompanySelectorVisible) {
        CompanySelectorDialog(
            companies = state.companies,
            selectedId = state.selectedCompanyId,
            onSelect = { onAction(MeterAction.CompanySelected(it)) },
            onDismiss = { onAction(MeterAction.CompanySelectorDismissed) },
        )
    }
}

/**
 * The company and its tariff stay visible beneath the meter. Selection changes the durable
 * profile before a ride; during one this shows the ride's own locked snapshot instead.
 */
@Composable
private fun SelectedCompany(state: MeterUiState, onAction: (MeterAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.meter_selected_company),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // A ride locked before companies existed has no name to show, and the app
                    // must not invent one, so it is labelled as unrecorded instead.
                    text = state.company?.name
                        ?: stringResource(
                            if (state.company == null) {
                                R.string.meter_no_company
                            } else {
                                R.string.company_legacy_label
                            },
                        ),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.company?.tariff?.let { tariff ->
                    Text(
                        text = stringResource(
                            R.string.tariff_summary,
                            tariff.initialTax,
                            tariff.perKmRate,
                            tariff.perMinuteStillRate,
                            tariff.waitingCrossoverKilometersPerHour,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = { onAction(MeterAction.CompanySelectorOpened) },
                enabled = state.canManageCompanies && state.companies.isNotEmpty(),
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_change_company))
            }
        }
        TextButton(
            onClick = { onAction(MeterAction.ManageCompanies) },
            enabled = state.canManageCompanies,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.action_manage_companies))
        }
        if (!state.canManageCompanies) {
            Text(
                text = stringResource(R.string.company_locked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Lists each saved profile with enough tariff detail to tell two of them apart. */
@Composable
private fun CompanySelectorDialog(
    companies: List<CompanySummary>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.company_selector_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                companies.forEach { company ->
                    val tariffSummary = stringResource(
                        R.string.tariff_summary,
                        company.tariff.initialTax,
                        company.tariff.perKmRate,
                        company.tariff.perMinuteStillRate,
                        company.tariff.waitingCrossoverKilometersPerHour,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = company.id == selectedId,
                                role = Role.RadioButton,
                                onClick = { onSelect(company.id) },
                            )
                            .heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // The selectable row merges its children, so each option is
                        // announced as its name followed by the rates that distinguish it.
                        RadioButton(selected = company.id == selectedId, onClick = null)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = company.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = tariffSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun StatusHeader(status: MeterStatus) {
    val label = stringResource(status.labelRes())
    val statusDescription = stringResource(R.string.status_label, label)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleMedium,
        )
        // Announced as a status so it is never mistaken for part of the fare reading.
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = statusDescription },
        )
    }
}

@Composable
private fun RideControls(state: MeterUiState, onAction: (MeterAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        when (state.presentation.phase) {
            MeterPhaseLabel.Ready -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryControl(
                    label = stringResource(R.string.action_start),
                    enabled = state.canStart,
                    onClick = { onAction(MeterAction.StartRide) },
                    modifier = Modifier.weight(2f),
                )
                SecondaryControl(
                    label = stringResource(R.string.action_reset),
                    onClick = { onAction(MeterAction.Reset) },
                    modifier = Modifier.weight(1f),
                )
            }

            MeterPhaseLabel.Running -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SecondaryControl(
                    label = stringResource(R.string.action_pause),
                    onClick = { onAction(MeterAction.PauseRide) },
                    modifier = Modifier.weight(1f),
                )
                PrimaryControl(
                    label = stringResource(R.string.action_stop_save),
                    onClick = { onAction(MeterAction.StopAndSave) },
                    modifier = Modifier.weight(1f),
                )
            }

            MeterPhaseLabel.Paused -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PrimaryControl(
                    label = stringResource(R.string.action_resume),
                    onClick = { onAction(MeterAction.ResumeRide) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryControl(
                    label = stringResource(R.string.action_stop_save),
                    onClick = { onAction(MeterAction.StopAndSave) },
                    modifier = Modifier.weight(1f),
                )
            }

            MeterPhaseLabel.Interrupted -> PrimaryControl(
                label = stringResource(R.string.action_save_interrupted),
                onClick = { onAction(MeterAction.StopAndSave) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Kept apart from Stop & save, and always confirmed, so the two cannot be confused.
        if (state.presentation.phase != MeterPhaseLabel.Ready) {
            OutlinedButton(
                onClick = { onAction(MeterAction.DiscardRequested) },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.action_discard))
            }
        }
    }
}

@Composable
private fun PrimaryControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun SecondaryControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun DiscardConfirmationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_body)) },
        confirmButton = {
            // Deliberately not "Discard ride": the confirm step spells out the consequence.
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@StringRes
private fun MeterPhaseLabel.labelRes(): Int = when (this) {
    MeterPhaseLabel.Ready -> R.string.meter_ready
    MeterPhaseLabel.Running -> R.string.meter_running
    MeterPhaseLabel.Paused -> R.string.meter_paused
    MeterPhaseLabel.Interrupted -> R.string.meter_interrupted
}

@StringRes
private fun MeterStatus.labelRes(): Int = when (this) {
    MeterStatus.CompanyNeeded -> R.string.status_company_needed
    MeterStatus.ReadyToStart -> R.string.status_ready
    MeterStatus.PermissionNeeded -> R.string.gps_status_permission_needed
    MeterStatus.NotificationsNeeded -> R.string.status_notifications_needed
    MeterStatus.GpsDisabled -> R.string.status_gps_disabled
    MeterStatus.Searching -> R.string.gps_status_searching
    MeterStatus.Good -> R.string.gps_status_good
    MeterStatus.Weak -> R.string.gps_status_weak
    MeterStatus.GpsLost -> R.string.gps_status_lost
    MeterStatus.Paused -> R.string.status_paused
    MeterStatus.PendingInterrupted -> R.string.status_interrupted
}

@StringRes
private fun MeterRecovery.labelRes(): Int = when (this) {
    MeterRecovery.GrantPreciseLocation -> R.string.recovery_grant_location
    MeterRecovery.GrantNotifications -> R.string.recovery_grant_notifications
    MeterRecovery.EnableGps -> R.string.recovery_enable_gps
}

@StringRes
private fun MeterMessage.labelRes(): Int = when (this) {
    MeterMessage.CompanyNeededToStart -> R.string.company_needed_to_start
}
