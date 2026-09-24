package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MeetServiceTest {

    @TempDir
    Path tempDir;

    private MeetService meetService;

    @BeforeEach
    void setUp() {
        meetService = new MeetService(tempDir.resolve("meet.json").toString());
    }

    private static Map<String, Object> request(Object... keyValues) {
        Map<String, Object> request = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            request.put((String) keyValues[i], keyValues[i + 1]);
        }
        return request;
    }

    @Test
    void emptyDetailsBeforeAnythingIsSaved() throws Exception {
        Map<String, Object> meet = meetService.get();
        assertEquals("", meet.get("name"));
        assertEquals("", meet.get("location"));
        assertEquals("", meet.get("host"));
        assertNull(meet.get("date_from"));
        assertNull(meet.get("date_to"));
        assertEquals(0, meet.get("version"));
        assertFalse(Files.exists(tempDir.resolve("meet.json")));
    }

    @Test
    void savedDetailsAreTrimmedAndReloaded() throws Exception {
        Map<String, Object> saved = meetService.update(request(
            "name", "  Staatsmeisterschaft ", "location", "Bad Zell", "host", "SV Bad Zell",
            "date_from", "2026-06-26", "date_to", "2026-06-27", "version", 0));
        assertEquals("Staatsmeisterschaft", saved.get("name"));
        assertEquals(1, saved.get("version"));

        Map<String, Object> reloaded = new MeetService(tempDir.resolve("meet.json").toString()).get();
        assertEquals("Staatsmeisterschaft", reloaded.get("name"));
        assertEquals("Bad Zell", reloaded.get("location"));
        assertEquals("SV Bad Zell", reloaded.get("host"));
        assertEquals("2026-06-26", reloaded.get("date_from"));
        assertEquals("2026-06-27", reloaded.get("date_to"));
        assertEquals(1, reloaded.get("version"));
    }

    @Test
    void blankDatesAreCleared() throws Exception {
        meetService.update(request("name", "Meet", "date_from", "2026-06-26", "date_to", "2026-06-27"));
        Map<String, Object> saved = meetService.update(request("name", "Meet", "date_from", "", "date_to", null));
        assertNull(saved.get("date_from"));
        assertNull(saved.get("date_to"));
    }

    @Test
    void invalidDatesAreRejected() throws Exception {
        IllegalArgumentException badDate = assertThrows(IllegalArgumentException.class,
            () -> meetService.update(request("date_from", "26.06.2026")));
        assertEquals("date_from must be YYYY-MM-DD", badDate.getMessage());
        IllegalArgumentException reversed = assertThrows(IllegalArgumentException.class,
            () -> meetService.update(request("date_from", "2026-06-27", "date_to", "2026-06-26")));
        assertEquals("date_from must not be after date_to", reversed.getMessage());
        assertThrows(IllegalArgumentException.class, () -> meetService.update(request("name", "x".repeat(201))));
        assertFalse(Files.exists(tempDir.resolve("meet.json")));
    }

    @Test
    void staleVersionIsAConflict() throws Exception {
        meetService.update(request("name", "First", "version", 0));
        ConflictException conflict = assertThrows(ConflictException.class,
            () -> meetService.update(request("name", "Second", "version", 0)));
        assertEquals("First", ((Map<?, ?>) conflict.getCurrent()).get("name"));
        assertEquals("First", meetService.get().get("name"));
    }
}
