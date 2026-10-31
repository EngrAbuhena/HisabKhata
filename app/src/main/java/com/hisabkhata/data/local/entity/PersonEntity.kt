package com.hisabkhata.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Schema for Step 2; no person-management UI is included in Step 1. */
enum class PersonType { LENT, BORROWED }

@Entity(tableName = "people", indices = [Index(value = ["type", "name"])])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val note: String = "",
    val type: PersonType
)
