package com.competition.service;

import com.competition.model.Ranking;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Combined rankings (KUCR = KUCRS + KUCRU, KUC = KUCO + KUCRS + KUCRU):
 * competitors start in the disciplines, the meet decides which rankings there
 * are, and a discipline of an active combined ranking is ranked only through it.
 */
class CombinedRankingDisciplineTest {

    private static final String CATALOG =
        "[{\"id\":1,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"1_Miquelet_O\",\"short_name\":\"MIQO\"},"
            + "{\"id\":51,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"6_Kuchenreuter_O\",\"short_name\":\"KUCO\"},"
            + "{\"id\":56,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"6_Kuchenreuter_RU\",\"short_name\":\"KUCRU\"},"
            + "{\"id\":62,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"6_Kuchenreuter_RS\",\"short_name\":\"KUCRS\"},"
            + "{\"id\":71,\"category\":\"pistol\",\"level\":\"team\",\"type\":\"reproduction\",\"event\":\"33_Forsyth\",\"team_size\":3,\"team_of\":[56]},"
            + "{\"id\":77,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"6_Kuchenreuter_R\","
            + "\"short_name\":\"KUCR\",\"combined_of\":[62,56]},"
            + "{\"id\":78,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"combined\",\"event\":\"6_Kuchenreuter_O/R\","
            + "\"short_name\":\"KUC\",\"combined_of\":[51,62,56]}]";

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;
    private RankingService rankingService;
    private StartService startService;
    private ProgramService programService;

    @BeforeEach
    void setUp() throws Exception {
        // Anna shot KUCRS, Ben KUCRU, Cara KUCO; Dan started in KUCRS and KUCO; Eva is entered in KUCRU, no result yet
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + competitor(1, "Anna", "\"62\":[" + start("1-62-1") + "]")
            + "," + competitor(2, "Ben", "\"56\":[" + start("2-56-1") + "]")
            + "," + competitor(3, "Cara", "\"51\":[" + start("3-51-1") + "]")
            + "," + competitor(4, "Dan", "\"62\":[" + start("4-62-1") + "],\"51\":[" + start("4-51-1") + "]")
            + "," + competitor(5, "Eva", "\"56\":[" + start("5-56-1") + "]")
            + "],\"results\":["
            + result(1, "1-62-1", 62, 1, "[10,10,9]") + ","
            + result(2, "2-56-1", 56, 2, "[10,10,10]") + ","
            + result(3, "3-51-1", 51, 3, "[10,9,9]") + ","
            + result(4, "4-62-1", 62, 4, "[9,9,9]") + ","
            + result(5, "4-51-1", 51, 4, "[8,8,8]")
            + "]}");
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
        dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
        TeamService teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        rankingService = new RankingService(dataService, disciplineService, teamService);
        startService = new StartService(dataService, disciplineService);
        programService = new ProgramService(dataService, disciplineService, teamService);
    }

    private static String competitor(int id, String name, String starts) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"starts\":{" + starts + "}}";
    }

    private static String start(String generatedId) {
        String[] parts = generatedId.split("-");
        return "{\"generated_id\":\"" + generatedId + "\",\"start_number\":" + parts[2]
            + ",\"discipline_id\":" + parts[1] + ",\"status\":\"registered\"}";
    }

    private static String result(int id, String startId, int disciplineId, int competitorId, String entries) {
        return "{\"id\":" + id + ",\"start_id\":\"" + startId + "\",\"discipline_id\":" + disciplineId
            + ",\"competitor_id\":" + competitorId + ",\"entries\":" + entries + "}";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(int disciplineId) throws Exception {
        return (List<Map<String, Object>>) rankingService.getBestResultRanking(disciplineId).get("rankings");
    }

    private static List<Object> names(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (Object) ((Map<?, ?>) r.get("competitor")).get("name")).toList();
    }

    @Test
    void ranksTheDisciplinesTogetherTaggedWithTheDisciplineShot() throws Exception {
        List<Map<String, Object>> rows = rowsOf(77);

        assertEquals(List.of("Ben", "Anna", "Dan", "Eva"), names(rows), "KUCRU 30, KUCRS 29 and 27, then Eva without a result");
        assertEquals(List.of("KUCRU", "KUCRS", "KUCRS", "KUCRU"), rows.stream().map(r -> r.get("discipline_tag")).toList());
        assertEquals(1, rows.get(0).get("rank"));
        assertNull(rows.get(3).get("rank"), "Eva has no result yet");

        @SuppressWarnings("unchecked")
        Map<String, Object> discipline = (Map<String, Object>) rankingService.getBestResultRanking(77).get("discipline");
        assertEquals("6_Kuchenreuter_R", discipline.get("name"));
        assertEquals("reproduction", discipline.get("type"));
        assertEquals(List.of(62, 56), discipline.get("combined_of"));
    }

    @Test
    void ranksOriginalsAndReproductionsInOneList() throws Exception {
        assertEquals(List.of("Ben", "Anna", "Cara", "Dan", "Eva"), names(rowsOf(78)),
            "Dan once, on his better result (KUCRS 27, not KUCO 24)");
        assertEquals(List.of("reproduction", "reproduction", "original", "reproduction", "reproduction"),
            rowsOf(78).stream().map(r -> r.get("discipline_type")).toList());
    }

    @Test
    void theDetailedRankingIsTaggedToo() throws Exception {
        @SuppressWarnings("unchecked")
        List<Ranking> rankings = (List<Ranking>) rankingService.getRanking(77).get("rankings");
        assertEquals(List.of("KUCRU", "KUCRS", "KUCRS"), rankings.stream().map(Ranking::getDisciplineTag).toList());
    }

    @Test
    void anActiveCombinedRankingReplacesItsDisciplinesRankings() throws Exception {
        disciplineService.setActiveDisciplines(List.of(51, 56, 62), null);
        assertEquals(List.of(51, 56, 62), List.copyOf(rankingService.getAllBestResultRankings().keySet()));

        disciplineService.setActiveDisciplines(List.of(51, 56, 62, 77), null);
        assertEquals(List.of(51, 77), List.copyOf(rankingService.getAllBestResultRankings().keySet()),
            "KUCRS and KUCRU are ranked only through KUCR, KUCO on its own");
        assertEquals(List.of(51, 77), List.copyOf(rankingService.getAllRankings().keySet()));
    }

    @Test
    void theCatalogsCombinedRankingsAreOffUntilAMeetTurnsThemOn() throws Exception {
        assertFalse(disciplineService.getActiveDisciplines().contains(77));
        assertFalse(disciplineService.getActiveDisciplines().contains(78));
        assertTrue(disciplineService.getActiveDisciplines().containsAll(List.of(1, 51, 56, 62)));
    }

    @Test
    void competitorsStartInTheDisciplinesNotInTheCombinedRanking() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 77));
    }

    @Test
    void whileRankedTogetherACompetitorStartsInOnlyOneOfTheDisciplines() throws Exception {
        disciplineService.setActiveDisciplines(List.of(51, 56, 62, 77), null);
        ConflictException e = assertThrows(ConflictException.class, () -> startService.createStart(1, 56));
        assertTrue(e.getMessage().contains("6_Kuchenreuter_R"), e.getMessage());
        // KUCO is not part of KUCR
        assertNotNull(startService.createStart(1, 51));
    }

    @Test
    void theProgramRanksACombinedRankingOnlyWithAllItsDisciplinesShot() throws Exception {
        disciplineService.setActiveDisciplines(List.of(56, 62), null);
        Map<String, Object> request = new HashMap<>();
        request.put("combined_rankings", List.of(78));
        assertThrows(IllegalArgumentException.class, () -> programService.setProgram(request), "KUCO is not shot");

        request.put("combined_rankings", List.of(77));
        Map<String, Object> program = programService.setProgram(request);
        assertTrue(disciplineService.getActiveDisciplines().contains(77));
        @SuppressWarnings("unchecked")
        Map<String, Object> kucr = ((List<Map<String, Object>>) program.get("combined_rankings")).stream()
            .filter(r -> ((Number) r.get("id")).intValue() == 77).findFirst().orElseThrow();
        assertEquals(true, kucr.get("active"));
        assertEquals("KUCR", kucr.get("short_name"));

        // Stopping to shoot KUCRU stops ranking KUCR
        Map<String, Object> events = Map.of("pistol|Kuchenreuter_RU", Map.of("active", List.of()));
        programService.setProgram(Map.of("events", events));
        assertFalse(disciplineService.getActiveDisciplines().contains(77));
    }

    @Test
    void rankingItIsRefusedWhileACompetitorStartsInTwoOfItsDisciplines() throws Exception {
        disciplineService.setActiveDisciplines(List.of(51, 56, 62), null);
        ConflictException e = assertThrows(ConflictException.class,
            () -> programService.setProgram(Map.of("combined_rankings", List.of(78))));
        assertTrue(e.getMessage().contains("Dan"), e.getMessage());
        assertFalse(disciplineService.getActiveDisciplines().contains(78));
    }

    @Test
    void combinedRankingsAreNotEventsOrTeamMembers() throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) programService.getProgram().get("events");
        assertTrue(events.stream().noneMatch(e -> String.valueOf(e.get("key")).equals("pistol|Kuchenreuter")
            && ((List<?>) e.get("disciplines")).size() > 1), "KUCR/KUC do not join the Kuchenreuter event");
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(71, Map.of("team_of", List.of(77))));
    }

    @Test
    void validatesTheDisciplinesRankedTogether() throws Exception {
        var created = disciplineService.createCatalogDiscipline(combined(List.of(51, 62, 51)));
        assertEquals(List.of(51, 62), created.getCombinedOf());
        assertNull(created.getShootingDistance(), "a combined ranking takes no lane");
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(combined(List.of(62))),
            "at least two");
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(combined(List.of(62, 1))),
            "a rifle discipline");
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(combined(List.of(62, 71))),
            "a team");
        assertThrows(IllegalArgumentException.class, () -> disciplineService.createCatalogDiscipline(combined(List.of(62, 77))),
            "another combined ranking");
        assertThrows(ConflictException.class, () -> disciplineService.deleteCatalogDiscipline(62),
            "KUCRS is ranked in KUCR and KUC");
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(56, Map.of("category", "rifle")),
            "KUCRU would no longer fit KUCR");
    }

    private static Map<String, Object> combined(List<Integer> ids) {
        Map<String, Object> data = new HashMap<>();
        data.put("event", "Test combined");
        data.put("category", "pistol");
        data.put("level", "individual");
        data.put("type", "combined");
        data.put("combined_of", ids);
        data.put("shooting_distance", "m25");
        return data;
    }
}
