package com.betteraudio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Generous, soft "squircle"-style corner radii — the modern, non-traditional
 * look. Cards and surfaces lean large; pills use [Pill].
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small      = RoundedCornerShape(14.dp),
    medium     = RoundedCornerShape(20.dp),
    large      = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Fully-rounded pill shape for nav bars, chips, and floating controls. */
val Pill = RoundedCornerShape(percent = 50)
