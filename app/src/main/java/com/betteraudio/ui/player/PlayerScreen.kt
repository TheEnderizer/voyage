package com.betteraudio.ui.player

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.ui.haptics.HapticTextButton
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme
import com.betteraudio.ui.immersive.player.PlayerContent as ImmersivePlayerContent
import com.betteraudio.ui.material.player.PlayerContent as MaterialPlayerContent

/** Dispatches to the Immersive or Material You implementation — see CLAUDE.md's theming section
 *  for the split convention. The real content lives in `ui/immersive/player/PlayerScreen.kt` /
 *  `ui/material/player/PlayerScreen.kt`; this file only exists so PlayerSheet's nested NavHost
 *  keeps calling `PlayerContent(...)` from a stable package. */
@Composable
fun PlayerContent(
    onCollapse: () -> Unit,
    startPlaying: Boolean = true,
    /** [fromSync] is true only for "Read from here", which has just converted the audio position
     *  into a text locator — the reader then flashes the paragraph it lands on. A plain "Open
     *  reader" from Book options is not a jump from anywhere, and passes false. */
    onOpenReader: (bookId: Long, fromSync: Boolean) -> Unit = { _, _ -> },
    viewModel: PlayerViewModel = hiltViewModel()
) {
    when (LocalAppTheme.current) {
        AppTheme.IMMERSIVE -> ImmersivePlayerContent(onCollapse, startPlaying, onOpenReader, viewModel)
        AppTheme.MATERIAL_YOU -> MaterialPlayerContent(onCollapse, startPlaying, onOpenReader, viewModel)
    }

    // A refused EPUB (DRM, corrupt) has to say so. Hosted here rather than in each theme's screen
    // because the failure is identical in both, and a connect attempt that fails silently is the
    // shape of bug this whole change is fixing.
    val ebookError by viewModel.ebookError.collectAsStateWithLifecycle()
    ebookError?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissEbookError() },
            title = { Text("Couldn't connect ebook") },
            text = { Text(message) },
            confirmButton = {
                HapticTextButton(onClick = { viewModel.dismissEbookError() }) { Text("OK") }
            }
        )
    }
}
