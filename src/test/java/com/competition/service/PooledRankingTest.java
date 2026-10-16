package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The rankings of Disziplinen.pdf: Whitworth_O/R (two disciplines pooled),
 * Whitworth_R (one), Kuchenreuter (O + RS + RU pooled) and Remington_O/R
 * (two sums pooled: Colt + Donald Malson_O, Mariette + Donald Malson_R).
 */
class PooledRankingTest {

    private static final String CATALOG = "["
        + individual(4, "rifle", "original", "4_Whitworth_O", "WHITO") + ","
        + individual(14, "rifle", "reproduction", "4_Whitworth_R", "WHITR") + ","
        + individual(51, "pistol", "original", "6_Kuchenreuter_O", "KUCO") + ","
        + individual(62, "pistol", "reproduction", "6_Kuchenreuter_RS", "KUCRS") + ","
        + individual(56, "pistol", "reproduction", "6_Kuchenreuter_RU", "KUCRU") + ","
        + individual(52, "pistol", "original", "7_Colt", "COLT") + ","
        + individual(53, "pistol", "original", "23_Donald Malson O", "MALO") + ","
        + individual(57, "pistol", "reproduction", "12_Mariette", "MAR") + ","
        + individual(58, "pistol", "reproduction", "23_Donald Malson R", "MALR")
        + "]";

    private static final String RANKINGS = "{\"max_disciplines_per_ranking\":4,\"rankings\":["
        + "{\"id\":1,\"name\":\"Whitworth O/R\",\"entries\":[{\"discipline_ids\":[4]},{\"discipline_ids\":[14]}]},"
        + "{\"id\":2,\"name\":\"Whitworth R\",\"entries\":[{\"discipline_ids\":[14]}]},"
        + "{\"id\":3,\"name\":\"Kuchenreuter\",\"entries\":[{\"discipline_ids\":[51]},{\"discipline_ids\":[62]},"
        + "{\"discipline_ids\":[56]}]},"
        + "{\"id\":4,\"name\":\"Remington O/R\",\"entries\":[{\"label\":\"Remington_O\",\"discipline_ids\":[52,53]},"
        + "{\"label\":\"Remington_R\",\"discipline_ids\":[57,58]}]}]}";

    @TempDir
    Path tempDir;

    private RankingService rankingService;

    private static String individual(int id, String category, String type, String name, String shortName) {
        return "{\"id\":" + id + ",\"category\":\"" + category + "\",\"level\":\"individual\",\"type\":\"" + type
            + "\",\"name\":\"" + name + "\",\"short_name\":\"" + shortName + "\"}";
    }

    private static String competitor(int id, String name, String... startIds) {
        Map<String, List<String>> byDiscipline = new java.util.LinkedHashMap<>();
        for (String startId : startIds) {
            String[] parts = startId.split("-");
            byDiscipline.computeIfAbsent(parts[1], k -> new java.util.ArrayList<>())
                .add("{\"generated_id\":\"" + startId + "\",\"start_number\":" + parts[2] + "}");
        }
        List<String> starts = byDiscipline.entrySet().stream()
            .map(e -> "\"" + e.getKey() + "\":[" + String.join(",", e.getValue()) + "]").toList();
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"starts\":{" + String.join(",", starts) + "}}";
    }

    private static String result(int id, String startId, String entries) {
        return "{\"id\":" + id + ",\"start_id\":\"" + startId + "\",\"entries\":" + entries + "}";
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("catalog.json"), TestFiles.catalog(CATALOG));
        Files.writeString(tempDir.resolve("rankings.json"), RANKINGS);
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + competitor(1, "Anna", "1-4-1", "1-14-1") + ","
            + competitor(2, "Ben", "2-14-1") + ","
            + competitor(3, "Cara", "3-4-1") + ","
            + competitor(4, "Dan", "4-14-1") + ","
            + competitor(5, "Eva", "5-52-1", "5-53-1") + ","
            + competitor(6, "Fred", "6-52-1", "6-57-1", "6-58-1") + ","
            + competitor(7, "Gus", "7-62-1") + ","
            + competitor(8, "Hal", "8-56-1", "8-51-1")
            + "],\"results\":["
            + result(1, "1-4-1", "[10,10,9]") + ","      // Anna WHITO 29
            + result(2, "1-14-1", "[10,9,9]") + ","      // Anna WHITR 28
            + result(3, "2-14-1", "[10,10,10]") + ","    // Ben WHITR 30
            + result(4, "3-4-1", "[10,10,9]") + ","      // Cara WHITO 29
            + result(5, "5-52-1", "[10,10]") + ","       // Eva Colt 20
            + result(6, "5-53-1", "[9,9]") + ","         // Eva Malson O 18
            + result(7, "6-52-1", "[10]") + ","          // Fred Colt 10
            + result(8, "6-57-1", "[10,10,10]") + ","    // Fred Mariette 30
            + result(9, "6-58-1", "[5]") + ","           // Fred Malson R 5
            + result(10, "7-62-1", "[10,10]") + ","      // Gus KUCRS 20
            + result(11, "8-56-1", "[10,10,10]") + ","   // Hal KUCRU 30
            + result(12, "8-51-1", "[1]")                // Hal KUCO 1
            + "]}");
        DataService dataService = TestFiles.dataService(tempDir);
        rankingService = new RankingService(dataService,
            new TeamService(tempDir.resolve("teams.json").toString(), dataService));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(int rankingId) throws Exception {
        return (List<Map<String, Object>>) rankingService.getRankingResult(rankingId).get("rankings");
    }

    private static List<Object> field(List<Map<String, Object>> rows, String name) {
        return rows.stream().map(r -> r.get(name)).toList();
    }

    private static List<Object> names(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (Object) ((Map<?, ?>) r.get("competitor")).get("name")).toList();
    }

    @Test
    void whitworthOrRanksOriginalAndReproductionTogetherBestResultFirstThenTheNext() throws Exception {
        List<Map<String, Object>> rows = rows(1);
        // Anna and Cara both have 29 at best; Anna's next result (28) puts her ahead
        assertEquals(List.of("Ben", "Anna", "Cara", "Dan"), names(rows));
        assertEquals(java.util.Arrays.asList(1, 2, 3, null), field(rows, "rank"));
        assertEquals(java.util.Arrays.asList(30.0, 29.0, 29.0, null), field(rows, "score"));
        assertEquals(List.of("WHITR", "WHITO", "WHITO", "WHITR"), field(rows, "discipline_tag"));
        assertEquals(false, rows.get(3).get("has_result"), "Dan started but has no result yet");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> annasOthers = (List<Map<String, Object>>) rows.get(1).get("components");
        assertEquals(1, annasOthers.size());
        assertEquals(28.0, annasOthers.get(0).get("score"));
        assertEquals("WHITR", annasOthers.get(0).get("label"));
    }

    @Test
    void whitworthRRanksTheReproductionOnly() throws Exception {
        List<Map<String, Object>> rows = rows(2);
        assertEquals(List.of("Ben", "Anna", "Dan"), names(rows));
        assertEquals(java.util.Arrays.asList(30.0, 28.0, null), field(rows, "score"));
        assertFalse(rows.get(0).containsKey("discipline_tag"), "one discipline: no column for it");
    }

    @Test
    void kuchenreuterRanksItsThreeDisciplinesTogether() throws Exception {
        List<Map<String, Object>> rows = rows(3);
        assertEquals(List.of("Hal", "Gus"), names(rows));
        assertEquals(List.of("KUCRU", "KUCRS"), field(rows, "discipline_tag"));
    }

    @Test
    void remingtonOrPoolsTwoSums() throws Exception {
        List<Map<String, Object>> rows = rows(4);
        assertEquals(List.of("Eva", "Fred"), names(rows));
        assertEquals(List.of(38.0, 35.0), field(rows, "score"));
        assertEquals(List.of("Remington_O", "Remington_R"), field(rows, "discipline_tag"));
        // The rings of both results added up
        assertEquals(2, ((Map<?, ?>) rows.get(0).get("freq_counts")).get("9"));

        // Below Fred: the two disciplines of his sum, then his other sum (Colt alone so far)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> components = (List<Map<String, Object>>) rows.get(1).get("components");
        assertEquals(List.of("12_Mariette", "23_Donald Malson R", "Remington_O"), field(components, "label"));
        assertEquals(List.of(30.0, 5.0, 10.0), field(components, "score"));
    }

    @Test
    void allResultsListEveryRankingWithEntriesInTheMeetsOrder() throws Exception {
        List<Map<String, Object>> all = rankingService.getAllRankingResults();
        assertEquals(List.of("Whitworth O/R", "Whitworth R", "Kuchenreuter", "Remington O/R"),
            all.stream().map(r -> ((Map<?, ?>) r.get("ranking")).get("name")).toList());
        Map<?, ?> remington = (Map<?, ?>) all.get(3).get("ranking");
        assertEquals(true, remington.get("pooled"));
        assertEquals("pistol", remington.get("category"));
        assertNull(rankingService.getRankingResult(99));
    }
}
