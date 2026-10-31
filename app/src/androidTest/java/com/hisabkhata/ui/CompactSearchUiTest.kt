package com.hisabkhata.ui

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.R
import com.hisabkhata.data.local.HisabDatabase
import com.hisabkhata.data.local.entity.PersonType
import com.hisabkhata.data.repository.CashRepository
import com.hisabkhata.data.repository.LoanRepository
import com.hisabkhata.ui.cash.CashBookScreen
import com.hisabkhata.ui.cash.CashBookViewModel
import com.hisabkhata.ui.cash.DateFilter
import com.hisabkhata.ui.loan.PeopleScreen
import com.hisabkhata.ui.loan.PeopleViewModel
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompactSearchUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var context: Context
    private lateinit var db: HisabDatabase
    private lateinit var cash: CashRepository
    private lateinit var loans: LoanRepository
    private val oct1 = LocalDate.of(2026, 10, 1).toEpochDay()
    private val oct31 = LocalDate.of(2026, 10, 31).toEpochDay()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, HisabDatabase::class.java).build()
        cash = CashRepository(db.cashEntryDao())
        loans = LoanRepository(db, context)
    }
    @After fun tearDown() { db.close() }

    @Composable private fun Sized(width: Dp, height: Dp, scale: Float, content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
            MaterialTheme { Box(Modifier.width(width).height(height).testTag("viewport")) { content() } }
        }
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private val pixelsPerDp: Float get() = context.resources.displayMetrics.density
    private fun inDp(px: Float): Float = px / pixelsPerDp

    private fun textFits(value: String, ancestor: String) {
        val nodes = compose.onAllNodes(hasText(value) and hasAnyAncestor(hasTestTag(ancestor)),
            useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Expected full $value text inside $ancestor", nodes.isNotEmpty())
        val layouts = mutableListOf<TextLayoutResult>()
        val action = nodes.first().config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertNotNull("Text layout must be available", action)
        assertTrue(action!!.action!!.invoke(layouts))
        assertEquals("One status/chip/placeholder line", 1, layouts.first().lineCount)
        assertFalse("Text should not overflow vertically or be clipped: $value", layouts.first().hasVisualOverflow)
    }

    private fun chipScrollMaximum(): Float {
        val node = compose.onNodeWithTag("cash_filter_chips", useUnmergedTree = true).fetchSemanticsNode()
        val range = node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
        assertNotNull("Chip row reports its scroll range", range)
        return range!!.maxValue()
    }

    @Test fun compactGeometryAndCountsAtNormalFont() = runBlocking {
        repeat(4) { cash.addEntry(oct1 + it, 100, 0, "Entry $it") }
        val vm = CashBookViewModel(cash, loans)
        compose.setContent { Sized(320.dp, 700.dp, 1f) { CashBookScreen({}, {}, {}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.totalCount == 4 }
        compose.onNodeWithText("4 entries").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.filter_all_summary)).assertDoesNotExist()
        val summary = bounds("cash_summary")
        val search = bounds("cash_search")
        val chip = bounds("cash_filter_all")
        val status = bounds("cash_status")
        val header = bounds("cash_ledger_header")
        assertTrue("Summary should be about 30 percent shorter than the old 120dp card: ${inDp(summary.height)}dp",
            inDp(summary.height) in 75f..90f)
        assertEquals("Search height", 48f, inDp(search.height), 1f)
        assertEquals("12dp screen edge", 12f, inDp(search.left - bounds("viewport").left), 1f)
        assertEquals("Search fills available width", 296f, inDp(search.width), 1f)
        assertEquals("Visible chip height", 36f, inDp(chip.height), 1f)
        assertEquals("Search to chip", 8f, inDp(chip.top - search.bottom), 1f)
        assertEquals("Chip to status", 8f, inDp(status.top - chip.bottom), 1f)
        assertEquals("Status to header", 8f, inDp(header.top - status.bottom), 1f)
        assertEquals("Fitting chips do not scroll", 0f, chipScrollMaximum(), 0.1f)
        textFits("4 entries", "cash_status")
        textFits(context.getString(R.string.search_description), "cash_search")
    }

    @Test fun searchMonthAndDateRangeShowValuesAndClearIndependently() = runBlocking {
        cash.addEntry(oct1 + 1, 100, 0, "Rent")
        cash.addEntry(oct1 + 4, 100, 0, "Market")
        cash.addEntry(oct31 + 1, 100, 0, "Other")
        val vm = CashBookViewModel(cash, loans)
        compose.setContent { Sized(480.dp, 750.dp, 1f) { CashBookScreen({}, {}, {}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.totalCount == 3 }
        compose.onNodeWithTag("cash_search").performTextInput("Rent")
        compose.waitUntil(30_000) { vm.uiState.value.query == "Rent" && vm.uiState.value.rows.size == 1 }
        compose.onNodeWithText("Showing 1 of 3 entries").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.clear_search)).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(30_000) { vm.currentQuery.isEmpty() && vm.uiState.value.rows.size == 3 }
        compose.onNodeWithText("3 entries").assertIsDisplayed()

        compose.runOnIdle { vm.setFilter(DateFilter.Month(2026, 10)) }
        compose.waitUntil(30_000) { vm.uiState.value.filter is DateFilter.Month && vm.uiState.value.rows.size == 2 }
        compose.onNodeWithText("Oct 2026").assertIsDisplayed()
        compose.onNodeWithText("Showing 2 of 3 entries").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.clear_month_filter)).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(30_000) { vm.uiState.value.filter == DateFilter.All && vm.uiState.value.rows.size == 3 }
        compose.onNodeWithText("3 entries").assertIsDisplayed()

        compose.runOnIdle { vm.setFilter(DateFilter.Range(oct1, oct31)) }
        compose.waitUntil(30_000) { vm.uiState.value.filter is DateFilter.Range && vm.uiState.value.rows.size == 2 }
        compose.onNodeWithText("01.10.26 - 31.10.26").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.clear_date_range_filter)).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(30_000) { vm.uiState.value.filter == DateFilter.All && vm.uiState.value.rows.size == 3 }
        compose.onNodeWithText("3 entries").assertIsDisplayed()
        Unit
    }

    @Test fun controlsScrollAwayAndReturnWhileSummaryAndHeaderStay() = runBlocking {
        repeat(25) { cash.addEntry(oct1 + it, 100, 0, "Entry $it") }
        val vm = CashBookViewModel(cash, loans)
        compose.setContent { Sized(360.dp, 700.dp, 1f) { CashBookScreen({}, {}, {}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.totalCount == 25 }
        compose.onNodeWithTag("cash_search").assertIsDisplayed()
        val initialSummary = bounds("cash_summary")
        compose.onNodeWithTag("cash_ledger").performScrollToIndex(20)
        compose.onNodeWithTag("cash_controls").assertDoesNotExist()
        compose.onNodeWithTag("cash_summary").assertIsDisplayed()
        compose.onNodeWithTag("cash_ledger_header").assertIsDisplayed()
        assertEquals("Summary stays fixed", initialSummary.top, bounds("cash_summary").top, 1f)
        assertEquals("Header sticks to top of the list", bounds("cash_ledger").top, bounds("cash_ledger_header").top, 1f)
        compose.onNodeWithTag("cash_ledger").performScrollToIndex(0)
        compose.onNodeWithTag("cash_search").assertIsDisplayed()
        compose.onNodeWithTag("cash_status").assertIsDisplayed()
        assertEquals("Summary never moved", initialSummary.top, bounds("cash_summary").top, 1f)
    }

    @Test fun largeFontOverflowScrollsOnlyTheChipsAndKeepsFullRange() = runBlocking {
        cash.addEntry(oct1 + 1, 100, 0, "Market")
        val vm = CashBookViewModel(cash, loans)
        compose.setContent { Sized(320.dp, 700.dp, 2f) { CashBookScreen({}, {}, {}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.totalCount == 1 }
        assertTrue("Large chips overflow and can scroll", chipScrollMaximum() > 0f)
        assertEquals("Search is still 48dp", 48f, inDp(bounds("cash_search").height), 1f)
        textFits(context.getString(R.string.search_description), "cash_search")
        compose.runOnIdle { vm.setFilter(DateFilter.Range(oct1, oct31)) }
        compose.waitUntil(30_000) { vm.uiState.value.filter is DateFilter.Range }
        compose.onNodeWithTag("cash_filter_chips").performTouchInput { swipeLeft() }
        compose.onNodeWithText("01.10.26 - 31.10.26").assertIsDisplayed()
        textFits("01.10.26 - 31.10.26", "cash_filter_range")
        compose.onNodeWithContentDescription(context.getString(R.string.clear_date_range_filter)).assertIsDisplayed()
        Unit
    }

    @Test fun shortLandscapeAtLargeFontStillHasFixedSummaryAndUsableLedger() = runBlocking {
        val id = cash.addEntry(oct1 + 1, 100, 0, "Landscape")
        val vm = CashBookViewModel(cash, loans)
        compose.setContent { Sized(480.dp, 320.dp, 2f) { CashBookScreen({}, {}, {}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.totalCount == 1 }
        compose.onNodeWithTag("cash_ledger").performScrollToIndex(2)
        compose.onNodeWithTag("cash_summary").assertIsDisplayed()
        compose.onNodeWithTag("cash_ledger_header").assertIsDisplayed()
        compose.onNodeWithTag("cash_row_$id").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertTrue("Header must not cover the fixed summary", bounds("cash_ledger_header").top >= bounds("cash_summary").bottom)
        assertTrue("List needs enough vertical room for both header and a row", inDp(bounds("cash_ledger").height) >= 96f)
    }

    @Test fun veryShortViewportKeepsAddActionsAwayFromBothSearchFields() = runBlocking {
        cash.addEntry(oct1 + 1, 100, 0, "Landscape")
        loans.addPerson("Example Person A", "", "", PersonType.LENT)
        val cashVm = CashBookViewModel(cash, loans)
        val peopleVm = PeopleViewModel(loans)
        val showPeople = mutableStateOf(false)
        val addedCash = AtomicBoolean(false)
        val addedPerson = AtomicBoolean(false)
        // 216dp is the space left above a large-font bottom navigation bar on a 320dp-high screen.
        compose.setContent {
            Sized(480.dp, 216.dp, 2f) {
                if (showPeople.value) PeopleScreen({ addedPerson.set(true) }, {}, peopleVm)
                else CashBookScreen({ addedCash.set(true) }, {}, {}, {}, cashVm)
            }
        }
        compose.waitUntil(30_000) { !cashVm.uiState.value.isLoading && cashVm.uiState.value.totalCount == 1 }
        val addCash = compose.onNodeWithContentDescription(context.getString(R.string.add_entry)).assertIsDisplayed()
        assertTrue("Add entry cannot overlap search", addCash.fetchSemanticsNode().boundsInRoot.bottom <= bounds("cash_search").top)
        assertTrue("Header and a row have at least 96dp of list space", inDp(bounds("cash_ledger").height) >= 96f)
        addCash.performClick()
        assertTrue("Add entry remains functional", addedCash.get())
        compose.runOnIdle { showPeople.value = true }
        compose.waitUntil(30_000) { !peopleVm.uiState.value.isLoading && peopleVm.uiState.value.totalCount == 1 }
        val addPerson = compose.onNodeWithContentDescription(context.getString(R.string.add_person)).assertIsDisplayed()
        assertTrue("Add person cannot overlap search", addPerson.fetchSemanticsNode().boundsInRoot.bottom <= bounds("people_search").top)
        addPerson.performClick()
        assertTrue("Add person remains functional", addedPerson.get())
    }

    @Test fun peopleSearchUsesCompactClearableFieldAndOneAccurateCount() = runBlocking {
        loans.addPerson("Example Person A", "", "", PersonType.LENT)
        loans.addPerson("Example Person B", "", "", PersonType.BORROWED)
        val vm = PeopleViewModel(loans)
        compose.setContent { Sized(320.dp, 700.dp, 2f) { PeopleScreen({}, {}, vm) } }
        compose.waitUntil(30_000) { !vm.uiState.value.isLoading && vm.uiState.value.totalCount == 2 }
        assertEquals("People search height", 48f, inDp(bounds("people_search").height), 1f)
        compose.onNodeWithText("2 people").assertIsDisplayed()
        textFits(context.getString(R.string.search_people), "people_search")
        compose.onNodeWithTag("people_search").performTextInput("Example Person A")
        compose.waitUntil(30_000) { vm.currentQuery == "Example Person A" && vm.uiState.value.rows.size == 1 }
        compose.onNodeWithText("Showing 1 of 2 people").assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.clear_search)).assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil(30_000) { vm.currentQuery.isEmpty() && vm.uiState.value.rows.size == 2 }
        compose.onNodeWithText("2 people").assertIsDisplayed()
        Unit
    }
}
