package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_presets")
data class AudioPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // Presets are unified BUNDLES (speed + boost + EQ). [type] is retained only so pre-existing
    // rows still parse; new presets use TYPE_BUNDLE and every facet is meaningful.
    val type: String = TYPE_BUNDLE,
    val speedMult: Float = 1.0f,
    val boostDb: Int = 0,
    val eqBandsJson: String? = null,
    val isDefault: Boolean = false
) {
    /** Whether this preset carries a non-flat equalizer. */
    val hasEq: Boolean get() = !eqBandsJson.isNullOrBlank() && eqBandsJson != "[0,0,0,0,0]"

    /** Short one-line description of the bundle, e.g. "1.2× · +3 dB · EQ". */
    fun summary(): String = buildList {
        add("${String.format("%.2f", speedMult)}×")
        if (boostDb != 0) add("+$boostDb dB")
        if (hasEq) add("EQ")
    }.joinToString(" · ")

    companion object {
        const val TYPE_SPEED = "SPEED"
        const val TYPE_BOOST = "BOOST"
        const val TYPE_EQ = "EQ"
        const val TYPE_BUNDLE = "BUNDLE"
    }
}
