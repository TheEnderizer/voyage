package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_widget_design")
data class CustomWidgetDesign(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** WidgetSizeBucket.name */
    val sizeBucket: String,
    /** WidgetBackground.name */
    val backgroundType: String,
    /** "" for BOOK_COVER/SERIES_COVER/APP_COLOR; "#AARRGGBB" or "seedPalette:<b64>" for CUSTOM_COLOR; absolute file path for CUSTOM_IMAGE */
    val backgroundValue: String = "",
    /** JSON-encoded List<WidgetElement>, see WidgetElementCodec */
    val elementsJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)
