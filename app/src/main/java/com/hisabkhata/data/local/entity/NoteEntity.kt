package com.hisabkhata.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["deletedAtMs", "isMine", "pinned", "updatedAtMs"])
    ]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val body: String = "",
    val isPublic: Boolean = false,
    val showAuthor: Boolean = false,
    val pinned: Boolean = false,
    val isMine: Boolean = true,
    val authorDisplayName: String? = null,
    val updatedAtMs: Long,
    val deletedAtMs: Long? = null
)
