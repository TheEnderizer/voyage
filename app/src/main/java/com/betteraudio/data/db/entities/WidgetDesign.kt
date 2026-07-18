package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A saved widget design: a canvas aspect ratio + a JSON-encoded WidgetDesignDoc (background +
 *  free-placed elements, see widget/model/WidgetDesignDoc.kt). One design can be bound to any
 *  number of placed home-screen widgets (see WidgetBinding) and renders at whatever pixel size
 *  each one is granted. */
@Entity(tableName = "widget_designs")
data class WidgetDesign(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val aspectRatio: Float = 2.0f,
    /** JSON-encoded WidgetDesignDoc, see WidgetDesignCodec. */
    val documentJson: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
