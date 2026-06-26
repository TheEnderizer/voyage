package com.betteraudio.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.theme.Pill

/** Circular dark-scrim icon button — used on the player's full-bleed backdrop. */
@Composable
fun ScrimButton(icon: ImageVector, cd: String, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(Pill).background(Color.Black.copy(alpha = 0.32f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, cd, Modifier.size(22.dp), tint = Color.White)
    }
}

/** Pill-shaped scrim chip — for jump-return and confirm controls on the player. */
@Composable
fun ScrimPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: ImageVector? = null,
    filled: Boolean = false
) {
    val bg = if (filled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.14f)
    val fg = if (filled) MaterialTheme.colorScheme.onPrimary else Color.White
    Row(
        Modifier
            .clip(Pill)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = fg)
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
        if (trailing != null) {
            Spacer(Modifier.width(2.dp))
            Icon(trailing, null, Modifier.size(15.dp), tint = fg)
        }
    }
}

/**
 * Full-bleed backdrop: reflected progressive blur cover + vertical scrim fading to near-black.
 * Single source of truth for the player design language backdrop — used by player, book info,
 * and group info screens so they all render identically.
 */
@Composable
fun ReflectedCoverBackdrop(
    coverPath: String?,
    bakedPath: String?,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        Box(Modifier.fillMaxSize().clipToBounds()) {
            ReflectedProgressiveBlurCover(
                coverPath = coverPath,
                bakedPath = bakedPath,
                modifier  = Modifier.fillMaxWidth()
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f    to Color.Black.copy(alpha = 0.15f),
                    0.38f to Color.Black.copy(alpha = 0.04f),
                    0.54f to Color.Black.copy(alpha = 0.52f),
                    0.75f to Color.Black.copy(alpha = 0.86f),
                    1f    to Color.Black.copy(alpha = 0.97f)
                )
            )
        )
    }
}
