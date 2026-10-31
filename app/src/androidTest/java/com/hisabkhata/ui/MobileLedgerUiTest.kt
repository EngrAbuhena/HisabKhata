package com.hisabkhata.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hisabkhata.R
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.CashEntryEntity
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.CashRepository
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.domain.MoneyFormatter
import com.hisabkhata.ui.cash.CashBookScreen
import com.hisabkhata.ui.cash.CashBookViewModel
import com.hisabkhata.ui.loan.LoanEntryEditorScreen
import com.hisabkhata.ui.loan.LoanEntryEditorViewModel
import com.hisabkhata.ui.loan.PersonLedgerScreen
import com.hisabkhata.ui.loan.PersonLedgerViewModel
import com.hisabkhata.util.Dates
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class MobileLedgerUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var db: HisabDatabase
    private lateinit var loans: LoanRepository
    private lateinit var cash: CashRepository

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        loans = LoanRepository(db, context)
        cash = CashRepository(db.cashEntryDao())
    }
    @After fun tearDown() { db.close() }

    @Composable private fun SizedContent(width: Dp, scale: Float, height: Dp? = null, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            val size = if (height == null) Modifier.width(width).fillMaxHeight() else Modifier.width(width).height(height)
            MaterialTheme { Box(size) { content() } }
        }
    }

    private fun assertNoHorizontalScrolling() {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    // Filter chips may scroll when they overflow. The ledger columns themselves never do.
    private fun assertNoHorizontalScrollingIn(tag: String) {
        compose.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true
        ).assertCountEquals(0)
    }

    private fun textLayoutInRow(value: String, rowTag: String): TextLayoutResult {
        val nodes = compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Expected full text in $rowTag: $value", nodes.isNotEmpty())
        val results = mutableListOf<TextLayoutResult>()
        val action = nodes.first().config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertNotNull("Text layout semantics are required", action)
        assertTrue(action!!.action!!.invoke(results))
        return results.first()
    }

    private fun assertSingleLineFits(value: String, rowTag: String) {
        val rowBounds = compose.onNodeWithTag(rowTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val textNodes = compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNodes()
        textNodes.forEach { node ->
            val bounds = node.boundsInRoot
            assertTrue("Text must visually fit one column: $value ($bounds vs $rowBounds)",
                bounds.width <= rowBounds.width / 4f + 1f && bounds.left >= rowBounds.left - 1f && bounds.right <= rowBounds.right + 1f)
        }
        val result = textLayoutInRow(value, rowTag)
        assertEquals("Amount must not wrap", 1, result.lineCount)
        assertTrue("Text must never shrink below 11sp", result.layoutInput.style.fontSize.value >= 11f)
        assertFalse("Full amount/date must fit: $value, size=${result.size}, paragraph=${result.multiParagraph.width}x${result.multiParagraph.height}, " +
            "widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}, constraints=${result.layoutInput.constraints}",
            result.hasVisualOverflow)
    }

    @Test fun cashScreenFits320dpAtLargeFontWithoutHorizontalScroll() = runBlocking {
        val description = "Long shop description that should wrap naturally over multiple lines on a narrow phone screen"
        val id = db.cashEntryDao().insert(CashEntryEntity(date = 20_000, cost = Long.MAX_VALUE,
            description = description, createdAt = 1))
        val vm = CashBookViewModel(cash, loans)
        compose.setContent { SizedContent(320.dp, 2f) { CashBookScreen({}, {}, {}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.rows.isNotEmpty() }
        compose.onNodeWithTag("cash_ledger").performScrollToNode(hasTestTag("cash_row_$id"))
        compose.onNodeWithTag("cash_ledger_header").assertExists()
        compose.onNodeWithTag("cash_row_$id").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertNoHorizontalScrollingIn("cash_ledger_header")
        assertNoHorizontalScrollingIn("cash_row_$id")
        assertSingleLineFits(Dates.display(20_000), "cash_row_$id")
        assertSingleLineFits(MoneyFormatter.format(Long.MAX_VALUE), "cash_row_$id")
        assertSingleLineFits("-" + MoneyFormatter.format(Long.MAX_VALUE), "cash_row_$id")
        val desc = textLayoutInRow(description, "cash_row_$id")
        assertTrue("Description should wrap into 2–3 lines", desc.lineCount in 2..3)
    }

    @Test fun personScreenFits320dpAtLargeFontWithoutHorizontalScroll() = runBlocking {
        val person = loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val id = loans.saveEntry(person, null, 20_000, Long.MAX_VALUE, 0,
            "A long loan description that wraps without adding another table column", true)
        val vm = PersonLedgerViewModel(SavedStateHandle(mapOf("personId" to person)), loans)
        compose.setContent { SizedContent(320.dp, 2f) { PersonLedgerScreen({}, onEditPerson = {}, onAddEntry = {},
            onEditEntry = { _, _ -> }, onExportPerson = {}, viewModel = vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.rows.isNotEmpty() }
        assertNoHorizontalScrolling()
        compose.onNodeWithTag("loan_ledger").performScrollToNode(hasTestTag("loan_row_$id"))
        compose.onNodeWithTag("loan_ledger_header").assertExists()
        compose.onNodeWithTag("loan_row_$id").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        assertNoHorizontalScrolling()
        assertSingleLineFits(Dates.display(20_000), "loan_row_$id")
        assertSingleLineFits(MoneyFormatter.format(Long.MAX_VALUE), "loan_row_$id")
    }

    @Test fun bothScreensFitLandscapeSized480By320Viewport() = runBlocking {
        val cashId = cash.addEntry(20_000, 123_456_789, 0, "Landscape cash")
        val person = loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val loanId = loans.saveEntry(person, null, 20_000, 123_456_789, 0, "Landscape loan", false)
        val cashVm = CashBookViewModel(cash, loans)
        val loanVm = PersonLedgerViewModel(SavedStateHandle(mapOf("personId" to person)), loans)
        val showLoan = mutableStateOf(false)
        compose.setContent {
            SizedContent(480.dp, 1f, height = 320.dp) {
                if (showLoan.value) PersonLedgerScreen({}, onEditPerson = {}, onAddEntry = {},
                    onEditEntry = { _, _ -> }, onExportPerson = {}, viewModel = loanVm)
                else CashBookScreen({}, {}, {}, {}, cashVm)
            }
        }
        compose.waitUntil(30_000) { !cashVm.uiState.value.isLoading && cashVm.uiState.value.rows.isNotEmpty() }
        compose.onNodeWithTag("cash_ledger").performScrollToNode(hasTestTag("cash_row_$cashId"))
        assertSingleLineFits(MoneyFormatter.format(123_456_789L), "cash_row_$cashId")
        assertNoHorizontalScrollingIn("cash_ledger_header")
        assertNoHorizontalScrollingIn("cash_row_$cashId")
        compose.runOnIdle { showLoan.value = true }
        compose.waitUntil(30_000) { !loanVm.uiState.value.isLoading && loanVm.uiState.value.rows.isNotEmpty() }
        assertNoHorizontalScrolling()
        compose.onNodeWithTag("loan_ledger").performScrollToNode(hasTestTag("loan_row_$loanId"))
        assertSingleLineFits(MoneyFormatter.format(123_456_789L), "loan_row_$loanId")
        assertNoHorizontalScrolling()
    }

    @Test fun linkedLoanDialogDefaultsToCancelAndReflectsSavedLink() = runBlocking {
        val person = loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val id = loans.saveEntry(person, null, 20_000, 7_000, 0, "Saved description", true)
        val cashId = db.loanEntryDao().findById(id)!!.linkedCashEntryId!!
        val vm = LoanEntryEditorViewModel(SavedStateHandle(mapOf("personId" to person, "entryId" to id)), loans)
        val deleted = AtomicBoolean(false)
        compose.setContent { MaterialTheme { LoanEntryEditorScreen({}, onDeleted = { deleted.set(true) }, viewModel = vm) } }
        compose.waitUntil(30_000) { !vm.state.value.isLoading && vm.state.value.originalEntry != null }
        compose.runOnIdle { vm.setRecordInCash(false); vm.setDescription("Unsaved description") }
        compose.onNodeWithContentDescription(context.getString(R.string.delete_entry)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete_linked_cash_warning), substring = true).assertExists()
        compose.onNodeWithText("Saved description", substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.cancel)).assertIsFocused()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertFalse(vm.state.value.pendingDelete)
        assertNotNull(db.loanEntryDao().findById(id))
        compose.onNodeWithContentDescription(context.getString(R.string.delete_entry)).performClick()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNotNull(db.loanEntryDao().findById(id))
        assertNotNull(db.cashEntryDao().findById(cashId))
        compose.onNodeWithContentDescription(context.getString(R.string.delete_entry)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.waitUntil(30_000) { deleted.get() }
        assertNull(db.loanEntryDao().findById(id))
        assertNull(db.cashEntryDao().findById(cashId))
    }
    @Test fun personDialogShowsEntryCountAndCancelKeepsLinkedRows() = runBlocking {
        val person = loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val first = loans.saveEntry(person, null, 20_000, 1_000, 0, "First", true)
        val second = loans.saveEntry(person, null, 20_001, 2_000, 0, "Second", true)
        val firstCash = db.loanEntryDao().findById(first)!!.linkedCashEntryId!!
        val secondCash = db.loanEntryDao().findById(second)!!.linkedCashEntryId!!
        val vm = PersonLedgerViewModel(SavedStateHandle(mapOf("personId" to person)), loans)
        val deleted = AtomicBoolean(false)
        compose.setContent { MaterialTheme { PersonLedgerScreen({}, onDeleted = { deleted.set(true) },
            onEditPerson = {}, onAddEntry = {}, onEditEntry = { _, _ -> }, onExportPerson = {}, viewModel = vm) } }
        compose.waitUntil(30_000) { vm.uiState.value.person != null && vm.uiState.value.rows.size == 2 }
        compose.onNodeWithContentDescription(context.getString(R.string.delete_person)).performClick()
        compose.onNodeWithText("All 2 entries of this person and their linked Cash Book entries will also be deleted. This cannot be undone.",
            substring = true).assertExists()
        compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
        assertNotNull(db.personDao().findById(person))
        assertNotNull(db.cashEntryDao().findById(firstCash))
        compose.onNodeWithContentDescription(context.getString(R.string.delete_person)).performClick()
        compose.onNodeWithText(context.getString(R.string.delete)).performClick()
        compose.waitUntil(30_000) { deleted.get() }
        assertNull(db.personDao().findById(person))
        assertNull(db.loanEntryDao().findById(first))
        assertNull(db.loanEntryDao().findById(second))
        assertNull(db.cashEntryDao().findById(firstCash))
        assertNull(db.cashEntryDao().findById(secondCash))
    }

}
