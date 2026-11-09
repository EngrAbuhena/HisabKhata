package com.hisabkhata.data.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import java.util.Locale

/** The choice is app-local: it never changes the phone's language or ledger data. */
enum class AppLanguage { ENGLISH, BENGALI, SYSTEM }

object AppLocale {
    const val PREFERENCES = "hisab_settings"
    const val LANGUAGE_KEY = "language"

    fun selected(context: Context): AppLanguage {
        val saved = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, AppLanguage.ENGLISH.name)
        return runCatching { AppLanguage.valueOf(saved ?: "") }.getOrDefault(AppLanguage.ENGLISH)
    }

    /**
     * Use a plain locale for resource lookup. Android 7 (API 24) can incorrectly
     * fall back to English when a Unicode numbering extension is put on the
     * Configuration locale, even though the Bengali resources are packaged.
     */
    fun locale(language: AppLanguage, systemLocale: Locale): Locale {
        val bengali = language == AppLanguage.BENGALI ||
            (language == AppLanguage.SYSTEM && systemLocale.language.equals("bn", ignoreCase = true))
        return Locale.forLanguageTag(if (bengali) "bn-BD" else "en-US")
    }

    /**
     * Material's calendar uses NumberFormat.getIntegerInstance() for its day and
     * year grid, which consults the process FORMAT category, not the app's
     * resource locale. Restrict only the app process's formatting category; the
     * phone language and the DISPLAY category remain untouched.
     */
    fun useLatinNumberFormatting() {
        Locale.setDefault(Locale.Category.FORMAT, Locale.US)
    }

    /** The calendar needs Bengali month names but explicitly Latin numerals. */
    fun calendarLocale(resourceLocale: Locale): Locale = Locale.Builder()
        .setLocale(resourceLocale)
        .setUnicodeLocaleKeyword("nu", "latn")
        .build()

    /** Wrap both UI and background contexts so exports and notifications match the choice. */
    fun context(base: Context): Context {
        val system = Resources.getSystem().configuration.locales[0] ?: Locale.US
        val configuration = Configuration(base.resources.configuration).apply {
            setLocale(locale(selected(base), system))
        }
        return base.createConfigurationContext(configuration)
    }
}
