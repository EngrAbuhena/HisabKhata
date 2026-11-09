package com.hisabkhata.util

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.util.Locale

/**
 * Resolve words in the app language, then format numeric arguments with Latin digits.
 * Formatting the template rather than rewriting its result leaves names and other
 * user-entered text (including any Bengali digits in it) completely unchanged.
 */
fun latinFormat(context: Context, @StringRes id: Int, vararg args: Any): String =
    String.format(Locale.US, context.getString(id), *args)

fun latinQuantityFormat(context: Context, @PluralsRes id: Int, quantity: Int, vararg args: Any): String =
    String.format(Locale.US, context.resources.getQuantityString(id, quantity), *args)

@Composable
fun latinStringResource(@StringRes id: Int, vararg args: Any): String =
    String.format(Locale.US, stringResource(id), *args)

@Composable
fun latinPluralResource(@PluralsRes id: Int, quantity: Int, vararg args: Any): String =
    String.format(Locale.US, pluralStringResource(id, quantity), *args)
