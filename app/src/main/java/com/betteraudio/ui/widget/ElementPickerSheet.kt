package com.betteraudio.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementType
import com.betteraudio.widget.model.ShapeKind
import com.betteraudio.widget.render.IconAssets

/** A pickable entry — usually one-to-one with an [ElementType], but [shapeKind]/[label]/[icon] let
 *  a single [ElementType.RECT] under the hood be offered as several named shape variants (e.g.
 *  "Circle" = a RECT preset to [ShapeKind.CIRCLE]) instead of requiring the user to add a plain
 *  rectangle and then find the shape chip buried in its options. */
private data class PickerEntry(
    val type: ElementType,
    val shapeKind: ShapeKind? = null,
    val label: String = labelFor(type),
    val icon: ImageVector? = null,
)

private data class ElementCategory(val title: String, val entries: List<PickerEntry>)

private val CATEGORIES = listOf(
    ElementCategory("Background", listOf(PickerEntry(ElementType.BACKGROUND_LAYER))),
    ElementCategory(
        "Controls", listOf(
            ElementType.PLAY_PAUSE, ElementType.SKIP_FORWARD, ElementType.SKIP_BACK,
            ElementType.CHAPTER_FORWARD, ElementType.CHAPTER_BACK, ElementType.SPEED_UP,
            ElementType.SPEED_DOWN, ElementType.BOOST_UP, ElementType.BOOST_DOWN,
            ElementType.SLEEP_TIMER, ElementType.QUICK_BOOKMARK, ElementType.CLOSE_BOOK,
        ).map { PickerEntry(it) }
    ),
    ElementCategory(
        "Text", listOf(
            ElementType.BOOK_TITLE, ElementType.AUTHOR, ElementType.CHAPTER_TITLE,
            ElementType.SERIES_NAME, ElementType.SPEED_LABEL, ElementType.TIME_REMAINING_BOOK,
            ElementType.TIME_REMAINING_CHAPTER, ElementType.PROGRESS_PERCENT, ElementType.CUSTOM_TEXT,
        ).map { PickerEntry(it) }
    ),
    ElementCategory(
        "Images",
        listOf(ElementType.BOOK_COVER, ElementType.SERIES_COVER, ElementType.CUSTOM_IMAGE).map { PickerEntry(it) }
    ),
    ElementCategory(
        "Shapes", listOf(
            PickerEntry(ElementType.RECT, label = "Rectangle"),
            PickerEntry(ElementType.RECT, shapeKind = ShapeKind.CIRCLE, label = "Circle", icon = Icons.Default.Circle),
            PickerEntry(ElementType.PROGRESS_BAR),
        )
    ),
)

/** The Elements panel — docked inline in the editor (not a modal sheet), so picking an element
 *  never covers the canvas. Scrollable since every category's grid is laid out at full (unclipped)
 *  height inside it. */
@Composable
fun ElementPickerPanel(onPick: (ElementType, ShapeKind?) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        CATEGORIES.forEach { category ->
            Text(category.title, style = MaterialTheme.typography.titleSmall)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().height(((category.entries.size + 2) / 3 * 84).dp)
            ) {
                items(category.entries) { entry ->
                    Card(
                        onClick = { onPick(entry.type, entry.shapeKind) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (entry.icon != null) {
                                Icon(entry.icon, contentDescription = null, modifier = Modifier.size(22.dp))
                            } else {
                                ElementTypeIcon(entry.type, modifier = Modifier.size(22.dp))
                            }
                            Text(entry.label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

internal fun labelFor(type: ElementType): String = when (type) {
    ElementType.PLAY_PAUSE -> "Play/Pause"
    ElementType.SKIP_FORWARD -> "Skip forward"
    ElementType.SKIP_BACK -> "Skip back"
    ElementType.CHAPTER_FORWARD -> "Next chapter"
    ElementType.CHAPTER_BACK -> "Prev chapter"
    ElementType.SPEED_UP -> "Speed up"
    ElementType.SPEED_DOWN -> "Speed down"
    ElementType.BOOST_UP -> "Boost up"
    ElementType.BOOST_DOWN -> "Boost down"
    ElementType.SLEEP_TIMER -> "Sleep timer"
    ElementType.QUICK_BOOKMARK -> "Bookmark"
    ElementType.CLOSE_BOOK -> "Close book"
    ElementType.BOOK_TITLE -> "Book title"
    ElementType.AUTHOR -> "Author"
    ElementType.CHAPTER_TITLE -> "Chapter title"
    ElementType.SERIES_NAME -> "Series name"
    ElementType.SPEED_LABEL -> "Speed label"
    ElementType.TIME_REMAINING_BOOK -> "Time left (book)"
    ElementType.TIME_REMAINING_CHAPTER -> "Time left (chapter)"
    ElementType.PROGRESS_PERCENT -> "Progress %"
    ElementType.CUSTOM_TEXT -> "Custom text"
    ElementType.BOOK_COVER -> "Book cover"
    ElementType.SERIES_COVER -> "Series cover"
    ElementType.CUSTOM_IMAGE -> "Custom image"
    ElementType.RECT -> "Rectangle"
    ElementType.PROGRESS_BAR -> "Progress bar"
    ElementType.BACKGROUND_LAYER -> "Background"
}

/** Renders the element's "real" icon where one exists — the same `ic_w_*` glyph the widget itself
 *  uses for control types (via [IconAssets]) — falling back to a representative Material icon for
 *  text/image/shape/background types, which have no single-glyph equivalent on the real widget. */
@Composable
internal fun ElementTypeIcon(type: ElementType, modifier: Modifier = Modifier) {
    val drawableRes = IconAssets.resFor(type, isPlaying = false, sleepActive = false)
    if (drawableRes != null) {
        Icon(painter = painterResource(drawableRes), contentDescription = null, modifier = modifier)
    } else {
        Icon(imageVector = materialIconFor(type), contentDescription = null, modifier = modifier)
    }
}

private fun materialIconFor(type: ElementType): ImageVector = when (type) {
    ElementType.BOOK_TITLE -> Icons.Default.Title
    ElementType.AUTHOR -> Icons.Default.Person
    ElementType.CHAPTER_TITLE -> Icons.AutoMirrored.Filled.MenuBook
    ElementType.SERIES_NAME -> Icons.Default.CollectionsBookmark
    ElementType.SPEED_LABEL -> Icons.Default.Speed
    ElementType.TIME_REMAINING_BOOK, ElementType.TIME_REMAINING_CHAPTER -> Icons.Default.Schedule
    ElementType.PROGRESS_PERCENT -> Icons.Default.Percent
    ElementType.CUSTOM_TEXT -> Icons.Default.TextFields
    ElementType.BOOK_COVER -> Icons.Default.Image
    ElementType.SERIES_COVER -> Icons.Default.PhotoLibrary
    ElementType.CUSTOM_IMAGE -> Icons.Default.AddPhotoAlternate
    ElementType.RECT -> Icons.Default.CropSquare
    ElementType.PROGRESS_BAR -> Icons.Default.LinearScale
    ElementType.BACKGROUND_LAYER -> Icons.Default.Wallpaper
    else -> Icons.Default.Circle // unreachable: every control type resolves via IconAssets above
}
