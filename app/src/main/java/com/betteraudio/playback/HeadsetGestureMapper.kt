package com.betteraudio.playback

import com.betteraudio.data.settings.SettingsStore
import com.betteraudio.util.AppLog
import com.betteraudio.util.log.LogCat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounces the HEADSETHOOK/MEDIA_PLAY_PAUSE key into a single resolved action based on how many
 * times it was pressed within [MULTI_PRESS_WINDOW_MS] — 1 press always plays/pauses, 2 or 3+ run
 * whatever the user assigned in Settings. Used by [PlaybackService]'s `onMediaButtonEvent`; a
 * MEDIA_NEXT/MEDIA_PREVIOUS keycode (many BT headsets already debounce a double/triple click into
 * one of these in firmware) is a direct double-/triple-press equivalent and doesn't go through
 * this counter at all — see the call site.
 */
class HeadsetGestureMapper(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val onAction: (action: String) -> Unit
) {
    companion object {
        private const val MULTI_PRESS_WINDOW_MS = 400L
    }

    private var pressCount = 0
    private var debounceJob: Job? = null

    /** Call once per physical click (ACTION_UP, repeatCount == 0) — DOWN+UP both fire per click,
     *  and counting both would double every press. */
    fun onHeadsetHookPress() {
        pressCount++
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(MULTI_PRESS_WINDOW_MS)
            val count = pressCount
            pressCount = 0
            val action = when (count) {
                1 -> "play_pause"
                2 -> settings.currentHeadsetDoublePressAction
                else -> settings.currentHeadsetTriplePressAction
            }
            AppLog.i(LogCat.PLAYBACK, "headset press count=$count -> action=$action")
            onAction(action)
        }
    }

    fun cancel() {
        debounceJob?.cancel()
        debounceJob = null
    }
}
