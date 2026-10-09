package com.competition.service;

import com.competition.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggregate disciplines (Remington): entered with a start of their own, which
 * takes no lane, and ranked on the results of other disciplines added up.
 */
class AggregateRankingTest {

    private static final String CATALOG =
        "[{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Colt\",\"shooting_distance\":\"m25\"},"
            + "{\"id\":53,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Donald Malson\",\"shooting_distance\":\"m50\"},"
            + "{\"id\":57,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Mariette\"},"
            + "{\"id\":58,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Donald Malson\"},"
            + "{\"id\":74,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"Remington\","
            + "\"aggregate_of\":[\"Colt\",\"Donald Malson\"]},"
            + "{\"id\":75,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"Remington\","
            + "\"aggregate_of\":[\"Mariette\",\"Donald Malson\"]}]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;
    private RankingService rankingService;
    private StartService startService;

    @BeforeEach
    void setUp() throws Exception {
        // Anna, Ben, Cara and Eva are entered in Remington original, Gus in the reproduction.
        // Anna and Ben tie on 39 with the same rings; Cara has only shot Colt so far; Eva nothing yet.
        // Dan shot Colt and Donald Malson but is not entered in Remington.
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + competitor(1, "Anna", "\"74\":[" + start("1-74-1") + "],\"52\":[" + start("1-52-1") + "," + start("1-52-2")
                + "],\"53\":[" + start("1-53-1") + "]")
            + "," + competitor(2, "Ben", "\"74\":[" + start("2-74-1") + "],\"52\":[" + start("2-52-1") + "],\"53\":[" + start("2-53-1") + "]")
            + "," + competitor(3, "Cara", "\"74\":[" + start("3-74-1") + "],\"52\":[" + start("3-52-1") + "],\"53\":[" + start("3-53-1") + "]")
            + "," + competitor(4, "Dan", "\"52\":[" + start("4-52-1") + "],\"53\":[" + start("4-53-1") + "]")
            + "," + competitor(5, "Eva", "\"74\":[" + start("5-74-1") + "]")
            + "," + competitor(6, "Gus", "\"75\":[" + start("6-75-1") + "],\"57\":[" + start("6-57-1") + "],\"58\":[" + start("6-58-1") + "]")
            + "],\"results\":["
            + result(1, "1-52-1", 52, 1, "[8,8]", 10.0) + ","
            + result(2, "1-52-2", 52, 1, "[10,9]", 30.0) + ","
            + result(3, "1-53-1", 53, 1, "[10,10]", 25.0) + ","
            + result(4, "2-52-1", 52, 2, "[10,10]", 20.0) + ","
            + result(5, "2-53-1", 53, 2, "[10,9]", 40.0) + ","
            + result(6, "3-52-1", 52, 3, "[10,10]", null) + ","
            + result(7, "4-52-1", 52, 4, "[10,10]", null) + ","
            + result(8, "4-53-1", 53, 4, "[10,10]", null) + ","
            + result(9, "6-57-1", 57, 6, "[7]", null) + ","
            + result(10, "6-58-1", 58, 6, "[6]", null)
            + "]}");
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
        disciplineService.setActiveDisciplines(List.of(52, 53, 57, 58, 74, 75), null);
        rankingService = new RankingService(dataService, disciplineService,
            new TeamService(tempDir.resolve("teams.json").toString(), dataService));
        startService = new StartService(dataService, disciplineService);
    }

    private static String competitor(int id, String name, String starts) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"starts\":{" + starts + "}}";
    }

    private static String start(String generatedId) {
        String[] parts = generatedId.split("-");
        return "{\"generated_id\":\"" + generatedId + "\",\"start_number\":" + parts[2]
            + ",\"discipline_id\":" + parts[1] + ",\"status\":\"registered\"}";
    }

    private static String result(int id, String startId, int disciplineId, int competitorId, String entries, Double tieBreak) {
        return "{\"id\":" + id + ",\"start_id\":\"" + startId + "\",\"discipline_id\":" + disciplineId
            + ",\"competitor_id\":" + competitorId + ",\"entries\":" + entries
            + (tieBreak != null ? ",\"override_value\":" + tieBreak : "") + "}";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(int disciplineId) throws Exception {
        return (List<Map<String, Object>>) rankingService.getBestResultRanking(disciplineId).get("rankings");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addsUpTheBestResultOfEachComponentForEveryoneEntered() throws Exception {
        Map<String, Object> entry = rankingService.getBestResultRanking(74);
        List<Map<String, Object>> components = (List<Map<String, Object>>) ((Map<String, Object>) entry.get("discipline")).get("components");
        assertEquals(List.of(52, 53), components.stream().map(c -> c.get("id")).toList());

        List<Map<String, Object>> rows = rowsOf(74);
        assertEquals(List.of("Anna", "Ben", "Cara", "Eva"), rows.stream().map(AggregateRankingTest::nameOf).toList(),
            "Dan is not entered in Remington");

        Map<String, Object> anna = rows.get(0);
        assertEquals(39.0, anna.get("score"), "best Colt (19) + Donald Malson (20)");
        assertEquals(Arrays.asList(19.0, 20.0), anna.get("component_scores"));
        assertEquals("1-74-1", anna.get("start_id"), "the Remington start");
        assertEquals(3, ((Map<String, Integer>) anna.get("freq_counts")).get("10"));
    }

    @Test
    void aTieGoesToTheFurthestShotOfTheResultsAddedUp() throws Exception {
        List<Map<String, Object>> rows = rowsOf(74);
        // Same total and rings: Anna's furthest shot is 30 (of 30 and 25), Ben's 40 (of 20 and 40); lower wins
        assertEquals(30.0, rows.get(0).get("override_value"));
        assertEquals(1, rows.get(0).get("rank"));
        assertEquals("Ben", nameOf(rows.get(1)));
        assertEquals(40.0, rows.get(1).get("override_value"));
        assertEquals(2, rows.get(1).get("rank"));
    }

    @Test
    void oneResultAlreadyCountsAsTheTotal() throws Exception {
        Map<String, Object> cara = rowsOf(74).get(2);
        assertEquals(20.0, cara.get("score"));
        assertEquals(3, cara.get("rank"));
        assertEquals(true, cara.get("has_result"));
        assertEquals(Arrays.asList(20.0, null), cara.get("component_scores"));
        assertNull(cara.get("override_value"), "her Colt result has no tie-break");

        Map<String, Object> eva = rowsOf(74).get(3);
        assertEquals(false, eva.get("has_result"), "entered, but nothing shot yet");
        assertNull(eva.get("rank"));
    }

    @Test
    void reproductionAddsUpTheReproductionDisciplines() throws Exception {
        List<Map<String, Object>> rows = rowsOf(75);
        assertEquals(List.of("Gus"), rows.stream().map(AggregateRankingTest::nameOf).toList());
        assertEquals(13.0, rows.get(0).get("score"));
    }

    @Test
    void listedWithTheOtherRankings() throws Exception {
        assertTrue(rankingService.getAllBestResultRankings().containsKey(74));
        assertTrue(rankingService.getAllRankings().containsKey(75));
    }

    @Test
    void startsCanBeCreatedButTakeNoResultOfTheirOwn() throws Exception {
        assertEquals("4-74-1", startService.createStart(4, 74).getGeneratedId());

        ResultService resultService = new ResultService(dataService, disciplineService);
        Result result = new Result();
        result.setStartId("4-74-1");
        result.setDisciplineId(74);
        result.setCompetitorId(4);
        assertThrows(IllegalArgumentException.class, () -> resultService.createResult(result));
    }

    @Test
    @SuppressWarnings("unchecked")
    void startsTakeNoLaneButAreListedForTheStartCard() throws Exception {
        RelayService relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);
        Map<String, Object> day = relayService.createDay(Map.of("date", "2026-10-03", "start_time", "09:00"));
        String relayId = (String) relayService.addRelays((String) day.get("id"), Map.of("count", 1)).get(0).get("id");

        for (String range : List.of("m25", "m50", "m100")) {
            assertTrue(relayService.getAvailableStarts(relayId, range).stream()
                .noneMatch(s -> "1-74-1".equals(s.get("start_id"))), "not offered on " + range);
        }
        assertThrows(IllegalArgumentException.class, () -> relayService.assign(
            Map.of("relay_id", relayId, "range_id", "m25", "lane_no", 1, "start_id", "1-74-1")));

        relayService.setAutoAssignEnabled(Map.of("enabled", true));
        relayService.autoAssign(Map.of());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) relayService.getOverview().get("rows");
        Map<String, Object> anna = rows.stream()
            .filter(r -> "Anna".equals(((Map<?, ?>) r.get("competitor")).get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> noLane = (List<Map<String, Object>>) anna.get("no_lane");
        assertEquals(List.of("1-74-1"), noLane.stream().map(s -> s.get("start_id")).toList());
        assertEquals("Remington (original)", noLane.get(0).get("discipline_name"));
        for (String list : List.of("scheduled", "unscheduled")) {
            assertTrue(((List<Map<String, Object>>) anna.get(list)).stream()
                .noneMatch(s -> "1-74-1".equals(s.get("start_id"))), "not in " + list);
        }
    }

    @Test
    void notCombinable() throws Exception {
        assertTrue(disciplineService.getCombinableEvents().stream().noneMatch(e -> "Remington".equals(e.get("event"))));
    }

    private static String nameOf(Map<String, Object> row) {
        return (String) ((Map<?, ?>) row.get("competitor")).get("name");
    }
}
