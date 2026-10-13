package com.competition.service;

import com.competition.model.Competitor;
import com.competition.model.Discipline;
import com.competition.model.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ids are never handed out twice, and deleting a start or competitor leaves
 * nothing (lane, team member, result) pointing at it.
 */
class IdAndReferenceIntegrityTest {

    @TempDir
    Path tempDir;

    private DataService dataService;
    private DisciplineService disciplineService;
    private CompetitorService competitorService;
    private StartService startService;
    private ResultService resultService;
    private TeamService teamService;
    private RelayService relayService;
    private CascadingDeletes cascadingDeletes;
    private String relay1;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"),
            "[{\"id\":3,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"No 3 Minie\"},"
                + "{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"No 7 Colt\"},"
                + "{\"id\":53,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"reproduction\",\"event\":\"No 8 Kuchenreuter\"},"
                + "{\"id\":80,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"combined\",\"event\":\"KUC\",\"combined_of\":[52,53]},"
                + "{\"id\":31,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"event\":\"No 9 Gustav Adolph\","
                + "\"team_of\":[3],\"team_size\":3}]");
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":["
                + competitor(1, "Anna", "\"52\":[" + start("1-52-1", 1, 52) + "," + start("1-52-2", 2, 52) + "],"
                    + "\"3\":[" + start("1-3-1", 1, 3) + "]")
                + "," + competitor(2, "Ben", "\"52\":[" + start("2-52-1", 1, 52) + "]")
                + "],\"results\":[]}");

        dataService = new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString(),
            tempDir.resolve("competition.json").toString());
        disciplineService = new DisciplineService(dataService);
        competitorService = new CompetitorService(dataService);
        startService = new StartService(dataService, disciplineService);
        resultService = new ResultService(dataService, disciplineService);
        teamService = new TeamService(tempDir.resolve("teams.json").toString(), dataService);
        relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);
        cascadingDeletes = new CascadingDeletes(startService, competitorService, teamService, relayService);

        Map<String, Object> day = relayService.createDay(Map.of("date", "2026-10-03", "start_time", "09:00"));
        relay1 = (String) relayService.addRelays((String) day.get("id"), Map.of("count", 1)).get(0).get("id");
    }

    private static String competitor(int id, String name, String starts) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"starts\":{" + starts + "}}";
    }

    private static String start(String generatedId, int number, int disciplineId) {
        return "{\"generated_id\":\"" + generatedId + "\",\"start_number\":" + number
            + ",\"discipline_id\":" + disciplineId + ",\"status\":\"registered\"}";
    }

    private Map<String, Object> assign(int lane, String startId) throws Exception {
        return relayService.assign(Map.of("relay_id", relay1, "range_id", "m25", "lane_no", lane, "start_id", startId));
    }

    private int laneCount() throws Exception {
        return ((List<?>) relayService.getAll().get("assignments")).size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members(int teamId) throws Exception {
        return (List<Map<String, Object>>) teamService.getTeam(teamId).get("members");
    }

    @Test
    void brokenDataFileIsNeverReplaced() throws Exception {
        String broken = "{\"competitors\":[{\"id\":1,";
        Files.writeString(tempDir.resolve("data.json"), broken);

        assertThrows(IOException.class, () -> competitorService.getAllCompetitors());
        assertEquals(broken, Files.readString(tempDir.resolve("data.json")), "the broken file must be kept for repair");
    }

    @Test
    void deletedCompetitorIdIsNotReused() throws Exception {
        Competitor carl = competitorService.createCompetitor(new Competitor("Carl"));
        assertEquals(3, carl.getId());
        competitorService.deleteCompetitor(carl.getId(), null);

        assertEquals(4, competitorService.createCompetitor(new Competitor("Dora")).getId());
    }

    @Test
    void deletedStartIdIsNotReusedAndItsLaneAndTeamMembershipGo() throws Exception {
        Map<String, Object> team = teamService.createTeam(Map.of("discipline_id", 31, "members", List.of("1-3-1")));
        int teamId = ((Number) team.get("id")).intValue();
        assign(1, "1-52-2");

        cascadingDeletes.deleteStart(1, "1-52-2");
        cascadingDeletes.deleteStart(1, "1-3-1");

        assertEquals(0, laneCount(), "the deleted start's lane is cleared");
        assertTrue(members(teamId).isEmpty(), "the deleted start left its team");
        // The highest start was deleted: its id is not handed out again
        assertEquals("1-52-3", startService.createStart(1, 52, null).getGeneratedId());
        assertEquals("1-3-2", startService.createStart(1, 3, null).getGeneratedId());
    }

    @Test
    void deletedCompetitorTakesTheirLanesAndTeamMembershipsAlong() throws Exception {
        Map<String, Object> team = teamService.createTeam(Map.of("discipline_id", 31, "members", List.of("1-3-1")));
        assign(1, "1-52-1");
        assign(2, "2-52-1");

        cascadingDeletes.deleteCompetitor(1, null);

        assertEquals(1, laneCount(), "only Ben's lane stays");
        assertTrue(members(((Number) team.get("id")).intValue()).isEmpty());
        assertThrows(RecordNotFoundException.class, () -> cascadingDeletes.deleteStart(1, "1-52-1"));
    }

    @Test
    void deletedLaneAssignmentIdIsNotReused() throws Exception {
        Map<String, Object> first = assign(1, "1-52-1");
        relayService.deleteAssignment((String) first.get("id"));
        Map<String, Object> second = assign(1, "2-52-1");
        assertNotEquals(first.get("id"), second.get("id"));

        // A client still showing the first lane does not overwrite the second
        assertThrows(ConflictException.class, () -> relayService.assign(Map.of("relay_id", relay1, "range_id", "m25",
            "lane_no", 1, "start_id", "1-52-2", "expected_assignment_id", first.get("id"))));
    }

    @Test
    void deletedTeamIdIsNotReused() throws Exception {
        Map<String, Object> first = teamService.createTeam(Map.of("discipline_id", 31, "members", List.of("1-3-1")));
        teamService.deleteTeam(((Number) first.get("id")).intValue(), null);
        Map<String, Object> second = teamService.createTeam(Map.of("discipline_id", 31, "members", List.of("1-3-1")));
        assertNotEquals(first.get("id"), second.get("id"));
    }

    @Test
    void resultTakesCompetitorAndDisciplineFromItsStart() throws Exception {
        Result result = new Result(99, 77, 90);
        result.setStartId("2-52-1");
        Result saved = resultService.createResult(result);
        assertEquals(2, saved.getCompetitorId());
        assertEquals(52, saved.getDisciplineId());
        assertEquals(1, resultService.getResults(52).size());

        Result unknownStart = new Result(1, 52, 80);
        unknownStart.setStartId("9-9-9");
        assertThrows(IllegalArgumentException.class, () -> resultService.createResult(unknownStart));
        assertThrows(IllegalArgumentException.class, () -> resultService.createResult(new Result(1, 52, 80)));
    }

    @Test
    void deletedResultIdIsNotReused() throws Exception {
        Result first = new Result(1, 52, 90);
        first.setStartId("1-52-1");
        int firstId = resultService.createResult(first).getId();
        resultService.deleteResult(firstId, null);

        Result second = new Result(2, 52, 80);
        second.setStartId("2-52-1");
        assertNotEquals(firstId, resultService.createResult(second).getId());
    }

    @Test
    void startsOnlyGoIntoDisciplinesThatTakeThem() {
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 999, null), "no such discipline");
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 31, null), "a team discipline");
        assertThrows(IllegalArgumentException.class, () -> startService.createStart(1, 80, null), "a combined ranking");
        assertThrows(RecordNotFoundException.class, () -> startService.createStart(9, 3, null), "no such competitor");
    }

    @Test
    void disciplineStillInUseCannotBeDeleted() throws Exception {
        assertThrows(ConflictException.class, () -> disciplineService.deleteCatalogDiscipline(3),
            "counted by team discipline 31");
        assertThrows(ConflictException.class, () -> disciplineService.deleteCatalogDiscipline(52),
            "ranked in KUC and has starts");
        assertThrows(RecordNotFoundException.class, () -> disciplineService.deleteCatalogDiscipline(999));

        teamService.createTeam(Map.of("discipline_id", 31, "members", List.of("1-3-1")));
        assertThrows(ConflictException.class, () -> teamService.withoutTeams(31, () -> {
            disciplineService.deleteCatalogDiscipline(31);
            return null;
        }));
        assertNotNull(disciplineService.getAvailableDisciplineById(31));
    }

    @Test
    void addedDisciplineIdIsNotReused() throws Exception {
        Discipline first = disciplineService.createCatalogDiscipline(Map.of("category", "rifle", "event", "Custom"));
        assertEquals(DisciplineService.FIRST_CUSTOM_ID, first.getId());
        disciplineService.deleteCatalogDiscipline(first.getId());

        Discipline second = disciplineService.createCatalogDiscipline(Map.of("category", "rifle", "event", "Other"));
        assertEquals(DisciplineService.FIRST_CUSTOM_ID + 1, second.getId());
    }

    @Test
    void teamDisciplineWithTeamsStaysATeamDiscipline() throws Exception {
        teamService.createTeam(Map.of("discipline_id", 31, "members", List.of("1-3-1")));
        Map<String, Object> edit = Map.of("level", "individual");

        assertThrows(ConflictException.class,
            () -> teamService.changeComposition(31, edit, () -> disciplineService.updateCatalogDiscipline(31, edit)));
        assertEquals("team", disciplineService.getAvailableDisciplineById(31).getLevel());
    }

    @Test
    void combinedRankingStopsWhenOneOfItsDisciplinesIsNoLongerShot() throws Exception {
        disciplineService.setActiveDisciplines(List.of(3, 52, 53, 80), null);
        assertTrue(disciplineService.getActiveDisciplines().contains(80));

        List<Integer> active = disciplineService.deactivateDiscipline(53);
        assertFalse(active.contains(80), "KUC cannot be ranked without KUCR shot");
        assertEquals(List.of(3, 52), disciplineService.setActiveDisciplines(List.of(3, 52, 80), active));
    }
}
