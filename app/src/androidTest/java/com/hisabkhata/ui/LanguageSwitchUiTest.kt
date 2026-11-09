package com.hisabkhata.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hisabkhata.MainActivity
import com.hisabkhata.data.settings.AppLanguage
import com.hisabkhata.data.settings.AppLocale
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSwitchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var oldLanguage: String? = null

    @Before fun setEnglish() {
        val prefs = compose.activity.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
        oldLanguage = prefs.getString(AppLocale.LANGUAGE_KEY, null)
        compose.runOnIdle {
            compose.activity.preferences.setLanguage(AppLanguage.ENGLISH)
            compose.activity.recreate()
        }
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Cash Book").fetchSemanticsNodes().isNotEmpty() }
    }

    @After fun restoreLanguage() {
        val language = runCatching { AppLanguage.valueOf(oldLanguage ?: "") }.getOrDefault(AppLanguage.ENGLISH)
        compose.runOnIdle { compose.activity.preferences.setLanguage(language) }
        if (oldLanguage == null) compose.activity.getSharedPreferences(AppLocale.PREFERENCES, Context.MODE_PRIVATE)
            .edit().remove(AppLocale.LANGUAGE_KEY).commit()
    }

    @Test fun switchesBengaliAndBackWithoutChangingThePhoneLanguage() {
        val phoneLanguage = android.content.res.Resources.getSystem().configuration.locales[0]
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("বাংলা").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("নগদ খাতা").fetchSemanticsNodes().isNotEmpty() }
        if (compose.onAllNodesWithText("ভাষা").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithText("সেটিংস").performClick()
        }
        compose.onNodeWithText("ভাষা").assertIsDisplayed()
        compose.onNodeWithText("English").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("Cash Book").fetchSemanticsNodes().isNotEmpty() }
        org.junit.Assert.assertEquals(phoneLanguage, android.content.res.Resources.getSystem().configuration.locales[0])
    }
}
