package com.betteraudio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.theme.MotionTokens

/**
 * A darkening scrim with floating [content] drawn over whatever page hosts it (player /
 * book-info). The frosted-glass look comes from the *host* blurring its own content behind
 * this overlay (see [frostedWhenVisible]) — this composable only draws the dimming scrim and
 * the floating panel so the two animate together. Tapping the scrim dismisses.
 *
 * Place this as the LAST child of the host Box so it draws on top.
 */
@Composable
fun FrostedOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MotionTokens.floatEffects),
        exit = fadeOut(MotionTokens.floatEffects)
    ) {
        Box(
            modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(MotionTokens.floatEffects) +
                slideInVertically(spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)) { it / 6 } +
                scaleIn(MotionTokens.floatSpatial, initialScale = 0.94f),
        exit = fadeOut(MotionTokens.floatEffects) +
                slideOutVertically(spring(MotionTokens.spatialDamping, MotionTokens.spatialStiffness)) { it / 6 } +
                scaleOut(MotionTokens.floatEffects, targetScale = 0.96f)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/**
 * Host-side companion: progressively blurs the content it's applied to while an overlay is
 * [visible], so the frosted overlay reads as real frosted glass over the page. Blur is only
 * honoured on API 31+; on older devices it's a no-op and the dimming scrim alone carries it.
 */
@Composable
fun Modifier.frostedWhenVisible(visible: Boolean): Modifier {
    val radius by animateDpAsState(
        targetValue = if (visible) 24.dp else 0.dp,
        animationSpec = spring(MotionTokens.effectsDamping, MotionTokens.effectsStiffness),
        label = "frostBlur"
    )
    return if (radius > 0.dp) this.blur(radius) else this
}
