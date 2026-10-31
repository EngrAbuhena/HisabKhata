package com.hisabkhata.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.hisabkhata.data.local.dao.CashEntryDao
import com.hisabkhata.data.local.dao.LoanEntryDao
import com.hisabkhata.data.local.dao.PersonDao
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.LoanEntryEntity
import com.hisabkhata.data.local.entity.PersonEntity

@Database(
    entities = [CashEntryEntity::class, PersonEntity::class, LoanEntryEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class HisabDatabase : RoomDatabase() {
    abstract fun cashEntryDao(): CashEntryDao
    abstract fun personDao(): PersonDao
    abstract fun loanEntryDao(): LoanEntryDao
}
