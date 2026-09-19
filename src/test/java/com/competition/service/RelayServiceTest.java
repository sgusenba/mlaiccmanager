package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RelayServiceTest {

    @TempDir
    Path tempDir;

    private RelayService relayService;
    private DataService dataService;
    private String relay1;
    private String relay2;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"),
            "[{\"id\":3,\"category\":\"rifle\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"No 3 Minie\"},"
                + "{\"id\":52,\"category\":\"pistol\",\"level\":\"individual\",\"type\":\"original\",\"event\":\"No 7 Colt\"},"
                + "{\"id\":31,\"category\":\"rifle\",\"level\":\"team\",\"type\":\"original\",\"event\":\"No 9 Gustav Adolph\","
                + "\"based_on\":\"No 1 Miquelet\",\"team_size\":3}]");
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":["
                + competitor(1, "Anna", "\"52\":[" + start("1-52-1", 1, 52) + "," + start("1-52-2", 2, 52) + "],"
                    + "\"3\":[" + start("1-3-1", 1, 3) + "],"
                    + "\"31\":[" + start("1-31-1", 1, 31) + "]")
                + "," + competitor(2, "Ben", "\"52\":[" + start("2-52-1", 1, 52) + "]")
                + "],\"results\":[],\"disciplines\":[],\"teams\":[],\"active_disciplines\":[3,52,31]}");

        dataService = new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString());
        relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);

        Map<String, Object> day = relayService.createDay(Map.of("date", "2026-10-03", "start_time", "09:00"));
        List<Map<String, Object>> relays = relayService.addRelays((String) day.get("id"), Map.of("count", 2));
        relay1 = (String) relays.get(0).get("id");
        relay2 = (String) relays.get(1).get("id");
    }

    private static String competitor(int id, String name, String starts) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"starts\":{" + starts + "}}";
    }

    private static String start(String generatedId, int number, int disciplineId) {
        return "{\"generated_id\":\"" + generatedId + "\",\"start_number\":" + number
            + ",\"discipline_id\":" + disciplineId + ",\"status\":\"registered\"}";
    }

    private Map<String, Object> assign(String relayId, String rangeId, int lane, String startId) throws Exception {
        return relayService.assign(Map.of("relay_id", relayId, "range_id", rangeId,
            "lane_no", lane, "start_id", startId));
    }

    private static List<String> startIds(List<Map<String, Object>> starts) {
        return starts.stream().map(s -> (String) s.get("start_id")).toList();
    }

    private void setShootingDistances(String... pairs) throws Exception {
        var disciplines = dataService.loadDisciplines();
        for (int i = 0; i < pairs.length; i += 2) {
            int id = Integer.parseInt(pairs[i]);
            String distance = pairs[i + 1];
            disciplines.stream().filter(d -> d.getId() == id).findFirst()
                .ifPresent(d -> d.setShootingDistance(distance.isEmpty() ? null : distance));
        }
        dataService.saveDisciplines(disciplines);
    }

    @Test
    void relaysGetSequentialStartTimesAndAreStoredInRelaysJson() throws Exception {
        assertEquals("09:00", relayService.getRelay(relay1).get("start_time"));
        assertEquals("09:10", relayService.getRelay(relay2).get("start_time"));

        relayService.updateConfig(Map.of("relay_duration_min", 15));
        assertEquals("09:15", relayService.getRelay(relay2).get("start_time"));

        relayService.deleteRelay(relay1);
        Map<String, Object> moved = relayService.getRelay(relay2);
        assertEquals(1, moved.get("sequence_no"));
        assertEquals("09:00", moved.get("start_time"));

        assertTrue(Files.readString(tempDir.resolve("relays.json")).contains("\"relay_duration_min\" : 15"));
        assertFalse(Files.readString(tempDir.resolve("data.json")).contains("relay"));
    }

    @Test
    void aRegisteredStartTakesAtMostOneLane() throws Exception {
        Map<String, Object> assignment = assign(relay1, "m25", 1, "1-52-1");
        assertEquals("No 7 Colt (original)", assignment.get("discipline_name"));
        assertEquals("Anna", ((Map<?, ?>) assignment.get("competitor")).get("name"));

        ConflictException conflict = assertThrows(ConflictException.class, () -> assign(relay2, "m25", 1, "1-52-1"));
        assertEquals("This start already has a lane", conflict.getMessage());
        assertFalse(startIds(relayService.getAvailableStarts(relay2, "m25")).contains("1-52-1"));

        // the competitor's other start in the same discipline is still schedulable
        assign(relay2, "m25", 1, "1-52-2");
        assertEquals(2, relayService.getCompetitorSchedule(1).size());
    }

    @Test
    void competitorCanHaveOnlyOneLanePerRelay() throws Exception {
        assign(relay1, "m25", 1, "1-52-1");

        ConflictException conflict = assertThrows(ConflictException.class, () -> assign(relay1, "m50", 3, "1-3-1"));
        assertEquals("Competitor already has a lane in this relay", conflict.getMessage());
        assertFalse(startIds(relayService.getAvailableStarts(relay1, "m50")).contains("1-3-1"));
        assertTrue(startIds(relayService.getAvailableStarts(relay2, "m50")).contains("1-3-1"));
    }

    @Test
    void mappedDisciplinesAreOnlyOfferedOnTheirRange() throws Exception {
        setShootingDistances("52", "m25", "3", "m100");

        assertEquals(List.of("1-52-1", "1-52-2", "2-52-1"), startIds(relayService.getAvailableStarts(relay1, "m25")));
        assertEquals(List.of("1-3-1"), startIds(relayService.getAvailableStarts(relay1, "m100")));
        assertTrue(relayService.getAvailableStarts(relay1, "m50").isEmpty());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> assign(relay1, "m100", 1, "1-52-1"));
        assertEquals("No 7 Colt (original) is set to fire on 25m, not on 100m", error.getMessage());

        // an unmapped discipline stays available everywhere
        setShootingDistances("3", "");
        assertTrue(startIds(relayService.getAvailableStarts(relay1, "m50")).contains("1-3-1"));
    }

    @Test
    void overviewListsScheduledAndUnscheduledStartsAndFlagsRemappedOnes() throws Exception {
        assign(relay1, "m50", 2, "1-3-1");
        setShootingDistances("3", "m100");

        Map<String, Object> overview = relayService.getOverview();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) overview.get("rows");
        Map<String, Object> anna = rows.stream()
            .filter(r -> "Anna".equals(((Map<?, ?>) r.get("competitor")).get("name"))).findFirst().orElseThrow();

        assertEquals(List.of("1-3-1"), startIds(castRows(anna.get("scheduled"))));
        // unscheduled includes individual starts only (team start 1-31-1 is excluded from lane assignment)
        assertTrue(startIds(castRows(anna.get("unscheduled"))).containsAll(List.of("1-52-1", "1-52-2")));
        assertEquals(List.of("No 3 Minie (original) is scheduled on 50m but is set to fire on 100m"), anna.get("issues"));
    }

    @Test
    void assigningReplacesLaneOccupantAndDetectsConcurrentChanges() throws Exception {
        Map<String, Object> anna = assign(relay1, "m50", 4, "1-3-1");

        // The user saw an empty lane, but Anna was put there meanwhile
        Map<String, Object> stale = new HashMap<>(Map.of("relay_id", relay1, "range_id", "m50",
            "lane_no", 4, "start_id", "2-52-1"));
        stale.put("expected_assignment_id", null);
        ConflictException conflict = assertThrows(ConflictException.class, () -> relayService.assign(stale));
        assertEquals(anna.get("id"), ((Map<?, ?>) conflict.getCurrent()).get("id"));

        stale.put("expected_assignment_id", anna.get("id"));
        relayService.assign(stale);
        assertTrue(relayService.getCompetitorSchedule(1).isEmpty());
        assertEquals(1, relayService.getCompetitorSchedule(2).size());
    }

    @Test
    void rejectsLanesOutsideTheRangeAndUnknownStarts() {
        assertThrows(IllegalArgumentException.class, () -> assign(relay1, "m100", 9, "1-52-1"));
        assertThrows(IllegalArgumentException.class, () -> assign(relay1, "m100", 1, "9-9-9"));
        assertThrows(IllegalArgumentException.class, () -> assign(relay1, "m200", 1, "1-52-1"));
    }

    @Test
    void deletingDayRemovesItsRelaysAndAssignments() throws Exception {
        assign(relay1, "m25", 1, "1-52-1");
        String dayId = (String) relayService.getRelay(relay1).get("day_id");

        relayService.deleteDay(dayId);

        assertThrows(RecordNotFoundException.class, () -> relayService.getRelay(relay1));
        assertTrue(relayService.getCompetitorSchedule(1).isEmpty());
    }

    @Test
    void teamDisciplinesAreNotOfferedForLanes() throws Exception {
        List<String> available = startIds(relayService.getAvailableStarts(relay1, "m25"));
        assertFalse(available.contains("1-31-1"), "team discipline starts should not appear");
        assertTrue(available.contains("1-52-1"), "individual discipline starts should appear");
    }

    @Test
    void assignRejectsTeamDisciplines() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> assign(relay1, "m25", 1, "1-31-1"));
        assertTrue(error.getMessage().contains("team discipline"));
    }

    @Test
    void lockingADayDistancePairBlocksAssignmentChangesOnlyForThatPair() throws Exception {
        Map<String, Object> anna = assign(relay1, "m25", 1, "1-52-1");
        relayService.setLock((String) relayService.getRelay(relay1).get("day_id"), "m25", Map.of("locked", true));

        List<Map<String, Object>> blocks = castRows(relayService.getRelay(relay1).get("ranges"));
        Map<String, Object> lockedBlock = blocks.stream().filter(b -> "m25".equals(b.get("id"))).findFirst().orElseThrow();
        assertEquals(true, lockedBlock.get("locked"));

        IllegalArgumentException assignError = assertThrows(IllegalArgumentException.class,
            () -> assign(relay1, "m25", 2, "2-52-1"));
        assertEquals("This day/distance is locked for editing", assignError.getMessage());

        IllegalArgumentException deleteError = assertThrows(IllegalArgumentException.class,
            () -> relayService.deleteAssignment((String) anna.get("id")));
        assertEquals("This day/distance is locked for editing", deleteError.getMessage());

        // other ranges/relays of the same day are unaffected (relay2 is on the same day as relay1)
        assign(relay2, "m50", 1, "1-3-1");

        relayService.setLock((String) relayService.getRelay(relay1).get("day_id"), "m25", Map.of("locked", false));
        assign(relay1, "m25", 2, "2-52-1");
    }

    @Test
    void lockingAnyRangeOfADayBlocksAddingOrDeletingRelaysAndDeletingTheDay() throws Exception {
        String dayId = (String) relayService.getRelay(relay1).get("day_id");
        relayService.setLock(dayId, "m50", Map.of("locked", true));

        assertThrows(IllegalArgumentException.class, () -> relayService.addRelays(dayId, Map.of("count", 1)));
        assertThrows(IllegalArgumentException.class, () -> relayService.deleteRelay(relay1));
        assertThrows(IllegalArgumentException.class, () -> relayService.deleteDay(dayId));

        // day date/time can still be edited while locked
        relayService.updateDay(dayId, Map.of("date", "2026-10-04", "start_time", "10:00"));

        relayService.setLock(dayId, "m50", Map.of("locked", false));
        relayService.addRelays(dayId, Map.of("count", 1));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castRows(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
