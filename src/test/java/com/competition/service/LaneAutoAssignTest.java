package com.competition.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class LaneAutoAssignTest {

    @TempDir
    Path tempDir;

    private RelayService relayService;
    private String dayId;

    // 1 Miquelet original, 11 reproduction, 21 combined: all m50; 2 Maximilian original: m100;
    // 40 Free has no range; 31 is a team discipline
    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(tempDir.resolve("disciplines.json"), "["
            + discipline(1, "individual", "original", "Miquelet", "m50") + ","
            + discipline(2, "individual", "original", "Maximilian", "m100") + ","
            + discipline(11, "individual", "reproduction", "Miquelet", "m50") + ","
            + discipline(21, "individual", "combined", "Miquelet", "m50") + ","
            + discipline(31, "team", "original", "Gustav Adolph", null) + ","
            + discipline(40, "individual", "original", "Free", null) + "]");

        StringBuilder competitors = new StringBuilder();
        for (int id = 1; id <= 4; id++) {
            if (id > 1) competitors.append(",");
            StringBuilder starts = new StringBuilder();
            for (int discipline : new int[] {1, 11, 21}) {
                starts.append(starts.length() > 0 ? "," : "").append("\"").append(discipline).append("\":[")
                    .append(start(id, discipline)).append("]");
            }
            if (id == 1) {
                starts.append(",\"2\":[").append(start(1, 2)).append("],\"31\":[").append(start(1, 31)).append("]");
            }
            competitors.append("{\"id\":").append(id).append(",\"name\":\"C").append(id)
                .append("\",\"starts\":{").append(starts).append("}}");
        }
        Files.writeString(tempDir.resolve("data.json"),
            "{\"competitors\":[" + competitors + "],\"results\":[],\"disciplines\":[],\"teams\":[]}");

        DataService dataService = new DataService(tempDir.resolve("data.json").toString(),
            tempDir.resolve("disciplines.json").toString(), tempDir.resolve("competition.json").toString());
        relayService = new RelayService(tempDir.resolve("relays.json").toString(), dataService);

        relayService.setConfigLock(Map.of("locked", false));
        relayService.updateRange("m25", Map.of("name", "25m", "lane_count", 1));
        relayService.updateRange("m50", Map.of("name", "50m", "lane_count", 2));
        relayService.updateRange("m100", Map.of("name", "100m", "lane_count", 2));
        relayService.setConfigLock(Map.of("locked", true));
        dayId = (String) relayService.createDay(Map.of("date", "2026-10-03", "start_time", "09:00")).get("id");
    }

    private static String discipline(int id, String level, String type, String event, String distance) {
        return "{\"id\":" + id + ",\"category\":\"rifle\",\"level\":\"" + level + "\",\"type\":\"" + type
            + "\",\"event\":\"" + event + "\"" + (distance != null ? ",\"shooting_distance\":\"" + distance + "\"" : "") + "}";
    }

    private static String start(int competitor, int discipline) {
        return "{\"generated_id\":\"" + competitor + "-" + discipline + "-1\",\"start_number\":1,\"discipline_id\":"
            + discipline + ",\"status\":\"registered\"}";
    }

    private void relays(int count) throws Exception {
        relayService.addRelays(dayId, Map.of("count", count));
    }

    private void enable() throws Exception {
        relayService.setAutoAssignEnabled(Map.of("enabled", true));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> assignments() throws Exception {
        return (List<Map<String, Object>>) relayService.getAll().get("assignments");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> sequenceOfRelay() throws Exception {
        Map<String, Integer> sequence = new java.util.HashMap<>();
        ((List<Map<String, Object>>) relayService.getAll().get("relays"))
            .forEach(r -> sequence.put((String) r.get("id"), (Integer) r.get("sequence_no")));
        return sequence;
    }

    /** discipline id -> the relay numbers it occupies */
    private Map<Integer, TreeSet<Integer>> relaysByDiscipline() throws Exception {
        Map<String, Integer> sequence = sequenceOfRelay();
        Map<Integer, TreeSet<Integer>> result = new java.util.HashMap<>();
        for (Map<String, Object> a : assignments()) {
            int discipline = Integer.parseInt(((String) a.get("start_id")).split("-")[1]);
            result.computeIfAbsent(discipline, k -> new TreeSet<>()).add(sequence.get((String) a.get("relay_id")));
        }
        return result;
    }

    @Test
    void isRefusedWhileTheToggleIsOff() throws Exception {
        relays(3);
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) relayService.getAll().get("config")).get("auto_assign_enabled")));
        assertThrows(IllegalArgumentException.class, () -> relayService.autoAssign(Map.of()));
        assertTrue(assignments().isEmpty());
    }

    @Test
    void needsRelaysOnAnUnlockedDay() throws Exception {
        enable();
        assertThrows(IllegalArgumentException.class, () -> relayService.autoAssign(Map.of()));
        relays(1);
        relayService.setDayLock(dayId, Map.of("locked", true));
        assertThrows(IllegalArgumentException.class, () -> relayService.autoAssign(Map.of()));
    }

    @Test
    void keepsEveryHardRule() throws Exception {
        relays(20);
        enable();
        Map<String, Object> result = relayService.autoAssign(Map.of());

        // 4 x (1, 11, 21) + 1-2-1; the team start 1-31-1 is never placed
        assertEquals(13, result.get("assigned"));
        assertTrue(((List<?>) result.get("unplaced")).isEmpty());

        Set<String> starts = new HashSet<>();
        Set<String> competitorInRelay = new HashSet<>();
        Set<String> lanes = new HashSet<>();
        Map<String, Integer> laneCount = Map.of("m25", 1, "m50", 2, "m100", 2);
        for (Map<String, Object> a : assignments()) {
            String startId = (String) a.get("start_id");
            int discipline = Integer.parseInt(startId.split("-")[1]);
            assertTrue(starts.add(startId), "start has one lane: " + startId);
            assertNotEquals(31, discipline, "team discipline");
            assertTrue(competitorInRelay.add(startId.split("-")[0] + "@" + a.get("relay_id")),
                "competitor twice in one relay: " + startId);
            assertTrue(lanes.add(a.get("relay_id") + "/" + a.get("range_id") + "/" + a.get("lane_no")), "lane used twice");
            assertTrue((Integer) a.get("lane_no") >= 1 && (Integer) a.get("lane_no") <= laneCount.get((String) a.get("range_id")));
            if (discipline == 2) assertEquals("m100", a.get("range_id"));
            if (discipline == 1 || discipline == 11 || discipline == 21) assertEquals("m50", a.get("range_id"));
        }
    }

    @Test
    void placesADisciplineContiguouslyAndOriginalReproductionCombinedBackToBack() throws Exception {
        relays(20);
        enable();
        relayService.autoAssign(Map.of());

        Map<Integer, TreeSet<Integer>> byDiscipline = relaysByDiscipline();
        // 2 lanes on 50m, 4 starters each: original, reproduction, combined take two relays each, in a row
        assertEquals(new TreeSet<>(List.of(1, 2)), byDiscipline.get(1));
        assertEquals(new TreeSet<>(List.of(3, 4)), byDiscipline.get(11));
        assertEquals(new TreeSet<>(List.of(5, 6)), byDiscipline.get(21));
    }

    @Test
    void aCompetitorWithTwoStartsInOneRelayWaitsForTheNext() throws Exception {
        relays(20);
        enable();
        relayService.autoAssign(Map.of());
        // competitor 1 shoots 1, 11, 21 (m50) and 2 (m100); never twice in the same relay
        Map<String, Integer> sequence = sequenceOfRelay();
        List<Integer> relaysOfCompetitor1 = new ArrayList<>();
        for (Map<String, Object> a : assignments()) {
            if (((String) a.get("start_id")).startsWith("1-")) {
                relaysOfCompetitor1.add(sequence.get((String) a.get("relay_id")));
            }
        }
        assertEquals(4, relaysOfCompetitor1.size());
        assertEquals(4, new HashSet<>(relaysOfCompetitor1).size());
    }

    @Test
    void reportsStartsThatDoNotFitInsteadOfBreakingARule() throws Exception {
        relays(2); // room for 4 starts on 50m only
        enable();
        Map<String, Object> result = relayService.autoAssign(Map.of());

        List<?> unplaced = (List<?>) result.get("unplaced");
        assertFalse(unplaced.isEmpty());
        assertEquals(assignments().size(), result.get("assigned"));
        assertEquals(13, assignments().size() + unplaced.size());
    }

    @Test
    void keepsExistingLanesUnlessToldToReplaceThem() throws Exception {
        relays(20);
        enable();
        String relay1 = firstRelay();
        relayService.assign(Map.of("relay_id", relay1, "range_id", "m50", "lane_no", 2, "start_id", "4-21-1"));

        Map<String, Object> filled = relayService.autoAssign(Map.of());
        assertEquals(12, filled.get("assigned")); // 13 starts in all, one was already placed
        assertTrue(assignments().stream().anyMatch(a -> "4-21-1".equals(a.get("start_id"))
            && relay1.equals(a.get("relay_id")) && Integer.valueOf(2).equals(a.get("lane_no"))));

        Map<String, Object> replaced = relayService.autoAssign(Map.of("replace", true));
        assertEquals(13, replaced.get("cleared"));
        assertEquals(13, replaced.get("assigned"));
        assertEquals(13, assignments().size());
    }

    @Test
    void usesTheLaneNumbersOfARangeThatDoesNotStartAtOne() throws Exception {
        relayService.setConfigLock(Map.of("locked", false));
        relayService.updateRange("m50", Map.of("name", "50m", "lane_count", 2, "first_lane_no", 16));
        relayService.setConfigLock(Map.of("locked", true));
        relays(20);
        enable();
        String relay1 = firstRelay();
        relayService.assign(Map.of("relay_id", relay1, "range_id", "m50", "lane_no", 17, "start_id", "4-21-1"));

        relayService.autoAssign(Map.of());

        for (Map<String, Object> a : assignments()) {
            if ("m50".equals(a.get("range_id"))) {
                assertTrue((Integer) a.get("lane_no") >= 16 && (Integer) a.get("lane_no") <= 17, "lane " + a.get("lane_no"));
            }
        }
        // lane 17 of relay 1 was taken, so only lane 16 is left there
        assertEquals(2, assignments().stream().filter(a -> relay1.equals(a.get("relay_id")) && "m50".equals(a.get("range_id"))).count());
        // and every lane shows in the relay view
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) relayService.getRelay(relay1).get("ranges");
        long shown = blocks.stream().filter(b -> "m50".equals(b.get("id")))
            .flatMap(b -> ((List<Map<String, Object>>) b.get("lanes")).stream()).filter(l -> l.get("assignment") != null).count();
        assertEquals(2, shown);
    }

    @Test
    void neverTouchesLockedDays() throws Exception {
        relays(1);
        String lockedRelay = firstRelay();
        relayService.assign(Map.of("relay_id", lockedRelay, "range_id", "m100", "lane_no", 1, "start_id", "1-2-1"));
        relayService.setDayLock(dayId, Map.of("locked", true));
        String openDay = (String) relayService.createDay(Map.of("date", "2026-10-04", "start_time", "09:00")).get("id");
        relayService.addRelays(openDay, Map.of("count", 20));
        enable();

        Map<String, Object> result = relayService.autoAssign(Map.of("replace", true));

        assertEquals(12, result.get("assigned")); // 1-2-1 stays on the locked day
        assertEquals(0, result.get("cleared"));
        assertEquals(1, assignments().stream().filter(a -> lockedRelay.equals(a.get("relay_id"))).count());
        assertTrue(assignments().stream().noneMatch(a -> lockedRelay.equals(a.get("relay_id")) && !"1-2-1".equals(a.get("start_id"))));
    }

    @Test
    void placesADisciplineWithoutARangeOnOneRangeAsAWhole() throws Exception {
        // competitors 1..4 get a start in discipline 40, which has no range
        String data = Files.readString(tempDir.resolve("data.json"));
        for (int id = 1; id <= 4; id++) {
            data = data.replace("{\"id\":" + id + ",\"name\":\"C" + id + "\",\"starts\":{",
                "{\"id\":" + id + ",\"name\":\"C" + id + "\",\"starts\":{\"40\":[" + start(id, 40) + "],");
        }
        Files.writeString(tempDir.resolve("data.json"), data);
        relays(30);
        enable();
        relayService.autoAssign(Map.of());

        Set<Object> ranges = new HashSet<>();
        for (Map<String, Object> a : assignments()) {
            if (((String) a.get("start_id")).contains("-40-")) ranges.add(a.get("range_id"));
        }
        assertEquals(1, ranges.size());
        assertEquals(17, assignments().size());
    }

    @SuppressWarnings("unchecked")
    private String firstRelay() throws Exception {
        return (String) ((List<Map<String, Object>>) relayService.getAll().get("relays")).get(0).get("id");
    }
}
