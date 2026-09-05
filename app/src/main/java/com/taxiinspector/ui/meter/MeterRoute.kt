package com.taxiinspector.ui.meter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxiinspector.TaxiInspectorApplication
import com.taxiinspector.tracking.RideServiceOwnershipConnection
import com.taxiinspector.tracking.RideTrackingCommands
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val MESSAGE_VISIBLE_MILLIS = 4_000L

/**
 * Connects the meter screen to Android: it reads permission and GPS state, runs permission
 * dialogs, opens Settings, sends explicit service commands, and binds to a live service
 * before recovery. The ViewModel stays free of every one of those Android types.
 */
@Composable
fun MeterRoute(
    onEditTariff: () -> Unit,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as TaxiInspectorApplication
    val viewModel: MeterViewModel = viewModel(
        factory = remember(application) {
            MeterViewModel.factory(application.appContainer.rideRepository)
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val ownershipConnection = remember(application) {
        RideServiceOwnershipConnection(application)
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val environment = context.readMeterEnvironment()
        viewModel.onAction(
            MeterAction.PermissionResult(environment, environment.hasPreciseLocationPermission),
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        val environment = context.readMeterEnvironment()
        viewModel.onAction(
            MeterAction.PermissionResult(environment, environment.hasNotificationPermission),
        )
    }

    // Permissions and the GPS provider can change while the app is away, so they are re-read
    // every time the screen becomes visible rather than cached in the ViewModel.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAction(MeterAction.EnvironmentChanged(context.readMeterEnvironment()))
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Collected only while started, so Start and Resume always reach a visible activity.
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    MeterEffect.RequestPreciseLocationPermission ->
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)

                    MeterEffect.RequestNotificationPermission ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            val environment = context.readMeterEnvironment()
                            viewModel.onAction(MeterAction.PermissionResult(environment, true))
                        }

                    MeterEffect.OpenAppSettings -> context.openAppSettings()
                    MeterEffect.OpenLocationSettings -> context.openLocationSettings()
                    is MeterEffect.SendCommand ->
                        RideTrackingCommands.sendFromVisibleActivity(context, effect.command)

                    is MeterEffect.CheckServiceOwnership -> launch {
                        ownershipConnection.withOwnership { owner ->
                            viewModel.onAction(
                                MeterAction.ServiceOwnershipChecked(
                                    rideId = effect.rideId,
                                    isOwnedByLiveService = owner?.ownsRide(effect.rideId) == true,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(MESSAGE_VISIBLE_MILLIS)
            viewModel.onAction(MeterAction.MessageShown)
        }
    }

    MeterScreen(
        state = state,
        onAction = { action ->
            when (action) {
                MeterAction.EditTariff -> onEditTariff()
                MeterAction.ViewHistory -> onViewHistory()
                else -> viewModel.onAction(action)
            }
        },
        modifier = modifier,
    )
}

private fun Context.readMeterEnvironment(): MeterEnvironment = MeterEnvironment(
    hasPreciseLocationPermission = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED,
    hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED,
    isGpsProviderEnabled = runCatching {
        getSystemService(LocationManager::class.java)
            ?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
    }.getOrDefault(false),
)

private fun Context.openAppSettings() {
    startActivitySafely(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

private fun Context.openLocationSettings() {
    startActivitySafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private fun Context.startActivitySafely(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
