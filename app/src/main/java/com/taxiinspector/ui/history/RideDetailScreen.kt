package com.taxiinspector.ui.history

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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.taxiinspector.R

/** A route-free view of one saved summary and its locked tariff. */
@Composable
fun RideDetailScreen(
    state: RideDetailUiState,
    onAction: (RideDetailAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DestinationHeader(
                title = stringResource(R.string.ride_detail_title),
                onBack = { onAction(RideDetailAction.Back) },
            )
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                state.ride == null -> Text(
                    text = stringResource(R.string.ride_detail_missing),
                    style = MaterialTheme.typography.bodyLarge,
                )
                else -> RideDetails(state.ride, state, onAction)
            }
        }
    }

    if (state.isDeleteConfirmationVisible) {
        AlertDialog(
            onDismissRequest = { onAction(RideDetailAction.DeleteDismissed) },
            title = { Text(stringResource(R.string.delete_ride_title)) },
            text = { Text(stringResource(R.string.delete_ride_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(RideDetailAction.DeleteConfirmed) }) {
                    Text(stringResource(R.string.delete_ride_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(RideDetailAction.DeleteDismissed) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RideDetails(
    ride: RideDetailPresentation,
    state: RideDetailUiState,
    onAction: (RideDetailAction) -> Unit,
) {
    Text(
        text = stringResource(R.string.ride_detail_total),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = ride.total, style = MaterialTheme.typography.headlineSmall)
    DetailRow(stringResource(R.string.ride_detail_status), stringResource(ride.status.labelRes()))
    DetailRow(stringResource(R.string.ride_detail_ended), ride.endedAt)
    DetailRow(
        stringResource(R.string.ride_detail_distance),
        stringResource(R.string.ride_detail_distance_value, ride.distanceKilometres),
    )
    DetailRow(stringResource(R.string.ride_detail_wait), ride.waitTime)
    DetailRow(stringResource(R.string.ride_detail_elapsed), ride.elapsedTime)

    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        text = stringResource(R.string.ride_detail_locked_tariff),
        style = MaterialTheme.typography.titleMedium,
    )
    DetailRow(stringResource(R.string.ride_detail_initial_tax), ride.initialTax)
    DetailRow(stringResource(R.string.ride_detail_per_km), ride.perKmRate)
    DetailRow(stringResource(R.string.ride_detail_per_minute), ride.perMinuteStillRate)

    if (state.deleteFailed) {
        Text(
            text = stringResource(R.string.delete_ride_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    OutlinedButton(
        onClick = { onAction(RideDetailAction.DeleteRequested) },
        enabled = !state.isDeleting,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.action_delete_ride))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}
