package com.betteraudio.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import coil3.compose.AsyncImage
import com.betteraudio.ui.material.MaterialAdaptive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverSearchSheet(
    initialQuery: String,
    onSearch: suspend (String) -> List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (query.isBlank()) return
        scope.launch {
            loading = true
            searched = true
            results = onSearch(query.trim())
            loading = false
        }
    }

    // Kick off an initial search with the pre-filled query
    LaunchedEffect(Unit) { if (initialQuery.isNotBlank()) runSearch() }

    ModalBottomSheet(
        containerColor = com.betteraudio.ui.components.appSheetColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        // Fixed minimums sized for a portrait window can alone exceed a short landscape one's
        // available height (the outer Column has no scroll of its own — the grid below scrolls
        // itself, but the surrounding chrome doesn't) — shrink them there rather than restructure.
        val wide = MaterialAdaptive.isMaterialLandscape()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (wide) 160.dp else 300.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Find cover online", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { runSearch() }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() })
            )

            when {
                loading -> Box(
                    Modifier.fillMaxWidth().heightIn(min = if (wide) 100.dp else 200.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                searched && results.isEmpty() -> Box(
                    Modifier.fillMaxWidth().heightIn(min = if (wide) 100.dp else 200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No covers found — try a different search",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = if (wide) 220.dp else 420.dp)
                ) {
                    items(results, key = { it }) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Cover option",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(0.7f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPick(url) }
                        )
                    }
                }
            }
        }
    }
}
