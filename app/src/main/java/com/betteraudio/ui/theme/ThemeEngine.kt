package com.betteraudio.ui.theme

/*
 * Material You color-scheme generation, ported from ArchiveTune
 * (https://github.com/rukamori/ArchiveTune), GPL-3.0 License.
 * Original: © Rukamori — github.com/rukamori. Adapted for Voyage.
 */

import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.ktx.toHct
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.min

/** Fallback seed when no wallpaper/cover/custom color is available (Voyage's brand amber). */
val DefaultThemeColor = Amber

/** A user-authored 4-role custom palette (Theme Creator / imported theme). */
data class ThemeSeedPalette(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val neutral: Color,
)

/**
 * Re-emits [targetColorScheme] with every role individually animated, so the whole app
 * transitions smoothly when the seed color changes (wallpaper swap, book change, palette edit)
 * instead of snapping.
 */
@Composable
fun animateColorScheme(
    targetColorScheme: ColorScheme,
    animationSpec: FiniteAnimationSpec<Color>,
): ColorScheme = ColorScheme(
    primary = animateColorAsState(targetColorScheme.primary, animationSpec, label = "primary").value,
    onPrimary = animateColorAsState(targetColorScheme.onPrimary, animationSpec, label = "onPrimary").value,
    primaryContainer = animateColorAsState(targetColorScheme.primaryContainer, animationSpec, label = "primaryContainer").value,
    onPrimaryContainer = animateColorAsState(targetColorScheme.onPrimaryContainer, animationSpec, label = "onPrimaryContainer").value,
    inversePrimary = animateColorAsState(targetColorScheme.inversePrimary, animationSpec, label = "inversePrimary").value,
    secondary = animateColorAsState(targetColorScheme.secondary, animationSpec, label = "secondary").value,
    onSecondary = animateColorAsState(targetColorScheme.onSecondary, animationSpec, label = "onSecondary").value,
    secondaryContainer = animateColorAsState(targetColorScheme.secondaryContainer, animationSpec, label = "secondaryContainer").value,
    onSecondaryContainer = animateColorAsState(targetColorScheme.onSecondaryContainer, animationSpec, label = "onSecondaryContainer").value,
    tertiary = animateColorAsState(targetColorScheme.tertiary, animationSpec, label = "tertiary").value,
    onTertiary = animateColorAsState(targetColorScheme.onTertiary, animationSpec, label = "onTertiary").value,
    tertiaryContainer = animateColorAsState(targetColorScheme.tertiaryContainer, animationSpec, label = "tertiaryContainer").value,
    onTertiaryContainer = animateColorAsState(targetColorScheme.onTertiaryContainer, animationSpec, label = "onTertiaryContainer").value,
    background = animateColorAsState(targetColorScheme.background, animationSpec, label = "background").value,
    onBackground = animateColorAsState(targetColorScheme.onBackground, animationSpec, label = "onBackground").value,
    surface = animateColorAsState(targetColorScheme.surface, animationSpec, label = "surface").value,
    onSurface = animateColorAsState(targetColorScheme.onSurface, animationSpec, label = "onSurface").value,
    surfaceVariant = animateColorAsState(targetColorScheme.surfaceVariant, animationSpec, label = "surfaceVariant").value,
    onSurfaceVariant = animateColorAsState(targetColorScheme.onSurfaceVariant, animationSpec, label = "onSurfaceVariant").value,
    surfaceTint = animateColorAsState(targetColorScheme.surfaceTint, animationSpec, label = "surfaceTint").value,
    inverseSurface = animateColorAsState(targetColorScheme.inverseSurface, animationSpec, label = "inverseSurface").value,
    inverseOnSurface = animateColorAsState(targetColorScheme.inverseOnSurface, animationSpec, label = "inverseOnSurface").value,
    error = animateColorAsState(targetColorScheme.error, animationSpec, label = "error").value,
    onError = animateColorAsState(targetColorScheme.onError, animationSpec, label = "onError").value,
    errorContainer = animateColorAsState(targetColorScheme.errorContainer, animationSpec, label = "errorContainer").value,
    onErrorContainer = animateColorAsState(targetColorScheme.onErrorContainer, animationSpec, label = "onErrorContainer").value,
    outline = animateColorAsState(targetColorScheme.outline, animationSpec, label = "outline").value,
    outlineVariant = animateColorAsState(targetColorScheme.outlineVariant, animationSpec, label = "outlineVariant").value,
    scrim = animateColorAsState(targetColorScheme.scrim, animationSpec, label = "scrim").value,
    surfaceBright = animateColorAsState(targetColorScheme.surfaceBright, animationSpec, label = "surfaceBright").value,
    surfaceDim = animateColorAsState(targetColorScheme.surfaceDim, animationSpec, label = "surfaceDim").value,
    surfaceContainer = animateColorAsState(targetColorScheme.surfaceContainer, animationSpec, label = "surfaceContainer").value,
    surfaceContainerLow = animateColorAsState(targetColorScheme.surfaceContainerLow, animationSpec, label = "surfaceContainerLow").value,
    surfaceContainerLowest = animateColorAsState(targetColorScheme.surfaceContainerLowest, animationSpec, label = "surfaceContainerLowest").value,
    surfaceContainerHigh = animateColorAsState(targetColorScheme.surfaceContainerHigh, animationSpec, label = "surfaceContainerHigh").value,
    surfaceContainerHighest = animateColorAsState(targetColorScheme.surfaceContainerHighest, animationSpec, label = "surfaceContainerHighest").value,
)

/** [palette]'s 4 seeds generate one [ColorScheme] with an independent hue per role. */
fun exactPaletteColorScheme(palette: ThemeSeedPalette, isDark: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = palette.primary,
    isDark = isDark,
    isAmoled = false,
    primary = palette.primary,
    secondary = palette.secondary,
    tertiary = palette.tertiary,
    neutral = palette.neutral,
    style = paletteStyleFor(palette.primary),
)

/** A single [keyColor] used for all four roles — the common "one accent" Material You case. */
fun materialKolorDynamicColorScheme(
    keyColor: Color,
    isDark: Boolean,
    contrastLevel: Double = 0.0,
    style: PaletteStyle,
): ColorScheme = dynamicColorScheme(
    seedColor = keyColor,
    isDark = isDark,
    isAmoled = false,
    style = style,
    contrastLevel = contrastLevel,
)

/** Picks a palette style from the seed's chroma: low-chroma seeds read as near-grey, so they get
 *  a flatter (Monochrome/Neutral) style instead of a saturated TonalSpot. */
fun paletteStyleFor(seedColor: Color): PaletteStyle {
    val chroma = seedColor.toHct().chroma
    return when {
        chroma < 4.0 -> PaletteStyle.Monochrome
        chroma < 12.0 -> PaletteStyle.Neutral
        else -> PaletteStyle.TonalSpot
    }
}

private fun Int.toComposeColor(): Color = Color(this.toLong() and 0xFFFFFFFFL)

/** Dominant/vibrant swatch of [this] bitmap, for a single-seed Material You theme. */
fun Bitmap.extractThemeColor(): Color {
    val palette = Palette.from(this).maximumColorCount(16).generate()
    val swatch = palette.vibrantSwatch
        ?: palette.dominantSwatch
        ?: palette.mutedSwatch
        ?: palette.lightVibrantSwatch
        ?: palette.darkVibrantSwatch
        ?: palette.lightMutedSwatch
        ?: palette.darkMutedSwatch
    return swatch?.rgb?.toComposeColor() ?: DefaultThemeColor
}

/** Two hue-distant swatches (brighter first) for a gradient backdrop. */
fun Bitmap.extractGradientColors(): List<Color> {
    val palette = Palette.from(this).maximumColorCount(48).generate()
    val swatches = palette.swatches.filter { it.population > 0 }.sortedByDescending { it.population }
    if (swatches.isEmpty()) return listOf(Color(0xFF595959), Color(0xFF0D0D0D))

    val first = swatches.first()
    val firstHsv = FloatArray(3)
    android.graphics.Color.colorToHSV(first.rgb, firstHsv)

    val second = swatches.drop(1).maxByOrNull { candidate ->
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(candidate.rgb, hsv)
        val hueDiffRaw = abs(hsv[0] - firstHsv[0])
        val hueDiff = min(hueDiffRaw, 360f - hueDiffRaw) / 180f
        val satDiff = abs(hsv[1] - firstHsv[1])
        val valueDiff = abs(hsv[2] - firstHsv[2])
        hueDiff * 0.65f + satDiff * 0.2f + valueDiff * 0.15f
    } ?: first

    return listOf(first.rgb.toComposeColor(), second.rgb.toComposeColor())
        .sortedByDescending { it.luminance() }
}

/** AMOLED-black override: forces surface/background to true black when dark + enabled. */
fun ColorScheme.pureBlack(apply: Boolean): ColorScheme =
    if (apply) copy(surface = Color.Black, background = Color.Black) else this

@Serializable
data class ThemeExportV1(
    val version: Int = 1,
    val name: String? = null,
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val neutral: String,
)

/** Encodes/decodes a [ThemeSeedPalette] as JSON (export/import files) or as a DataStore string
 *  value (`seedPalette:<base64>` prefix, stored directly in `CUSTOM_THEME_COLOR`). */
object ThemeSeedPaletteCodec {
    private const val PREFERENCE_PREFIX = "seedPalette:"
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    fun encodeForPreference(palette: ThemeSeedPalette, name: String? = null): String {
        val payload = json.encodeToString(palette.toExport(name))
        val b64 = Base64.encodeToString(payload.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return PREFERENCE_PREFIX + b64
    }

    fun decodeFromPreference(value: String): ThemeSeedPalette? {
        if (!value.startsWith(PREFERENCE_PREFIX)) return null
        val decoded = decodeBase64(value.removePrefix(PREFERENCE_PREFIX)) ?: return null
        return decodeFromJson(decoded)
    }

    fun encodeAsJson(palette: ThemeSeedPalette, name: String? = null): String =
        json.encodeToString(palette.toExport(name))

    fun decodeFromJson(text: String): ThemeSeedPalette? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            val obj = json.parseToJsonElement(trimmed).jsonObject
            val version = obj["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
            if (version != 1) return@runCatching null
            fun getColor(key: String) = obj[key]?.jsonPrimitive?.content?.toColorOrNull()
            val primary = getColor("primary") ?: return@runCatching null
            ThemeSeedPalette(
                primary = primary,
                secondary = getColor("secondary") ?: primary,
                tertiary = getColor("tertiary") ?: primary,
                neutral = getColor("neutral") ?: primary,
            )
        }.getOrNull()
    }

    fun extractNameFromJsonOrNull(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return runCatching {
            (json.parseToJsonElement(trimmed) as JsonElement).jsonObject["name"]
                ?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun extractNameFromPreference(value: String): String? {
        if (!value.startsWith(PREFERENCE_PREFIX)) return null
        val decoded = decodeBase64(value.removePrefix(PREFERENCE_PREFIX)) ?: return null
        return extractNameFromJsonOrNull(decoded)
    }

    private fun decodeBase64(b64: String): String? = runCatching {
        Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP).toString(Charsets.UTF_8)
    }.getOrNull()

    private fun ThemeSeedPalette.toExport(name: String?) = ThemeExportV1(
        name = name,
        primary = primary.toHexArgbString(),
        secondary = secondary.toHexArgbString(),
        tertiary = tertiary.toHexArgbString(),
        neutral = neutral.toHexArgbString(),
    )

    private fun Color.toHexArgbString(): String = String.format("#%08X", this.toArgb())

    private fun String.toColorOrNull(): Color? {
        val normalized = trim()
        if (normalized.isEmpty()) return null
        return runCatching {
            val withHash = if (normalized.startsWith("#")) normalized else "#$normalized"
            Color(android.graphics.Color.parseColor(withHash))
        }.getOrNull()
    }
}
