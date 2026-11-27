package com.hisabkhata.data.repository

import android.content.Context
import com.hisabkhata.R
import com.hisabkhata.data.cloud.CloudSession
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.sync.RoomSyncStore
import com.hisabkhata.domain.LoanCashSynchronizer
import com.hisabkhata.domain.sync.SyncEntity
import com.hisabkhata.domain.sync.SyncOperation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoanRepository @Inject constructor(
    private val database: HisabDatabase,
    private val syncStore: RoomSyncStore,
    private val session: CloudSession,
    @ApplicationContext context: Context
) {
    private val people = database.personDao()
    private val loans = database.loanEntryDao()
    private val sync = LoanCashSynchronizer(
        store = RoomLoanSyncStore(database),
        cashDescription = { name, description -> context.getString(R.string.loan_cash_description, name, description) }
    )

    fun observePeople() = people.observeAll()
    fun observeAllEntries() = loans.observeAll()
    fun observePerson(personId: Long) = people.observeById(personId)
    fun observeEntries(personId: Long) = loans.observeForPerson(personId)
    suspend fun findPerson(id: Long) = people.findById(id)
    suspend fun findEntry(id: Long) = loans.findById(id)

    /**
     * Every mutation below queues its upload in the same Room transaction as the
     * local write. A loan entry that also writes a linked cash row queues both, so
     * the pair can never be half-synced.
     */
    suspend fun addPerson(name: String, phone: String, note: String, type: PersonType): Long {
        require(name.isNotBlank())
        return syncStore.track(session.currentUserId(), SyncEntity.PERSON, SyncOperation.UPSERT) {
            val id = people.insert(PersonEntity(name = name.trim(), phone = phone.trim(), note = note.trim(), type = type))
            id to listOf(id)
        }
    }

    suspend fun updatePerson(person: PersonEntity) {
        val beforeCash = cashRowsForPerson(person.id)
        syncStore.track(session.currentUserId(), SyncEntity.PERSON, SyncOperation.UPSERT) {
            val changed = sync.updatePerson(person)
            changed to listOf(person.id)
        }
        // The synchronizer rewrites linked cash descriptions and directions, so
        // queue those rows as well (only the ones that actually exist).
        queue(SyncEntity.CASH, SyncOperation.UPSERT, cashRowsForPerson(person.id) + beforeCash)
    }

    suspend fun deletePerson(personId: Long) {
        val affectedLoans = loans.findAllForPerson(personId).map { it.id }
        val affectedCash = affectedLoans.mapNotNull { loans.findById(it)?.linkedCashEntryId }
        syncStore.track(session.currentUserId(), SyncEntity.PERSON, SyncOperation.DELETE) {
            sync.deletePerson(personId) to listOf(personId)
        }
        // Room's FK cascade removed the loans and the synchronizer removed their
        // cash rows, so each needs its own soft delete in the cloud.
        queue(SyncEntity.LOAN, SyncOperation.DELETE, affectedLoans)
        queue(SyncEntity.CASH, SyncOperation.DELETE, affectedCash)
    }

    suspend fun saveEntry(personId: Long, entryId: Long?, date: Long, loan: Long, paid: Long,
        description: String, recordInCash: Boolean): Long {
        val beforeCash = cashRowsForPerson(personId)
        val beforeLoans = loans.findAllForPerson(personId).map { it.id }
        val savedId = syncStore.track(session.currentUserId(), SyncEntity.LOAN, SyncOperation.UPSERT) {
            val id = sync.saveEntry(personId, entryId, date, loan, paid, description, recordInCash)
            id to listOf(id)
        }
        // The person's name feeds the linked cash description, so queue it too.
        queue(SyncEntity.PERSON, SyncOperation.UPSERT, listOf(personId))
        queue(SyncEntity.LOAN, SyncOperation.UPSERT, beforeLoans)
        queue(SyncEntity.CASH, SyncOperation.UPSERT, cashRowsForPerson(personId))
        queue(SyncEntity.CASH, SyncOperation.DELETE, beforeCash.minus(cashRowsForPerson(personId).toSet()))
        return savedId
    }

    suspend fun deleteEntry(personId: Long, entryId: Long) {
        val affectedCash = loans.findAllForPerson(personId).mapNotNull { it.linkedCashEntryId }
        syncStore.track(session.currentUserId(), SyncEntity.LOAN, SyncOperation.DELETE) {
            sync.deleteEntry(personId, entryId) to listOf(entryId)
        }
        queue(SyncEntity.CASH, SyncOperation.DELETE, affectedCash)
    }

    private suspend fun queue(entity: SyncEntity, operation: SyncOperation, ids: List<Long>) {
        val account = session.currentUserId() ?: return
        ids.distinct().forEach { syncStore.enqueue(account, entity, operation, it) }
    }

    private suspend fun cashRowsForPerson(personId: Long): List<Long> =
        loans.findAllForPerson(personId).mapNotNull { it.linkedCashEntryId }

}
