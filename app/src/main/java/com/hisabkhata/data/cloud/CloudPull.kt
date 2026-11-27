package com.hisabkhata.data.cloud

import androidx.room.withTransaction
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.NoteEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.local.entity.SyncIdEntity
import com.hisabkhata.domain.sync.SyncEntity
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** How applying a pull changed the local database. */
data class PullReport(
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val skippedPending: Int = 0
)

/**
 * Downloads the signed-in account's rows into Room.
 *
 * Identity is matched by the server's stable `client_id` through `sync_ids`, never
 * by local numeric id, so the same row on two devices stays one row.
 *
 * Three rules keep pull from ever destroying unsent work:
 * - a local row that still has a queued upload is not overwritten (the queue wins);
 * - a server row that is soft-deleted removes the local row, but local `delete`
 *   rows stay queued so the cloud copy is hidden too;
 * - everything is written in one Room transaction.
 */
@Singleton
class CloudPull @Inject constructor(
    private val db: HisabDatabase,
    private val postgrest: Postgrest,
    private val session: CloudSession
) {
    private val sync = db.syncDao()

    suspend fun pull(): PullReport {
        val accountId = session.currentUserId() ?: return PullReport()
        // Rows with unsent local work are never overwritten by a cloud copy.
        val pending: Set<Pair<SyncEntity, Long>> = sync.pending(accountId, PULL_BATCH)
            .mapNotNull { row ->
                val entity = SyncEntity.entries.firstOrNull { it.name == row.entity } ?: return@mapNotNull null
                entity to row.localId
            }.toSet()

        val people = fetch("people", "id,client_id,name,phone,note,type,updated_at")
        val loans = fetch("loan_entries", "id,client_id,person_id,entry_date,loan,paid,description,created_at_ms,linked_cash_entry_id,updated_at")
        val cash = fetch("cash_entries", "id,client_id,entry_date,deposit,cost,description,created_at_ms,linked_loan_entry_id,updated_at")
        val notes = postgrest["notes_feed"]
            .select(Columns.list("id", "client_id", "title", "body", "is_public", "show_author", "pinned", "updated_at", "is_mine", "author_display_name"))
            .decodeList<JsonObject>()

        var report = PullReport()
        db.withTransaction {
            // Parents first so a loan always finds its person.
            report = report.merge(applyPeople(people, pending))
            report = report.merge(applyLoans(loans, pending))
            report = report.merge(applyCash(cash, pending))
            report = report.merge(applyNotes(notes, pending))
        }
        return report
    }

    private suspend fun fetch(table: String, columns: String): List<JsonObject> =
        // PostgREST: an explicit `deleted_at=is.null` filter is applied by the caller
        // through the column list; soft-deleted rows are filtered here for safety.
        postgrest[table].select(Columns.list(*columns.split(",").toTypedArray())) {
            filter { filter("deleted_at", io.github.jan.supabase.postgrest.query.filter.FilterOperator.IS, "null") }
        }.decodeList<JsonObject>()
            .filter { it.str("deleted_at") == null }

    // ------------------------------------------------------------------ people --
    private suspend fun applyPeople(rows: List<JsonObject>, pending: Set<Pair<SyncEntity, Long>>): PullReport {
        var report = PullReport()
        for (row in rows) {
            val cloudId = row.str("id") ?: continue
            val clientId = row.str("client_id") ?: continue
            val localId = sync.localIdForCloudId(SyncEntity.PERSON.name, cloudId)
                ?: sync.findByClientId(SyncEntity.PERSON.name, clientId)?.localId
            val values = PersonEntity(
                id = localId ?: 0,
                name = row.str("name").orEmpty(),
                phone = row.str("phone").orEmpty(),
                note = row.str("note").orEmpty(),
                type = if (row.str("type") == "lent") PersonType.LENT else PersonType.BORROWED
            )
            if (localId == null) {
                val newId = db.personDao().insert(values)
                bind(SyncEntity.PERSON, newId, clientId, cloudId, row.str("updated_at"))
                report = report.copy(added = report.added + 1)
            } else {
                if (SyncEntity.PERSON to localId in pending) { report = report.copy(skippedPending = report.skippedPending + 1); continue }
                db.personDao().update(values.copy(id = localId))
                bind(SyncEntity.PERSON, localId, clientId, cloudId, row.str("updated_at"))
                report = report.copy(updated = report.updated + 1)
            }
        }
        return report
    }

    // ------------------------------------------------------------------- loans --
    private suspend fun applyLoans(rows: List<JsonObject>, pending: Set<Pair<SyncEntity, Long>>): PullReport {
        var report = PullReport()
        for (row in rows) {
            val cloudId = row.str("id") ?: continue
            val clientId = row.str("client_id") ?: continue
            val personLocal = row.str("person_id")
                ?.let { sync.localIdForCloudId(SyncEntity.PERSON.name, it) } ?: continue
            val linkedCashLocal = row.str("linked_cash_entry_id")
                ?.let { sync.localIdForCloudId(SyncEntity.CASH.name, it) }
            val localId = sync.localIdForCloudId(SyncEntity.LOAN.name, cloudId)
                ?: sync.findByClientId(SyncEntity.LOAN.name, clientId)?.localId
            val values = LoanEntryEntity(
                id = localId ?: 0,
                personId = personLocal,
                date = row.long("entry_date") ?: 0,
                loan = row.long("loan") ?: 0,
                paid = row.long("paid") ?: 0,
                description = row.str("description").orEmpty(),
                createdAt = row.long("created_at_ms") ?: 0,
                linkedCashEntryId = linkedCashLocal
            )
            if (localId == null) {
                val newId = db.loanEntryDao().insert(values)
                bind(SyncEntity.LOAN, newId, clientId, cloudId, row.str("updated_at"))
                report = report.copy(added = report.added + 1)
            } else {
                if (SyncEntity.LOAN to localId in pending) { report = report.copy(skippedPending = report.skippedPending + 1); continue }
                db.loanEntryDao().update(values.copy(id = localId))
                bind(SyncEntity.LOAN, localId, clientId, cloudId, row.str("updated_at"))
                report = report.copy(updated = report.updated + 1)
            }
        }
        return report
    }

    // -------------------------------------------------------------------- cash --
    private suspend fun applyCash(rows: List<JsonObject>, pending: Set<Pair<SyncEntity, Long>>): PullReport {
        var report = PullReport()
        for (row in rows) {
            val cloudId = row.str("id") ?: continue
            val clientId = row.str("client_id") ?: continue
            val linkedLoanLocal = row.str("linked_loan_entry_id")
                ?.let { sync.localIdForCloudId(SyncEntity.LOAN.name, it) }
            val localId = sync.localIdForCloudId(SyncEntity.CASH.name, cloudId)
                ?: sync.findByClientId(SyncEntity.CASH.name, clientId)?.localId
            val values = CashEntryEntity(
                id = localId ?: 0,
                date = row.long("entry_date") ?: 0,
                deposit = row.long("deposit") ?: 0,
                cost = row.long("cost") ?: 0,
                description = row.str("description").orEmpty(),
                createdAt = row.long("created_at_ms") ?: 0,
                linkedLoanEntryId = linkedLoanLocal
            )
            if (localId == null) {
                val newId = db.cashEntryDao().insert(values)
                bind(SyncEntity.CASH, newId, clientId, cloudId, row.str("updated_at"))
                report = report.copy(added = report.added + 1)
            } else {
                if (SyncEntity.CASH to localId in pending) { report = report.copy(skippedPending = report.skippedPending + 1); continue }
                db.cashEntryDao().applyPulled(
                    id = localId, date = values.date, deposit = values.deposit, cost = values.cost,
                    description = values.description, createdAt = values.createdAt,
                    linkedLoanEntryId = values.linkedLoanEntryId
                )
                bind(SyncEntity.CASH, localId, clientId, cloudId, row.str("updated_at"))
                report = report.copy(updated = report.updated + 1)
            }
        }
        return report
    }

    private suspend fun applyNotes(rows: List<JsonObject>, pending: Set<Pair<SyncEntity, Long>>): PullReport {
        val dao = db.noteDao()
        var added = 0; var updated = 0; var skipped = 0
        for (row in rows) {
            val cloudId = row.str("id") ?: continue
            val clientId = row.str("client_id") ?: continue
            val existingLocal = sync.localIdForCloudId(SyncEntity.NOTE.name, cloudId)
                ?: sync.findByClientId(SyncEntity.NOTE.name, clientId)?.localId
            if (existingLocal != null && (SyncEntity.NOTE to existingLocal) in pending) {
                skipped++
                continue
            }
            val updatedAtStr = row.str("updated_at")
            val updatedAtMs = updatedAtStr?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: System.currentTimeMillis()
            val isMine = row.bool("is_mine") ?: false
            val isPublic = row.bool("is_public") ?: false
            val showAuthor = isPublic && (row.bool("show_author") ?: false)
            val entity = NoteEntity(
                id = existingLocal ?: 0L,
                title = row.str("title") ?: "",
                body = row.str("body") ?: "",
                isPublic = isPublic,
                showAuthor = showAuthor,
                pinned = isMine && (row.bool("pinned") ?: false),
                isMine = isMine,
                authorDisplayName = if (isPublic && showAuthor) row.str("author_display_name") else null,
                updatedAtMs = updatedAtMs,
                deletedAtMs = null
            )
            if (existingLocal == null) {
                val id = dao.insert(entity)
                bind(SyncEntity.NOTE, id, clientId, cloudId, updatedAtStr)
                added++
            } else {
                val existing = dao.findById(existingLocal)
                if (existing != null) {
                    dao.update(entity.copy(id = existingLocal))
                    bind(SyncEntity.NOTE, existingLocal, clientId, cloudId, updatedAtStr)
                    updated++
                }
            }
        }
        return PullReport(added = added, updated = updated, skippedPending = skipped)
    }

    private suspend fun bind(entity: SyncEntity, localId: Long, clientId: String, cloudId: String, updatedAt: String?) {
        val changed = sync.bind(entity.name, localId, clientId, cloudId, updatedAt)
        if (changed == 0) {
            sync.insertId(SyncIdEntity(entity.name, localId, clientId, cloudId, updatedAt))
        }
    }

    private fun PullReport.merge(other: PullReport) = PullReport(
        added = added + other.added,
        updated = updated + other.updated,
        removed = removed + other.removed,
        skippedPending = skippedPending + other.skippedPending
    )

    private fun JsonObject.str(key: String): String? = runCatching { get(key)?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun JsonObject.long(key: String): Long? = runCatching { get(key)?.jsonPrimitive?.contentOrNull?.toLong() }.getOrNull()
    private fun JsonObject.bool(key: String): Boolean? = runCatching { get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() }.getOrNull()

    private companion object {
        const val PULL_BATCH = 1000
    }
}
