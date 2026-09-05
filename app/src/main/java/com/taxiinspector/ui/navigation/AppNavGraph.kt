package com.taxiinspector.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taxiinspector.ui.history.HistoryRoute
import com.taxiinspector.ui.history.RideDetailRoute
import com.taxiinspector.ui.meter.MeterRoute
import com.taxiinspector.ui.tariff.TariffRoute

/** The app's four destinations. */
object Destinations {
    const val METER = "meter"
    const val TARIFF = "tariff"
    const val HISTORY = "history"
    const val RIDE_ID_ARGUMENT = "rideId"
    const val RIDE_DETAIL = "ride/{$RIDE_ID_ARGUMENT}"

    fun rideDetail(rideId: String): String = "ride/${Uri.encode(rideId)}"
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
            MeterRoute(
                onEditTariff = { navController.navigate(Destinations.TARIFF) },
                onViewHistory = { navController.navigate(Destinations.HISTORY) },
            )
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
        composable(Destinations.HISTORY) {
            HistoryRoute(
                onBack = { navController.popBackStack() },
                onRideSelected = { rideId ->
                    navController.navigate(Destinations.rideDetail(rideId))
                },
            )
        }
        composable(
            route = Destinations.RIDE_DETAIL,
            arguments = listOf(
                navArgument(Destinations.RIDE_ID_ARGUMENT) { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val rideId = requireNotNull(
                backStackEntry.arguments?.getString(Destinations.RIDE_ID_ARGUMENT),
            )
            RideDetailRoute(
                rideId = rideId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
