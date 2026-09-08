package com.taxiinspector.ui.companies

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxiinspector.TaxiInspectorApplication

/** Hosts [CompanyListScreen] and performs its navigation. */
@Composable
fun CompanyListRoute(
    onBack: () -> Unit,
    onAddCompany: () -> Unit,
    onEditCompany: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as TaxiInspectorApplication
    val viewModel: CompanyListViewModel = viewModel(
        factory = remember(application) {
            CompanyListViewModel.factory(application.appContainer.rideRepository)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    CompanyListScreen(
        state = state,
        onAction = { action ->
            when (action) {
                CompanyListAction.Back -> onBack()
                CompanyListAction.AddCompany -> onAddCompany()
                is CompanyListAction.EditCompany -> onEditCompany(action.id)
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
    )
}
