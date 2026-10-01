package com.competition.backup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupStoreTest {

    @TempDir
    Path tempDir;

    private BackupStore store;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0, 0);

    @BeforeEach
    void setUp() throws Exception {
        store = new BackupStore(tempDir.resolve("received"));
    }

    private static String name(LocalDateTime time) {
        return "mlaiccmanager-backup-"
            + time.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
    }

    private void put(LocalDateTime time) throws Exception {
        store.store(name(time), new byte[] {1, 2, 3});
    }

    private List<String> names() throws Exception {
        return store.list().stream().map(BackupStore.Entry::name).toList();
    }

    @Test
    void acceptsOnlyTheAppsFileNames() {
        assertTrue(BackupStore.isValidName("mlaiccmanager-backup-20261001-120000.zip"));
        assertFalse(BackupStore.isValidName("../mlaiccmanager-backup-20261001-120000.zip"));
        assertFalse(BackupStore.isValidName("mlaiccmanager-backup-20261001-120000.zip.tmp"));
        assertFalse(BackupStore.isValidName("mlaiccmanager-backup-20261301-120000.zip"));
        assertFalse(BackupStore.isValidName("evil.zip"));
        assertFalse(BackupStore.isValidName(null));
        assertThrows(IllegalArgumentException.class, () -> store.store("evil.zip", new byte[0]));
    }

    @Test
    void storesReadsAndListsNewestFirst() throws Exception {
        put(NOW.minusHours(2));
        put(NOW);
        assertEquals(List.of(name(NOW), name(NOW.minusHours(2))), names());
        assertArrayEquals(new byte[] {1, 2, 3}, store.read(name(NOW)).orElseThrow());
        assertTrue(store.read(name(NOW.minusDays(9))).isEmpty());
        assertTrue(store.read("../x").isEmpty());
    }

    @Test
    void storingTheSameNameTwiceReplacesIt() throws Exception {
        store.store(name(NOW), new byte[] {1});
        store.store(name(NOW), new byte[] {2, 2});
        assertEquals(1, names().size());
        assertArrayEquals(new byte[] {2, 2}, store.read(name(NOW)).orElseThrow());
        try (var files = Files.list(tempDir.resolve("received"))) {
            assertEquals(1, files.count(), "no temp file is left behind");
        }
    }

    @Test
    void keepsEverythingOfTheLastDay() throws Exception {
        for (int minutes = 0; minutes < 6 * 60; minutes += 10) {
            put(NOW.minusMinutes(minutes));
        }
        int before = names().size();
        store.prune(NOW);
        assertEquals(before, names().size());
    }

    @Test
    void keepsTheNewestOfEachDayUpToThirtyDays() throws Exception {
        LocalDateTime day = NOW.minusDays(3);
        put(day.withHour(8));
        put(day.withHour(15));
        put(day.withHour(22));
        store.prune(NOW);
        assertEquals(List.of(name(day.withHour(22))), names());
    }

    @Test
    void keepsTheNewestOfEachMonthBeyondThirtyDays() throws Exception {
        LocalDateTime august = LocalDateTime.of(2026, 8, 3, 10, 0);
        put(august);
        put(august.plusDays(10));
        put(august.plusDays(20));
        LocalDateTime july = LocalDateTime.of(2026, 7, 4, 10, 0);
        put(july);
        put(july.plusDays(1));
        store.prune(NOW);
        assertEquals(List.of(name(august.plusDays(20)), name(july.plusDays(1))), names());
    }

    @Test
    void theNewestBackupIsNeverPruned() throws Exception {
        put(NOW.minusDays(1).minusHours(1));
        put(NOW.minusDays(40));
        store.prune(NOW);
        assertEquals(2, names().size());
        assertEquals(name(NOW.minusDays(1).minusHours(1)), names().get(0));
    }
}
