package com.competition.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DangerZoneServiceTest {

    @TempDir
    Path tempDir;

    private DangerZoneService dangerZoneService;
    private final ObjectMapper mapper = new ObjectMapper();

    // Discipline 1 is in the catalog, 1000 was added for this competition
    private static final String CATALOG = "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"event\":\"Miquelet\"},"
        + "{\"id\":31,\"category\":\"rifle\",\"level\":\"team\",\"event\":\"Gustav Adolph\",\"based_on\":\"Miquelet\"}]";
    private static final String COMPETITION = "{\"active_disciplines\":[1,31,1000],\"discipline_overrides\":{},"
        + "\"custom_disciplines\":[{\"id\":1000,\"category\":\"rifle\",\"level\":\"individual\",\"event\":\"Extra\"},"
        + "{\"id\":1001,\"category\":\"rifle\",\"level\":\"team\",\"event\":\"Extra Team\",\"based_on\":\"Extra\"}],"
        + "\"removed_disciplines\":[]}";
    private static final String DATA = "{\"competitors\":["
        + "{\"id\":1,\"name\":\"Anna\",\"starts\":{\"1\":[{\"generated_id\":\"1-1-1\",\"start_number\":1}],"
        + "\"1000\":[{\"generated_id\":\"1-1000-1\",\"start_number\":1}]}},"
        + "{\"id\":2,\"name\":\"Bert\",\"starts\":{\"1\":[{\"generated_id\":\"2-1-1\",\"start_number\":1}]}}],"
        + "\"results\":[{\"id\":1,\"start_id\":\"1-1-1\",\"value\":90},{\"id\":2,\"start_id\":\"1-1000-1\",\"value\":80}]}";
    private static final String TEAMS = "{\"teams\":["
        + "{\"id\":1,\"discipline_id\":31,\"name\":\"SG\",\"members\":[\"1-1-1\",\"2-1-1\"]},"
        + "{\"id\":2,\"discipline_id\":1001,\"name\":\"Extra\",\"members\":[\"1-1000-1\"]}]}";
    private static final String RELAYS = "{\"days\":[{\"id\":\"d1\",\"date\":\"2026-10-01\",\"start_time\":\"09:00\",\"locked\":true}],"
        + "\"relays\":[{\"id\":\"r1\",\"day_id\":\"d1\",\"number\":1}],"
        + "\"assignments\":[{\"id\":\"a1\",\"relay_id\":\"r1\",\"range_id\":\"m25\",\"lane_no\":1,\"start_id\":\"1-1-1\"},"
        + "{\"id\":\"a2\",\"relay_id\":\"r1\",\"range_id\":\"m25\",\"lane_no\":2,\"start_id\":\"1-1000-1\"}]}";
    private static final String MEET = "{\"name\":\"Staatsmeisterschaft\",\"location\":\"Bad Zell\",\"version\":1}";

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        Files.writeString(tempDir.resolve("competition.json"), COMPETITION);
        Files.writeString(tempDir.resolve("data.json"), DATA);
        Files.writeString(tempDir.resolve("teams.json"), TEAMS);
        Files.writeString(tempDir.resolve("relays.json"), RELAYS);
        Files.writeString(tempDir.resolve("meet.json"), MEET);

        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        RelayService relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);
        MeetService meetService = new MeetService(tempDir.resolve("meet.json").toString());
        BackupService backupService = new BackupService(tempDir, dataService, teamService, relayService, meetService);
        dangerZoneService = new DangerZoneService(dataService, teamService, relayService, meetService, backupService);
    }

    private JsonNode read(String name) throws Exception {
        return mapper.readTree(tempDir.resolve(name).toFile());
    }

    private static List<String> startIds(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("competitors").forEach(c -> c.path("starts").forEach(list -> list.forEach(
            start -> ids.add(start.path("generated_id").asText()))));
        return ids;
    }

    private static List<String> values(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.path(field).asText()));
        return values;
    }

    private static List<String> textsOf(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> cleared(Map<String, Object> result) {
        return (Map<String, Integer>) result.get("cleared");
    }

    @Test
    void summaryCountsEachKindOfData() throws Exception {
        Map<String, Object> summary = dangerZoneService.summary();
        assertEquals(2, summary.get("results"));
        assertEquals(3, summary.get("starts"));
        assertEquals(2, summary.get("competitors"));
        assertEquals(2, summary.get("lanes"));
        assertEquals(1, summary.get("days"));
        assertEquals(1, summary.get("relays"));
        assertEquals(2, summary.get("teams"));
        assertEquals(1, summary.get("meet"));
        assertEquals(3L, summary.get("active_disciplines"));
        assertEquals(2L, summary.get("added_disciplines"));
    }

    @Test
    void clearResultsKeepsEverythingElse() throws Exception {
        Map<String, Object> result = dangerZoneService.clear("results");

        assertEquals(Map.of("results", 2), cleared(result));
        assertEquals(0, read("data.json").path("results").size());
        assertEquals(3, startIds(read("data.json")).size());
        assertEquals(2, read("relays.json").path("assignments").size());
    }

    @Test
    void everyClearSavesASafetyCopyFirst() throws Exception {
        Map<String, Object> first = dangerZoneService.clear("results");
        Map<String, Object> second = dangerZoneService.clear("results");

        String copy = (String) first.get("safety_copy");
        assertTrue(copy.startsWith("backups/pre-clear-results-"), copy);
        assertNotEquals(copy, second.get("safety_copy"), "a copy taken in the same second must not be overwritten");
        assertTrue(Files.exists(tempDir.resolve(copy)));
        assertTrue(Files.exists(tempDir.resolve((String) second.get("safety_copy"))));
    }

    @Test
    void clearLanesIgnoresDayLocksAndKeepsTheDays() throws Exception {
        dangerZoneService.clear("lanes");

        JsonNode relays = read("relays.json");
        assertEquals(0, relays.path("assignments").size());
        assertEquals(1, relays.path("days").size());
        assertEquals(1, relays.path("relays").size());
    }

    @Test
    void clearStartsAlsoClearsResultsLanesAndTeamMembers() throws Exception {
        Map<String, Object> result = dangerZoneService.clear("starts");

        assertEquals(3, cleared(result).get("starts"));
        assertEquals(2, cleared(result).get("results"));
        assertEquals(2, cleared(result).get("lane assignments"));
        assertEquals(3, cleared(result).get("team members"));

        JsonNode data = read("data.json");
        assertEquals(2, data.path("competitors").size());
        assertTrue(startIds(data).isEmpty());
        assertEquals(0, data.path("results").size());
        assertEquals(0, read("relays.json").path("assignments").size());
        JsonNode teams = read("teams.json").path("teams");
        assertEquals(2, teams.size(), "the teams themselves stay");
        teams.forEach(team -> assertEquals(0, team.path("members").size()));
    }

    @Test
    void clearCompetitorsClearsEverythingThatPointsToThem() throws Exception {
        Map<String, Object> result = dangerZoneService.clear("competitors");

        assertEquals(2, cleared(result).get("competitors"));
        assertEquals(3, cleared(result).get("starts"));
        JsonNode data = read("data.json");
        assertEquals(0, data.path("competitors").size());
        assertEquals(0, data.path("results").size());
        assertEquals(0, read("relays.json").path("assignments").size());
        read("teams.json").path("teams").forEach(team -> assertEquals(0, team.path("members").size()));
    }

    @Test
    void clearTeamsLeavesStartsAlone() throws Exception {
        dangerZoneService.clear("teams");

        assertEquals(0, read("teams.json").path("teams").size());
        assertEquals(3, startIds(read("data.json")).size());
    }

    @Test
    void clearDaysRemovesRelaysAndLanesButKeepsRangesAndTimes() throws Exception {
        Map<String, Object> result = dangerZoneService.clear("days");

        assertEquals(Map.of("meet days", 1, "relays", 1, "lane assignments", 2), cleared(result));
        JsonNode relays = read("relays.json");
        assertEquals(0, relays.path("days").size());
        assertEquals(0, relays.path("relays").size());
        assertEquals(0, relays.path("assignments").size());
        assertEquals(3, relays.path("ranges").size());
        assertTrue(relays.path("config").has("relay_duration_min"));
    }

    @Test
    void clearMeetRemovesTheMeetDetails() throws Exception {
        assertEquals(Map.of("meet details", 1), cleared(dangerZoneService.clear("meet")));
        assertFalse(Files.exists(tempDir.resolve("meet.json")));
        assertEquals(Map.of("meet details", 0), cleared(dangerZoneService.clear("meet")));
    }

    @Test
    void resetDisciplinesDropsAddedDisciplinesWithTheirStartsAndTeams() throws Exception {
        Map<String, Object> result = dangerZoneService.clear("disciplines");

        assertEquals(1, cleared(result).get("starts"));
        assertEquals(1, cleared(result).get("results"));
        assertEquals(1, cleared(result).get("lane assignments"));
        assertEquals(1, cleared(result).get("teams"));

        JsonNode competition = read("competition.json");
        assertEquals(List.of("1", "31"), textsOf(competition.path("active_disciplines")),
            "like a fresh install: the whole catalog is active");
        assertEquals(0, competition.path("custom_disciplines").size());
        JsonNode data = read("data.json");
        assertEquals(List.of("1-1-1", "2-1-1"), startIds(data).stream().sorted().toList());
        assertEquals(List.of("1-1-1"), values(data.path("results"), "start_id"));
        assertEquals(List.of("1-1-1"), values(read("relays.json").path("assignments"), "start_id"));
        assertEquals(List.of("31"), values(read("teams.json").path("teams"), "discipline_id"));
    }

    @Test
    void unknownTargetIsRejectedAndChangesNothing() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> dangerZoneService.clear("everything"));
        assertFalse(Files.exists(tempDir.resolve("backups")));
        assertEquals(2, read("data.json").path("results").size());
    }
}
