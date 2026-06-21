package com.betteraudio.data.db

import androidx.room.TypeConverter
import com.betteraudio.data.db.entities.BookStatus

class Converters {
    @TypeConverter
    fun fromBookStatus(status: BookStatus): String = status.name

    @TypeConverter
    fun toBookStatus(value: String): BookStatus = BookStatus.valueOf(value)
}
