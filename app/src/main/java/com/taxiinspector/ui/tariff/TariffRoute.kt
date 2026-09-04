package com.taxiinspector.ui.tariff

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxiinspector.TaxiInspectorApplication

/** Hosts [TariffScreen] and leaves the destination once the tariff is durably stored. */
@Composable
fun TariffRoute(
    onSaved: () -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as TaxiInspectorApplication
    val viewModel: TariffViewModel = viewModel(
        factory = remember(application) {
            TariffViewModel.factory(application.appContainer.rideRepository)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(viewModel) {
        viewModel.saved.collect { currentOnSaved() }
    }

    TariffScreen(
        state = state,
        onAction = viewModel::onAction,
        onCancel = onCancel,
        modifier = modifier,
    )
}
