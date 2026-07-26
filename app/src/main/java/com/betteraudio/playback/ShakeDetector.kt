package com.betteraudio.playback

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Detects a shake gesture via `TYPE_LINEAR_ACCELERATION` and invokes [onShake] — used by
 * [PlaybackService]'s sleep timer for "shake to extend"/"shake to resume". Owns its own sensor
 * registration lifecycle: [start]/[stop] are idempotent, so callers don't need to track a
 * `SensorManager`/listener pair themselves.
 */
class ShakeDetector(
    private val context: Context,
    private val onShake: () -> Unit
) {
    companion object {
        private const val SHAKE_MAGNITUDE_THRESHOLD = 12f // m/s^2, on TYPE_LINEAR_ACCELERATION
    }

    private var sensorManager: SensorManager? = null
    private var listener: SensorEventListener? = null

    var isListening: Boolean = false
        private set

    fun start() {
        if (isListening) return
        val sm = (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val mag = sqrt(
                    event.values[0] * event.values[0] +
                        event.values[1] * event.values[1] +
                        event.values[2] * event.values[2]
                )
                if (mag > SHAKE_MAGNITUDE_THRESHOLD) onShake()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager = sm
        listener = l
        isListening = true
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    fun stop() {
        listener?.let { sensorManager?.unregisterListener(it) }
        listener = null
        isListening = false
    }
}
