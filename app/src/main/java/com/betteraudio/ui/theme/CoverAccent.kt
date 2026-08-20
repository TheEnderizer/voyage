package com.betteraudio.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * How many colours Palette is allowed to quantize a cover down to. Both automatic accent pickers
 * ([com.betteraudio.ui.theme.extractThemeColor] for Material You, `recolouredFrom` for Immersive)
 * already work from this number, so a picker built on it offers exactly the set the app was
 * choosing between — not a second, differently-derived list.
 */
const val COVER_PALETTE_SIZE = 16

/**
 * The cover the app is themed from right now — the same path [VoyageTheme] was handed, which may
 * belong to the playing book, its series, or the media session. Null when nothing is playing and
 * no book has been opened.
 *
 * Settings reads this so the accent picker can show the live palette without re-deriving which
 * cover won; the theme is the only place that decision is made.
 */
val LocalThemeCoverPath = staticCompositionLocalOf<String?> { null }

/**
 * The cover's quantized palette, most-populous colour first, or empty if [coverPath] is missing or
 * undecodable. Population order is stable for a given file and puts the colours that actually
 * cover area in the art at the front, which is the order a person scanning swatches expects.
 */
suspend fun coverSwatchColors(coverPath: String?): List<Color> {
    if (coverPath.isNullOrBlank()) return emptyList()
    return withContext(Dispatchers.IO) {
        try {
            val file = File(coverPath)
            if (!file.exists()) return@withContext emptyList()
            // inSampleSize 2 matches what both automatic pickers decode at, so the swatches shown
            // are the swatches they saw rather than a slightly different quantization.
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeFile(coverPath, opts) ?: return@withContext emptyList()
            Palette.from(bmp).maximumColorCount(COVER_PALETTE_SIZE).generate()
                .swatches
                .sortedByDescending { it.population }
                .map { Color(it.rgb.toLong() and 0xFFFFFFFFL) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** [coverSwatchColors], decoded off the main thread and cached against [coverPath]. */
@Composable
fun rememberCoverSwatches(coverPath: String?): List<Color> {
    var swatches by remember(coverPath) { mutableStateOf(emptyList<Color>()) }
    LaunchedEffect(coverPath) { swatches = coverSwatchColors(coverPath) }
    return swatches
}
