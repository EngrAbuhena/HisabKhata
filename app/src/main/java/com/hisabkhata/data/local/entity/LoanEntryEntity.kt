package com.hisabkhata.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Prepared for Step 2; all monetary fields are Long paisa. */
@Entity(
    tableName = "loan_entries",
    foreignKeys = [
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["personId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["personId", "date", "createdAt", "id"]),
        Index(value = ["linkedCashEntryId"])
    ]
)
data class LoanEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personId: Long,
    val date: Long,
    val loan: Long = 0,
    val paid: Long = 0,
    val description: String = "",
    val createdAt: Long,
    val linkedCashEntryId: Long? = null
)
