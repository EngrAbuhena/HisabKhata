package com.hisabkhata.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the v1.2.0 -> v1.3.0 upgrade keeps the ledger.
 *
 * The test creates a real schema-1 database, inserts people/loans/cash with a
 * cross-ledger link, runs [MIGRATION_1_2], and then verifies every row, amount and
 * link survived and that the new sync tables exist and are empty.
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HisabDatabase::class.java
    )

    @Test
    fun migrate1To2_keepsLedgerAndAddsSyncTables() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO people (id, name, phone, note, type) VALUES (1, 'Ann', '017', 'n', 'LENT')")
            execSQL("INSERT INTO people (id, name, phone, note, type) VALUES (2, 'Bilal', '', '', 'BORROWED')")
            execSQL(
                "INSERT INTO loan_entries (id, personId, date, loan, paid, description, createdAt, linkedCashEntryId) " +
                    "VALUES (10, 1, 20000, 123456789, 0, 'lent', 1700000000000, 100)"
            )
            execSQL(
                "INSERT INTO cash_entries (id, date, deposit, cost, description, createdAt, linkedLoanEntryId) " +
                    "VALUES (100, 20000, 0, 123456789, 'Ann - lent', 1700000000000, 10)"
            )
            // A pre-existing unlinked cash row must survive too.
            execSQL(
                "INSERT INTO cash_entries (id, date, deposit, cost, description, createdAt, linkedLoanEntryId) " +
                    "VALUES (101, 20001, 5000, 0, 'opening', 1700000001000, NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT COUNT(*) FROM people").use { c ->
            c.moveToFirst(); assertEquals(2, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM cash_entries").use { c ->
            c.moveToFirst(); assertEquals(2, c.getInt(0))
        }
        // Amounts are still integer paisa and the cross-link is intact both ways.
        db.query(
            "SELECT l.loan, c.cost, l.linkedCashEntryId, c.linkedLoanEntryId " +
                "FROM loan_entries l JOIN cash_entries c ON c.id = l.linkedCashEntryId WHERE l.id = 10"
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(123456789L, c.getLong(0))
            assertEquals(123456789L, c.getLong(1))
            assertEquals(100L, c.getLong(2))
            assertEquals(10L, c.getLong(3))
        }
        db.query("SELECT COUNT(*) FROM sync_ids").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM sync_outbox").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
