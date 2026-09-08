package com.taxiinspector.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taxiinspector.ui.companies.CompanyEditorRoute
import com.taxiinspector.ui.companies.CompanyListRoute
import com.taxiinspector.ui.history.HistoryRoute
import com.taxiinspector.ui.history.RideDetailRoute
import com.taxiinspector.ui.meter.MeterRoute

/** The app's five destinations. */
object Destinations {
    const val METER = "meter"
    const val COMPANIES = "companies"
    const val COMPANY_ID_ARGUMENT = "companyId"

    /** The optional argument is absent when creating, so one route serves both modes. */
    const val COMPANY_EDITOR = "companies/editor?$COMPANY_ID_ARGUMENT={$COMPANY_ID_ARGUMENT}"
    const val HISTORY = "history"
    const val RIDE_ID_ARGUMENT = "rideId"
    const val RIDE_DETAIL = "ride/{$RIDE_ID_ARGUMENT}"

    fun companyEditor(companyId: String? = null): String = if (companyId == null) {
        "companies/editor"
    } else {
        "companies/editor?$COMPANY_ID_ARGUMENT=${Uri.encode(companyId)}"
    }

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
                onManageCompanies = { navController.navigate(Destinations.COMPANIES) },
                onViewHistory = { navController.navigate(Destinations.HISTORY) },
            )
        }
        composable(Destinations.COMPANIES) {
            CompanyListRoute(
                onBack = { navController.popBackStack() },
                onAddCompany = { navController.navigate(Destinations.companyEditor()) },
                onEditCompany = { companyId ->
                    navController.navigate(Destinations.companyEditor(companyId))
                },
            )
        }
        composable(
            route = Destinations.COMPANY_EDITOR,
            arguments = listOf(
                navArgument(Destinations.COMPANY_ID_ARGUMENT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            // The editor is the start destination on a first run, so there is nothing to
            // return to until a company exists; after that it is reached from the list.
            val canReturn = navController.previousBackStackEntry != null
            CompanyEditorRoute(
                companyId = backStackEntry.arguments?.getString(Destinations.COMPANY_ID_ARGUMENT),
                onSaved = {
                    if (canReturn) {
                        navController.popBackStack()
                    } else {
                        navController.navigate(Destinations.METER) {
                            popUpTo(Destinations.COMPANY_EDITOR) { inclusive = true }
                        }
                    }
                },
                onCancel = if (canReturn) {
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
