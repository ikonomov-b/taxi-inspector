package com.taxiinspector.ui.companies

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taxiinspector.R

/**
 * The company editor. It opens automatically on a first run with no saved companies, and is
 * otherwise reached from the company list. Keeping entry fields on their own destination means
 * the fare reading never shares a screen with a soft keyboard.
 */
@Composable
fun CompanyEditorScreen(
    state: CompanyEditorUiState,
    onAction: (CompanyEditorAction) -> Unit,
    onCancel: (() -> Unit)?,
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
            Text(
                text = stringResource(
                    if (state.mode == CompanyEditorMode.Create) {
                        R.string.company_editor_add_title
                    } else {
                        R.string.company_editor_edit_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(
                    if (onCancel == null) {
                        R.string.company_editor_first_run_intro
                    } else {
                        R.string.company_editor_intro
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.form.name,
                onValueChange = { onAction(CompanyEditorAction.NameChanged(it)) },
                label = { Text(stringResource(R.string.company_name)) },
                enabled = !state.isLocked,
                isError = state.form.nameError != null,
                singleLine = true,
                supportingText = state.form.nameError?.let { error ->
                    { Text(stringResource(error.messageRes())) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            RateField(
                label = stringResource(R.string.tariff_initial_tax),
                field = CompanyRateField.InitialTax,
                state = state,
                imeAction = ImeAction.Next,
                onAction = onAction,
            )
            RateField(
                label = stringResource(R.string.tariff_per_km),
                field = CompanyRateField.PerKmRate,
                state = state,
                imeAction = ImeAction.Next,
                onAction = onAction,
            )
            RateField(
                label = stringResource(R.string.tariff_per_minute),
                field = CompanyRateField.PerMinuteStillRate,
                state = state,
                imeAction = ImeAction.Done,
                onAction = onAction,
            )

            Text(
                text = stringResource(R.string.tariff_unit_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.company_name_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isLocked) {
                Text(
                    text = stringResource(R.string.companies_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.error?.let { error ->
                Text(
                    text = stringResource(error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { onAction(CompanyEditorAction.Save) },
                enabled = !state.isLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.company_save))
            }
            onCancel?.let { cancel ->
                OutlinedButton(
                    onClick = cancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        }
    }
}

@Composable
private fun RateField(
    label: String,
    field: CompanyRateField,
    state: CompanyEditorUiState,
    imeAction: ImeAction,
    onAction: (CompanyEditorAction) -> Unit,
) {
    val isInvalid = field in state.form.invalidRates
    OutlinedTextField(
        value = state.form.valueOf(field),
        onValueChange = { onAction(CompanyEditorAction.RateChanged(field, it)) },
        label = { Text(label) },
        enabled = !state.isLocked,
        isError = isInvalid,
        singleLine = true,
        supportingText = if (isInvalid) {
            { Text(stringResource(R.string.tariff_invalid_field)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = imeAction,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@StringRes
private fun CompanyNameError.messageRes(): Int = when (this) {
    CompanyNameError.Blank -> R.string.company_name_blank
    CompanyNameError.TooLong -> R.string.company_name_too_long
}

@StringRes
private fun CompanyEditorError.messageRes(): Int = when (this) {
    CompanyEditorError.DuplicateName -> R.string.company_name_duplicate
    CompanyEditorError.LimitReached -> R.string.companies_limit_reached
    CompanyEditorError.SaveFailed -> R.string.company_save_failed
}
