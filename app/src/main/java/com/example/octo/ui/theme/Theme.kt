package com.example.octo.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = OctoTeal80,
    onPrimary = TealOnDark,
    primaryContainer = TealContainerDark,
    onPrimaryContainer = OctoTeal90,
    secondary = OctoPurple80,
    onSecondary = PurpleOnDark,
    secondaryContainer = PurpleContainerDark,
    onSecondaryContainer = OctoPurple90,
    tertiary = OctoCoral80,
    onTertiary = CoralOnDark,
    background = Abyss,
    onBackground = FoamDark,
    surface = AbyssSurface,
    onSurface = FoamDark,
    surfaceVariant = AbyssVariant,
    onSurfaceVariant = FoamMuted,
)

private val LightColors = lightColorScheme(
    primary = OctoTeal40,
    onPrimary = Color.White,
    primaryContainer = TealContainerLight,
    onPrimaryContainer = TealOnContainerLight,
    secondary = OctoPurple40,
    onSecondary = Color.White,
    secondaryContainer = PurpleContainerLight,
    onSecondaryContainer = PurpleOnContainerLight,
    tertiary = OctoCoral40,
    onTertiary = Color.White,
    background = Foam,
    onBackground = InkDeep,
    surface = FoamSurface,
    onSurface = InkDeep,
    surfaceVariant = FoamVariant,
    onSurfaceVariant = InkMuted,
)

@Composable
fun OctoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand identity first: dynamic color would wash out the deep-sea palette.
    // (Pass true to restore Material You on Android 12+.)
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
