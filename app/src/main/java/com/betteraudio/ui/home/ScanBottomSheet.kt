package com.betteraudio.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.betteraudio.ui.components.FolderBrowser
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanBottomSheet(
    startPath: String,
    onDismiss: () -> Unit,
    onScan: (String) -> Unit,
    onOpenStorageSettings: () -> Unit,
    scan: ScanResult
) {
    val defaultPath = remember(startPath) {
        startPath.takeIf { it.isNotBlank() && File(it).isDirectory }
            ?: listOf("/sdcard/Audiobooks", "/sdcard/AudioBooks", "/storage/emulated/0/Audiobooks")
                .firstOrNull { File(it).isDirectory }
            ?: "/storage/emulated/0"
    }
    var path by remember { mutableStateOf(defaultPath) }
    var showBrowser by remember { mutableStateOf(false) }

    if (showBrowser) {
        FolderBrowser(
            startPath = path,
            onSelect = { selected -> path = selected; showBrowser = false },
            onCancel = { showBrowser = false }
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Scan Audiobooks", style = MaterialTheme.typography.titleLarge)

            Text(
                "Pick the folder where your audiobooks live. Sub-folders and series are " +
                "detected automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Selected folder display + Browse
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            File(path).name.ifBlank { path },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { showBrowser = true }) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Browse")
                    }
                }
            }

            Button(
                onClick = { onScan(path) },
                enabled = File(path).isDirectory && scan.status != ScanStatus.Running,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (scan.status == ScanStatus.Running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning…")
                } else {
                    Text("Scan Library")
                }
            }

            when (scan.status) {
                ScanStatus.Done -> if (scan.booksFound > 0) {
                    ResultCard(
                        icon = Icons.Default.CheckCircle,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        onContainer = MaterialTheme.colorScheme.onPrimaryContainer,
                        title = "Found ${scan.booksFound} book${if (scan.booksFound > 1) "s" else ""}",
                        message = "They're now in your Library tab."
                    )
                } else {
                    ResultCard(
                        icon = Icons.Default.Error,
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        onContainer = MaterialTheme.colorScheme.onTertiaryContainer,
                        title = "No audiobooks found here",
                        message = "Make sure you picked the right folder. Audio files " +
                            "(mp3, m4a, m4b, etc.) should be inside it or its sub-folders.",
                        action = "Choose a different folder" to { showBrowser = true }
                    )
                }
                ScanStatus.Error -> ResultCard(
                    icon = Icons.Default.Error,
                    container = MaterialTheme.colorScheme.errorContainer,
                    onContainer = MaterialTheme.colorScheme.onErrorContainer,
                    title = "Scan failed",
                    message = scan.errorMessage
                        ?: "Something went wrong reading this folder.",
                    action = "Open storage settings" to onOpenStorageSettings
                )
                else -> {}
            }
        }
    }
}

@Composable
private fun ResultCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    container: androidx.compose.ui.graphics.Color,
    onContainer: androidx.compose.ui.graphics.Color,
    title: String,
    message: String,
    action: Pair<String, () -> Unit>? = null
) {
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = onContainer)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, color = onContainer)
            }
            Text(message, style = MaterialTheme.typography.bodySmall, color = onContainer)
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = action.second,
                    colors = ButtonDefaults.textButtonColors(contentColor = onContainer)
                ) { Text(action.first) }
            }
        }
    }
}
