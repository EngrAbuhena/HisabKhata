package com.hisabkhata.domain

import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType

/** The implementation supplies a single transaction covering all calls in [block]. */
interface LoanSyncStore {
    suspend fun <T> inTransaction(block: suspend LoanSyncAccess.() -> T): T
}

/** A small persistence boundary so the same synchronization logic is unit-testable. */
interface LoanSyncAccess {
    suspend fun findPerson(id: Long): PersonEntity?
    suspend fun findLoan(id: Long): LoanEntryEntity?
    suspend fun findCash(id: Long): CashEntryEntity?
    suspend fun loansForPerson(personId: Long): List<LoanEntryEntity>
    suspend fun insertLoan(entry: LoanEntryEntity): Long
    suspend fun updateLoan(entry: LoanEntryEntity): Int
    suspend fun deleteLoan(id: Long, personId: Long): Int
    suspend fun insertCash(entry: CashEntryEntity): Long
    suspend fun updateLinkedCash(entry: CashEntryEntity, loanEntryId: Long): Int
    suspend fun deleteCashForLoan(loanEntryId: Long): Int
    suspend fun deleteCashForPerson(personId: Long): Int
    suspend fun updatePerson(person: PersonEntity): Int
    suspend fun deletePerson(id: Long): Int
}

class LoanRecordMissingException : IllegalStateException()
class LoanLinkIntegrityException : IllegalStateException()

/**
 * Coordinates loan and cash writes. Every public mutation is ONE store transaction.
 * The Room implementation uses withTransaction; no cash row is changed merely because
 * its numeric ID was referenced: the back-link must also identify the owning loan.
 */
class LoanCashSynchronizer(
    private val store: LoanSyncStore,
    private val cashDescription: (name: String, description: String) -> String,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun saveEntry(
        personId: Long,
        entryId: Long?,
        date: Long,
        loan: Long,
        paid: Long,
        description: String,
        recordInCash: Boolean
    ): Long {
        require(loan >= 0 && paid >= 0 && (loan > 0 || paid > 0))
        val cleanedDescription = description.trim()
        return store.inTransaction {
            val person = findPerson(personId) ?: throw LoanRecordMissingException()
            if (entryId == null) {
                val createdAt = now()
                val draft = LoanEntryEntity(
                    personId = personId, date = date, loan = loan, paid = paid,
                    description = cleanedDescription, createdAt = createdAt
                )
                val loanId = insertLoan(draft)
                if (recordInCash) {
                    val cashId = insertCash(cashFor(person, draft.copy(id = loanId), createdAt))
                    check(updateLoan(draft.copy(id = loanId, linkedCashEntryId = cashId)) == 1)
                }
                loanId
            } else {
                val original = findLoan(entryId)?.takeIf { it.personId == personId }
                    ?: throw LoanRecordMissingException()
                val updated = original.copy(date = date, loan = loan, paid = paid, description = cleanedDescription)
                when {
                    recordInCash && original.linkedCashEntryId != null -> {
                        val cash = linkedCashOrThrow(original)
                        val values = cashFor(person, updated, cash.createdAt).copy(id = cash.id)
                        if (updateLinkedCash(values, original.id) != 1) throw LoanLinkIntegrityException()
                        check(updateLoan(updated) == 1)
                    }
                    recordInCash -> {
                        val cashId = insertCash(cashFor(person, updated, now()))
                        check(updateLoan(updated.copy(linkedCashEntryId = cashId)) == 1)
                    }
                    else -> {
                        // Delete by the loan back-link, never by an unrelated numeric cash ID.
                        if (original.linkedCashEntryId != null) linkedCashOrThrow(original)
                        deleteCashForLoan(original.id)
                        check(updateLoan(updated.copy(linkedCashEntryId = null)) == 1)
                    }
                }
                entryId
            }
        }
    }

    suspend fun deleteEntry(personId: Long, entryId: Long) = store.inTransaction {
        val entry = findLoan(entryId)?.takeIf { it.personId == personId } ?: throw LoanRecordMissingException()
        deleteCashForLoan(entry.id)
        check(deleteLoan(entry.id, personId) == 1)
    }

    /** Renaming or changing type refreshes every linked cash description and direction. */
    suspend fun updatePerson(person: PersonEntity) = store.inTransaction {
        require(person.name.isNotBlank())
        if (findPerson(person.id) == null) throw LoanRecordMissingException()
        val cleaned = person.copy(name = person.name.trim(), phone = person.phone.trim(), note = person.note.trim())
        loansForPerson(person.id).forEach { loan ->
            if (loan.linkedCashEntryId != null) {
                val cash = linkedCashOrThrow(loan)
                val updatedCash = cashFor(cleaned, loan, cash.createdAt).copy(id = cash.id)
                if (updateLinkedCash(updatedCash, loan.id) != 1) throw LoanLinkIntegrityException()
            }
        }
        check(updatePerson(cleaned) == 1)
    }

    /** Delete cash by loan back-link first, then Room's FK cascades the person's loans. */
    suspend fun deletePerson(personId: Long) = store.inTransaction {
        if (findPerson(personId) == null) throw LoanRecordMissingException()
        deleteCashForPerson(personId)
        check(deletePerson(personId) == 1)
    }

    private suspend fun LoanSyncAccess.linkedCashOrThrow(loan: LoanEntryEntity): CashEntryEntity {
        val cash = loan.linkedCashEntryId?.let { findCash(it) } ?: throw LoanLinkIntegrityException()
        if (cash.linkedLoanEntryId != loan.id) throw LoanLinkIntegrityException()
        return cash
    }

    private fun cashFor(person: PersonEntity, loan: LoanEntryEntity, createdAt: Long): CashEntryEntity {
        val deposit = if (person.type == PersonType.LENT) loan.paid else loan.loan
        val cost = if (person.type == PersonType.LENT) loan.loan else loan.paid
        return CashEntryEntity(
            date = loan.date,
            deposit = deposit,
            cost = cost,
            description = cashDescription(person.name, loan.description),
            createdAt = createdAt,
            linkedLoanEntryId = loan.id
        )
    }
}
