package com.competition.service;

import com.competition.model.Ranking;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Ranking an event's original and reproduction disciplines together. */
class CombinedRankingTest {

    // Miquelet exists as original (1) and reproduction (11); Colt only as original, Mariette only as reproduction
    private static final String CATALOG =
        "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Miquelet\"},"
            + "{\"id\":11,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Miquelet\"},"
            + "{\"id\":21,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"combined\",\"event\":\"Miquelet\"},"
            + "{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Colt\"},"
            + "{\"id\":57,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Mariette\"},"
            + "{\"id\":31,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"event\":\"Gustav Adolph\",\"based_on\":\"Miquelet\",\"team_size\":3}]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;
    private RankingService rankingService;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        // Anna shot an original, Ben a reproduction, Cara is registered in the reproduction without a result
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + "{\"id\":1,\"name\":\"Anna\",\"starts\":{\"1\":[{\"generated_id\":\"1-1-1\",\"start_number\":1}]}},"
            + "{\"id\":2,\"name\":\"Ben\",\"starts\":{\"11\":[{\"generated_id\":\"2-11-1\",\"start_number\":1}]}},"
            + "{\"id\":3,\"name\":\"Cara\",\"starts\":{\"11\":[{\"generated_id\":\"3-11-1\",\"start_number\":1}]}}"
            + "],\"results\":["
            + "{\"id\":1,\"start_id\":\"1-1-1\",\"discipline_id\":1,\"competitor_id\":1,\"entries\":[10,9]},"
            + "{\"id\":2,\"start_id\":\"2-11-1\",\"discipline_id\":11,\"competitor_id\":2,\"entries\":[10,10]}"
            + "]}");
        dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
        disciplineService.setActiveDisciplines(List.of(1, 11, 52), null);
        rankingService = new RankingService(dataService, disciplineService,
            new TeamService(tempDir.resolve("teams.json").toString(), dataService));
    }

    @Test
    void onlyEventsWithOneOriginalAndOneReproductionCanBeCombined() throws Exception {
        List<Map<String, Object>> events = disciplineService.getCombinableEvents();
        assertEquals(1, events.size(), "Colt, Mariette and team disciplines have no pair");
        assertEquals("rifle|Miquelet", events.get(0).get("key"));
        assertEquals(1, events.get(0).get("original_id"));
        assertEquals(11, events.get(0).get("reproduction_id"));
        assertEquals(false, events.get(0).get("combined"));
        assertThrows(IllegalArgumentException.class, () -> disciplineService.setCombined("pistol|Colt", true));
    }

    @Test
    void separateRankingsUntilCombined() throws Exception {
        Map<Integer, Object> all = rankingService.getAllBestResultRankings();
        assertEquals(List.of(1, 11), List.copyOf(all.keySet()));
        assertEquals(1, rowsOf(all.get(1)).size());
        assertEquals(2, rowsOf(all.get(11)).size());
        assertNull(rowsOf(all.get(1)).get(0).get("discipline_type"), "no O/R tag in a separate ranking");
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinedBestResultRankingMergesBothTypesAndTagsEachRow() throws Exception {
        List<Map<String, Object>> events = disciplineService.setCombined("rifle|Miquelet", true);
        assertEquals(true, events.get(0).get("combined"));

        Map<Integer, Object> all = rankingService.getAllBestResultRankings();
        assertEquals(List.of(1), List.copyOf(all.keySet()), "one entry, listed under the original");
        Map<String, Object> discipline = (Map<String, Object>) ((Map<String, Object>) all.get(1)).get("discipline");
        assertEquals("combined", discipline.get("type"));
        assertEquals(List.of(1, 11), discipline.get("combined_ids"));

        List<Map<String, Object>> rows = rowsOf(all.get(1));
        assertEquals(List.of("Ben", "Anna", "Cara"),
            rows.stream().map(r -> ((Map<String, Object>) r.get("competitor")).get("name")).toList());
        assertEquals(List.of("reproduction", "original", "reproduction"),
            rows.stream().map(r -> r.get("discipline_type")).toList());
        assertEquals(1, rows.get(0).get("rank"));
        assertNull(rows.get(2).get("rank"), "starter without result stays unranked");

        // Asking for either discipline gives the combined ranking
        assertEquals(rows.size(), rowsOf(rankingService.getBestResultRanking(11)).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinedDetailedRankingTagsEachCompetitor() throws Exception {
        disciplineService.setCombined("rifle|Miquelet", true);

        Map<Integer, Object> all = rankingService.getAllRankings();
        assertEquals(List.of(1), List.copyOf(all.keySet()));
        List<Ranking> rankings = (List<Ranking>) ((Map<String, Object>) all.get(1)).get("rankings");
        assertEquals(List.of("Ben", "Anna"), rankings.stream().map(r -> r.getCompetitor().getName()).toList());
        assertEquals(List.of("reproduction", "original"), rankings.stream().map(Ranking::getDisciplineType).toList());
    }

    @Test
    void combiningIsRefusedWhileSomeoneStartsInBothTypes() throws Exception {
        StartService startService = new StartService(dataService, disciplineService);
        startService.createStart(1, 11);

        ConflictException e = assertThrows(ConflictException.class,
            () -> disciplineService.setCombined("rifle|Miquelet", true));
        assertEquals(List.of("Anna"), e.getCurrent());
        assertEquals(false, disciplineService.getCombinableEvents().get(0).get("combined"));
    }

    @Test
    void whileCombinedAStartInTheOtherTypeIsRefused() throws Exception {
        disciplineService.setCombined("rifle|Miquelet", true);
        StartService startService = new StartService(dataService, disciplineService);

        assertThrows(ConflictException.class, () -> startService.createStart(1, 11));
        assertDoesNotThrow(() -> startService.createStart(1, 1), "another start in the same type is fine");

        disciplineService.setCombined("rifle|Miquelet", false);
        assertDoesNotThrow(() -> startService.createStart(1, 11));
    }

    @Test
    void combinedEventsArePersistedKeptOnDisciplineChangesAndClearedOnReset() throws Exception {
        disciplineService.setCombined("rifle|Miquelet", true);
        disciplineService.setActiveDisciplines(List.of(1, 11), null);
        disciplineService.updateCatalogDiscipline(52, Map.of("short_name", "C"));

        assertEquals(List.of("rifle|Miquelet"),
            List.of(new ObjectMapper().readTree(tempDir.resolve("competition.json").toFile())
                .path("combined_events").get(0).asText()));
        assertTrue(dataService.loadCombinedEvents().contains("rifle|Miquelet"));

        dataService.resetDisciplines();
        assertTrue(dataService.loadCombinedEvents().isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Object entry) {
        return (List<Map<String, Object>>) ((Map<String, Object>) entry).get("rankings");
    }
}
