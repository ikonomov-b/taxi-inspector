package com.taxiinspector.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val MeterColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = CabYellow,
    onSecondary = Ink,
    tertiary = MeterGreen,
    onTertiary = Ink,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Line,
    onSurfaceVariant = MutedInk,
    outline = MutedInk,
    error = Danger,
    onError = Paper,
)

@Composable
fun TaxiInspectorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeterColorScheme,
        typography = MeterTypography,
        content = content,
    )
}
