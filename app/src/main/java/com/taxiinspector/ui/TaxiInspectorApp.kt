package com.taxiinspector.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.taxiinspector.TaxiInspectorApplication
import com.taxiinspector.ui.navigation.AppNavGraph
import com.taxiinspector.ui.navigation.Destinations
import com.taxiinspector.ui.theme.TaxiInspectorTheme

/**
 * The application's composable entry point. A first run with no saved tariff opens the
 * tariff destination directly, so the meter is never shown without rates to bill with.
 */
@Composable
fun TaxiInspectorApp() {
    val context = LocalContext.current
    val repository = remember(context) {
        (context.applicationContext as TaxiInspectorApplication).appContainer.rideRepository
    }
    // Resolved before the first destination is composed, so the meter never flashes past.
    var startDestination by remember(repository) { mutableStateOf<String?>(null) }
    LaunchedEffect(repository) {
        startDestination = if (repository.currentTariff() == null) {
            Destinations.TARIFF
        } else {
            Destinations.METER
        }
    }

    TaxiInspectorTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            startDestination?.let { AppNavGraph(startDestination = it) }
        }
    }
}
