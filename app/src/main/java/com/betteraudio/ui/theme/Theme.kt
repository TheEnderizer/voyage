package com.betteraudio.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF231405),
    primaryContainer = AmberDeep,
    onPrimaryContainer = Cream,

    secondary = Cream,
    onSecondary = Color(0xFF3A2A12),
    secondaryContainer = InkRaisedHi,
    onSecondaryContainer = Cream,

    tertiary = Teal,
    onTertiary = Color(0xFF06302A),

    background = Ink,
    onBackground = OnInk,
    surface = Ink,
    onSurface = OnInk,
    surfaceVariant = InkRaised,
    onSurfaceVariant = OnInkMuted,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkRaisedHi,
    surfaceContainerHighest = InkRaisedHi,
    surfaceContainerLow = InkRaised,
    surfaceContainerLowest = Ink,
    outline = InkOutline,
    outlineVariant = InkOutline,

    error = Coral,
    onError = Color(0xFF3A0E08),
    errorContainer = Color(0xFF5C271F),
    onErrorContainer = Color(0xFFFFD9D3)
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AmberBright,
    onPrimaryContainer = Color(0xFF3A1E04),

    secondary = AmberDeep,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = PaperRaisedHi,
    onSecondaryContainer = OnPaper,

    tertiary = Color(0xFF12897A),
    onTertiary = Color(0xFFFFFFFF),

    background = Paper,
    onBackground = OnPaper,
    surface = Paper,
    onSurface = OnPaper,
    surfaceVariant = PaperRaisedHi,
    onSurfaceVariant = OnPaperMuted,
    surfaceContainer = PaperRaised,
    surfaceContainerHigh = PaperRaisedHi,
    surfaceContainerHighest = PaperRaisedHi,
    surfaceContainerLow = PaperRaised,
    surfaceContainerLowest = PaperRaised,
    outline = PaperOutline,
    outlineVariant = PaperOutline,

    error = Coral,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD4),
    onErrorContainer = Color(0xFF410001)
)

@Composable
fun VoyageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Brand palette is the identity — dynamic color is opt-in only.
    dynamicColor: Boolean = false,
    // When set, the whole app recolours to the playing book's cover art.
    coverArtPath: String? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val coverScheme = rememberCoverScheme(coverArtPath, baseScheme, darkTheme)
    val colorScheme = rememberAnimatedScheme(coverScheme ?: baseScheme)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}
