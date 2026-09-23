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
        + individual(1, "original", "No 1 Miquelet") + ","
        + individual(11, "reproduction", "No 1 Miquelet") + ","
        + individual(21, "combined", "No 1 Miquelet") + ","
        + individual(3, "original", "No 3 Minie") + ","
        + "{\"id\":50,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"No 5 Cominazzo\"},"
        + team(31, "original", "No 9 Gustav Adolph", "No 1 Miquelet", 3) + ","
        + team(39, "original", "No 31 Halikko", "No 1 Miquelet", 3) + ","
        + team(42, "reproduction", "No 29 Lucca", "No 1 Miquelet", 3) + ","
        + team(47, "open", "Open Miquelet", "No 1 Miquelet", 3) + ","
        + team(46, "open", "No 11 Versailles", "Nos. 9 + 10 (aggregate)", 6)
        + "]";

    private static String individual(int id, String type, String event) {
        return "{\"id\":" + id + ",\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"" + type
            + "\",\"event\":\"" + event + "\"}";
    }

    private static String team(int id, String type, String event, String basedOn, int size) {
        return "{\"id\":" + id + ",\"category\":\"rifle\",\"level\":\"team\",\"type\":\"" + type
            + "\",\"event\":\"" + event + "\",\"based_on\":\"" + basedOn + "\",\"team_size\":" + size + "}";
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
        Files.writeString(tempDir.resolve("disciplines.json"), CATALOG);
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

        dataService = new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString(),
            tempDir.resolve("competition.json").toString());
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
    void teamDisciplinesAreScoredFromTheirBasedOnDiscipline() throws Exception {
        List<Map<String, Object>> disciplines = teamService.getTeamDisciplines();
        assertEquals(5, disciplines.size());
        assertEquals(List.of(1), eligibleIds(disciplines, 31));
        assertEquals(List.of(11), eligibleIds(disciplines, 42));
        assertEquals(List.of(1, 11, 21), eligibleIds(disciplines, 47));
        // based_on names no event: any rifle individual discipline
        assertEquals(List.of(1, 11, 21, 3), eligibleIds(disciplines, 46));
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

        // Another team discipline based on the same event may use the same start
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
    void mainRankingServesTeamRankingForTeamDisciplines() throws Exception {
        create(31, "X", "1-1-1", "2-1-1", "3-1-1");
        RankingService rankingService = new RankingService(dataService, new DisciplineService(dataService), teamService);
        Map<String, Object> ranking = rankingService.getRanking(31);
        assertEquals("team", ranking.get("kind"));
        assertEquals(1, ((List<?>) ranking.get("rankings")).size());
        assertTrue(rankingService.getAllRankings().containsKey(31));
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

    private static List<Object> ranks(List<Map<String, Object>> ranking) {
        return ranking.stream().map(r -> r.get("rank")).toList();
    }
}
