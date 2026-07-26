package com.betteraudio.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler

/**
 * Watches for a Bluetooth/wired audio device connecting and invokes [onDeviceConnected] so
 * [PlaybackService] can decide whether to auto-resume. Uses `AudioDeviceCallback` (no runtime
 * permission needed) rather than `BluetoothDevice` broadcasts — `ACTION_ACL_CONNECTED` fires for
 * ANY paired device including watches and car head units doing phonebook sync, which would
 * false-trigger a resume; this only fires for devices Android itself considers audio sinks.
 */
class BtAutoResumeWatcher(
    private val context: Context,
    private val isEnabled: () -> Boolean,
    private val onDeviceConnected: () -> Unit
) {
    private var audioDeviceCallback: AudioDeviceCallback? = null

    fun register() {
        if (audioDeviceCallback != null) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (!isEnabled()) return
                if (addedDevices.any { isAudioSinkDevice(it) }) onDeviceConnected()
            }
        }
        am.registerAudioDeviceCallback(callback, Handler(context.mainLooper))
        audioDeviceCallback = callback
    }

    fun unregister() {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
    }

    companion object {
        fun isAudioSinkDevice(info: AudioDeviceInfo): Boolean = when (info.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }
}
