package com.komic.reader

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test fun simplifiedChineseAndRegionalVariants() {
        for (tag in listOf("zh", "zh-CN", "zh-SG", "zh-Hans", "zh-Hans-HK")) {
            assertEquals(tag, "zh-Hans", supportedLocale(Locale.forLanguageTag(tag)).toLanguageTag())
        }
    }

    @Test fun unsupportedMissingAndEnglishLocalesUseEnglish() {
        for (tag in listOf("en-AU", "fr-FR", "ja-JP", "zh-TW", "zh-HK", "zh-MO", "zh-Hant-CN", "und", "")) {
            assertEquals(tag, Locale.ENGLISH, supportedLocale(Locale.forLanguageTag(tag)))
        }
        assertEquals(Locale.ENGLISH, supportedLocale(null))
    }
}
