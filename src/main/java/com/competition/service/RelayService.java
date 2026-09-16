package com.competition.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Relay management: meet days, relays (heats) and lane assignments. Everything
 * lives in relays.json, separate from data.json; competitors are only read
 * from data.json and referenced by id.
 */
public class RelayService {
    private static final Logger logger = LoggerFactory.getLogger(RelayService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static final int DEFAULT_RELAY_DURATION_MIN = 10;

    // Same approach as DataService: one lock serializes every access to
    // relays.json. Lock order is always relays.json first, then data.json
    // (read only), so the two locks cannot deadlock.
    private final ReentrantLock lock = new ReentrantLock();

    private final String relaysFilePath;
    private final DataService dataService;

    @FunctionalInterface
    private interface RelayFunction<T> {
        T apply(Map<String, Object> relays) throws Exception;
    }

    public RelayService(String relaysFilePath, DataService dataService) {
        this.relaysFilePath = relaysFilePath;
        this.dataService = dataService;
    }

    /** The whole relays.json: config, disciplines, days, relays and assignments. */
    public Map<String, Object> getAll() throws Exception {
        return read(relays -> relays);
    }

    public Map<String, Object> updateConfig(Map<String, Object> request) throws Exception {
        int duration = requireInt(request, "relay_duration_min");
        if (duration < 1 || duration > 24 * 60) {
            throw new IllegalArgumentException("relay_duration_min must be between 1 and 1440");
        }
        return update(relays -> {
            configOf(relays).put("relay_duration_min", duration);
            for (Map<String, Object> day : listOf(relays, "days")) {
                recompute(relays, day);
            }
            return configOf(relays);
        });
    }

    public Map<String, Object> createDay(Map<String, Object> request) throws Exception {
        String date = requireDate(request);
        String startTime = requireTime(request);
        return update(relays -> {
            List<Map<String, Object>> days = listOf(relays, "days");
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("id", nextId(days, "day"));
            day.put("date", date);
            day.put("start_time", startTime);
            days.add(day);
            days.sort(Comparator.comparing((Map<String, Object> d) -> (String) d.get("date"))
                .thenComparing(d -> (String) d.get("start_time")));
            return day;
        });
    }

    public Map<String, Object> updateDay(String dayId, Map<String, Object> request) throws Exception {
        String date = requireDate(request);
        String startTime = requireTime(request);
        return update(relays -> {
            Map<String, Object> day = findDay(relays, dayId);
            day.put("date", date);
            day.put("start_time", startTime);
            recompute(relays, day);
            return day;
        });
    }

    /** Deletes the day together with its relays and their assignments. */
    public void deleteDay(String dayId) throws Exception {
        update(relays -> {
            findDay(relays, dayId);
            List<Map<String, Object>> relayList = listOf(relays, "relays");
            List<Object> relayIds = new ArrayList<>();
            for (Map<String, Object> relay : relayList) {
                if (dayId.equals(relay.get("day_id"))) {
                    relayIds.add(relay.get("id"));
                }
            }
            relayList.removeIf(r -> relayIds.contains(r.get("id")));
            listOf(relays, "assignments").removeIf(a -> relayIds.contains(a.get("relay_id")));
            listOf(relays, "days").removeIf(d -> dayId.equals(d.get("id")));
            return null;
        });
    }

    /** Appends count relays to the end of the day. No maximum per day. */
    public List<Map<String, Object>> addRelays(String dayId, Map<String, Object> request) throws Exception {
        int count = request != null && request.get("count") != null ? requireInt(request, "count") : 1;
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("count must be between 1 and 100");
        }
        return update(relays -> {
            Map<String, Object> day = findDay(relays, dayId);
            List<Map<String, Object>> relayList = listOf(relays, "relays");
            int sequenceNo = relaysOfDay(relays, dayId).size();
            List<Map<String, Object>> created = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Map<String, Object> relay = new LinkedHashMap<>();
                relay.put("id", nextId(relayList, "r"));
                relay.put("day_id", dayId);
                relay.put("sequence_no", ++sequenceNo);
                relay.put("start_time", null);
                relayList.add(relay);
                created.add(relay);
            }
            recompute(relays, day);
            return created;
        });
    }

    /** Deletes the relay and its assignments; later relays of the day move up. */
    public void deleteRelay(String relayId) throws Exception {
        update(relays -> {
            Map<String, Object> relay = findRelay(relays, relayId);
            listOf(relays, "relays").remove(relay);
            listOf(relays, "assignments").removeIf(a -> relayId.equals(a.get("relay_id")));
            Map<String, Object> day = find(listOf(relays, "days"), relay.get("day_id"));
            if (day != null) {
                recompute(relays, day);
            }
            return null;
        });
    }

    /** The relay with its day and one lane block per discipline. */
    public Map<String, Object> getRelay(String relayId) throws Exception {
        return read(relays -> {
            Map<String, Object> relay = findRelay(relays, relayId);
            Map<Integer, Map<String, Object>> competitors = competitorsById();

            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Map<String, Object> discipline : listOf(relays, "disciplines")) {
                List<Map<String, Object>> lanes = new ArrayList<>();
                int laneCount = RelayRules.intOf(discipline.get("lane_count"));
                for (int laneNo = 1; laneNo <= laneCount; laneNo++) {
                    Map<String, Object> assignment = findLane(relays, relayId, (String) discipline.get("id"), laneNo);
                    Map<String, Object> lane = new LinkedHashMap<>();
                    lane.put("lane_no", laneNo);
                    lane.put("assignment", assignment != null ? withCompetitor(assignment, competitors) : null);
                    lanes.add(lane);
                }
                Map<String, Object> block = new LinkedHashMap<>(discipline);
                block.put("lanes", lanes);
                blocks.add(block);
            }

            Map<String, Object> detail = new LinkedHashMap<>(relay);
            detail.put("day", findDay(relays, (String) relay.get("day_id")));
            detail.put("relay_duration_min", relayDuration(relays));
            detail.put("disciplines", blocks);
            return detail;
        });
    }

    /** Competitors that may take a lane in this relay and discipline without breaking a rule. */
    public List<Map<String, Object>> getAvailableCompetitors(String relayId, String disciplineId) throws Exception {
        return read(relays -> {
            findRelay(relays, relayId);
            findDiscipline(relays, disciplineId);
            List<Map<String, Object>> assignments = listOf(relays, "assignments");
            List<Map<String, Object>> available = new ArrayList<>();
            for (Map<String, Object> competitor : competitorsById().values()) {
                int competitorId = RelayRules.intOf(competitor.get("id"));
                if (RelayRules.checkConflict(competitorId, relayId, disciplineId, assignments, null) == null) {
                    available.add(competitorSummary(competitor));
                }
            }
            return available;
        });
    }

    /**
     * Puts a competitor into a lane, replacing whoever is in it. If the request
     * contains "expected_assignment_id" (the lane occupant the user saw, null for
     * an empty lane) and the lane changed meanwhile, a ConflictException carries
     * the current occupant.
     */
    public Map<String, Object> assign(Map<String, Object> request) throws Exception {
        String relayId = requireString(request, "relay_id");
        String disciplineId = requireString(request, "discipline_id");
        int laneNo = requireInt(request, "lane_no");
        int competitorId = requireInt(request, "competitor_id");

        return update(relays -> {
            findRelay(relays, relayId);
            Map<String, Object> discipline = findDiscipline(relays, disciplineId);
            int laneCount = RelayRules.intOf(discipline.get("lane_count"));
            if (laneNo < 1 || laneNo > laneCount) {
                throw new IllegalArgumentException("Lane must be between 1 and " + laneCount + " for " + discipline.get("name"));
            }
            Map<Integer, Map<String, Object>> competitors = competitorsById();
            if (!competitors.containsKey(competitorId)) {
                throw new IllegalArgumentException("Competitor not found");
            }

            Map<String, Object> current = findLane(relays, relayId, disciplineId, laneNo);
            String currentId = current != null ? (String) current.get("id") : null;
            if (request.containsKey("expected_assignment_id")
                && !Objects.equals(request.get("expected_assignment_id"), currentId)) {
                throw new ConflictException("Lane was changed by someone else",
                    current != null ? withCompetitor(current, competitors) : null);
            }
            if (current != null && RelayRules.intOf(current.get("competitor_id")) == competitorId) {
                return withCompetitor(current, competitors);
            }

            List<Map<String, Object>> assignments = listOf(relays, "assignments");
            String conflict = RelayRules.checkConflict(competitorId, relayId, disciplineId, assignments, currentId);
            if (conflict != null) {
                throw new ConflictException(conflict, null);
            }

            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put("id", nextId(assignments, "a"));
            assignment.put("relay_id", relayId);
            assignment.put("discipline_id", disciplineId);
            assignment.put("lane_no", laneNo);
            assignment.put("competitor_id", competitorId);
            if (current != null) {
                assignments.remove(current);
            }
            assignments.add(assignment);
            return withCompetitor(assignment, competitors);
        });
    }

    public void deleteAssignment(String assignmentId) throws Exception {
        update(relays -> {
            if (!listOf(relays, "assignments").removeIf(a -> assignmentId.equals(a.get("id")))) {
                throw new RecordNotFoundException("Assignment not found");
            }
            return null;
        });
    }

    /** All lanes of one competitor over the whole meet, in time order. */
    public List<Map<String, Object>> getCompetitorSchedule(int competitorId) throws Exception {
        return read(relays -> {
            List<Map<String, Object>> schedule = new ArrayList<>();
            for (Map<String, Object> assignment : listOf(relays, "assignments")) {
                if (RelayRules.intOf(assignment.get("competitor_id")) == competitorId) {
                    schedule.add(scheduleEntry(relays, assignment));
                }
            }
            schedule.sort(SCHEDULE_ORDER);
            return schedule;
        });
    }

    /**
     * Every competitor with their lanes per discipline, plus any rule
     * violations found in the stored data (safety net: e.g. a hand-edited
     * relays.json or a competitor deleted from data.json).
     */
    public Map<String, Object> getOverview() throws Exception {
        return read(relays -> {
            Map<Integer, Map<String, Object>> competitors = competitorsById();
            List<Map<String, Object>> assignments = listOf(relays, "assignments");

            Map<Integer, List<Map<String, Object>>> byCompetitor = new LinkedHashMap<>();
            for (Integer id : competitors.keySet()) {
                byCompetitor.put(id, new ArrayList<>());
            }
            for (Map<String, Object> assignment : assignments) {
                byCompetitor.computeIfAbsent(RelayRules.intOf(assignment.get("competitor_id")), k -> new ArrayList<>())
                    .add(scheduleEntry(relays, assignment));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map.Entry<Integer, List<Map<String, Object>>> entry : byCompetitor.entrySet()) {
                Map<String, Object> competitor = competitors.get(entry.getKey());
                List<Map<String, Object>> entries = entry.getValue();
                entries.sort(SCHEDULE_ORDER);

                List<String> issues = new ArrayList<>();
                if (competitor == null) {
                    issues.add("Competitor #" + entry.getKey() + " no longer exists");
                }
                Map<Object, Integer> perDiscipline = new HashMap<>();
                Map<Object, Integer> perRelay = new HashMap<>();
                for (Map<String, Object> e : entries) {
                    perDiscipline.merge(e.get("discipline_id"), 1, Integer::sum);
                    perRelay.merge(e.get("relay_id"), 1, Integer::sum);
                    if (find(listOf(relays, "relays"), e.get("relay_id")) == null
                        || find(listOf(relays, "disciplines"), e.get("discipline_id")) == null) {
                        issues.add("Assignment " + e.get("assignment_id") + " points to a missing relay or discipline");
                    }
                }
                perDiscipline.forEach((d, n) -> {
                    if (n > 1) issues.add(n + " starts in discipline " + disciplineName(relays, d));
                });
                perRelay.forEach((r, n) -> {
                    if (n > 1) issues.add(n + " lanes in the same relay");
                });

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("competitor", competitor != null
                    ? competitorSummary(competitor)
                    : Map.of("id", entry.getKey(), "name", "Unknown competitor"));
                row.put("assignments", entries);
                row.put("issues", issues);
                rows.add(row);
            }

            // Two assignments in the same lane can only come from editing the file by hand
            List<String> laneIssues = new ArrayList<>();
            Map<String, Integer> laneUse = new HashMap<>();
            for (Map<String, Object> a : assignments) {
                laneUse.merge(a.get("relay_id") + "/" + a.get("discipline_id") + "/" + a.get("lane_no"), 1, Integer::sum);
            }
            laneUse.forEach((lane, n) -> {
                if (n > 1) laneIssues.add("Lane " + lane + " is assigned " + n + " times");
            });

            Map<String, Object> overview = new LinkedHashMap<>();
            overview.put("disciplines", listOf(relays, "disciplines"));
            overview.put("rows", rows);
            overview.put("lane_issues", laneIssues);
            return overview;
        });
    }

    // --- helpers -----------------------------------------------------------

    private static final Comparator<Map<String, Object>> SCHEDULE_ORDER = Comparator
        .comparing((Map<String, Object> e) -> Objects.toString(e.get("date"), ""))
        .thenComparing(e -> Objects.toString(e.get("start_time"), ""));

    private Map<String, Object> scheduleEntry(Map<String, Object> relays, Map<String, Object> assignment) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("assignment_id", assignment.get("id"));
        entry.put("relay_id", assignment.get("relay_id"));
        entry.put("discipline_id", assignment.get("discipline_id"));
        entry.put("discipline_name", disciplineName(relays, assignment.get("discipline_id")));
        entry.put("lane_no", assignment.get("lane_no"));
        Map<String, Object> relay = find(listOf(relays, "relays"), assignment.get("relay_id"));
        Map<String, Object> day = relay != null ? find(listOf(relays, "days"), relay.get("day_id")) : null;
        entry.put("sequence_no", relay != null ? relay.get("sequence_no") : null);
        entry.put("start_time", relay != null ? relay.get("start_time") : null);
        entry.put("date", day != null ? day.get("date") : null);
        return entry;
    }

    private static String disciplineName(Map<String, Object> relays, Object disciplineId) {
        Map<String, Object> discipline = find(listOf(relays, "disciplines"), disciplineId);
        return discipline != null ? (String) discipline.get("name") : String.valueOf(disciplineId);
    }

    private void recompute(Map<String, Object> relays, Map<String, Object> day) {
        RelayRules.recomputeDaySchedule(day, relaysOfDay(relays, (String) day.get("id")), relayDuration(relays));
    }

    private static int relayDuration(Map<String, Object> relays) {
        return RelayRules.intOf(configOf(relays).get("relay_duration_min"));
    }

    // Returns the day's relay maps themselves (not copies), so changes are saved
    private static List<Map<String, Object>> relaysOfDay(Map<String, Object> relays, String dayId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> relay : listOf(relays, "relays")) {
            if (dayId.equals(relay.get("day_id"))) {
                result.add(relay);
            }
        }
        return result;
    }

    private static Map<String, Object> findLane(Map<String, Object> relays, String relayId, String disciplineId, int laneNo) {
        for (Map<String, Object> a : listOf(relays, "assignments")) {
            if (relayId.equals(a.get("relay_id")) && disciplineId.equals(a.get("discipline_id"))
                && RelayRules.intOf(a.get("lane_no")) == laneNo) {
                return a;
            }
        }
        return null;
    }

    private static Map<String, Object> findDay(Map<String, Object> relays, String dayId) {
        Map<String, Object> day = find(listOf(relays, "days"), dayId);
        if (day == null) {
            throw new RecordNotFoundException("Day not found");
        }
        return day;
    }

    private static Map<String, Object> findRelay(Map<String, Object> relays, String relayId) {
        Map<String, Object> relay = find(listOf(relays, "relays"), relayId);
        if (relay == null) {
            throw new RecordNotFoundException("Relay not found");
        }
        return relay;
    }

    private static Map<String, Object> findDiscipline(Map<String, Object> relays, String disciplineId) {
        Map<String, Object> discipline = find(listOf(relays, "disciplines"), disciplineId);
        if (discipline == null) {
            throw new IllegalArgumentException("Unknown discipline " + disciplineId);
        }
        return discipline;
    }

    private static Map<String, Object> find(List<Map<String, Object>> items, Object id) {
        for (Map<String, Object> item : items) {
            if (Objects.equals(item.get("id"), id)) {
                return item;
            }
        }
        return null;
    }

    /** prefix + (highest existing number + 1), e.g. "r12". */
    private static String nextId(List<Map<String, Object>> items, String prefix) {
        int max = 0;
        for (Map<String, Object> item : items) {
            Object id = item.get("id");
            if (id instanceof String s && s.startsWith(prefix)) {
                try {
                    max = Math.max(max, Integer.parseInt(s.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // hand-written id, cannot collide with a generated one
                }
            }
        }
        return prefix + (max + 1);
    }

    private Map<Integer, Map<String, Object>> competitorsById() throws Exception {
        return dataService.read(data -> {
            Map<Integer, Map<String, Object>> byId = new LinkedHashMap<>();
            if (data.get("competitors") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> competitor) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> c = (Map<String, Object>) competitor;
                        byId.put(RelayRules.intOf(c.get("id")), c);
                    }
                }
            }
            return byId;
        });
    }

    private static Map<String, Object> competitorSummary(Map<String, Object> competitor) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", competitor.get("id"));
        summary.put("name", competitor.get("name"));
        summary.put("club", competitor.get("club"));
        return summary;
    }

    private static Map<String, Object> withCompetitor(Map<String, Object> assignment, Map<Integer, Map<String, Object>> competitors) {
        Map<String, Object> result = new LinkedHashMap<>(assignment);
        Map<String, Object> competitor = competitors.get(RelayRules.intOf(assignment.get("competitor_id")));
        result.put("competitor", competitor != null ? competitorSummary(competitor) : null);
        return result;
    }

    private static String requireString(Map<String, Object> request, String key) {
        Object value = request != null ? request.get(key) : null;
        if (!(value instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return s;
    }

    private static int requireInt(Map<String, Object> request, String key) {
        Object value = request != null ? request.get(key) : null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the error below
            }
        }
        throw new IllegalArgumentException(key + " must be a number");
    }

    private static String requireDate(Map<String, Object> request) {
        String date = requireString(request, "date");
        try {
            return LocalDate.parse(date).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("date must be YYYY-MM-DD");
        }
    }

    private static String requireTime(Map<String, Object> request) {
        String time = requireString(request, "start_time");
        try {
            return LocalTime.parse(time, RelayRules.TIME_FORMAT).format(RelayRules.TIME_FORMAT);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("start_time must be HH:mm");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> configOf(Map<String, Object> relays) {
        return (Map<String, Object>) relays.get("config");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> relays, String key) {
        return (List<Map<String, Object>>) relays.get(key);
    }

    // --- storage -----------------------------------------------------------

    private <T> T read(RelayFunction<T> fn) throws Exception {
        lock.lock();
        try {
            return fn.apply(load());
        } finally {
            lock.unlock();
        }
    }

    /** Nothing is saved if fn throws. */
    private <T> T update(RelayFunction<T> fn) throws Exception {
        lock.lock();
        try {
            Map<String, Object> relays = load();
            T result = fn.apply(relays);
            save(relays);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> load() throws IOException {
        File file = new File(relaysFilePath);
        if (!file.exists()) {
            logger.info("Relays file not found, creating default structure");
            Map<String, Object> relays = new LinkedHashMap<>();
            normalize(relays);
            save(relays);
            return relays;
        }
        // Unlike data.json a broken file is not replaced: fail loudly and keep it for repair
        Map<String, Object> relays = objectMapper.readValue(file, new TypeReference<LinkedHashMap<String, Object>>() {});
        normalize(relays);
        return relays;
    }

    private static void normalize(Map<String, Object> relays) {
        if (!(relays.get("config") instanceof Map)) {
            relays.put("config", new LinkedHashMap<>());
        }
        configOf(relays).putIfAbsent("relay_duration_min", DEFAULT_RELAY_DURATION_MIN);
        if (!(relays.get("disciplines") instanceof List)) {
            relays.put("disciplines", defaultDisciplines());
        }
        for (String key : new String[] {"days", "relays", "assignments"}) {
            if (!(relays.get(key) instanceof List)) {
                relays.put(key, new ArrayList<>());
            }
        }
    }

    private static List<Map<String, Object>> defaultDisciplines() {
        List<Map<String, Object>> disciplines = new ArrayList<>();
        disciplines.add(discipline("d25", "25m", 15));
        disciplines.add(discipline("d50", "50m", 12));
        disciplines.add(discipline("d100", "100m", 8));
        return disciplines;
    }

    private static Map<String, Object> discipline(String id, String name, int laneCount) {
        Map<String, Object> discipline = new LinkedHashMap<>();
        discipline.put("id", id);
        discipline.put("name", name);
        discipline.put("lane_count", laneCount);
        return discipline;
    }

    private void save(Map<String, Object> relays) throws IOException {
        File tempFile = new File(relaysFilePath + ".tmp");
        objectMapper.writeValue(tempFile, relays);
        Files.move(tempFile.toPath(), Paths.get(relaysFilePath),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
