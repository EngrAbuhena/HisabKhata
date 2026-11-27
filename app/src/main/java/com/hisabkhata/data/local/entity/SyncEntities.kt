package com.hisabkhata.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local row id -> cloud uid mapping. Rows keep their numeric Room ids so existing
 * links, backups and UI code stay valid; the cloud id lives beside them instead of
 * rewriting the ledger tables (the agreed Step 4 choice).
 *
 * [clientId] is generated once per row and never changes. Supabase has a unique
 * (owner_id, client_id) constraint, so retrying an upload can never duplicate a row.
 */
@Entity(
    tableName = "sync_ids",
    primaryKeys = ["entity", "localId"],
    indices = [Index(value = ["clientId"], unique = true)]
)
data class SyncIdEntity(
    val entity: String,
    val localId: Long,
    val clientId: String,
    val cloudId: String? = null,
    val knownUpdatedAt: String? = null
)

enum class OutboxState { PENDING, CONFLICT }

/**
 * Durable upload queue. Every local mutation inserts here inside the same Room
 * transaction, so "saved but never queued" cannot happen.
 */
@Entity(
    tableName = "sync_outbox",
    indices = [
        Index(value = ["accountId", "id"]),
        Index(value = ["entity", "localId"], unique = true)
    ]
)
data class SyncOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** auth user id (uuid) that produced this change. */
    val accountId: String,
    val entity: String,
    val operation: String,
    val localId: Long,
    val clientId: String,
    val state: String = OutboxState.PENDING.name,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAt: Long
)
