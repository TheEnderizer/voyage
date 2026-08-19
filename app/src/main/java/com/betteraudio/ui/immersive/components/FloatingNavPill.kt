package com.betteraudio.ui.immersive.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.components.NAV_PILL_BOTTOM_PADDING
import com.betteraudio.ui.components.NAV_PILL_HEIGHT
import com.betteraudio.ui.home.HomeSection
import com.betteraudio.ui.home.HomeViewMode
import com.betteraudio.ui.theme.Pill
import com.betteraudio.ui.theme.pressScale
import com.betteraudio.util.FeatureFlags

/**
 * ArchiveTune-style floating bottom pill: all-icon slots for the Audio/Ebooks sections (with a
 * sliding selection indicator between those two), a Books→Series→Authors view-cycle button
 * (Audio section only), and Search/Settings actions. Slides off-screen in lockstep with the
 * player sheet's expansion ([expandProgress] read only inside graphicsLayer — no per-frame
 * recomposition).
 */
@Composable
fun FloatingNavPill(
    section: HomeSection,
    viewMode: HomeViewMode,
    onSelectSection: (HomeSection) -> Unit,
    onCycleViewMode: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    expandProgress: State<Float>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val hideTravelPx = with(density) { (NAV_PILL_HEIGHT + NAV_PILL_BOTTOM_PADDING + 48.dp).toPx() }

    GlassPillSurface(
        shape = Pill,
        modifier = modifier
            .graphicsLayer { translationY = expandProgress.value.coerceIn(0f, 1f) * hideTravelPx }
            // Sit outside the padding/widthIn, so the drawn pill hugs the icon row instead of
            // stretching to whatever width the parent hands down (on a 360dp screen that was a
            // 328dp pill around 276dp of icons — a slot's worth of dead space on the right).
            .wrapContentWidth(Alignment.CenterHorizontally)
            .padding(horizontal = 16.dp)
            .widthIn(max = 420.dp)
            .height(NAV_PILL_HEIGHT)
    ) {
        // Slot geometry for the sliding indicator, measured per stateful tab.
        val slotX = remember { mutableStateMapOf<HomeSection, Dp>() }
        val slotW = remember { mutableStateMapOf<HomeSection, Dp>() }
        val indicatorX by animateDpAsState(
            slotX[section] ?: 0.dp, spring(dampingRatio = 0.8f, stiffness = 380f), label = "pillX"
        )
        val indicatorW by animateDpAsState(
            slotW[section] ?: 0.dp, spring(dampingRatio = 0.8f, stiffness = 380f), label = "pillW"
        )

        Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (indicatorW > 0.dp) {
                Box(
                    Modifier
                        .offset(x = indicatorX)
                        .width(indicatorW)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            RoundedCornerShape(24.dp)
                        )
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PillSlot(
                    icon = Icons.Default.Headphones,
                    cd = "Audiobooks",
                    selected = section == HomeSection.AUDIO,
                    onClick = { onSelectSection(HomeSection.AUDIO) },
                    measure = { x, w -> slotX[HomeSection.AUDIO] = x; slotW[HomeSection.AUDIO] = w }
                )
                if (FeatureFlags.EBOOKS_UI) {
                    PillSlot(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        cd = "Ebooks",
                        selected = section == HomeSection.EBOOKS,
                        onClick = { onSelectSection(HomeSection.EBOOKS) },
                        measure = { x, w -> slotX[HomeSection.EBOOKS] = x; slotW[HomeSection.EBOOKS] = w }
                    )
                }
                // View-cycle: icon shows the CURRENT view; tap advances Books→Series→Authors.
                // Ebooks ignores view modes, so the slot collapses away in that section.
                AnimatedVisibility(
                    visible = section == HomeSection.AUDIO,
                    enter = expandHorizontally() + fadeIn(),
                    exit = shrinkHorizontally() + fadeOut()
                ) {
                    PillSlot(
                        icon = null,
                        cd = "Change library view",
                        selected = false,
                        onClick = onCycleViewMode
                    ) {
                        Crossfade(viewMode, label = "viewIcon") { mode ->
                            Icon(
                                when (mode) {
                                    HomeViewMode.BOOKS -> Icons.Default.Book
                                    HomeViewMode.SERIES -> Icons.Default.CollectionsBookmark
                                    HomeViewMode.AUTHORS -> Icons.Default.Person
                                },
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                PillSlot(
                    icon = Icons.Default.Search,
                    cd = "Search",
                    selected = false,
                    onClick = onSearch
                )
                PillSlot(
                    icon = Icons.Default.Settings,
                    cd = "Settings",
                    selected = false,
                    onClick = onSettings
                )
            }
        }
    }
}

/** One icon slot. When [icon] is null, [content] draws the icon instead (view-cycle crossfade). */
@Composable
private fun PillSlot(
    icon: ImageVector?,
    cd: String,
    selected: Boolean,
    onClick: () -> Unit,
    measure: ((x: Dp, w: Dp) -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, label = "slotScale")
    Box(
        Modifier
            .then(
                if (measure != null) Modifier.onGloballyPositioned {
                    with(density) {
                        measure(it.positionInParent().x.toDp(), it.size.width.toDp())
                    }
                } else Modifier
            )
            .clip(Pill)
            .pressScale()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            content()
        } else if (icon != null) {
            Icon(
                icon,
                contentDescription = cd,
                modifier = Modifier.size(24.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
