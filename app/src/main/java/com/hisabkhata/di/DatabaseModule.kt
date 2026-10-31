package com.hisabkhata.di

import android.content.Context
import androidx.room.Room
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.dao.CashEntryDao
import com.hisabkhata.data.local.dao.LoanEntryDao
import com.hisabkhata.data.local.dao.PersonDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): HisabDatabase =
        Room.databaseBuilder(context, HisabDatabase::class.java, "hisab-khata.db").build()

    @Provides fun cashEntryDao(db: HisabDatabase): CashEntryDao = db.cashEntryDao()
    @Provides fun personDao(db: HisabDatabase): PersonDao = db.personDao()
    @Provides fun loanEntryDao(db: HisabDatabase): LoanEntryDao = db.loanEntryDao()
}
