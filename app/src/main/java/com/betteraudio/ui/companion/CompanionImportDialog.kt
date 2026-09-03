package com.betteraudio.ui.companion

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betteraudio.companion.CompanionImportService

/**
 * Global "receive a companion pack" surface (docs/companion-packs.md §10.3, P4) — hosted once near
 * the app root (see `MainActivity`), the same way `UpdateAvailableScreen` is, since an incoming
 * pack can arrive from the share sheet at any point in the app, not from a route tied to one book.
 *
 * [pendingUri] is set by the host whenever a new file needs importing (a share-sheet intent, or
 * the manual picker in Settings → Library); this composable owns turning it into a result and
 * clearing it back to null via [onConsumed] once the resulting dialog is dismissed.
 */
@Composable
fun CompanionImportDialog(
    pendingUri: Uri?,
    onConsumed: () -> Unit,
    viewModel: CompanionImportViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(pendingUri) {
        pendingUri?.let { viewModel.import(it) }
    }

    when (val s = state) {
        is CompanionImportViewModel.UiState.Idle -> Unit
        is CompanionImportViewModel.UiState.Importing -> {
            AlertDialog(
                onDismissRequest = {},
                containerColor = com.betteraudio.ui.components.appDialogColor(),
                title = { Text("Importing companion pack…") },
                text = {
                    Row {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                },
                confirmButton = {}
            )
        }
        is CompanionImportViewModel.UiState.Done -> {
            val dismiss = { viewModel.dismiss(); onConsumed() }
            when (val result = s.result) {
                is CompanionImportService.ImportResult.Attached -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Companion pack added") },
                        text = {
                            Column {
                                Text("\"${result.packTitle}\" is now attached to \"${result.bookTitle}\".")
                                if (result.existingProgressMs > 0L) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "You already have progress on this book — the companion will reveal everything up to where you are.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } },
                        dismissButton = if (result.existingProgressMs > 0L) {
                            {
                                TextButton(onClick = {
                                    viewModel.startFresh(result.bookId)
                                    dismiss()
                                }) { Text("Start fresh instead") }
                            }
                        } else null
                    )
                }
                is CompanionImportService.ImportResult.Updated -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Companion pack updated") },
                        text = {
                            Column {
                                Text("\"${result.packTitle}\" on \"${result.bookTitle}\" is now at revision ${result.newRevision}.")
                                if (result.conflictCount > 0) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "${result.conflictCount} of your local edits might disagree with this update — your changes were kept. Review them from the companion's edit screen.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } }
                    )
                }
                is CompanionImportService.ImportResult.QueuedFullImport -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Importing in the background") },
                        text = {
                            Text("\"${result.packTitle}\" includes the audio, so it's being placed in your library in the background. This can take a while for a large book — you'll find it in your library once it's done.")
                        },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } }
                    )
                }
                is CompanionImportService.ImportResult.AlreadyUpToDate -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Already up to date") },
                        text = { Text("\"${result.packTitle}\" is already at this revision or newer.") },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } }
                    )
                }
                is CompanionImportService.ImportResult.NoMatch -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Book not found") },
                        text = { Text("\"${result.memberTitle}\" isn't in your library yet, so \"${result.packTitle}\" couldn't be attached. Add the book first, then import the pack again.") },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } }
                    )
                }
                is CompanionImportService.ImportResult.NotAPack -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Not a companion pack") },
                        text = { Text(result.reason) },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } }
                    )
                }
                is CompanionImportService.ImportResult.Failure -> {
                    AlertDialog(
                        onDismissRequest = dismiss,
                        containerColor = com.betteraudio.ui.components.appDialogColor(),
                        title = { Text("Import failed") },
                        text = { Text(result.reason) },
                        confirmButton = { Button(onClick = dismiss) { Text("OK") } }
                    )
                }
            }
        }
    }
}
