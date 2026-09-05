package com.taxiinspector.ui.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxiinspector.TaxiInspectorApplication

/** Connects the saved-ride list to Room and reports navigation intent to the app graph. */
@Composable
fun HistoryRoute(
    onBack: () -> Unit,
    onRideSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as TaxiInspectorApplication
    val viewModel: HistoryViewModel = viewModel(
        factory = remember(application) {
            HistoryViewModel.factory(application.appContainer.rideRepository)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    HistoryScreen(
        state = state,
        onAction = { action ->
            when (action) {
                HistoryAction.Back -> onBack()
                is HistoryAction.RideSelected -> onRideSelected(action.id)
            }
        },
        modifier = modifier,
    )
}
