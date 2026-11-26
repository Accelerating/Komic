package com.komic.reader

import android.content.Context
import androidx.annotation.StringRes
import java.io.IOException
import java.util.Locale

/** Use only the primary device (or per-app) language. English is the fallback. */
internal fun supportedLocale(primary: Locale?): Locale {
    if (primary?.language != "zh") return Locale.ENGLISH
    val simplified = when (primary.script) {
        "Hans" -> true
        "" -> primary.country !in setOf("TW", "HK", "MO")
        else -> false
    }
    return if (simplified) Locale.forLanguageTag("zh-Hans") else Locale.ENGLISH
}

internal class LibraryError(@StringRes private val messageId: Int, private vararg val args: Any) : IOException() {
    fun describe(context: Context): String = context.getString(messageId, *args)
}
