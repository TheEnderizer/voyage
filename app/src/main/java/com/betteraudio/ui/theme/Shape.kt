package com.betteraudio.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape

// AppShapes (a Shapes() set for MaterialTheme) was removed here — unreferenced everywhere;
// Theme.kt's own `expressiveShapes` is what VoyageTheme actually passes to MaterialTheme.

/** Fully-rounded pill shape for nav bars, chips, and floating controls. */
val Pill = RoundedCornerShape(percent = 50)
