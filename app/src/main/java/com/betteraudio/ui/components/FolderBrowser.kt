package com.betteraudio.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
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
    onCancel: () -> Unit,
    // File-pick mode: when set, matching non-hidden files are listed after sub-folders and
    // tapping one immediately calls [onSelectFile] (the bottom "Select" button is hidden — there's
    // nothing to "select" but a folder in that mode). null = folders-only (original behavior).
    fileExtensions: Set<String>? = null,
    onSelectFile: ((String) -> Unit)? = null,
    title: String = "Choose Folder"
) {
    val start = remember {
        File(startPath).takeIf { it.isDirectory } ?: File("/storage/emulated/0")
    }
    var current by remember { mutableStateOf(start) }

    Dialog(
        onDismissRequest = onCancel,
        // decorFitsSystemWindows = false: without it a Dialog window doesn't draw edge-to-edge,
        // so navigationBarsPadding()/safeDrawing below report ZERO insets and the Cancel/Select
        // buttons get clipped under the gesture bar. This opts the dialog into real insets.
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title, style = MaterialTheme.typography.titleMedium)
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
                    if (fileExtensions == null) {
                        Surface(tonalElevation = 3.dp) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    // The dialog draws edge-to-edge (usePlatformDefaultWidth = false),
                                    // so keep the buttons clear of the gesture/nav bar and the IME.
                                    .navigationBarsPadding()
                                    .imePadding()
                                    // Extra fixed cushion beyond the computed inset — some OEM skins
                                    // under-report the gesture-nav inset for Dialog windows, which
                                    // otherwise leaves these buttons sitting right at the edge.
                                    .padding(bottom = 8.dp)
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
                    } else {
                        Surface(tonalElevation = 3.dp) {
                            Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(bottom = 8.dp).padding(16.dp)) {
                                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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
                val matchingFiles = remember(current, fileExtensions) {
                    if (fileExtensions == null) emptyList()
                    else current.listFiles()
                        ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.lowercase() in fileExtensions }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                }

                if (subDirs.isEmpty() && matchingFiles.isEmpty()) {
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
                            else if (fileExtensions != null)
                                "Nothing here matching .${fileExtensions.joinToString("/.")}"
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
                        items(subDirs, key = { "dir:${it.absolutePath}" }) { dir ->
                            FolderRow(dir = dir, onClick = { current = dir })
                            HorizontalDivider()
                        }
                        items(matchingFiles, key = { "file:${it.absolutePath}" }) { file ->
                            FileRow(file = file, onClick = { onSelectFile?.invoke(file.absolutePath) })
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(file: File, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(Icons.Default.Description, null) },
        modifier = Modifier.clickable(onClick = onClick)
    )
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
