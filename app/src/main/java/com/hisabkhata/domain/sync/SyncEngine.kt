package com.hisabkhata.domain.sync

/**
 * Pushes queued local changes to Supabase one at a time, oldest first.
 *
 * Guarantees exercised by [com.hisabkhata.domain.sync.SyncEngineTest]:
 * - a queued change is only ever sent for the account that created it;
 * - a parent is uploaded before its children, and a child waits (staying queued)
 *   until the parent has a cloud id instead of being sent with a wrong id;
 * - a conflict never overwrites the server row and never drops the local change;
 * - a permanent rejection keeps the local data and the queue row;
 * - the same pass can be replayed without duplicating rows, because uploads are
 *   upserts keyed by `client_id`;
 * - a linked loan/cash pair is joined by one atomic server call, and the queue row
 *   survives until that call has actually succeeded.
 */
class SyncEngine(
    private val store: SyncStore,
    private val records: SyncRecordSource,
    private val gateway: CloudGateway,
    private val maxAttemptsBeforePause: Int = 8
) {
    suspend fun push(accountId: String, limit: Int = 100): SyncPass {
        val batch = store.pending(accountId, limit)
        if (batch.isEmpty()) return SyncPass()

        var uploaded = 0
        var deleted = 0
        var conflicts = 0
        var waiting = 0
        var lastError: String? = null

        for (entry in batch) {
            // Defence in depth: the query is account-scoped, but a stale row from
            // another account must never be sent under this session.
            if (entry.accountId != accountId) continue

            if (entry.attempts >= maxAttemptsBeforePause) {
                lastError = "paused after repeated failures"
                continue
            }

            val cloudId = store.cloudId(entry.entity, entry.localId)

            if (entry.operation == SyncOperation.DELETE) {
                if (cloudId == null) {
                    // Never reached the server, so there is nothing to delete there.
                    store.remove(entry.outboxId)
                    deleted++
                    continue
                }
                when (val result = gateway.markDeleted(entry.entity, cloudId)) {
                    is UploadResult.Uploaded -> {
                        store.remove(entry.outboxId)
                        deleted++
                    }
                    is UploadResult.Conflict -> {
                        store.markConflict(entry.outboxId, "delete conflict")
                        conflicts++
                    }
                    is UploadResult.Rejected -> {
                        store.recordFailure(entry.outboxId, result.reason)
                        lastError = result.reason
                    }
                    is UploadResult.Retry -> {
                        store.recordFailure(entry.outboxId, result.reason)
                        lastError = result.reason
                    }
                }
                continue
            }

            when (val prepared = records.prepare(entry)) {
                is PreparedRecord.RowGone -> {
                    // Deleted after being queued; the delete has its own queue entry.
                    store.remove(entry.outboxId)
                }
                is PreparedRecord.Deferred -> {
                    store.recordFailure(entry.outboxId, "waiting for ${prepared.waitingFor.name.lowercase()}")
                    waiting++
                }
                is PreparedRecord.Ready -> {
                    val record = SyncRecord(
                        entry = entry,
                        cloudId = cloudId,
                        knownUpdatedAt = store.knownUpdatedAt(entry.entity, entry.localId),
                        payload = prepared.payload
                    )
                    when (val result = gateway.upsert(record)) {
                        is UploadResult.Uploaded -> {
                            store.recordUploaded(entry.entity, entry.localId, result.cloudId, result.updatedAt)
                            uploaded++
                            val linkProblem = applyLink(entry, prepared.payload)
                            if (linkProblem == null) {
                                store.remove(entry.outboxId)
                            } else {
                                // The row itself is saved; keep the queue row so the
                                // retry (an idempotent upsert) can finish the link.
                                store.recordFailure(entry.outboxId, linkProblem)
                                lastError = linkProblem
                            }
                        }
                        is UploadResult.Conflict -> {
                            store.markConflict(entry.outboxId, "changed on another device")
                            conflicts++
                        }
                        is UploadResult.Rejected -> {
                            store.recordFailure(entry.outboxId, result.reason)
                            lastError = result.reason
                        }
                        is UploadResult.Retry -> {
                            store.recordFailure(entry.outboxId, result.reason)
                            lastError = result.reason
                        }
                    }
                }
            }
        }

        return SyncPass(
            uploaded = uploaded,
            deleted = deleted,
            conflicts = conflicts,
            waiting = waiting,
            remaining = store.countPending(accountId),
            lastError = lastError
        )
    }

    /**
     * Joins a loan and its cash row through the server's atomic RPC.
     *
     * Returns null when there is nothing to do (no link, or the link was applied),
     * or a short reason when the partner is not uploaded yet. The caller keeps the
     * queue row in that case rather than silently losing the link — an unlinked but
     * saved pair is exactly the bug this check exists to prevent.
     */
    private suspend fun applyLink(entry: SyncEntry, payload: CloudPayload): String? {
        val loanClientId: String
        val cashClientId: String
        when (payload) {
            is CloudPayload.Person -> return null
            is CloudPayload.Loan -> {
                if (payload.linkedCashEntryId == null) return null
                loanClientId = entry.clientId
                cashClientId = payload.linkedCashLocalId?.let { store.clientId(SyncEntity.CASH, it) }
                    ?: return "link pending: cash row has no client id yet"
            }
            is CloudPayload.Cash -> {
                if (payload.linkedLoanEntryId == null) return null
                cashClientId = entry.clientId
                loanClientId = payload.linkedLoanLocalId?.let { store.clientId(SyncEntity.LOAN, it) }
                    ?: return "link pending: loan row has no client id yet"
            }
        }
        return when (val result = gateway.link(loanClientId, cashClientId, linked = true)) {
            is UploadResult.Uploaded -> null
            // The link already holds (or a delete removed the row); nothing to retry.
            is UploadResult.Conflict -> null
            is UploadResult.Retry -> "link retry: ${result.reason}"
            is UploadResult.Rejected -> "link rejected: ${result.reason}"
        }
    }
}
