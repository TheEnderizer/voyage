package com.betteraudio.ui.components

import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.betteraudio.ui.theme.AppTheme
import com.betteraudio.ui.theme.LocalAppTheme

/**
 * Container fill for the shared modal bottom sheets (book options, audio settings, sleep timer,
 * bookmarks, scan, sort/filter, cover search). The sheets themselves stay unsplit — no layout
 * differs between the two looks — so, like the shared Settings building blocks, they resolve
 * their fill per theme here: near-opaque frosted in Immersive (the cover/scrim glows through
 * faintly instead of the sheet looking like an opaque Material panel pasted over the player),
 * and the stock M3 sheet color in Material You.
 */
@Composable
fun appSheetColor(): Color =
    if (LocalAppTheme.current == AppTheme.IMMERSIVE)
        MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.92f)
    else
        BottomSheetDefaults.ContainerColor
