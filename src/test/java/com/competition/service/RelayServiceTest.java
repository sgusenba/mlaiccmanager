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
    private String relay1;
    private String relay2;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[{\"id\":1,\"name\":\"Anna\"},{\"id\":2,\"name\":\"Ben\"}],"
                + "\"results\":[],\"disciplines\":[],\"teams\":[],\"active_disciplines\":[]}");
        DataService dataService = new DataService(tempDir.resolve("data.json").toString(), tempDir.resolve("disciplines.json").toString());
        relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);

        Map<String, Object> day = relayService.createDay(Map.of("date", "2026-10-03", "start_time", "09:00"));
        List<Map<String, Object>> relays = relayService.addRelays((String) day.get("id"), Map.of("count", 2));
        relay1 = (String) relays.get(0).get("id");
        relay2 = (String) relays.get(1).get("id");
    }

    private Map<String, Object> assign(String relayId, String disciplineId, int lane, int competitorId) throws Exception {
        return relayService.assign(Map.of("relay_id", relayId, "discipline_id", disciplineId,
            "lane_no", lane, "competitor_id", competitorId));
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
    void competitorCanHaveOnlyOneStartPerDiscipline() throws Exception {
        assign(relay1, "d25", 1, 1);

        ConflictException conflict = assertThrows(ConflictException.class, () -> assign(relay2, "d25", 1, 1));
        assertEquals("Competitor already has a start in this discipline", conflict.getMessage());
        assertFalse(ids(relayService.getAvailableCompetitors(relay2, "d25")).contains(1));
        assertTrue(ids(relayService.getAvailableCompetitors(relay2, "d50")).contains(1));
    }

    @Test
    void competitorCanHaveOnlyOneLanePerRelay() throws Exception {
        assign(relay1, "d25", 1, 1);

        ConflictException conflict = assertThrows(ConflictException.class, () -> assign(relay1, "d100", 3, 1));
        assertEquals("Competitor already has a lane in this relay", conflict.getMessage());
        assertFalse(ids(relayService.getAvailableCompetitors(relay1, "d100")).contains(1));

        assign(relay2, "d100", 3, 1);
        assertEquals(2, relayService.getCompetitorSchedule(1).size());
    }

    @Test
    void assigningReplacesLaneOccupantAndDetectsConcurrentChanges() throws Exception {
        Map<String, Object> anna = assign(relay1, "d50", 4, 1);

        // The user saw an empty lane, but Anna was put there meanwhile
        Map<String, Object> stale = new HashMap<>(Map.of("relay_id", relay1, "discipline_id", "d50", "lane_no", 4, "competitor_id", 2));
        stale.put("expected_assignment_id", null);
        ConflictException conflict = assertThrows(ConflictException.class, () -> relayService.assign(stale));
        assertEquals(anna.get("id"), ((Map<?, ?>) conflict.getCurrent()).get("id"));

        stale.put("expected_assignment_id", anna.get("id"));
        relayService.assign(stale);
        assertTrue(relayService.getCompetitorSchedule(1).isEmpty());
        assertEquals(1, relayService.getCompetitorSchedule(2).size());
    }

    @Test
    void rejectsLanesOutsideTheDiscipline() {
        assertThrows(IllegalArgumentException.class, () -> assign(relay1, "d100", 9, 1));
        assertThrows(IllegalArgumentException.class, () -> assign(relay1, "d100", 1, 99));
    }

    @Test
    void deletingDayRemovesItsRelaysAndAssignments() throws Exception {
        assign(relay1, "d25", 1, 1);
        String dayId = (String) relayService.getRelay(relay1).get("day_id");

        relayService.deleteDay(dayId);

        assertThrows(RecordNotFoundException.class, () -> relayService.getRelay(relay1));
        assertTrue(relayService.getCompetitorSchedule(1).isEmpty());
    }

    private static List<Integer> ids(List<Map<String, Object>> competitors) {
        return competitors.stream().map(c -> ((Number) c.get("id")).intValue()).toList();
    }
}
