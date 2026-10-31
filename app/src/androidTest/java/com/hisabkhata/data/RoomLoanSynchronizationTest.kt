package com.hisabkhata.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.LoanRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Integration checks against the real Room/SQLite transaction and foreign-key implementation. */
@RunWith(AndroidJUnit4::class)
class RoomLoanSynchronizationTest {
    private lateinit var db: HisabDatabase
    private lateinit var repo: LoanRepository

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        repo = LoanRepository(db, context)
    }

    @After fun tearDown() { db.close() }

    @Test fun createEditSwitchAndDeleteKeepBothLinksAndProtectCash() = runBlocking {
        val person = repo.addPerson("Example Person A", "", "", PersonType.LENT)
        val loanId = repo.saveEntry(person, null, 20, 5_000_000, 3_000_000, "Example transfer", true)
        val loan = db.loanEntryDao().findById(loanId)!!
        val cashId = loan.linkedCashEntryId!!
        val cash = db.cashEntryDao().findById(cashId)!!
        assertEquals(loanId, cash.linkedLoanEntryId)
        assertEquals(20L, cash.date)
        assertEquals(5_000_000L, cash.cost)
        assertEquals(3_000_000L, cash.deposit)
        assertEquals("Example Person A - Example transfer", cash.description)
        assertEquals(0, db.cashEntryDao().updateUnlinked(cashId, 1, 10, 20, "no"))
        assertEquals(0, db.cashEntryDao().deleteUnlinked(cashId))

        repo.saveEntry(person, loanId, 10, 7_000, 4_000, "Bank", true)
        val editedLoan = db.loanEntryDao().findById(loanId)!!
        val editedCash = db.cashEntryDao().findById(cashId)!!
        assertEquals(cashId, editedLoan.linkedCashEntryId)
        assertEquals(loanId, editedCash.linkedLoanEntryId)
        assertEquals(loan.createdAt, editedLoan.createdAt)
        assertEquals(cash.createdAt, editedCash.createdAt)
        assertEquals(10L, editedCash.date)
        assertEquals(7_000L, editedCash.cost)
        assertEquals(4_000L, editedCash.deposit)
        assertEquals("Example Person A - Bank", editedCash.description)

        repo.saveEntry(person, loanId, 10, 7_000, 4_000, "Bank", false)
        assertNull(db.loanEntryDao().findById(loanId)!!.linkedCashEntryId)
        assertNull(db.cashEntryDao().findById(cashId))
        repo.saveEntry(person, loanId, 10, 7_000, 4_000, "Bank", true)
        val replacementId = db.loanEntryDao().findById(loanId)!!.linkedCashEntryId!!
        assertNotEquals(cashId, replacementId)
        assertEquals(loanId, db.cashEntryDao().findById(replacementId)!!.linkedLoanEntryId)
        val independentId = db.cashEntryDao().insert(CashEntryEntity(date = 1, deposit = 100, createdAt = 1))
        repo.deleteEntry(person, loanId)
        assertNull(db.loanEntryDao().findById(loanId))
        assertNull(db.cashEntryDao().findById(replacementId))
        assertNotNull(db.cashEntryDao().findById(independentId))
    }

    @Test fun borrowedMappingsPersonChangesAndCascadeDeleteAreTransactional() = runBlocking {
        val borrowed = repo.addPerson("Example Person B", "", "", PersonType.BORROWED)
        val other = repo.addPerson("Other", "", "", PersonType.LENT)
        val loanId = repo.saveEntry(borrowed, null, 10, 12_000, 7_000, "Cash", true)
        val second = repo.saveEntry(borrowed, null, 11, 2_000, 0, "", true)
        val otherLoan = repo.saveEntry(other, null, 12, 9_000, 0, "Other", true)
        val cashId = db.loanEntryDao().findById(loanId)!!.linkedCashEntryId!!
        val secondCash = db.loanEntryDao().findById(second)!!.linkedCashEntryId!!
        val otherCash = db.loanEntryDao().findById(otherLoan)!!.linkedCashEntryId!!
        val cash = db.cashEntryDao().findById(cashId)!!
        assertEquals(12_000L, cash.deposit)
        assertEquals(7_000L, cash.cost)
        assertEquals("Example Person B - Cash", cash.description)

        val person = db.personDao().findById(borrowed)!!
        repo.updatePerson(person.copy(name = "Example Person B New", type = PersonType.LENT))
        val renamedCash = db.cashEntryDao().findById(cashId)!!
        assertEquals("Example Person B New - Cash", renamedCash.description)
        assertEquals(7_000L, renamedCash.deposit)
        assertEquals(12_000L, renamedCash.cost)
        assertEquals("Example Person B New - ", db.cashEntryDao().findById(secondCash)!!.description)
        repo.deletePerson(borrowed)
        assertNull(db.personDao().findById(borrowed))
        assertNull(db.loanEntryDao().findById(loanId))
        assertNull(db.loanEntryDao().findById(second))
        assertNull(db.cashEntryDao().findById(cashId))
        assertNull(db.cashEntryDao().findById(secondCash))
        assertNotNull(db.loanEntryDao().findById(otherLoan))
        assertNotNull(db.cashEntryDao().findById(otherCash))
    }

    @Test fun failureAfterCashInsertRollsBackLoanAndCash() = runBlocking {
        val person = repo.addPerson("Example Person A", "", "", PersonType.LENT)
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_loan_link BEFORE UPDATE OF linkedCashEntryId ON loan_entries
            WHEN NEW.linkedCashEntryId IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'simulated link failure'); END
        """.trimIndent())
        try {
            repo.saveEntry(person, null, 10, 5_000, 0, "Cash", true)
            fail("The trigger should have aborted the transaction")
        } catch (_: SQLiteConstraintException) {
            assertTrue(db.loanEntryDao().observeAll().first().isEmpty())
            assertTrue(db.cashEntryDao().observeAll().first().isEmpty())
            assertNotNull(db.personDao().findById(person))
        }
    }

    @Test fun failureAfterLinkedCashDeletionRollsBackTheWholePersonDeletion() = runBlocking {
        val person = repo.addPerson("Rollback person", "", "", PersonType.LENT)
        val first = repo.saveEntry(person, null, 10, 1_000, 0, "First", true)
        val second = repo.saveEntry(person, null, 11, 2_000, 0, "Second", true)
        val firstCash = db.loanEntryDao().findById(first)!!.linkedCashEntryId!!
        val secondCash = db.loanEntryDao().findById(second)!!.linkedCashEntryId!!
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_person_delete BEFORE DELETE ON people
            BEGIN SELECT RAISE(ABORT, 'simulated person delete failure'); END
        """.trimIndent())
        try {
            repo.deletePerson(person)
            fail("The trigger should abort after the cash deletions")
        } catch (_: SQLiteConstraintException) {
            assertNotNull(db.personDao().findById(person))
            assertNotNull(db.loanEntryDao().findById(first))
            assertNotNull(db.loanEntryDao().findById(second))
            assertNotNull(db.cashEntryDao().findById(firstCash))
            assertNotNull(db.cashEntryDao().findById(secondCash))
        }
    }

    @Test fun foreignKeyRejectsLoanForMissingPerson() = runBlocking {
        try {
            db.loanEntryDao().insert(LoanEntryEntity(personId = 987_654, date = 10, loan = 100, createdAt = 1))
            fail("Room must enforce the person foreign key")
        } catch (_: SQLiteConstraintException) {
            assertFalse(db.loanEntryDao().observeAll().first().isNotEmpty())
        }
    }
}
