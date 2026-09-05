package com.taxiinspector.ui.history

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

/** Hosts one live Room summary and leaves after a durable deletion. */
@Composable
fun RideDetailRoute(
    rideId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as TaxiInspectorApplication
    val viewModel: RideDetailViewModel = viewModel(
        key = rideId,
        factory = remember(application, rideId) {
            RideDetailViewModel.factory(rideId, application.appContainer.rideRepository)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.deleted.collect { currentOnBack() }
    }

    RideDetailScreen(
        state = state,
        onAction = { action ->
            if (action == RideDetailAction.Back) currentOnBack() else viewModel.onAction(action)
        },
        modifier = modifier,
    )
}
