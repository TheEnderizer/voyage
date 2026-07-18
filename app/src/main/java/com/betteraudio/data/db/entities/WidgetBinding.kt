package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Maps a placed home-screen appWidgetId to the WidgetDesign it renders. */
@Entity(tableName = "widget_bindings")
data class WidgetBinding(
    @PrimaryKey val appWidgetId: Int,
    val designId: Long,
    val boundAt: Long = System.currentTimeMillis(),
)
