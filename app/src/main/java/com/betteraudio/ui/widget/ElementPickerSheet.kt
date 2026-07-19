package com.betteraudio.ui.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.betteraudio.widget.model.ElementType

private data class ElementCategory(val title: String, val types: List<ElementType>)

private val CATEGORIES = listOf(
    ElementCategory(
        "Controls", listOf(
            ElementType.PLAY_PAUSE, ElementType.SKIP_FORWARD, ElementType.SKIP_BACK,
            ElementType.CHAPTER_FORWARD, ElementType.CHAPTER_BACK, ElementType.SPEED_UP,
            ElementType.SPEED_DOWN, ElementType.BOOST_UP, ElementType.BOOST_DOWN,
            ElementType.SLEEP_TIMER, ElementType.QUICK_BOOKMARK, ElementType.CLOSE_BOOK,
        )
    ),
    ElementCategory(
        "Text", listOf(
            ElementType.BOOK_TITLE, ElementType.AUTHOR, ElementType.CHAPTER_TITLE,
            ElementType.SERIES_NAME, ElementType.SPEED_LABEL, ElementType.TIME_REMAINING_BOOK,
            ElementType.TIME_REMAINING_CHAPTER, ElementType.PROGRESS_PERCENT, ElementType.CUSTOM_TEXT,
        )
    ),
    ElementCategory("Images", listOf(ElementType.BOOK_COVER, ElementType.SERIES_COVER, ElementType.CUSTOM_IMAGE)),
    ElementCategory("Shapes", listOf(ElementType.RECT, ElementType.PROGRESS_BAR)),
)

@Composable
fun ElementPickerSheet(onDismiss: () -> Unit, onPick: (ElementType) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CATEGORIES.forEach { category ->
                Text(category.title, style = MaterialTheme.typography.titleSmall)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(category.types) { type ->
                        Card(
                            onClick = { onPick(type); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                Modifier.padding(12.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(labelFor(type), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun labelFor(type: ElementType): String = when (type) {
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
}
