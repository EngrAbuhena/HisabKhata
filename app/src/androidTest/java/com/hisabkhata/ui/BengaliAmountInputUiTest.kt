package com.hisabkhata.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.R
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.CashRepository
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.ui.cash.CashEntryEditorScreen
import com.hisabkhata.ui.cash.CashEntryEditorViewModel
import com.hisabkhata.ui.loan.LoanEntryEditorScreen
import com.hisabkhata.ui.loan.LoanEntryEditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BengaliAmountInputUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var db: HisabDatabase
    private lateinit var cash: CashRepository
    private lateinit var loans: LoanRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        cash = CashRepository(db.cashEntryDao())
        loans = LoanRepository(db, context)
    }

    @After fun tearDown() { db.close() }

    @Test fun cashEditorAcceptsBengaliDigitsAndKeepsDescriptionUnchanged() {
        val vm = CashEntryEditorViewModel(SavedStateHandle(), cash)
        compose.setContent { MaterialTheme { CashEntryEditorScreen({}, {}, vm) } }
        compose.onNodeWithText(context.getString(R.string.deposit_label)).performTextInput("১,২৩৪.৫৬")
        compose.waitUntil(30_000) { vm.state.value.deposit == "1,234.56" }
        compose.runOnIdle { vm.setDescription("চালের হিসাব ০১ Shop") }
        compose.onNodeWithText(context.getString(R.string.save_entry)).performClick()
        compose.waitUntil(30_000) { runBlocking { db.cashEntryDao().findAll().isNotEmpty() } }
        val saved = runBlocking { db.cashEntryDao().findAll().single() }
        assertEquals(123456L, saved.deposit)
        assertEquals("চালের হিসাব ০১ Shop", saved.description)
    }

    @Test fun loanEditorNormalizesDigitsAndKeepsLinkedCashCorrect() {
        val personId = runBlocking { loans.addPerson("রাহিম ০১", "", "", PersonType.LENT) }
        val vm = LoanEntryEditorViewModel(SavedStateHandle(mapOf("personId" to personId)), loans)
        compose.setContent { MaterialTheme { LoanEntryEditorScreen({}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.state.value.isLoading }
        compose.onNodeWithText(context.getString(R.string.loan_amount_label)).performTextInput("৯৯.০৫")
        compose.waitUntil(30_000) { vm.state.value.loan == "99.05" }
        compose.runOnIdle { vm.setPaid("2.০০"); vm.setDescription("ফেরত ০১"); vm.save() }
        compose.waitUntil(30_000) { runBlocking { db.loanEntryDao().findAll().isNotEmpty() } }
        val loan = runBlocking { db.loanEntryDao().findAll().single() }
        val linked = runBlocking { db.cashEntryDao().findAll().single() }
        assertEquals(9905L, loan.loan)
        assertEquals(200L, loan.paid)
        assertEquals("ফেরত ০১", loan.description)
        assertEquals(linked.id, loan.linkedCashEntryId)
        assertEquals(loan.id, linked.linkedLoanEntryId)
        assertEquals(9905L, linked.cost)
        assertEquals(200L, linked.deposit)
    }
}
