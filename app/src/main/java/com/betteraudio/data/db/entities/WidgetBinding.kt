package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Maps a placed home-screen appWidgetId to the CustomWidgetDesign it renders. */
@Entity(tableName = "widget_binding")
data class WidgetBinding(
    @PrimaryKey val appWidgetId: Int,
    val designId: Long
)
