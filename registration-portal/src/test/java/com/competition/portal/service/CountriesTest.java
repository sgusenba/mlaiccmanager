package com.competition.portal.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountriesTest {
    @Test
    void englishNamesAsTheManagerStoresThem() {
        assertEquals(249, Countries.list().size(), "the Manager's ISO list");
        assertEquals("Austria", Countries.canonical("austria"));
        assertEquals("Germany", Countries.canonical(" Germany "));
        assertNull(Countries.canonical("Österreich"), "stored in English only");
        assertNull(Countries.canonical("Atlantis"));
        assertNull(Countries.canonical(null));
    }
}
