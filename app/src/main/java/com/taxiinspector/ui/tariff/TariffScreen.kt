package com.taxiinspector.ui.tariff

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
 * The tariff destination. It opens automatically when no tariff has been saved yet and is
 * reachable from the meter between rides. It renders state and reports intent only.
 */
@Composable
fun TariffScreen(
    state: TariffUiState,
    onAction: (TariffAction) -> Unit,
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
                text = stringResource(R.string.tariff_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = stringResource(
                    if (state.savedTariff == null) {
                        R.string.tariff_first_run_intro
                    } else {
                        R.string.tariff_edit_intro
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TariffTextField(
                label = stringResource(R.string.tariff_initial_tax),
                field = TariffField.InitialTax,
                form = state.form,
                isLocked = state.isLocked,
                imeAction = ImeAction.Next,
                onFieldChange = onAction,
            )
            TariffTextField(
                label = stringResource(R.string.tariff_per_km),
                field = TariffField.PerKmRate,
                form = state.form,
                isLocked = state.isLocked,
                imeAction = ImeAction.Next,
                onFieldChange = onAction,
            )
            TariffTextField(
                label = stringResource(R.string.tariff_per_minute),
                field = TariffField.PerMinuteStillRate,
                form = state.form,
                isLocked = state.isLocked,
                imeAction = ImeAction.Done,
                onFieldChange = onAction,
            )

            Text(
                text = stringResource(R.string.tariff_unit_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.isLocked) {
                Text(
                    text = stringResource(R.string.tariff_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = { onAction(TariffAction.Save) },
                enabled = !state.isLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.tariff_save))
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
private fun TariffTextField(
    label: String,
    field: TariffField,
    form: TariffFormState,
    isLocked: Boolean,
    imeAction: ImeAction,
    onFieldChange: (TariffAction) -> Unit,
) {
    val isInvalid = field in form.invalidFields
    OutlinedTextField(
        value = form.valueOf(field),
        onValueChange = { onFieldChange(TariffAction.FieldChanged(field, it)) },
        label = { Text(label) },
        enabled = !isLocked,
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
