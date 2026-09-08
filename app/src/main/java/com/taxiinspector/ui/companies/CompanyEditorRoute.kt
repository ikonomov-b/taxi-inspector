package com.taxiinspector.ui.companies

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

/**
 * Hosts [CompanyEditorScreen] and leaves the destination once the company is durably stored.
 * [companyId] is null when creating; [onCancel] is null on a first run, where there is no
 * meter to return to until a company exists.
 */
@Composable
fun CompanyEditorRoute(
    companyId: String?,
    onSaved: () -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as TaxiInspectorApplication
    val viewModel: CompanyEditorViewModel = viewModel(
        factory = remember(application, companyId) {
            CompanyEditorViewModel.factory(application.appContainer.rideRepository, companyId)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnSaved by rememberUpdatedState(onSaved)

    LaunchedEffect(viewModel) {
        viewModel.saved.collect { currentOnSaved() }
    }

    CompanyEditorScreen(
        state = state,
        onAction = viewModel::onAction,
        onCancel = onCancel,
        modifier = modifier,
    )
}
