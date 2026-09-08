package com.taxiinspector.ui.companies

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.taxiinspector.R
import com.taxiinspector.ui.DestinationHeader

/**
 * The Taxi companies destination: it selects a saved profile, and adds, edits, or deletes one
 * between rides. Rate entry itself lives on the editor, so no keyboard opens here.
 */
@Composable
fun CompanyListScreen(
    state: CompanyListUiState,
    onAction: (CompanyListAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DestinationHeader(
                title = stringResource(R.string.companies_title),
                onBack = { onAction(CompanyListAction.Back) },
            )

            if (state.companies.isEmpty() && !state.isLoading) {
                Text(
                    text = stringResource(R.string.companies_empty),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.companies_empty_detail),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.companies.forEach { company ->
                CompanyRow(
                    company = company,
                    isSelected = company.id == state.selectedCompanyId,
                    canManage = state.canManage,
                    onAction = onAction,
                )
                HorizontalDivider()
            }

            if (!state.canManage) {
                Text(
                    text = stringResource(R.string.companies_locked),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.isAtLimit) {
                Text(
                    text = stringResource(R.string.companies_limit_reached),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { onAction(CompanyListAction.AddCompany) },
                enabled = state.canManage && !state.isAtLimit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.companies_add))
            }
        }
    }

    state.pendingDeletion?.let { company ->
        AlertDialog(
            onDismissRequest = { onAction(CompanyListAction.DeleteDismissed) },
            title = { Text(stringResource(R.string.companies_delete_title, company.name)) },
            text = { Text(stringResource(R.string.companies_delete_body)) },
            confirmButton = {
                TextButton(onClick = { onAction(CompanyListAction.DeleteConfirmed) }) {
                    Text(stringResource(R.string.companies_delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { onAction(CompanyListAction.DeleteDismissed) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** One saved profile: selecting the row selects its name and all three rates together. */
@Composable
private fun CompanyRow(
    company: CompanySummary,
    isSelected: Boolean,
    canManage: Boolean,
    onAction: (CompanyListAction) -> Unit,
) {
    val tariffSummary = stringResource(
        R.string.tariff_summary,
        company.tariff.initialTax,
        company.tariff.perKmRate,
        company.tariff.perMinuteStillRate,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = isSelected,
                enabled = canManage,
                role = Role.RadioButton,
                onClick = { onAction(CompanyListAction.SelectCompany(company.id)) },
            )
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The selectable row merges its children, so the name and rates are announced
        // together as one option and the radio button is not announced separately.
        RadioButton(selected = isSelected, onClick = null, enabled = canManage)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = company.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = tariffSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = { onAction(CompanyListAction.EditCompany(company.id)) },
            enabled = canManage,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.companies_edit))
        }
        TextButton(
            onClick = { onAction(CompanyListAction.DeleteRequested(company.id)) },
            enabled = canManage,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(stringResource(R.string.companies_delete))
        }
    }
}
