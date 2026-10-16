package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamServiceTest {

    @TempDir
    Path tempDir;

    private DataService dataService;
    private TeamService teamService;

    private static final String CATALOG = "["
        + individual(1, "original", "1_Miquelet_O") + ","
        + individual(11, "reproduction", "1_Miquelet_R") + ","
        + individual(3, "original", "3_Minie_O") + ","
        + "{\"id\":50,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"name\":\"5_Cominazzo_O\"},"
        + team(31, "original", "9_Gustav Adolph", "[1]", 3) + ","
        + team(39, "original", "31_Halikko", "[1]", 3) + ","
        + team(42, "reproduction", "29_Lucca", "[11]", 3) + ","
        + team(47, "open", "Open Miquelet", "[1,11]", 3) + ","
        + team(46, "open", "11_Versailles", "[3]", 6)
        + "]";

    private static String individual(int id, String type, String name) {
        return "{\"id\":" + id + ",\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"" + type
            + "\",\"name\":\"" + name + "\"}";
    }

    private static String team(int id, String type, String name, String teamOf, int size) {
        return "{\"id\":" + id + ",\"category\":\"rifle\",\"level\":\"team\",\"type\":\"" + type
            + "\",\"name\":\"" + name + "\",\"team_of\":" + teamOf + ",\"team_size\":" + size + "}";
    }

    private static String competitor(int id, String name, String club, String country, String... startIds) {
        StringBuilder starts = new StringBuilder();
        Map<String, List<String>> byDiscipline = new HashMap<>();
        for (String startId : startIds) {
            byDiscipline.computeIfAbsent(startId.split("-")[1], k -> new ArrayList<>()).add(startId);
        }
        for (Map.Entry<String, List<String>> e : byDiscipline.entrySet()) {
            if (starts.length() > 0) starts.append(',');
            starts.append('"').append(e.getKey()).append("\":[");
            for (int i = 0; i < e.getValue().size(); i++) {
                String startId = e.getValue().get(i);
                if (i > 0) starts.append(',');
                starts.append("{\"generated_id\":\"").append(startId).append("\",\"start_number\":")
                    .append(startId.split("-")[2]).append(",\"status\":\"registered\"}");
            }
            starts.append(']');
        }
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"club\":\"" + club + "\",\"country\":\"" + country
            + "\",\"starts\":{" + starts + "}}";
    }

    private static String result(int id, String startId, int... entries) {
        int sum = Arrays.stream(entries).sum();
        return "{\"id\":" + id + ",\"start_id\":\"" + startId + "\",\"value\":" + sum + ",\"entries\":"
            + Arrays.toString(entries) + ",\"override_value\":null}";
    }

    @BeforeEach
    void setUp() throws Exception {
        TestFiles.writeCatalog(tempDir, CATALOG);
        Files.writeString(tempDir.resolve("data.json"), "{\"competitors\":["
            + competitor(1, "Anna", "SG Wien", "Austria", "1-1-1", "1-1-2", "1-3-1") + ","
            + competitor(2, "Ben", "SG Wien", "Austria", "2-1-1") + ","
            + competitor(3, "Cara", "SV Graz", "Austria", "3-1-1") + ","
            + competitor(4, "Dan", "SV Graz", "Germany", "4-1-1") + ","
            + competitor(5, "Eve", "SG Wien", "Austria", "5-11-1") + ","
            + competitor(6, "Fay", "", "", "6-1-1") + ","
            + competitor(7, "Gil", "", "", "7-1-1") + ","
            + competitor(8, "Hal", "", "", "8-1-1") + ","
            + competitor(9, "Ivy", "", "", "9-1-1") + ","
            + competitor(10, "Jon", "", "", "10-1-1") + ","
            + competitor(11, "Kim", "", "", "11-1-1")
            + "],\"results\":["
            + result(1, "1-1-1", 10, 9) + ","
            + result(2, "2-1-1", 10, 8) + ","
            + result(3, "3-1-1", 9, 9) + ","
            + result(4, "4-1-1", 10, 10) + ","
            + result(5, "6-1-1", 9, 8) + ","
            + result(6, "7-1-1", 9, 9) + ","
            + result(7, "8-1-1", 10, 10) + ","
            + result(8, "9-1-1", 10, 5) + ","
            + result(9, "10-1-1", 10, 10)
            + "]}");

        dataService = TestFiles.dataService(tempDir);
        teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
    }

    private Map<String, Object> create(int disciplineId, String name, String... members) throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("discipline_id", disciplineId);
        request.put("name", name);
        request.put("members", List.of(members));
        return teamService.createTeam(request);
    }

    private static List<Object> eligibleIds(List<Map<String, Object>> disciplines, int teamId) {
        Map<String, Object> discipline = disciplines.stream()
            .filter(d -> ((Number) d.get("id")).intValue() == teamId).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> eligible = (List<Map<String, Object>>) discipline.get("eligible_disciplines");
        return eligible.stream().map(e -> e.get("id")).toList();
    }

    @Test
    void candidatesListEligibleStartsFirstStartFirst() throws Exception {
        List<Map<String, Object>> candidates = teamService.getCandidates(31);
        Map<String, Object> anna = candidates.stream().filter(c -> "Anna".equals(c.get("name"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> starts = (List<Map<String, Object>>) anna.get("starts");
        assertEquals(List.of("1-1-1", "1-1-2"), starts.stream().map(s -> s.get("start_id")).toList());
        assertEquals(19.0, starts.get(0).get("score"));
        assertEquals(false, starts.get(1).get("has_result"));
        assertTrue(candidates.stream().noneMatch(c -> "Eve".equals(c.get("name"))), "reproduction start is not eligible");

        create(31, "", "1-1-1", "2-1-1");
        anna = teamService.getCandidates(31).stream().filter(c -> "Anna".equals(c.get("name"))).findFirst().orElseThrow();
        assertEquals("SG Wien", anna.get("team_name"));
    }

    @Test
    void membersAreValidated() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> create(31, "X", "1-1-1", "2-1-1", "3-1-1", "4-1-1"));
        assertThrows(IllegalArgumentException.class, () -> create(31, "X", "1-3-1"), "Minie start");
        assertThrows(IllegalArgumentException.class, () -> create(31, "X", "5-11-1"), "reproduction start");
        assertThrows(IllegalArgumentException.class, () -> create(31, "X", "1-1-1", "1-1-2"), "same competitor twice");
        assertThrows(IllegalArgumentException.class, () -> create(31, "X", "99-1-1"), "unknown start");
        assertThrows(IllegalArgumentException.class, () -> create(1, "X", "1-1-1"), "not a team discipline");

        create(31, "First", "1-1-1", "2-1-1");
        ConflictException conflict = assertThrows(ConflictException.class, () -> create(31, "Second", "1-1-2", "3-1-1"));
        assertTrue(conflict.getMessage().contains("First"));

        // Another team discipline counting the same discipline may use the same start
        assertNotNull(create(39, "Halikko", "1-1-1"));
        assertTrue(Files.exists(tempDir.resolve("teams.json")));
    }

    @Test
    void blankNameDefaultsToSharedClubThenCountryThenTeamNumber() throws Exception {
        assertEquals("SG Wien", create(31, "", "1-1-1", "2-1-1").get("name"));
        assertEquals("Austria", create(39, " ", "1-1-1", "3-1-1").get("name"));
        Map<String, Object> mixed = create(47, null, "1-1-1", "4-1-1");
        assertEquals("Team " + mixed.get("id"), mixed.get("name"));
        assertEquals("My name", create(42, "My name", "5-11-1").get("name"));
    }

    @Test
    void staleUpdatesAndDeletesAreRejected() throws Exception {
        Map<String, Object> team = create(31, "Team", "1-1-1");
        int id = ((Number) team.get("id")).intValue();

        Map<String, Object> update = new HashMap<>();
        update.put("name", "Renamed");
        update.put("members", List.of("1-1-1", "2-1-1"));
        update.put("version", 0);
        Map<String, Object> updated = teamService.updateTeam(id, update);
        assertEquals(1, updated.get("version"));
        assertEquals("Renamed", updated.get("name"));

        assertThrows(ConflictException.class, () -> teamService.updateTeam(id, update), "version 0 is stale now");
        assertThrows(ConflictException.class, () -> teamService.deleteTeam(id, 0));
        teamService.deleteTeam(id, 1);
        assertThrows(RecordNotFoundException.class, () -> teamService.getTeam(id));
    }

    @Test
    void rankingUsesTotalThenCountbackThenLowerTieBreak() throws Exception {
        create(31, "X", "1-1-1", "2-1-1", "3-1-1");    // 19+18+18 = 55, two 10s
        create(31, "Y", "4-1-1", "6-1-1", "7-1-1");    // 20+17+18 = 55, two 10s, same rings as X
        create(31, "Z", "8-1-1", "9-1-1", "10-1-1");   // 20+15+20 = 55, five 10s
        create(31, "Short", "11-1-1");                 // no result yet

        List<Map<String, Object>> ranking = rankings();
        assertEquals(List.of("Z", "X", "Y", "Short"), names(ranking));
        assertEquals(List.of(1, 2, 2, 4), ranks(ranking), "X and Y are fully tied without tie-break");
        assertEquals(55.0, ranking.get(0).get("total"));
        assertEquals(true, ranking.get(0).get("complete"));
        assertEquals(false, ranking.get(3).get("complete"));
        assertEquals(0.0, ranking.get(3).get("total"));

        // Furthest shot: the lower value wins
        setTieBreak("X", 12.5);
        setTieBreak("Y", 8.0);
        ranking = rankings();
        assertEquals(List.of("Z", "Y", "X", "Short"), names(ranking));
        assertEquals(List.of(1, 2, 3, 4), ranks(ranking));

        // A team without tie-break loses against one with it
        setTieBreak("Y", null);
        assertEquals(List.of("Z", "X", "Y", "Short"), names(rankings()));
    }

    @Test
    void rankingKeyComparesRingsInDescendingOrder() {
        int[] moreNines = Scoring.newRingCounts();
        moreNines[10] = 2;
        moreNines[9] = 3;
        int[] moreEights = Scoring.newRingCounts();
        moreEights[10] = 2;
        moreEights[9] = 2;
        moreEights[8] = 5;
        double[] a = TeamService.rankingKey(100, moreNines, null);
        double[] b = TeamService.rankingKey(100, moreEights, 1.0);
        assertTrue(Arrays.compare(a, b) > 0, "more 9s beats more 8s and a tie-break");
    }

    @Test
    void aTeamRankingRanksTheTeamsOfItsDisciplines() throws Exception {
        create(31, "X", "1-1-1", "2-1-1", "3-1-1");
        RankingService rankingService = new RankingService(dataService, teamService);
        Map<String, Object> ranking = rankingService.getRankingResult(rankingOf(31));
        assertEquals("team", ranking.get("kind"));
        assertEquals(1, ((List<?>) ranking.get("rankings")).size());
        assertTrue(rankingService.getAllRankingResults().stream().anyMatch(r -> r.get("kind").equals("team")));
    }

    @Test
    void aPooledTeamRankingRanksTheTeamsOfSeveralTeamDisciplinesTogether() throws Exception {
        create(31, "Adolph", "1-1-1");     // 19
        create(39, "Halikko", "4-1-1");    // 20
        RankingConfigService config = new RankingConfigService(dataService, teamService, "none");
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Miquelet teams");
        request.put("level", "team");
        request.put("entries", List.of(Map.of("discipline_ids", List.of(31)), Map.of("discipline_ids", List.of(39))));
        int id = config.createRanking(request).getId();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) new RankingService(dataService, teamService)
            .getRankingResult(id).get("rankings");
        assertEquals(List.of("Halikko", "Adolph"), names(rows));
        assertEquals(List.of("31_Halikko", "9_Gustav Adolph"), rows.stream().map(r -> r.get("discipline_tag")).toList());

        // A team ranking cannot add up team disciplines
        request.put("entries", List.of(Map.of("label", "Sum", "discipline_ids", List.of(31, 39))));
        assertThrows(IllegalArgumentException.class, () -> config.createRanking(request));
    }

    @Test
    void oneTeamPerCompetitorAcrossARankingThatAllowsOne() throws Exception {
        RankingConfigService config = new RankingConfigService(dataService, teamService, "none");
        Map<String, Object> request = new HashMap<>();
        request.put("name", "Miquelet teams");
        request.put("level", "team");
        request.put("one_start_per_competitor", true);
        request.put("entries", List.of(Map.of("discipline_ids", List.of(31)), Map.of("discipline_ids", List.of(39))));
        config.createRanking(request);

        create(31, "Adolph", "1-1-1");
        ConflictException conflict = assertThrows(ConflictException.class, () -> create(39, "Halikko", "1-1-2"));
        assertTrue(conflict.getMessage().contains("Adolph"));
        assertNotNull(create(42, "Lucca", "5-11-1"), "Lucca is not ranked together with them");
    }

    @Test
    void teamsOnlyForTeamDisciplinesOfTheMeetsRankings() throws Exception {
        RankingConfigService config = new RankingConfigService(dataService, teamService, "none");
        config.deleteRanking(rankingOf(47), null);
        assertThrows(IllegalArgumentException.class, () -> create(47, "Open", "1-1-1"));
        // Not offered on the team page either
        assertTrue(teamService.getTeamDisciplines().stream().noneMatch(d -> ((Number) d.get("id")).intValue() == 47));
    }

    private int rankingOf(int disciplineId) throws Exception {
        return dataService.loadRankingConfig().getRankings().stream()
            .filter(r -> r.disciplineIds().contains(disciplineId)).findFirst().orElseThrow().getId();
    }

    private void setTieBreak(String teamName, Double tieBreak) throws Exception {
        Map<String, Object> team = teamService.getTeams(31).stream()
            .filter(t -> teamName.equals(t.get("name"))).findFirst().orElseThrow();
        Map<String, Object> update = new HashMap<>();
        update.put("name", teamName);
        update.put("members", team.get("members"));
        update.put("tie_break", tieBreak);
        teamService.updateTeam(((Number) team.get("id")).intValue(), update);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rankings() throws Exception {
        return (List<Map<String, Object>>) teamService.getRanking(31).get("rankings");
    }

    private static List<Object> names(List<Map<String, Object>> ranking) {
        return ranking.stream().map(r -> r.get("name")).toList();
    }

    @Test
    void teamOfDecidesWhichStartsCount() throws Exception {
        DisciplineService disciplineService = new DisciplineService(dataService);
        // Halikko (counting the Miquelet original) set to count original and reproduction starts
        disciplineService.updateCatalogDiscipline(39, Map.of("team_of", List.of(1, 11)));
        assertEquals(List.of(1, 11), eligibleIds(teamService.getTeamDisciplines(), 39));
        assertNotNull(create(39, "Mixed", "1-1-1", "5-11-1"), "Eve's reproduction start now counts");
        // Gustav Adolph still counts original starts only
        assertThrows(IllegalArgumentException.class, () -> create(31, "No", "5-11-1"));

        // Only individual disciplines of the team's category
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(39, Map.of("team_of", List.of(31))));
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(39, Map.of("team_of", List.of(50))));

        // Dropping the reproduction would leave Eve's start out of her team: refused, nothing saved
        assertThrows(ConflictException.class, () -> teamService.changeComposition(39, Map.of("team_of", List.of(1)),
            () -> disciplineService.updateCatalogDiscipline(39, Map.of("team_of", List.of(1)))));
        assertEquals(List.of(1, 11), eligibleIds(teamService.getTeamDisciplines(), 39));
        // Adding one is fine
        teamService.changeComposition(39, Map.of("team_of", List.of(1, 11, 3)),
            () -> disciplineService.updateCatalogDiscipline(39, Map.of("team_of", List.of(1, 11, 3))));
        assertEquals(List.of(1, 11, 3), eligibleIds(teamService.getTeamDisciplines(), 39));

        // At most the catalog's limit of disciplines (4)
        assertThrows(IllegalArgumentException.class,
            () -> disciplineService.updateCatalogDiscipline(39, Map.of("team_of", List.of(1, 11, 3, 50, 2))));
        disciplineService.setMaxDisciplines(3);
        assertThrows(IllegalArgumentException.class, () -> disciplineService.setMaxDisciplines(2),
            "Halikko counts 3 disciplines");
    }

    private static List<Object> ranks(List<Map<String, Object>> ranking) {
        return ranking.stream().map(r -> r.get("rank")).toList();
    }
}
