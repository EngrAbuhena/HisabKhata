package com.hisabkhata.domain.sync

/**
 * Which local table a queued change belongs to. The server mirrors these as
 * `people`, `loan_entries` and `cash_entries`.
 */
enum class SyncEntity { PERSON, LOAN, CASH }

enum class SyncOperation { UPSERT, DELETE }

/**
 * One pending write. It is queued inside the SAME Room transaction as the local
 * change, so a crash can never leave a saved row with no queued upload.
 *
 * [accountId] is captured when the row was written. A queued change can only ever
 * be pushed for the account that created it; switching accounts never re-sends
 * another person's work.
 */
data class SyncEntry(
    val outboxId: Long,
    val accountId: String,
    val entity: SyncEntity,
    val operation: SyncOperation,
    val localId: Long,
    val clientId: String,
    val attempts: Int = 0
)

/**
 * The exact server columns for one row. Money stays integer paisa and dates stay
 * epoch days / epoch milliseconds, matching the Android Room schema.
 *
 * Links appear twice on purpose: `linked*Id` is the partner's cloud uuid (what the
 * column stores) and `linked*LocalId` is the partner's local row id, used only to
 * look up the partner's stable `client_id` for the atomic link call.
 */
sealed interface CloudPayload {
    data class Person(
        val clientId: String,
        val name: String,
        val phone: String,
        val note: String,
        /** "lent" or "borrowed" (the server enum). */
        val type: String
    ) : CloudPayload

    data class Loan(
        val clientId: String,
        val personId: String,
        val entryDate: Long,
        val loan: Long,
        val paid: Long,
        val description: String,
        val createdAtMs: Long,
        val linkedCashEntryId: String?,
        val linkedCashLocalId: Long? = null
    ) : CloudPayload

    data class Cash(
        val clientId: String,
        val entryDate: Long,
        val deposit: Long,
        val cost: Long,
        val description: String,
        val createdAtMs: Long,
        val linkedLoanEntryId: String?,
        val linkedLoanLocalId: Long? = null
    ) : CloudPayload
}

/** What the local row currently looks like, before it is sent. */
sealed interface PreparedRecord {
    data class Ready(val payload: CloudPayload) : PreparedRecord

    /** The row no longer exists locally, so the queued upsert is obsolete. */
    data object RowGone : PreparedRecord

    /** A parent row has no cloud id yet; send this child after the parent. */
    data class Deferred(val waitingFor: SyncEntity) : PreparedRecord
}

/** A ready-to-send record plus the server version we last saw. */
data class SyncRecord(
    val entry: SyncEntry,
    val cloudId: String?,
    val knownUpdatedAt: String?,
    val payload: CloudPayload
)

sealed interface UploadResult {
    /** Insert or conditional update succeeded; [updatedAt] is the new server timestamp. */
    data class Uploaded(val cloudId: String, val updatedAt: String?) : UploadResult

    /** The server row was edited on another device. Nothing was overwritten. */
    data object Conflict : UploadResult

    /** Network or server problem; keep the queued row and try again later. */
    data class Retry(val reason: String) : UploadResult

    /** Permanent refusal (for example a disabled account). Keep the row for the user. */
    data class Rejected(val reason: String) : UploadResult
}

/** Outcome of one push pass. */
data class SyncPass(
    val uploaded: Int = 0,
    val deleted: Int = 0,
    val conflicts: Int = 0,
    val waiting: Int = 0,
    val remaining: Int = 0,
    val lastError: String? = null
)

/** Persistence boundary for the queue and the local-to-cloud id mapping. */
interface SyncStore {
    /** Oldest-first so parents are always uploaded before children. */
    suspend fun pending(accountId: String, limit: Int = 100): List<SyncEntry>

    suspend fun cloudId(entity: SyncEntity, localId: Long): String?

    /** Stable per-row id sent to the server; also the key of the link call. */
    suspend fun clientId(entity: SyncEntity, localId: Long): String?

    suspend fun knownUpdatedAt(entity: SyncEntity, localId: Long): String?

    suspend fun recordUploaded(entity: SyncEntity, localId: Long, cloudId: String, updatedAt: String?)

    suspend fun remove(outboxId: Long)

    suspend fun recordFailure(outboxId: Long, reason: String)

    suspend fun markConflict(outboxId: Long, reason: String)

    suspend fun countPending(accountId: String): Int
}

/**
 * Network boundary; the Supabase adapter implements this.
 *
 * [link] exists because the server's cash/loan symmetry check is a DEFERRED
 * constraint: no single REST request can leave one side pointing at the other, so
 * a linked pair is joined by one atomic server call after both rows exist.
 * Verified against staging — writing the link through plain updates fails 23514.
 */
interface CloudGateway {
    suspend fun upsert(record: SyncRecord): UploadResult
    suspend fun markDeleted(entity: SyncEntity, cloudId: String): UploadResult
    suspend fun link(loanClientId: String, cashClientId: String, linked: Boolean): UploadResult
}

/** Builds the server payload from the live local row. */
interface SyncRecordSource {
    suspend fun prepare(entry: SyncEntry): PreparedRecord
}
