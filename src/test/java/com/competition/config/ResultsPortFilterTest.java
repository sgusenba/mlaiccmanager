package com.competition.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResultsPortFilterTest {

    @Test
    void servesTheResultsPageAndItsScripts() {
        assertTrue(ResultsPortFilter.isAllowed("GET", "/results/"));
        assertTrue(ResultsPortFilter.isAllowed("GET", "/results/results.js"));
        assertTrue(ResultsPortFilter.isAllowed("GET", "/js/modules/results.js"));
        assertTrue(ResultsPortFilter.isAllowed("GET", "/style.css"));
    }

    @Test
    void servesTheApiCallsOfResultEntry() {
        assertTrue(ResultsPortFilter.isAllowed("GET", "/api/competitors"));
        assertTrue(ResultsPortFilter.isAllowed("GET", "/api/available-disciplines"));
        assertTrue(ResultsPortFilter.isAllowed("GET", "/api/active-disciplines"));
        assertTrue(ResultsPortFilter.isAllowed("GET", "/api/results"));
        assertTrue(ResultsPortFilter.isAllowed("POST", "/api/results"));
        assertTrue(ResultsPortFilter.isAllowed("DELETE", "/api/results/12"));
    }

    @Test
    void refusesOtherPages() {
        assertFalse(ResultsPortFilter.isAllowed("GET", "/index.html"));
        assertFalse(ResultsPortFilter.isAllowed("GET", "/ranking/"));
        assertFalse(ResultsPortFilter.isAllowed("GET", "/danger/"));
        assertFalse(ResultsPortFilter.isAllowed("GET", "/backup/"));
        assertFalse(ResultsPortFilter.isAllowed("GET", "/swagger/"));
    }

    @Test
    void refusesEveryOtherChange() {
        assertFalse(ResultsPortFilter.isAllowed("POST", "/api/competitors"));
        assertFalse(ResultsPortFilter.isAllowed("DELETE", "/api/competitors/1"));
        assertFalse(ResultsPortFilter.isAllowed("PUT", "/api/results/12"));
        assertFalse(ResultsPortFilter.isAllowed("POST", "/api/active-disciplines"));
        assertFalse(ResultsPortFilter.isAllowed("GET", "/api/backup"));
        assertFalse(ResultsPortFilter.isAllowed("POST", "/api/danger/results"));
        assertFalse(ResultsPortFilter.isAllowed("DELETE", "/api/results"));
    }
}
