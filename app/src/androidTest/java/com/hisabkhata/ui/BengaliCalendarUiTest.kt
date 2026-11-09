package com.hisabkhata.ui

import android.content.Context
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.MainActivity
import com.hisabkhata.data.settings.AppLanguage
import com.hisabkhata.data.settings.AppLocale
import com.hisabkhata.domain.MoneyInputParser
import com.hisabkhata.util.Dates
import java.time.LocalDate
import java.util.Calendar
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BengaliCalendarUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var previous: String? = null

    @Before fun setUp() {
        val prefs = compose.activity.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
        previous = prefs.getString(AppLocale.LANGUAGE_KEY, null)
        compose.runOnIdle {
            compose.activity.preferences.setLanguage(AppLanguage.BENGALI)
            compose.activity.recreate()
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("নগদ খাতা").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun tearDown() {
        val language = runCatching { AppLanguage.valueOf(previous ?: "") }.getOrDefault(AppLanguage.ENGLISH)
        compose.runOnIdle { compose.activity.preferences.setLanguage(language) }
        if (previous == null) compose.activity.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(AppLocale.LANGUAGE_KEY).commit()
    }

    private fun assertNoBengaliDigits() {
        val nodes = compose.onAllNodes(SemanticsMatcher("all nodes") { true }, useUnmergedTree = true).fetchSemanticsNodes()
        val labels = nodes.flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
        assertFalse("Calendar visual and accessibility labels must use 0–9: $labels",
            labels.any { text -> text.any { it in '০'..'৯' } })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun calendarUsesBengaliLabelsAndEnglishDigits() {
        val today = LocalDate.now()
        val epochDay = today.toEpochDay()
        compose.onNodeWithContentDescription("এন্ট্রি যোগ করুন").performClick()
        compose.onNodeWithText(Dates.display(epochDay)).performClick()
        compose.onNodeWithText("নির্বাচিত: ${Dates.display(epochDay)}").assertIsDisplayed()
        val formatter = DatePickerDefaults.dateFormatter()
        val month = MoneyInputParser.normalizeDigits(
            formatter.formatMonthYear(Dates.toPickerMillis(epochDay), Locale.forLanguageTag("bn-BD"))!!
        )
        assertTrue("Expected Bengali month and Latin year: $month", month.contains(today.year.toString()))
        compose.onNodeWithText(month).assertIsDisplayed()
        assertNoBengaliDigits()

        // A 320dp phone must fit the whole surface and all seven weekday cells.
        val dialog = compose.onNodeWithTag("calendar_dialog").fetchSemanticsNode()
        val screenWidth = compose.activity.resources.displayMetrics.widthPixels
        assertTrue("Calendar width ${dialog.size.width} exceeds screen width $screenWidth", dialog.size.width <= screenWidth)
        val dialogBounds = dialog.boundsInWindow
        listOf("শুক্রবার", "শনিবার", "রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার").forEach { day ->
            val node = compose.onNodeWithContentDescription(day).assertIsDisplayed().fetchSemanticsNode()
            val bounds = node.boundsInWindow
            assertTrue("Weekday $day is clipped: $bounds outside $dialogBounds",
                bounds.left >= dialogBounds.left - 1 && bounds.right <= dialogBounds.right + 1)
        }
        compose.onNodeWithText("বাতিল").assertIsDisplayed()
        compose.onNodeWithText("ঠিক আছে").assertIsDisplayed()

        // The first and last columns must be selectable, not just visible.
        val firstWeekday = Calendar.getInstance(Locale.forLanguageTag("bn-BD")).firstDayOfWeek
        val firstIsoWeekday = ((firstWeekday + 5) % 7) + 1
        val edgeWeekdays = listOf(firstIsoWeekday, ((firstIsoWeekday + 5) % 7) + 1)
        edgeWeekdays.forEach { weekday ->
            val edgeDate = (1..today.lengthOfMonth()).map { today.withDayOfMonth(it) }
                .first { it.dayOfWeek.value == weekday }
            val description = MoneyInputParser.normalizeDigits(formatter.formatDate(
                Dates.toPickerMillis(edgeDate.toEpochDay()), Locale.forLanguageTag("bn-BD"), true
            )!!)
            compose.onNode(hasText(description, substring = true)).performClick()
            compose.onNodeWithText("নির্বাচিত: ${Dates.display(edgeDate.toEpochDay())}").assertIsDisplayed()
        }

        // The year chooser also displays Latin digits and returns to the day grid.
        compose.onNodeWithText(month).performClick()
        assertNoBengaliDigits()
        compose.onNodeWithText(month).performClick()

        val chosen = if (today.dayOfMonth == today.lengthOfMonth()) today.minusDays(1) else today.plusDays(1)
        val fullDate = MoneyInputParser.normalizeDigits(formatter.formatDate(
            Dates.toPickerMillis(chosen.toEpochDay()), Locale.forLanguageTag("bn-BD"), true
        )!!)
        compose.onNode(hasText(fullDate, substring = true)).performClick()
        compose.onNodeWithText("নির্বাচিত: ${Dates.display(chosen.toEpochDay())}").assertIsDisplayed()
        compose.onNodeWithText("ঠিক আছে").performClick()
        compose.onNodeWithText(Dates.display(chosen.toEpochDay())).assertIsDisplayed()
    }
}
