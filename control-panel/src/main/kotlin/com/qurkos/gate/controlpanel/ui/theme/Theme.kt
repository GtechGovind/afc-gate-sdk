package com.qurkos.gate.controlpanel.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ControlPanelColors =
    darkColorScheme(
        primary = Cyan500,
        onPrimary = Navy950,
        primaryContainer = SurfaceHighlight,
        onPrimaryContainer = Cyan300,
        secondary = Green500,
        onSecondary = Navy950,
        tertiary = Amber500,
        error = Red500,
        onError = White,
        errorContainer = Red800,
        background = Navy950,
        onBackground = White,
        surface = Navy900,
        onSurface = White,
        surfaceVariant = Navy800,
        onSurfaceVariant = Slate300,
        outline = Slate600,
        outlineVariant = Slate700,
    )

/** Applies the AFC dark operations-console design system to [content]. */
@Composable
fun AfcControlPanelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ControlPanelColors,
        typography = ControlPanelTypography,
        content = content,
    )
}
