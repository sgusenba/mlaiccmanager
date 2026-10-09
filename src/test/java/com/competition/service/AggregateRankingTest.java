package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Aggregate disciplines (Remington): the results of other disciplines added up, without starts of their own. */
class AggregateRankingTest {

    private static final String CATALOG =
        "[{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Colt\"},"
            + "{\"id\":53,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Donald Malson\"},"
            + "{\"id\":57,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Mariette\"},"
            + "{\"id\":58,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Donald Malson\"},"
            + "{\"id\":74,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Remington\","
            + "\"aggregate_of\":[\"Colt\",\"Donald Malson\"]},"
            + "{\"id\":75,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Remington\","
            + "\"aggregate_of\":[\"Mariette\",\"Donald Malson\"]}]";

    @TempDir
    Path tempDir;

    private DisciplineService disciplineService;
    private RankingService rankingService;
    private StartService startService;

    @BeforeEach
    void setUp() throws Exception {
        // Anna and Ben shot Colt and Donald Malson (original), Anna twice in Colt; Cara has no Donald Malson
        // result yet; Dan shot only Colt; Eva shot the reproductions
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + "{\"id\":1,\"name\":\"Anna\",\"starts\":{\"52\":[{\"generated_id\":\"1-52-1\"},{\"generated_id\":\"1-52-2\"}],"
            + "\"53\":[{\"generated_id\":\"1-53-1\"}]}},"
            + "{\"id\":2,\"name\":\"Ben\",\"starts\":{\"52\":[{\"generated_id\":\"2-52-1\"}],\"53\":[{\"generated_id\":\"2-53-1\"}]}},"
            + "{\"id\":3,\"name\":\"Cara\",\"starts\":{\"52\":[{\"generated_id\":\"3-52-1\"}],\"53\":[{\"generated_id\":\"3-53-1\"}]}},"
            + "{\"id\":4,\"name\":\"Dan\",\"starts\":{\"52\":[{\"generated_id\":\"4-52-1\"}]}},"
            + "{\"id\":5,\"name\":\"Eva\",\"starts\":{\"57\":[{\"generated_id\":\"5-57-1\"}],\"58\":[{\"generated_id\":\"5-58-1\"}]}}"
            + "],\"results\":["
            + "{\"id\":1,\"start_id\":\"1-52-1\",\"discipline_id\":52,\"competitor_id\":1,\"entries\":[8,8]},"
            + "{\"id\":2,\"start_id\":\"1-52-2\",\"discipline_id\":52,\"competitor_id\":1,\"entries\":[10,9]},"
            + "{\"id\":3,\"start_id\":\"1-53-1\",\"discipline_id\":53,\"competitor_id\":1,\"entries\":[10,10]},"
            + "{\"id\":4,\"start_id\":\"2-52-1\",\"discipline_id\":52,\"competitor_id\":2,\"entries\":[10,10]},"
            + "{\"id\":5,\"start_id\":\"2-53-1\",\"discipline_id\":53,\"competitor_id\":2,\"entries\":[10,9]},"
            + "{\"id\":6,\"start_id\":\"3-52-1\",\"discipline_id\":52,\"competitor_id\":3,\"entries\":[10,10]},"
            + "{\"id\":7,\"start_id\":\"4-52-1\",\"discipline_id\":52,\"competitor_id\":4,\"entries\":[10,10]},"
            + "{\"id\":8,\"start_id\":\"5-57-1\",\"discipline_id\":57,\"competitor_id\":5,\"entries\":[7]},"
            + "{\"id\":9,\"start_id\":\"5-58-1\",\"discipline_id\":58,\"competitor_id\":5,\"entries\":[6]}"
            + "]}");
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
        disciplineService.setActiveDisciplines(List.of(52, 53, 57, 58, 74, 75), null);
        rankingService = new RankingService(dataService, disciplineService,
            new TeamService(tempDir.resolve("teams.json").toString(), dataService));
        startService = new StartService(dataService, disciplineService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsUpTheBestResultOfEachComponent() throws Exception {
        Map<String, Object> entry = rankingService.getBestResultRanking(74);
        List<Map<String, Object>> components = (List<Map<String, Object>>) ((Map<String, Object>) entry.get("discipline")).get("components");
        assertEquals(List.of(52, 53), components.stream().map(c -> c.get("id")).toList());

        List<Map<String, Object>> rows = (List<Map<String, Object>>) entry.get("rankings");
        assertEquals(List.of("Anna", "Ben", "Cara"), rows.stream().map(AggregateRankingTest::nameOf).toList(),
            "Dan has no Donald Malson start, Eva shot the reproductions");

        // Anna 19 + 20 and Ben 20 + 19 tie on 39 with three 10s; the 9s don't break it either
        assertEquals(39.0, rows.get(0).get("score"));
        assertEquals(1, rows.get(0).get("rank"));
        assertEquals(1, rows.get(1).get("rank"));
        assertEquals(Arrays.asList(19.0, 20.0), rows.get(0).get("component_scores"));
        assertEquals("1-52-2 + 1-53-1", rows.get(0).get("start_id"), "the start ids of the results added up");
        assertEquals(3, ((Map<String, Integer>) rows.get(0).get("freq_counts")).get("10"));

        assertEquals(false, rows.get(2).get("has_result"), "Cara's Donald Malson result is missing");
        assertNull(rows.get(2).get("rank"));
        assertEquals(Arrays.asList(20.0, null), rows.get(2).get("component_scores"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reproductionAddsUpTheReproductionDisciplines() throws Exception {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rankingService.getBestResultRanking(75).get("rankings");
        assertEquals(1, rows.size());
        assertEquals("Eva", nameOf(rows.get(0)));
        assertEquals(13.0, rows.get(0).get("score"));
    }

    @Test
    void listedWithTheOtherRankings() throws Exception {
        assertTrue(rankingService.getAllBestResultRankings().containsKey(74));
        assertTrue(rankingService.getAllRankings().containsKey(75));
    }

    @Test
    void noStartsInAnAggregateDiscipline() {
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 74));
    }

    @Test
    void notCombinable() throws Exception {
        assertTrue(disciplineService.getCombinableEvents().stream().noneMatch(e -> "Remington".equals(e.get("event"))));
    }

    private static String nameOf(Map<String, Object> row) {
        return (String) ((Map<?, ?>) row.get("competitor")).get("name");
    }
}
