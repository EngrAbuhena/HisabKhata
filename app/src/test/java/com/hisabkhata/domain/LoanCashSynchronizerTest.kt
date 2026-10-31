package com.hisabkhata.domain

import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.PersonType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoanCashSynchronizerTest {
    private val store = FakeLoanSyncStore().apply {
        people[1] = PersonEntity(id = 1, name = "Example Person A", type = PersonType.LENT)
        people[2] = PersonEntity(id = 2, name = "Example Person B", type = PersonType.BORROWED)
    }
    private var clock = 100L
    private val sync = LoanCashSynchronizer(store, { name, description -> "$name - $description" }, { clock++ })

    private suspend fun save(person: Long, entry: Long? = null, date: Long = 20, loan: Long = 5_000_000,
        paid: Long = 3_000_000, description: String = "Example transfer", cash: Boolean = true): Long =
        sync.saveEntry(person, entry, date, loan, paid, description, cash)

    @Test fun createSetsBothLinksAndMapsBothAmountsForLentAndBorrowed() = runBlocking {
        val lentId = save(1)
        val lent = store.loans.getValue(lentId)
        val lentCash = store.cash.getValue(lent.linkedCashEntryId!!)
        assertEquals(lentId, lentCash.linkedLoanEntryId)
        assertEquals(20L, lentCash.date)
        assertEquals(5_000_000L, lentCash.cost)
        assertEquals(3_000_000L, lentCash.deposit)
        assertEquals("Example Person A - Example transfer", lentCash.description)

        val borrowedId = save(2)
        val borrowed = store.loans.getValue(borrowedId)
        val borrowedCash = store.cash.getValue(borrowed.linkedCashEntryId!!)
        assertEquals(borrowedId, borrowedCash.linkedLoanEntryId)
        assertEquals(5_000_000L, borrowedCash.deposit)
        assertEquals(3_000_000L, borrowedCash.cost)
        assertEquals("Example Person B - Example transfer", borrowedCash.description)
    }

    @Test fun editingAmountsAndDateUpdatesExistingCashWithoutChangingCreationTime() = runBlocking {
        val id = save(1)
        val beforeLoan = store.loans.getValue(id)
        val beforeCash = store.cash.getValue(beforeLoan.linkedCashEntryId!!)
        save(1, id, date = 12, loan = 9_000, paid = 2_000, description = "Bank")
        val afterLoan = store.loans.getValue(id)
        val afterCash = store.cash.getValue(afterLoan.linkedCashEntryId!!)
        assertEquals(beforeCash.id, afterCash.id)
        assertEquals(beforeCash.createdAt, afterCash.createdAt)
        assertEquals(beforeLoan.createdAt, afterLoan.createdAt)
        assertEquals(12L, afterLoan.date)
        assertEquals(12L, afterCash.date)
        assertEquals(9_000L, afterCash.cost)
        assertEquals(2_000L, afterCash.deposit)
        assertEquals("Example Person A - Bank", afterCash.description)
    }

    @Test fun switchingOffDeletesCashAndSwitchingBackOnCreatesANewLink() = runBlocking {
        val id = save(1)
        val firstCashId = store.loans.getValue(id).linkedCashEntryId!!
        save(1, id, cash = false)
        assertNull(store.loans.getValue(id).linkedCashEntryId)
        assertFalse(store.cash.containsKey(firstCashId))
        save(1, id, cash = true)
        val newCashId = store.loans.getValue(id).linkedCashEntryId!!
        assertNotEquals(firstCashId, newCashId)
        assertEquals(id, store.cash.getValue(newCashId).linkedLoanEntryId)
    }

    @Test fun deletingLoanDeletesItsCashButNotUnrelatedCash() = runBlocking {
        val id = save(1)
        val linked = store.loans.getValue(id).linkedCashEntryId!!
        val unrelated = store.insertCash(CashEntryEntity(date = 1, deposit = 10, createdAt = 1))
        sync.deleteEntry(1, id)
        assertFalse(store.loans.containsKey(id))
        assertFalse(store.cash.containsKey(linked))
        assertTrue(store.cash.containsKey(unrelated))
    }

    @Test fun deletingPersonCascadesLoansAndAllOfTheirLinkedCashOnly() = runBlocking {
        val first = save(1)
        val second = save(1, description = "Cash")
        val other = save(2)
        val firstCash = store.loans.getValue(first).linkedCashEntryId!!
        val secondCash = store.loans.getValue(second).linkedCashEntryId!!
        val otherCash = store.loans.getValue(other).linkedCashEntryId!!
        sync.deletePerson(1)
        assertFalse(store.people.containsKey(1))
        assertFalse(store.loans.containsKey(first))
        assertFalse(store.loans.containsKey(second))
        assertFalse(store.cash.containsKey(firstCash))
        assertFalse(store.cash.containsKey(secondCash))
        assertTrue(store.loans.containsKey(other))
        assertTrue(store.cash.containsKey(otherCash))
    }

    @Test fun personNameAndTypeEditsRefreshLinkedCashInTheSameTransaction() = runBlocking {
        val id = save(1)
        val before = store.loans.getValue(id)
        sync.updatePerson(store.people.getValue(1).copy(name = "Example Person A New", type = PersonType.BORROWED))
        val cash = store.cash.getValue(before.linkedCashEntryId!!)
        assertEquals("Example Person A New - Example transfer", cash.description)
        assertEquals(5_000_000L, cash.deposit)
        assertEquals(3_000_000L, cash.cost)
        assertEquals(PersonType.BORROWED, store.people.getValue(1).type)
    }

    @Test fun transactionRollsBackBothSidesWhenTheSecondWriteFails() = runBlocking {
        store.failLoanUpdate = true
        try {
            save(1)
            throw AssertionError("Expected the simulated failure")
        } catch (_: IllegalStateException) {
            assertTrue(store.loans.isEmpty())
            assertTrue(store.cash.isEmpty())
        }
    }

    @Test fun malformedLinkNeverDeletesAnUnrelatedCashEntry() = runBlocking {
        val id = save(1)
        val original = store.loans.getValue(id)
        val otherCash = store.insertCash(CashEntryEntity(date = 1, deposit = 10, createdAt = 1))
        store.loans[id] = original.copy(linkedCashEntryId = otherCash)
        try {
            save(1, id, cash = false)
            throw AssertionError("Expected link validation failure")
        } catch (_: LoanLinkIntegrityException) {
            assertTrue(store.cash.containsKey(otherCash))
            assertEquals(otherCash, store.loans.getValue(id).linkedCashEntryId)
        }
    }
}

private class FakeLoanSyncStore : LoanSyncStore, LoanSyncAccess {
    var people = linkedMapOf<Long, PersonEntity>()
    var loans = linkedMapOf<Long, LoanEntryEntity>()
    var cash = linkedMapOf<Long, CashEntryEntity>()
    private var nextLoan = 1L
    private var nextCash = 1L
    var failLoanUpdate = false

    override suspend fun <T> inTransaction(block: suspend LoanSyncAccess.() -> T): T {
        val oldPeople = LinkedHashMap(people)
        val oldLoans = LinkedHashMap(loans)
        val oldCash = LinkedHashMap(cash)
        val oldNextLoan = nextLoan
        val oldNextCash = nextCash
        return try { block(this) } catch (e: Throwable) {
            people = oldPeople; loans = oldLoans; cash = oldCash
            nextLoan = oldNextLoan; nextCash = oldNextCash
            throw e
        }
    }

    override suspend fun findPerson(id: Long) = people[id]
    override suspend fun findLoan(id: Long) = loans[id]
    override suspend fun findCash(id: Long) = cash[id]
    override suspend fun loansForPerson(personId: Long) = loans.values.filter { it.personId == personId }
    override suspend fun insertLoan(entry: LoanEntryEntity): Long {
        val id = nextLoan++; loans[id] = entry.copy(id = id); return id
    }
    override suspend fun updateLoan(entry: LoanEntryEntity): Int {
        if (failLoanUpdate) { failLoanUpdate = false; error("simulated failure") }
        if (!loans.containsKey(entry.id)) return 0
        loans[entry.id] = entry; return 1
    }
    override suspend fun deleteLoan(id: Long, personId: Long): Int {
        if (loans[id]?.personId != personId) return 0
        loans.remove(id); return 1
    }
    override suspend fun insertCash(entry: CashEntryEntity): Long {
        val id = nextCash++; cash[id] = entry.copy(id = id); return id
    }
    override suspend fun updateLinkedCash(entry: CashEntryEntity, loanEntryId: Long): Int {
        if (cash[entry.id]?.linkedLoanEntryId != loanEntryId) return 0
        cash[entry.id] = entry; return 1
    }
    override suspend fun deleteCashForLoan(loanEntryId: Long): Int {
        val ids = cash.values.filter { it.linkedLoanEntryId == loanEntryId }.map { it.id }
        ids.forEach { cash.remove(it) }; return ids.size
    }
    override suspend fun deleteCashForPerson(personId: Long): Int {
        val loanIds = loans.values.filter { it.personId == personId }.map { it.id }.toSet()
        val ids = cash.values.filter { it.linkedLoanEntryId?.let { id -> id in loanIds } == true }.map { it.id }
        ids.forEach { cash.remove(it) }; return ids.size
    }
    override suspend fun updatePerson(person: PersonEntity): Int {
        if (!people.containsKey(person.id)) return 0
        people[person.id] = person; return 1
    }
    override suspend fun deletePerson(id: Long): Int {
        if (people.remove(id) == null) return 0
        loans.values.filter { it.personId == id }.map { it.id }.forEach { loans.remove(it) }
        return 1
    }
}
