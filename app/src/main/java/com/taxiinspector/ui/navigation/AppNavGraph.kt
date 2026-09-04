package com.taxiinspector.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.taxiinspector.ui.meter.MeterRoute
import com.taxiinspector.ui.tariff.TariffRoute

/** The app's destinations. History and Ride Detail arrive with Phase 7. */
object Destinations {
    const val METER = "meter"
    const val TARIFF = "tariff"
}

@Composable
fun AppNavGraph(startDestination: String, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(Destinations.METER) {
            MeterRoute(onEditTariff = { navController.navigate(Destinations.TARIFF) })
        }
        composable(Destinations.TARIFF) {
            // Tariff is the start destination on first run, so there is nothing to return
            // to until a tariff exists; after that it is always reached from the meter.
            val canReturnToMeter = navController.previousBackStackEntry != null
            TariffRoute(
                onSaved = {
                    if (canReturnToMeter) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Destinations.METER) {
                            popUpTo(Destinations.TARIFF) { inclusive = true }
                        }
                    }
                },
                onCancel = if (canReturnToMeter) {
                    { navController.popBackStack() }
                } else {
                    null
                },
            )
        }
    }
}
