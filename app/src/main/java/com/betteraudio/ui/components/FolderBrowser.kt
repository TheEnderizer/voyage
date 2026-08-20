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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import com.betteraudio.ui.haptics.*

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
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // The gesture-bar clearance for this dialog's bottom bar.
        //
        // Compose's WindowInsets are useless here: the host Activity's window already excludes the
        // navigation bar, so navigationBars/safeDrawing report 0 everywhere in the app — but this
        // Dialog uses decorFitsSystemWindows = false, so *its* window is edge-to-edge and its
        // Scaffold lays out one nav-bar-height below the visible screen.
        //
        // Listen on the dialog's own decor view for live insets (a one-shot read of the Activity's
        // decor can be 0 or stale — rotation, nav-mode changes, some OEMs), and keep a 16dp floor
        // so the buttons are never flush with the screen edge even if the inset reports 0.
        val density = LocalDensity.current
        val view = LocalView.current
        var navBottomPx by remember { mutableIntStateOf(0) }
        DisposableEffect(view) {
            val root = view.rootView
            ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
                // Take the larger of the nav-bar and the gesture inset: on a gesture-nav device
                // the nav-bar inset can be 0 but there's still a pill at the bottom edge.
                navBottomPx = maxOf(
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom
                )
                insets
            }
            ViewCompat.requestApplyInsets(root)
            onDispose { ViewCompat.setOnApplyWindowInsetsListener(root, null) }
        }
        val navBottom = maxOf(with(density) { navBottomPx.toDp() }, 16.dp)

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
                            HapticIconButton(onClick = onCancel) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel")
                            }
                        },
                        actions = {
                            val parent = current.parentFile
                            HapticIconButton(
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
                                .imePadding()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = navBottom + 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (fileExtensions == null) {
                                HapticOutlinedButton(
                                    onClick = onCancel,
                                    modifier = Modifier.weight(1f)
                                ) { Text("Cancel") }
                                HapticButton(
                                    onClick = { onSelect(current.absolutePath) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Select")
                                }
                            } else {
                                // File-pick mode: tapping a file selects it, so there's nothing to
                                // confirm — only Cancel.
                                HapticOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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
    // Detects a previously-exported Voyage library (see the storage-redesign plan) so the picker
    // can flag "this folder already has your data" before the user commits to it. .voyage itself
    // stays invisible in the listing above (filtered by the leading-dot check), same as any other
    // hidden folder — this is a probe, not a browsable entry.
    val isVoyageLibrary = remember(dir) {
        File(dir, ".voyage/settings.json").isFile
    }
    ListItem(
        headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (isVoyageLibrary) Text("Voyage library found here")
            else if (audioCount > 0) Text("$audioCount audio file${if (audioCount > 1) "s" else ""}")
        },
        leadingContent = { Icon(Icons.Default.Folder, null) },
        trailingContent = if (isVoyageLibrary) {
            { Icon(Icons.Default.Check, "Voyage library", tint = MaterialTheme.colorScheme.primary) }
        } else null,
        modifier = Modifier.clickable(onClick = onClick)
    )
}
