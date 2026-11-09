package com.hisabkhata.data.settings

import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLocaleTest {
    @Test fun resourceLocaleAvoidsExtensionsButCalendarUsesLatinDigits() {
        val bengali = AppLocale.locale(AppLanguage.BENGALI, Locale.US)
        assertEquals("bn", bengali.language)
        assertEquals("BD", bengali.country)
        assertNull(bengali.getUnicodeLocaleType("nu"))
        val calendar = AppLocale.calendarLocale(bengali)
        assertEquals("bn", calendar.language)
        assertEquals("latn", calendar.getUnicodeLocaleType("nu"))
        assertEquals('0', DecimalFormatSymbols.getInstance(calendar).zeroDigit)
        assertEquals("1234", String.format(calendar, "%d", 1234))
    }

    @Test fun systemLanguageFallsBackToEnglishButExplicitChoiceWins() {
        assertEquals("bn", AppLocale.locale(AppLanguage.SYSTEM, Locale.forLanguageTag("bn-IN")).language)
        assertEquals("en", AppLocale.locale(AppLanguage.SYSTEM, Locale.FRANCE).language)
        assertEquals("en", AppLocale.locale(AppLanguage.ENGLISH, Locale.forLanguageTag("bn-BD")).language)
        assertEquals("bn", AppLocale.locale(AppLanguage.BENGALI, Locale.US).language)
    }
}
