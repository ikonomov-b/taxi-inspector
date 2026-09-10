package com.taxiinspector.ui.history

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.taxiinspector.TaxiInspectorApplication
import java.io.File

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
            RideDetailViewModel.factory(
                rideId = rideId,
                repository = application.appContainer.rideRepository,
                traceStore = application.appContainer.traceStore,
            )
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val currentOnBack by rememberUpdatedState(onBack)

    LaunchedEffect(viewModel) {
        viewModel.deleted.collect { currentOnBack() }
    }

    LaunchedEffect(viewModel) {
        viewModel.effect.collect { effect ->
            when (effect) {
                is RideDetailEffect.ShareFiles -> context.startActivity(
                    shareIntent(context, effect.files, effect.mimeType),
                )
            }
        }
    }

    RideDetailScreen(
        state = state,
        onAction = { action ->
            if (action == RideDetailAction.Back) currentOnBack() else viewModel.onAction(action)
        },
        modifier = modifier,
    )
}

/**
 * Hands the trace out through the app's own FileProvider rather than a readable path, so the
 * receiving app -- Locus Map for a GPX -- gets a one-off grant and nothing else is exposed.
 */
private fun shareIntent(
    context: android.content.Context,
    files: List<File>,
    mimeType: String,
): Intent {
    val authority = "${'$'}{context.packageName}.traces"
    val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
    val send = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    send.type = mimeType
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
