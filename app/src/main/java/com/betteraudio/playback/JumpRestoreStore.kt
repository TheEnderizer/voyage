package com.betteraudio.playback

import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A detected involuntary jump awaiting a user restore/dismiss decision. */
data class JumpRestore(val preJumpBookPosMs: Long, val bookId: Long, val atMs: Long)

/**
 * App-scoped bridge between jump detection (service-side, on the real ExoPlayer — see
 * `PlaybackService.onPositionDiscontinuity`) and the restore-pill UI (`PlayerViewModel`). Both
 * sides inject this singleton directly (same pattern as `WidgetStateStore`); an in-memory
 * `StateFlow` is sufficient because `PlaybackService` and the app's Activity/ViewModels always
 * run in the same process here (no `android:process` on the service in the manifest) — a late
 * collector still gets the latest value, so a jump detected during background playback is
 * delivered as soon as `PlayerViewModel` starts collecting.
 *
 * Deliberately NOT persisted (unlike `WidgetStateStore`): a pending offer is lost if the OS kills
 * the app process between the jump and the user opening the player. Acceptable — a background
 * jump only occurs while the playback foreground-service process is alive in the first place.
 */
@Singleton
class JumpRestoreStore @Inject constructor() {
    private val _restore = MutableStateFlow<JumpRestore?>(null)
    val restore: StateFlow<JumpRestore?> = _restore.asStateFlow()

    fun set(value: JumpRestore) {
        _restore.value = value
    }

    /** Clears only if the current value matches [bookId] — avoids a UI dismiss/consume racing a
     *  newer jump for a different (or the same) book that landed in between. */
    fun clear(bookId: Long) {
        val current = _restore.value
        val cleared = _restore.compareAndSet(current?.takeIf { it.bookId == bookId }, null)
        if (current != null && !cleared) {
            // The pill's underlying offer belonged to a different book than the caller expected —
            // a real symptom worth seeing ("restore pill didn't go away") rather than a silent no-op.
            AppLog.d(LogCat.PLAYBACK) { "JumpRestoreStore.clear(book=$bookId): no-op, current offer is for book=${current.bookId}" }
        }
    }

    fun clearAll() {
        if (_restore.value != null) AppLog.d(LogCat.PLAYBACK) { "JumpRestoreStore.clearAll" }
        _restore.value = null
    }
}
