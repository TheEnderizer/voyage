package com.betteraudio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.betteraudio.R
import java.io.File

/** App-wide font (matches the ArchiveTune-derived theme). The default, and the fallback whenever a
 *  user-chosen one is missing or unreadable. */
val AppFontFamily = FontFamily(Font(R.font.poppins))

/**
 * Expressive type scale: big, tight display headings for a modern feel,
 * comfortable body text for long-form browsing.
 *
 * A function of the family rather than a constant, so the user's own font can be dropped in
 * without restating the ramp: the sizes, weights, line heights and tracking below are the design,
 * and the face is the one thing about them that is a preference. See
 * [com.betteraudio.data.diskstore.CustomFontStore] for where a chosen face comes from.
 */
fun voyageTypography(family: FontFamily): Typography = Typography(
    displayLarge  = TextStyle(fontFamily = family, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    displaySmall  = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),

    headlineMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    headlineSmall  = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),

    titleLarge  = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall  = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),

    bodyLarge  = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall  = TextStyle(fontFamily = family, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),

    labelLarge = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = family, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = family, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp)
)

/** The shipped ramp, in Poppins. */
val Typography = voyageTypography(AppFontFamily)

/**
 * The [FontFamily] for a user-chosen font file, or null to stay on Poppins.
 *
 * Null covers three cases the caller should not have to tell apart: nothing is chosen, the recorded
 * file is gone (the library folder was moved, or `.voyage/fonts/` was cleaned out), or Android will
 * not build a family from it. All three mean the same thing on screen — the app is in its own type
 * — and none of them should be a crash on a cold start, which is why the family is only ever built
 * here, behind a `runCatching`, rather than at the point it is read.
 *
 * **One weight, several asks.** A font file usually carries one weight, and the ramp above asks for
 * five. Compose resolves a missing weight by synthesising it (faux-bolding), so a single-weight
 * font renders everywhere and simply looks flatter where Voyage asks for ExtraBold — which is worth
 * saying in the UI, and is not worth refusing an import over.
 */
@Composable
fun rememberCustomFontFamily(path: String): FontFamily? = remember(path) {
    if (path.isBlank()) return@remember null
    runCatching {
        val file = File(path)
        if (!file.isFile || !file.canRead() || file.length() == 0L) return@runCatching null
        // Parse it here rather than letting Compose find out. A file-backed `Font` loads lazily, so
        // an unreadable or corrupt one does not fail where it is built — it throws inside text
        // layout, which is to say on every screen in the app at once. The file was validated the
        // same way when it was imported; this is the second half of that check, for the case where
        // it stopped being readable in between (storage permission revoked, library folder on a
        // volume that is no longer mounted, the file replaced by something else).
        val typeface = android.graphics.Typeface.createFromFile(file)
        if (typeface == null || typeface == android.graphics.Typeface.DEFAULT) return@runCatching null
        FontFamily(Font(file))
    }.getOrNull()
}
