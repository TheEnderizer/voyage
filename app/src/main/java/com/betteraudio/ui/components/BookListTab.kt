package com.betteraudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.betteraudio.data.db.entities.Book
import java.io.File

private sealed interface ListItem {
    data class SeriesHeader(val name: String) : ListItem
    data class BookEntry(val book: Book) : ListItem
}

private fun List<Book>.toListItems(): List<ListItem> {
    val result = mutableListOf<ListItem>()
    var lastSeries: String? = null
    forEach { book ->
        if (book.seriesName != null && book.seriesName != lastSeries) {
            lastSeries = book.seriesName
            result.add(ListItem.SeriesHeader(book.seriesName))
        } else if (book.seriesName == null) {
            lastSeries = null
        }
        result.add(ListItem.BookEntry(book))
    }
    return result
}

@Composable
fun BookListTab(
    books: List<Book>,
    progressMap: Map<Long, Float> = emptyMap(),
    emptyMessage: String,
    onBookClick: (Book) -> Unit = {},
    onSeriesClick: (String) -> Unit = {},
    grouped: Boolean = false
) {
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    if (grouped) {
        val items = remember(books) { books.toListItems() }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(items, key = { when (it) {
                is ListItem.SeriesHeader -> "h_${it.name}"
                is ListItem.BookEntry   -> it.book.id
            }}) { item ->
                when (item) {
                    is ListItem.SeriesHeader -> SeriesHeaderRow(item.name, onClick = { onSeriesClick(item.name) })
                    is ListItem.BookEntry -> BookCard(
                        book = item.book,
                        progress = progressMap[item.book.id] ?: 0f,
                        onClick = { onBookClick(item.book) },
                        indented = item.book.seriesName != null
                    )
                }
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(books, key = { it.id }) { book ->
                BookCard(
                    book = book,
                    progress = progressMap[book.id] ?: 0f,
                    onClick = { onBookClick(book) }
                )
            }
        }
    }
}

@Composable
private fun SeriesHeaderRow(name: String, onClick: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "View series →",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun BookCard(
    book: Book,
    progress: Float = 0f,
    onClick: () -> Unit = {},
    indented: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (indented) 24.dp else 12.dp,
                end = 12.dp,
                top = 4.dp,
                bottom = 4.dp
            )
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(Modifier.height(88.dp)) {
            AsyncImage(
                model = book.coverArtPath?.let { File(it) },
                contentDescription = "Cover art",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp)
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(12.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
