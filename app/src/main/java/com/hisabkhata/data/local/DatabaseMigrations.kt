package com.hisabkhata.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 1 -> 2 adds the cloud-sync tables only.
 *
 * The ledger tables (`people`, `cash_entries`, `loan_entries`) are unchanged between
 * schema 1 and 2 — verified against the exported schemas and asserted by
 * `RoomMigrationTest` — so this is an additive migration. Existing rows, money in
 * paisa, dates and cross-ledger links are kept exactly as they are, and no table is
 * dropped or recreated.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The SQL matches app/schemas/.../2.json exactly; Room validates it on open.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_ids` (" +
                "`entity` TEXT NOT NULL, `localId` INTEGER NOT NULL, `clientId` TEXT NOT NULL, " +
                "`cloudId` TEXT, `knownUpdatedAt` TEXT, PRIMARY KEY(`entity`, `localId`))"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_ids_clientId` ON `sync_ids` (`clientId`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `accountId` TEXT NOT NULL, " +
                "`entity` TEXT NOT NULL, `operation` TEXT NOT NULL, `localId` INTEGER NOT NULL, " +
                "`clientId` TEXT NOT NULL, `state` TEXT NOT NULL, `attempts` INTEGER NOT NULL, " +
                "`lastError` TEXT, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sync_outbox_accountId_id` " +
                "ON `sync_outbox` (`accountId`, `id`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_entity_localId` " +
                "ON `sync_outbox` (`entity`, `localId`)"
        )
    }
}
