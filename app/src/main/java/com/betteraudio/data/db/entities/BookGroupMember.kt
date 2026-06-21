package com.betteraudio.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_group_members",
    foreignKeys = [
        ForeignKey(
            entity = BookGroup::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Book::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("groupId"), Index("bookId", unique = true)],
    primaryKeys = ["groupId", "bookId"]
)
data class BookGroupMember(
    val groupId: Long,
    val bookId: Long,
    val orderIndex: Int
)
