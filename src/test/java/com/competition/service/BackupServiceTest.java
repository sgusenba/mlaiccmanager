package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class BackupServiceTest {

    @TempDir
    Path tempDir;

    private BackupService backupService;

    private static final String DATA = "{\"competitors\":[{\"id\":1,\"name\":\"Anna\",\"starts\":{}}],\"results\":[]}";
    private static final String COMPETITION = "{\"active_disciplines\":[1]}";
    private static final String TEAMS = "{\"teams\":[{\"id\":1,\"name\":\"SG Wien\"}]}";
    private static final String RELAYS = "{\"config\":{\"relay_duration_min\":10},\"days\":[]}";
    private static final String MEET = "{\"name\":\"Staatsmeisterschaft\",\"location\":\"Bad Zell\",\"version\":1}";

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), "[{\"id\":1,\"level\":\"individual\"}]");
        Files.writeString(tempDir.resolve("data.json"), DATA);
        Files.writeString(tempDir.resolve("competition.json"), COMPETITION);
        Files.writeString(tempDir.resolve("teams.json"), TEAMS);
        Files.writeString(tempDir.resolve("relays.json"), RELAYS);
        Files.writeString(tempDir.resolve("meet.json"), MEET);

        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        RelayService relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);
        MeetService meetService = new MeetService(tempDir.resolve("meet.json").toString());
        backupService = new BackupService(tempDir, dataService, teamService, relayService, meetService);
    }

    private static Map<String, String> unzip(byte[] zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private String read(String name) throws IOException {
        return Files.readString(tempDir.resolve(name));
    }

    @Test
    void backupContainsEveryDataFileButNotTheCatalog() throws Exception {
        Map<String, String> entries = unzip(backupService.createBackup());
        assertEquals(List.of("backup-info.json", "data.json", "competition.json", "teams.json", "relays.json",
                "meet.json"),
            List.copyOf(entries.keySet()));
        assertEquals(DATA, entries.get("data.json"));
        assertEquals(TEAMS, entries.get("teams.json"));
        assertTrue(entries.get("backup-info.json").contains("\"format\" : 1"));
    }

    @Test
    void restoreBringsBackTheBackedUpStateAndKeepsASafetyCopy() throws Exception {
        byte[] backup = backupService.createBackup();

        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":[],\"results\":[]}");
        Files.writeString(tempDir.resolve("teams.json"), "{\"teams\":[]}");
        Files.writeString(tempDir.resolve("meet.json"), "{\"name\":\"Other meet\"}");

        Map<String, Object> result = backupService.restore(new ByteArrayInputStream(backup));
        assertEquals(DATA, read("data.json"));
        assertEquals(COMPETITION, read("competition.json"));
        assertEquals(TEAMS, read("teams.json"));
        assertEquals(RELAYS, read("relays.json"));
        assertEquals(MEET, read("meet.json"));
        assertEquals(List.of("data.json", "competition.json", "teams.json", "relays.json", "meet.json"),
            result.get("restored_files"));

        // The safety copy holds the data as it was right before the restore
        String safetyCopy = (String) result.get("safety_copy");
        assertTrue(safetyCopy.startsWith("backups/pre-restore-"));
        Map<String, String> previous = unzip(Files.readAllBytes(tempDir.resolve(safetyCopy)));
        assertEquals("{\"teams\":[]}", previous.get("teams.json"));
    }

    @Test
    void filesMissingFromTheBackupAreRemoved() throws Exception {
        backupService.restore(new ByteArrayInputStream(zip(Map.of("data.json", DATA))));
        assertTrue(Files.exists(tempDir.resolve("data.json")));
        assertFalse(Files.exists(tempDir.resolve("teams.json")));
        assertFalse(Files.exists(tempDir.resolve("relays.json")));
        assertFalse(Files.exists(tempDir.resolve("competition.json")));
        assertFalse(Files.exists(tempDir.resolve("meet.json")));
        // The catalog is never touched
        assertTrue(Files.exists(tempDir.resolve("disciplines.json")));
    }

    @Test
    void filesInsideAFolderAreFoundByName() throws Exception {
        String other = "{\"competitors\":[],\"results\":[]}";
        backupService.restore(new ByteArrayInputStream(zip(Map.of("my-backup/data.json", other, "notes.txt", "hello"))));
        assertEquals(other, read("data.json"));
    }

    @Test
    void invalidBackupsChangeNothing() throws Exception {
        IllegalArgumentException notAZip = assertThrows(IllegalArgumentException.class,
            () -> backupService.restore(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8))));
        assertEquals("The file is not a zip file", notAZip.getMessage());
        IllegalArgumentException noData = assertThrows(IllegalArgumentException.class,
            () -> backupService.restore(new ByteArrayInputStream(zip(Map.of("teams.json", TEAMS)))));
        assertEquals("Not a backup: data.json is missing", noData.getMessage());
        assertThrows(IllegalArgumentException.class,
            () -> backupService.restore(new ByteArrayInputStream(zip(Map.of("data.json", "{broken")))));
        assertThrows(IllegalArgumentException.class,
            () -> backupService.restore(new ByteArrayInputStream(zip(Map.of("data.json", DATA, "teams.json", "[]")))));

        assertEquals(DATA, read("data.json"));
        assertEquals(TEAMS, read("teams.json"));
        assertFalse(Files.exists(tempDir.resolve("backups")));
    }
}
