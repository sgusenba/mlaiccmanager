package com.competition.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure rules of the relay management: assignment conflicts and the schedule
 * of a day. Works on the plain maps stored in relays.json.
 */
public final class RelayRules {
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private RelayRules() {
    }

    /**
     * Returns why the start cannot take a lane in the given relay, or null if
     * it can. competitorOfStart resolves an assignment's start to its
     * competitor; ignoreAssignmentId is left out of the check (the assignment
     * being replaced).
     */
    public static String checkConflict(String startId, int competitorId, String relayId,
                                       List<Map<String, Object>> assignments,
                                       Map<String, Integer> competitorOfStart, String ignoreAssignmentId) {
        for (Map<String, Object> a : assignments) {
            if (Objects.equals(a.get("id"), ignoreAssignmentId)) {
                continue;
            }
            // Rule 1: a registered start is shot once, so it takes at most one lane
            if (startId.equals(a.get("start_id"))) {
                return "This start already has a lane";
            }
            // Rule 2: one lane per relay, across all ranges — all ranges fire at the same time
            if (relayId.equals(a.get("relay_id"))
                && Objects.equals(competitorOfStart.get((String) a.get("start_id")), competitorId)) {
                return "Competitor already has a lane in this relay";
            }
        }
        return null;
    }

    /**
     * Numbers the day's relays 1..n in their current order and sets each start
     * time from the day's start time and the meet-wide relay duration and
     * break between relays.
     */
    public static void recomputeDaySchedule(Map<String, Object> day, List<Map<String, Object>> relaysOfDay,
                                            int relayDurationMin, int breakMin) {
        relaysOfDay.sort(Comparator.comparingInt(r -> intOf(r.get("sequence_no"))));
        LocalTime time = LocalTime.parse((String) day.get("start_time"), TIME_FORMAT);
        int stepMin = relayDurationMin + breakMin;
        int sequenceNo = 1;
        for (Map<String, Object> relay : relaysOfDay) {
            relay.put("sequence_no", sequenceNo++);
            relay.put("start_time", time.format(TIME_FORMAT));
            time = time.plusMinutes(stepMin);
        }
    }

    static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
