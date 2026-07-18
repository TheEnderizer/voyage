package com.betteraudio.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Placeholder for the widget maker v2 editor (free-placement canvas, rich per-element styling —
 * see the widget/model + widget/render packages already rewired to the new backend). Temporary:
 * keeps the "widget_editor" nav route and Settings entry point compiling/functional between the
 * Phase 2 backend swap and the Phase 3 editor rebuild.
 */
@Composable
fun WidgetEditorScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Widget editor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "The redesigned widget editor is coming in the next update.",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
