package com.hisabkhata.data.cloud

import com.hisabkhata.domain.sync.CloudGateway
import com.hisabkhata.domain.sync.CloudPayload
import com.hisabkhata.domain.sync.SyncEntity
import com.hisabkhata.domain.sync.SyncRecord
import com.hisabkhata.domain.sync.UploadResult
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * PostgREST adapter for the sync engine.
 *
 * Upserts are keyed by `(owner_id, client_id)` — unique on the server — so a retry
 * after a timeout cannot create a second row. Updates are conditional on the
 * server `updated_at` seen last: zero matching rows means another device changed
 * the row, which is reported as a conflict instead of being overwritten.
 */
@Singleton
class CloudGatewayImpl @Inject constructor(private val postgrest: Postgrest) : CloudGateway {

    override suspend fun upsert(record: SyncRecord): UploadResult = try {
        val table = record.entry.entity.table()
        val body = buildJsonObject {
            put("client_id", JsonPrimitive(record.entry.clientId))
            when (val payload = record.payload) {
                is CloudPayload.Person -> {
                    put("name", JsonPrimitive(payload.name))
                    put("phone", JsonPrimitive(payload.phone))
                    put("note", JsonPrimitive(payload.note))
                    put("type", JsonPrimitive(payload.type))
                }
                is CloudPayload.Loan -> {
                    put("person_id", JsonPrimitive(payload.personId))
                    put("entry_date", JsonPrimitive(payload.entryDate))
                    put("loan", JsonPrimitive(payload.loan))
                    put("paid", JsonPrimitive(payload.paid))
                    put("description", JsonPrimitive(payload.description))
                    put("created_at_ms", JsonPrimitive(payload.createdAtMs))
                    // Never written directly: the server refuses a one-sided link
                    // (deferred symmetry check), so link_ledger_entries owns it.
                    put("linked_cash_entry_id", JsonNull)
                }
                is CloudPayload.Cash -> {
                    put("entry_date", JsonPrimitive(payload.entryDate))
                    put("deposit", JsonPrimitive(payload.deposit))
                    put("cost", JsonPrimitive(payload.cost))
                    put("description", JsonPrimitive(payload.description))
                    put("created_at_ms", JsonPrimitive(payload.createdAtMs))
                    put("linked_loan_entry_id", JsonNull)
                }
            }
        }

        if (record.cloudId == null) {
            // `owner_id` is defaulted from the caller's token server-side; it is never
            // sent from the client.
            val rows = postgrest[table]
                .upsert(body, onConflict = "owner_id,client_id") {
                    select(Columns.list("id", "updated_at"))
                }
                .decodeList<JsonObject>()
            val row = rows.firstOrNull()
            val id = row?.string("id")
            if (row == null || id == null) {
                UploadResult.Retry("empty upsert response")
            } else {
                UploadResult.Uploaded(cloudId = id, updatedAt = row.string("updated_at"))
            }
        } else {
            val rows = postgrest[table].update(body) {
                select(Columns.list("id", "updated_at"))
                filter {
                    eq("id", record.cloudId)
                    // Optimistic concurrency: only replace the version we last saw.
                    record.knownUpdatedAt?.let { eq("updated_at", it) }
                }
            }.decodeList<JsonObject>()
            when {
                rows.isEmpty() && record.knownUpdatedAt == null -> UploadResult.Retry("update matched no row")
                rows.isEmpty() -> UploadResult.Conflict
                else -> UploadResult.Uploaded(
                    cloudId = rows.first().string("id") ?: record.cloudId,
                    updatedAt = rows.first().string("updated_at")
                )
            }
        }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        classify(error)
    }

    override suspend fun markDeleted(entity: SyncEntity, cloudId: String): UploadResult = try {
        // Soft delete: the server keeps the row (and its audit trail) and RLS
        // filters it out of normal reads.
        val body = buildJsonObject { put("deleted_at", JsonPrimitive(Instant.now().toString())) }
        val rows = postgrest[entity.table()].update(body) {
            select(Columns.list("id", "updated_at"))
            filter { eq("id", cloudId) }
        }.decodeList<JsonObject>()
        rows.firstOrNull()?.let {
            UploadResult.Uploaded(cloudId = it.string("id") ?: cloudId, updatedAt = it.string("updated_at"))
        } ?: UploadResult.Uploaded(cloudId, null)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        classify(error)
    }

    /**
     * One atomic server call joins the pair. The RPC is idempotent and also refuses
     * rows the caller does not own, so a stale queued link cannot cross accounts.
     */
    override suspend fun link(loanClientId: String, cashClientId: String, linked: Boolean): UploadResult = try {
        val body = buildJsonObject {
            put("p_loan_client_id", JsonPrimitive(loanClientId))
            put("p_cash_client_id", JsonPrimitive(cashClientId))
            put("p_linked", JsonPrimitive(linked))
        }
        postgrest["rpc/link_ledger_entries"].insert(body) {
            select(Columns.list("loan_id", "cash_entry_id"))
        }.decodeList<JsonObject>()
        // The RPC returns the linked pair; any row means the link now holds.
        UploadResult.Uploaded(cloudId = loanClientId, updatedAt = null)
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        classify(error)
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.let { if (it is JsonNull) null else runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

    private fun classify(error: Exception): UploadResult {
        val message = (error.message ?: error::class.simpleName ?: "unknown") + " " +
            (error.cause?.message ?: "")
        val lower = message.lowercase()
        return when {
            // RLS refuses a disabled or expired session. Retrying will not help until
            // the owner re-enables the account, so keep the row but stop hammering.
            lower.contains("42501") || lower.contains("permission denied") ||
                lower.contains("row-level security") || lower.contains("jwt") -> UploadResult.Rejected("access denied")
            lower.contains("23514") || lower.contains("23503") || lower.contains("constraint") ->
                UploadResult.Rejected("invalid row")
            else -> UploadResult.Retry(message.trim().take(200))
        }
    }

    private fun SyncEntity.table(): String = when (this) {
        SyncEntity.PERSON -> "people"
        SyncEntity.LOAN -> "loan_entries"
        SyncEntity.CASH -> "cash_entries"
    }
}
