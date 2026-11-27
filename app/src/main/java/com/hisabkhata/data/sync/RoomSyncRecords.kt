package com.hisabkhata.data.sync

import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.domain.sync.CloudPayload
import com.hisabkhata.domain.sync.PreparedRecord
import com.hisabkhata.domain.sync.SyncEntity
import com.hisabkhata.domain.sync.SyncEntry
import com.hisabkhata.domain.sync.SyncRecordSource
import com.hisabkhata.domain.sync.SyncStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the live local row for a queued change.
 *
 * The queue stores only ids, so the newest local state is always what gets sent and
 * a stale snapshot can never overwrite a newer edit made while offline.
 */
@Singleton
class RoomSyncRecords @Inject constructor(
    private val db: HisabDatabase,
    private val store: SyncStore
) : SyncRecordSource {

    override suspend fun prepare(entry: SyncEntry): PreparedRecord = when (entry.entity) {
        SyncEntity.PERSON -> preparePerson(entry)
        SyncEntity.LOAN -> prepareLoan(entry)
        SyncEntity.CASH -> prepareCash(entry)
        SyncEntity.NOTE -> prepareNote(entry)
    }

    private suspend fun preparePerson(entry: SyncEntry): PreparedRecord {
        val person = db.personDao().findById(entry.localId) ?: return PreparedRecord.RowGone
        return PreparedRecord.Ready(
            CloudPayload.Person(
                clientId = entry.clientId,
                name = person.name,
                phone = person.phone,
                note = person.note,
                type = if (person.type == PersonType.LENT) "lent" else "borrowed"
            )
        )
    }

    private suspend fun prepareLoan(entry: SyncEntry): PreparedRecord {
        val loan = db.loanEntryDao().findById(entry.localId) ?: return PreparedRecord.RowGone
        // The person must exist in the cloud first; otherwise wait instead of
        // sending a loan that points at a local numeric id.
        val personId = store.cloudId(SyncEntity.PERSON, loan.personId)
            ?: return PreparedRecord.Deferred(SyncEntity.PERSON)
        // The column value is only a marker that a link exists; the link itself is
        // applied by link_ledger_entries after both rows exist (see CloudGateway).
        val linkedCashLocal = loan.linkedCashEntryId
        val linkedCashCloud = linkedCashLocal?.let { store.cloudId(SyncEntity.CASH, it) }
        return PreparedRecord.Ready(
            CloudPayload.Loan(
                clientId = entry.clientId,
                personId = personId,
                entryDate = loan.date,
                loan = loan.loan,
                paid = loan.paid,
                description = loan.description,
                createdAtMs = loan.createdAt,
                linkedCashEntryId = linkedCashCloud,
                linkedCashLocalId = linkedCashLocal
            )
        )
    }

    private suspend fun prepareCash(entry: SyncEntry): PreparedRecord {
        val cash = db.cashEntryDao().findById(entry.localId) ?: return PreparedRecord.RowGone
        val linkedLoanLocal = cash.linkedLoanEntryId
        val linkedLoanCloud = linkedLoanLocal?.let { store.cloudId(SyncEntity.LOAN, it) }
        return PreparedRecord.Ready(
            CloudPayload.Cash(
                clientId = entry.clientId,
                entryDate = cash.date,
                deposit = cash.deposit,
                cost = cash.cost,
                description = cash.description,
                createdAtMs = cash.createdAt,
                linkedLoanEntryId = linkedLoanCloud,
                linkedLoanLocalId = linkedLoanLocal
            )
        )
    }

    private suspend fun prepareNote(entry: SyncEntry): PreparedRecord {
        val note = db.noteDao().findById(entry.localId) ?: return PreparedRecord.RowGone
        if (note.deletedAtMs != null || !note.isMine) return PreparedRecord.RowGone
        val trimmedTitle = note.title.trim().take(200)
        if (trimmedTitle.isEmpty() && note.body.trim().isEmpty()) return PreparedRecord.RowGone
        return PreparedRecord.Ready(
            CloudPayload.Note(
                clientId = entry.clientId,
                title = trimmedTitle,
                body = note.body,
                isPublic = note.isPublic,
                showAuthor = note.isPublic && note.showAuthor,
                pinned = note.pinned
            )
        )
    }
}
