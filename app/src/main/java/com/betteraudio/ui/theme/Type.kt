package com.betteraudio.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.betteraudio.R

/** App-wide font (matches the ArchiveTune-derived theme). */
val AppFontFamily = FontFamily(Font(R.font.poppins))

/**
 * Expressive type scale: big, tight display headings for a modern feel,
 * comfortable body text for long-form browsing.
 */
val Typography = Typography(
    displayLarge  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-1).sp),
    displayMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    displaySmall  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),

    headlineMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 23.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    headlineSmall  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp),

    titleLarge  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 27.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),

    bodyLarge  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall  = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),

    labelLarge = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = AppFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp)
)
