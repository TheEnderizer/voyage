package com.betteraudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

private val AUDIO_EXTS = setOf("mp3", "m4a", "m4b", "ogg", "flac", "aac", "opus", "wav")

/**
 * Full-screen folder picker that walks the device filesystem with [java.io.File].
 * Requires all-files access (MANAGE_EXTERNAL_STORAGE) to list arbitrary directories.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderBrowser(
    startPath: String,
    onSelect: (String) -> Unit,
    onCancel: () -> Unit
) {
    val start = remember {
        File(startPath).takeIf { it.isDirectory } ?: File("/storage/emulated/0")
    }
    var current by remember { mutableStateOf(start) }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Choose Folder", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    current.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onCancel) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                            }
                        },
                        actions = {
                            val parent = current.parentFile
                            IconButton(
                                onClick = { parent?.let { current = it } },
                                enabled = parent != null && parent.canRead()
                            ) {
                                Icon(Icons.Default.KeyboardArrowUp, "Up one level")
                            }
                        }
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                // The dialog draws edge-to-edge (usePlatformDefaultWidth = false),
                                // so keep the buttons clear of the gesture/nav bar and the IME.
                                .navigationBarsPadding()
                                .imePadding()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = onCancel,
                                modifier = Modifier.weight(1f)
                            ) { Text("Cancel") }
                            Button(
                                onClick = { onSelect(current.absolutePath) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Select")
                            }
                        }
                    }
                }
            ) { padding ->
                val subDirs = remember(current) {
                    current.listFiles()
                        ?.filter { it.isDirectory && !it.name.startsWith(".") }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                }

                if (subDirs.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val readable = current.canRead()
                        Text(
                            if (!readable)
                                "Can't read this folder. Make sure all-files access is granted."
                            else
                                "No sub-folders here.\nTap Select to scan this folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        items(subDirs, key = { it.absolutePath }) { dir ->
                            FolderRow(dir = dir, onClick = { current = dir })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(dir: File, onClick: () -> Unit) {
    val audioCount = remember(dir) {
        dir.listFiles()?.count { it.isFile && it.extension.lowercase() in AUDIO_EXTS } ?: 0
    }
    ListItem(
        headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (audioCount > 0) Text("$audioCount audio file${if (audioCount > 1) "s" else ""}")
        },
        leadingContent = { Icon(Icons.Default.Folder, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
