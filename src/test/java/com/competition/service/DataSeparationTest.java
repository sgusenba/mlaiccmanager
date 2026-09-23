package com.competition.service;

import com.competition.config.ApplicationBinder;
import com.competition.model.Discipline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The catalog/competition split of the discipline files and the slimmed-down data.json. */
class DataSeparationTest {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String CATALOG =
        "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"A\",\"shooting_distance\":\"m50\"},"
            + "{\"id\":2,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"B\",\"shooting_distance\":\"m100\"},"
            + "{\"id\":3,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"C\",\"shooting_distance\":\"m25\"}]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        dataService = newDataService();
        disciplineService = new DisciplineService(dataService);
    }

    private DataService newDataService() {
        return new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString(),
            tempDir.resolve("competition.json").toString());
    }

    private JsonNode competitionJson() throws Exception {
        return mapper.readTree(tempDir.resolve("competition.json").toFile());
    }

    private static Discipline byId(List<Discipline> disciplines, int id) {
        return disciplines.stream().filter(d -> d.getId() == id).findFirst().orElse(null);
    }

    @Test
    void competitionChangesAreStoredAsDifferencesAndNeverTouchTheCatalog() throws Exception {
        disciplineService.setActiveDisciplines(List.of(1, 2), null);
        disciplineService.updateCatalogDiscipline(2, Map.of("event", "B renamed"));
        disciplineService.updateShootingDistances(Map.of("1", ""));
        Discipline added = disciplineService.createCatalogDiscipline(Map.of("category", "rifle", "event", "Custom"));
        disciplineService.deleteCatalogDiscipline(3);

        assertEquals(CATALOG, Files.readString(tempDir.resolve("disciplines.json")));

        JsonNode settings = competitionJson();
        assertEquals("[1,2," + added.getId() + "]", settings.get("active_disciplines").toString());
        assertEquals("{\"shooting_distance\":null}", settings.get("discipline_overrides").get("1").toString());
        assertEquals("{\"event\":\"B renamed\"}", settings.get("discipline_overrides").get("2").toString());
        assertEquals("[3]", settings.get("removed_disciplines").toString());
        assertEquals(1000, added.getId());
        assertEquals("Custom", settings.get("custom_disciplines").get(0).get("event").asText());

        // A fresh service (e.g. after a restart) sees the same merged list
        List<Discipline> reloaded = newDataService().loadDisciplines();
        assertEquals(List.of(1, 2, 1000), reloaded.stream().map(Discipline::getId).toList());
        assertNull(byId(reloaded, 1).getShootingDistance());
        assertEquals("B renamed", byId(reloaded, 2).getEvent());
        assertEquals("m100", byId(reloaded, 2).getShootingDistance());
    }

    @Test
    void aNewCatalogReleaseKeepsTheCompetitionSettings() throws Exception {
        disciplineService.setActiveDisciplines(List.of(2), null);
        disciplineService.updateShootingDistances(Map.of("2", "m50"));

        // Deploy replaces the catalog: discipline 2 renamed upstream, discipline 4 added
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG.replace("\"B\"", "\"B v2\"")
            .replace("]", ",{\"id\":4,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"D\"}]"));

        List<Discipline> disciplines = dataService.loadDisciplines();
        assertEquals(List.of(2), disciplineService.getActiveDisciplines());
        assertEquals("B v2", byId(disciplines, 2).getEvent());
        assertEquals("m50", byId(disciplines, 2).getShootingDistance());
        assertNotNull(byId(disciplines, 4));
    }

    @Test
    void migrationTakesTheActiveListFromOldDataJson() throws Exception {
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[],\"results\":[],\"disciplines\":[],\"teams\":[],\"active_disciplines\":[3]}");

        ApplicationBinder.migrateCompetitionSettings(tempDir.toString(), dataService);

        assertEquals(List.of(3), disciplineService.getActiveDisciplines());
        assertTrue(dataService.hasCompetitionSettings());
    }

    @Test
    void migrationKeepsEditsFromThePreviousRuntimeCatalog() throws Exception {
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[],\"results\":[],\"active_disciplines\":[1,2,3]}");
        // What the deploy script saved aside: discipline 1 inactive and moved, discipline 3 deleted
        Files.writeString(tempDir.resolve("disciplines.previous.json"),
            "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"A\",\"shooting_distance\":\"m100\",\"active\":false},"
                + "{\"id\":2,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"B\",\"shooting_distance\":\"m100\",\"active\":true}]");

        ApplicationBinder.migrateCompetitionSettings(tempDir.toString(), dataService);

        List<Discipline> disciplines = dataService.loadDisciplines();
        assertEquals(List.of(2), disciplineService.getActiveDisciplines(), "flags of the previous file win over data.json");
        assertEquals("m100", byId(disciplines, 1).getShootingDistance());
        assertNull(byId(disciplines, 3));
        assertFalse(Files.exists(tempDir.resolve("disciplines.previous.json")));
        assertTrue(Files.exists(tempDir.resolve("disciplines.previous.json.migrated")));
    }

    @Test
    void derivedIdsAndObsoleteKeysAreNotStoredButStillServed() throws Exception {
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[{\"id\":1,\"name\":\"Anna\",\"disciplines\":[],\"team_id\":null,\"relay_number\":null,"
                + "\"starts\":{\"2\":[{\"generated_id\":\"1-2-1\",\"start_number\":1,\"discipline_id\":2,\"status\":\"registered\"}]}}],"
                + "\"results\":[{\"id\":1,\"competitor_id\":1,\"discipline_id\":2,\"start_id\":\"1-2-1\",\"value\":42,\"entries\":[10,10,10,10,2]},"
                + "{\"id\":2,\"competitor_id\":9,\"discipline_id\":3,\"start_id\":\"9-3-1\",\"value\":7,\"entries\":[7]}],"
                + "\"disciplines\":[],\"teams\":[],\"active_disciplines\":[2]}");

        dataService.update(data -> null);

        JsonNode stored = mapper.readTree(tempDir.resolve("data.json").toFile());
        assertFalse(stored.has("active_disciplines"));
        assertFalse(stored.has("teams"));
        assertFalse(stored.has("disciplines"));
        JsonNode anna = stored.get("competitors").get(0);
        assertFalse(anna.has("team_id") || anna.has("relay_number") || anna.has("disciplines"));
        assertFalse(anna.get("starts").get("2").get(0).has("discipline_id"));
        JsonNode result = stored.get("results").get(0);
        assertFalse(result.has("competitor_id") || result.has("discipline_id"));
        // The orphaned result keeps its ids, nothing could restore them
        assertEquals(9, stored.get("results").get(1).get("competitor_id").asInt());

        ResultService resultService = new ResultService(dataService, disciplineService);
        Map<String, Object> served = resultService.getResults(2).get(0);
        assertEquals(1, ((Number) served.get("competitor_id")).intValue());
        assertEquals(2, ((Number) served.get("discipline_id")).intValue());

        Map<String, Object> ranking = new RankingService(dataService, disciplineService,
            new TeamService(tempDir.resolve("teams.json").toString(), dataService)).getRanking(2);
        assertEquals(1, ((List<?>) ranking.get("rankings")).size());

        StartService startService = new StartService(dataService);
        assertEquals("1-2-2", startService.createStart(1, 2).getGeneratedId());
    }
}
