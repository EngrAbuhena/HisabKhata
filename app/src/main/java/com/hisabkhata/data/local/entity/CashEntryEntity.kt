package com.hisabkhata.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Money is stored in paisa; date is a local calendar date stored as epoch days. */
@Entity(
    tableName = "cash_entries",
    indices = [
        Index(value = ["date", "createdAt", "id"]),
        Index(value = ["linkedLoanEntryId"])
    ]
)
data class CashEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: Long,
    val deposit: Long = 0,
    val cost: Long = 0,
    val description: String = "",
    val createdAt: Long,
    // Cross-ledger links are intentionally nullable scalar IDs, not circular FKs.
    val linkedLoanEntryId: Long? = null
)
