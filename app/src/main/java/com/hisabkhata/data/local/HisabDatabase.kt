package com.hisabkhata.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.hisabkhata.data.local.dao.CashEntryDao
import com.hisabkhata.data.local.dao.LoanEntryDao
import com.hisabkhata.data.local.dao.PersonDao
import com.hisabkhata.data.local.dao.SyncDao
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity
import com.hisabkhata.data.local.entity.SyncIdEntity
import com.hisabkhata.data.local.entity.SyncOutboxEntity

@Database(
    entities = [
        CashEntryEntity::class,
        PersonEntity::class,
        LoanEntryEntity::class,
        SyncIdEntity::class,
        SyncOutboxEntity::class
    ],
    // v2 adds only the sync tables: local ids, money and dates are untouched, so
    // an upgrade adds two empty tables instead of rebuilding the ledger.
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class HisabDatabase : RoomDatabase() {
    abstract fun cashEntryDao(): CashEntryDao
    abstract fun personDao(): PersonDao
    abstract fun loanEntryDao(): LoanEntryDao
    abstract fun syncDao(): SyncDao

    companion object {
        const val FILE_NAME = "hisab-khata.db"

        /**
         * One place that opens the database, so a migration can never be registered on
         * one code path and forgotten on another. There is deliberately no
         * fallbackToDestructiveMigration: an unknown schema must fail loudly instead of
         * erasing the user's ledger.
         */
        fun builder(context: Context): RoomDatabase.Builder<HisabDatabase> =
            Room.databaseBuilder(context, HisabDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
    }
}
