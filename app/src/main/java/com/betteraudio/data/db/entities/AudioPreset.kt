package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_presets")
data class AudioPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String = TYPE_SPEED, // SPEED, BOOST, or EQ — determines which tab shows this preset
    val speedMult: Float = 1.0f,
    val boostDb: Int = 0,
    val eqBandsJson: String? = null,
    val isDefault: Boolean = false
) {
    companion object {
        const val TYPE_SPEED = "SPEED"
        const val TYPE_BOOST = "BOOST"
        const val TYPE_EQ = "EQ"
    }
}
