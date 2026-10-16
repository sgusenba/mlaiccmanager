package com.competition.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LocaleFilterTest {
    @Test
    void cookieThenBrowserLanguageThenEnglish() {
        assertEquals(Locale.GERMAN, LocaleFilter.localeFor("de", "en-US,en;q=0.9"));
        assertEquals(Locale.ENGLISH, LocaleFilter.localeFor("en", "de-AT,de;q=0.9"));
        assertEquals(Locale.GERMAN, LocaleFilter.localeFor(null, "fr-FR,de;q=0.8"));
        assertEquals(Locale.GERMAN, LocaleFilter.localeFor("xx", "de"));
        assertEquals(Locale.ENGLISH, LocaleFilter.localeFor(null, "fr-FR"));
        assertEquals(Locale.ENGLISH, LocaleFilter.localeFor(null, null));
    }
}
