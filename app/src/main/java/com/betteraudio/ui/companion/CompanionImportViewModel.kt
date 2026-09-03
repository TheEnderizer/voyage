package com.betteraudio.ui.companion

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.betteraudio.companion.CompanionImportService
import com.betteraudio.companion.RevealCursorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Drives [com.betteraudio.ui.companion.CompanionImportDialog] — a thin wrapper around
 *  [CompanionImportService] plus the "start fresh" progress-reset action (§10.1) once an import
 *  attaches to a book the recipient already has progress on. */
@HiltViewModel
class CompanionImportViewModel @Inject constructor(
    private val importService: CompanionImportService,
    private val revealCursorRepository: RevealCursorRepository
) : ViewModel() {

    sealed class UiState {
        data object Idle : UiState()
        data object Importing : UiState()
        data class Done(val result: CompanionImportService.ImportResult) : UiState()
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun import(uri: Uri) {
        if (_state.value is UiState.Importing) return
        _state.value = UiState.Importing
        viewModelScope.launch {
            val result = importService.importFromUri(uri)
            _state.value = UiState.Done(result)
        }
    }

    /** "Start fresh instead" (§10.1) — only offered when the attach just matched a book the
     *  recipient had already made progress on. */
    fun startFresh(bookId: Long) {
        viewModelScope.launch { revealCursorRepository.resetProgressAndReveal(bookId) }
    }

    fun dismiss() {
        _state.value = UiState.Idle
    }
}
