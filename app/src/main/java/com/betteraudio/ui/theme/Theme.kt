package com.betteraudio.ui.theme

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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

private val expressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Decodes [coverPath] off-main and extracts a single Material You seed color from it. */
@Composable
private fun rememberCoverSeedColor(coverPath: String?): Color? {
    var color by remember(coverPath) { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverPath) {
        if (coverPath.isNullOrBlank()) { color = null; return@LaunchedEffect }
        color = withContext(Dispatchers.IO) {
            try {
                val file = File(coverPath)
                if (!file.exists()) return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(coverPath, opts)?.extractThemeColor()
            } catch (_: Exception) { null }
        }
    }
    return color
}

@Composable
fun VoyageTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Which of the two app looks is active (user-chosen on first launch / in Settings → Theme).
    appTheme: AppTheme = AppTheme.MATERIAL_YOU,
    // Colour source for the Material You theme; Immersive always uses the cover.
    colorSource: ThemeColorSource = ThemeColorSource.WALLPAPER,
    // Only used when colorSource == CUSTOM: "default" | "#AARRGGBB" | preset id |
    // "seedPalette:<base64>" (see ThemeSeedPaletteCodec).
    customThemeColor: String = "default",
    // AMOLED-black surfaces; Material You + dark only (Immersive's backdrop is the blurred cover).
    pureBlack: Boolean = false,
    // When set, the whole app recolours to the playing book's cover art.
    coverArtPath: String? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val targetScheme: ColorScheme = when (appTheme) {
        AppTheme.IMMERSIVE -> {
            // Static brand base, recoloured from the playing cover; ALL text tints toward the
            // cover accent (tintText = true) for the full-bleed immersive look.
            val baseScheme = if (darkTheme) DarkColors else LightColors
            rememberCoverScheme(coverArtPath, baseScheme, darkTheme, tintText = true) ?: baseScheme
        }
        AppTheme.MATERIAL_YOU -> {
            val useSystemWallpaper = colorSource == ThemeColorSource.WALLPAPER &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            val base = when {
                useSystemWallpaper ->
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                colorSource == ThemeColorSource.WALLPAPER -> {
                    // <API-31 fallback: no system dynamic colour available, use the brand seed.
                    val style = remember(DefaultThemeColor) { paletteStyleFor(DefaultThemeColor) }
                    materialKolorDynamicColorScheme(DefaultThemeColor, darkTheme, style = style)
                }
                colorSource == ThemeColorSource.COVER -> {
                    val seed = rememberCoverSeedColor(coverArtPath) ?: DefaultThemeColor
                    val style = remember(seed) { paletteStyleFor(seed) }
                    materialKolorDynamicColorScheme(seed, darkTheme, style = style)
                }
                else -> { // CUSTOM
                    val seedPalette = remember(customThemeColor) {
                        ThemeSeedPaletteCodec.decodeFromPreference(customThemeColor)
                    }
                    when {
                        seedPalette != null -> exactPaletteColorScheme(seedPalette, darkTheme)
                        customThemeColor.startsWith("#") -> {
                            val seed = remember(customThemeColor) {
                                runCatching { Color(android.graphics.Color.parseColor(customThemeColor)) }
                                    .getOrDefault(DefaultThemeColor)
                            }
                            val style = remember(seed) { paletteStyleFor(seed) }
                            materialKolorDynamicColorScheme(seed, darkTheme, style = style)
                        }
                        else -> { // "default" or an unrecognized value — fall back to the brand seed
                            val style = remember(DefaultThemeColor) { paletteStyleFor(DefaultThemeColor) }
                            materialKolorDynamicColorScheme(DefaultThemeColor, darkTheme, style = style)
                        }
                    }
                }
            }
            if (darkTheme && pureBlack) base.pureBlack(true) else base
        }
    }

    val colorScheme = animateColorScheme(targetScheme, tween(700))

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalAppTheme provides appTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = expressiveShapes,
            content = content
        )
    }
}
