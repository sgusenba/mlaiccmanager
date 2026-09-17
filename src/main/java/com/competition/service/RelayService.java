package com.competition.service;

import com.competition.model.Discipline;
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
 * lives in relays.json, separate from data.json; a lane holds one registered
 * start (e.g. "1-52-1"), which is only referenced by id and stays in data.json.
 *
 * <p>A range (25m/50m/100m) is the lane block a relay is divided into; the
 * MLAIC disciplines of data.json/disciplines.json can be mapped to a range so
 * that only the starts belonging there are offered for a lane.
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

    /** Competitors, their registered starts and the discipline metadata, read from data.json/disciplines.json. */
    private record Registry(Map<Integer, Map<String, Object>> competitors,
                            Map<String, Map<String, Object>> starts,
                            Map<Integer, String> disciplineNames,
                            Map<Integer, String> disciplineLevels,
                            Map<Integer, String> disciplineShootingDistances,
                            List<Integer> activeDisciplines) {

        Map<String, Integer> competitorOfStart() {
            Map<String, Integer> byStart = new HashMap<>();
            starts.forEach((startId, start) -> byStart.put(startId, RelayRules.intOf(start.get("competitor_id"))));
            return byStart;
        }
    }

    public RelayService(String relaysFilePath, DataService dataService) {
        this.relaysFilePath = relaysFilePath;
        this.dataService = dataService;
    }

    /** Everything the relay page needs at once: config, ranges, days, relays, assignments. */
    public Map<String, Object> getAll() throws Exception {
        return read(relays -> {
            Map<String, Object> result = new LinkedHashMap<>(relays);
            result.remove("discipline_ranges");
            return result;
        });
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
            sortDays(days);
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
            sortDays(listOf(relays, "days"));
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

    /** The relay with its day and one lane block per range. */
    public Map<String, Object> getRelay(String relayId) throws Exception {
        return read(relays -> {
            Map<String, Object> relay = findRelay(relays, relayId);
            Registry registry = registry();

            List<Map<String, Object>> blocks = new ArrayList<>();
            for (Map<String, Object> range : listOf(relays, "ranges")) {
                List<Map<String, Object>> lanes = new ArrayList<>();
                int laneCount = RelayRules.intOf(range.get("lane_count"));
                for (int laneNo = 1; laneNo <= laneCount; laneNo++) {
                    Map<String, Object> assignment = findLane(relays, relayId, (String) range.get("id"), laneNo);
                    Map<String, Object> lane = new LinkedHashMap<>();
                    lane.put("lane_no", laneNo);
                    lane.put("assignment", assignment != null ? resolve(assignment, registry) : null);
                    lanes.add(lane);
                }
                Map<String, Object> block = new LinkedHashMap<>(range);
                block.put("lanes", lanes);
                blocks.add(block);
            }

            Map<String, Object> detail = new LinkedHashMap<>(relay);
            detail.put("day", find(listOf(relays, "days"), relay.get("day_id")));
            detail.put("relay_duration_min", relayDuration(relays));
            detail.put("ranges", blocks);
            return detail;
        });
    }

    /**
     * Registered starts that may take a lane in this relay and range: not yet
     * assigned anywhere, competitor still free in this relay, the start's
     * discipline either mapped to this range or not mapped at all, and the
     * discipline is not a team discipline.
     */
    public List<Map<String, Object>> getAvailableStarts(String relayId, String rangeId) throws Exception {
        return read(relays -> {
            findRelay(relays, relayId);
            findRange(relays, rangeId);
            Registry registry = registry();
            List<Map<String, Object>> assignments = listOf(relays, "assignments");
            Map<String, Integer> competitorOfStart = registry.competitorOfStart();

            List<Map<String, Object>> available = new ArrayList<>();
            for (Map<String, Object> start : registry.starts().values()) {
                int disciplineId = RelayRules.intOf(start.get("discipline_id"));
                if ("team".equals(registry.disciplineLevels().get(disciplineId))) {
                    continue;
                }
                String mapped = shootingDistanceOfDiscipline(registry, disciplineId);
                if (mapped != null && !mapped.equals(rangeId)) {
                    continue;
                }
                String startId = (String) start.get("start_id");
                int competitorId = RelayRules.intOf(start.get("competitor_id"));
                if (RelayRules.checkConflict(startId, competitorId, relayId, assignments, competitorOfStart, null) == null) {
                    available.add(startSummary(start, registry));
                }
            }
            available.sort(Comparator
                .comparing((Map<String, Object> s) -> Objects.toString(s.get("discipline_name"), ""))
                .thenComparing(s -> Objects.toString(competitorName(s), "")));
            return available;
        });
    }

    /**
     * Puts a registered start into a lane, replacing whatever is in it. If the
     * request contains "expected_assignment_id" (the assignment the user saw,
     * null for an empty lane) and the lane changed meanwhile, a
     * ConflictException carries the current assignment.
     */
    public Map<String, Object> assign(Map<String, Object> request) throws Exception {
        String relayId = requireString(request, "relay_id");
        String rangeId = requireString(request, "range_id");
        int laneNo = requireInt(request, "lane_no");
        String startId = requireString(request, "start_id");

        return update(relays -> {
            findRelay(relays, relayId);
            Map<String, Object> range = findRange(relays, rangeId);
            int laneCount = RelayRules.intOf(range.get("lane_count"));
            if (laneNo < 1 || laneNo > laneCount) {
                throw new IllegalArgumentException("Lane must be between 1 and " + laneCount + " for " + range.get("name"));
            }

            Registry registry = registry();
            Map<String, Object> start = registry.starts().get(startId);
            if (start == null) {
                throw new IllegalArgumentException("Start " + startId + " not found");
            }
            int disciplineId = RelayRules.intOf(start.get("discipline_id"));
            if ("team".equals(registry.disciplineLevels().get(disciplineId))) {
                throw new IllegalArgumentException(disciplineName(registry, disciplineId)
                    + " is a team discipline and cannot be assigned to a lane");
            }
            String mapped = shootingDistanceOfDiscipline(registry, disciplineId);
            if (mapped != null && !mapped.equals(rangeId)) {
                throw new IllegalArgumentException(disciplineName(registry, disciplineId) + " is set to fire on "
                    + rangeName(relays, mapped) + ", not on " + range.get("name"));
            }

            Map<String, Object> current = findLane(relays, relayId, rangeId, laneNo);
            String currentId = current != null ? (String) current.get("id") : null;
            if (request.containsKey("expected_assignment_id")
                && !Objects.equals(request.get("expected_assignment_id"), currentId)) {
                throw new ConflictException("Lane was changed by someone else",
                    current != null ? resolve(current, registry) : null);
            }
            if (current != null && startId.equals(current.get("start_id"))) {
                return resolve(current, registry);
            }

            List<Map<String, Object>> assignments = listOf(relays, "assignments");
            String conflict = RelayRules.checkConflict(startId, RelayRules.intOf(start.get("competitor_id")),
                relayId, assignments, registry.competitorOfStart(), currentId);
            if (conflict != null) {
                throw new ConflictException(conflict, null);
            }

            Map<String, Object> assignment = new LinkedHashMap<>();
            assignment.put("id", nextId(assignments, "a"));
            assignment.put("relay_id", relayId);
            assignment.put("range_id", rangeId);
            assignment.put("lane_no", laneNo);
            assignment.put("start_id", startId);
            if (current != null) {
                assignments.remove(current);
            }
            assignments.add(assignment);
            return resolve(assignment, registry);
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
            Registry registry = registry();
            List<Map<String, Object>> schedule = new ArrayList<>();
            for (Map<String, Object> assignment : listOf(relays, "assignments")) {
                Map<String, Object> start = registry.starts().get((String) assignment.get("start_id"));
                if (start != null && RelayRules.intOf(start.get("competitor_id")) == competitorId) {
                    schedule.add(scheduleEntry(relays, assignment, registry));
                }
            }
            schedule.sort(SCHEDULE_ORDER);
            return schedule;
        });
    }

    /**
     * Every competitor with their registered starts, where each one is
     * scheduled and which starts still need a lane, plus any rule violation
     * found in the stored data (safety net: e.g. a hand-edited relays.json, a
     * deleted start, or a discipline remapped to another range afterwards).
     */
    public Map<String, Object> getOverview() throws Exception {
        return read(relays -> {
            Registry registry = registry();
            List<Map<String, Object>> assignments = listOf(relays, "assignments");

            // start id -> where it is scheduled
            Map<String, List<Map<String, Object>>> byStart = new LinkedHashMap<>();
            for (Map<String, Object> assignment : assignments) {
                byStart.computeIfAbsent((String) assignment.get("start_id"), k -> new ArrayList<>())
                    .add(scheduleEntry(relays, assignment, registry));
            }

            Map<Integer, List<Map<String, Object>>> startsByCompetitor = new LinkedHashMap<>();
            registry.competitors().keySet().forEach(id -> startsByCompetitor.put(id, new ArrayList<>()));
            registry.starts().values().forEach(start -> startsByCompetitor
                .computeIfAbsent(RelayRules.intOf(start.get("competitor_id")), k -> new ArrayList<>())
                .add(start));

            List<Map<String, Object>> rows = new ArrayList<>();
            List<String> dataIssues = new ArrayList<>();

            for (Map.Entry<Integer, List<Map<String, Object>>> entry : startsByCompetitor.entrySet()) {
                int competitorId = entry.getKey();
                Map<String, Object> competitor = registry.competitors().get(competitorId);
                List<Map<String, Object>> scheduled = new ArrayList<>();
                List<Map<String, Object>> unscheduled = new ArrayList<>();
                List<String> issues = new ArrayList<>();

                for (Map<String, Object> start : entry.getValue()) {
                    String startId = (String) start.get("start_id");
                    List<Map<String, Object>> entries = byStart.getOrDefault(startId, List.of());
                    if (entries.isEmpty()) {
                        unscheduled.add(startSummary(start, registry));
                        continue;
                    }
                    if (entries.size() > 1) {
                        issues.add("Start " + startId + " has " + entries.size() + " lanes");
                    }
                    for (Map<String, Object> scheduledEntry : entries) {
                        scheduled.add(scheduledEntry);
                        String mapped = shootingDistanceOfDiscipline(registry, RelayRules.intOf(start.get("discipline_id")));
                        if (mapped != null && !mapped.equals(scheduledEntry.get("range_id"))) {
                            issues.add(scheduledEntry.get("discipline_name") + " is scheduled on "
                                + scheduledEntry.get("range_name") + " but is set to fire on " + rangeName(relays, mapped));
                        }
                        if (scheduledEntry.get("start_time") == null) {
                            issues.add("Start " + startId + " points to a missing relay or range");
                        }
                    }
                }

                scheduled.sort(SCHEDULE_ORDER);
                Map<Object, Integer> perRelay = new HashMap<>();
                scheduled.forEach(e -> perRelay.merge(e.get("relay_id"), 1, Integer::sum));
                perRelay.forEach((relayId, count) -> {
                    if (count > 1) issues.add(count + " lanes in the same relay");
                });

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("competitor", competitor != null ? competitorSummary(competitor)
                    : Map.of("id", competitorId, "name", "Unknown competitor"));
                row.put("scheduled", scheduled);
                row.put("unscheduled", unscheduled);
                row.put("issues", issues);
                rows.add(row);
            }

            // Assignments whose start is gone from data.json have no competitor to list them under
            for (Map<String, Object> assignment : assignments) {
                String startId = (String) assignment.get("start_id");
                if (!registry.starts().containsKey(startId)) {
                    dataIssues.add("Lane " + laneLabel(relays, assignment) + " holds start " + startId
                        + ", which no longer exists");
                }
            }
            Map<String, Integer> laneUse = new HashMap<>();
            for (Map<String, Object> a : assignments) {
                laneUse.merge(laneLabel(relays, a), 1, Integer::sum);
            }
            laneUse.forEach((lane, count) -> {
                if (count > 1) dataIssues.add("Lane " + lane + " is assigned " + count + " times");
            });

            Map<String, Object> overview = new LinkedHashMap<>();
            overview.put("ranges", listOf(relays, "ranges"));
            overview.put("rows", rows);
            overview.put("data_issues", dataIssues);
            return overview;
        });
    }

    // --- helpers -----------------------------------------------------------

    private static final Comparator<Map<String, Object>> SCHEDULE_ORDER = Comparator
        .comparing((Map<String, Object> e) -> Objects.toString(e.get("date"), ""))
        .thenComparing(e -> Objects.toString(e.get("start_time"), ""));

    private Map<String, Object> scheduleEntry(Map<String, Object> relays, Map<String, Object> assignment, Registry registry) {
        Map<String, Object> entry = resolve(assignment, registry);
        Map<String, Object> relay = find(listOf(relays, "relays"), assignment.get("relay_id"));
        Map<String, Object> day = relay != null ? find(listOf(relays, "days"), relay.get("day_id")) : null;
        entry.put("range_name", rangeName(relays, (String) assignment.get("range_id")));
        entry.put("sequence_no", relay != null ? relay.get("sequence_no") : null);
        entry.put("start_time", relay != null ? relay.get("start_time") : null);
        entry.put("date", day != null ? day.get("date") : null);
        return entry;
    }

    /** The stored assignment plus the start, competitor and discipline it points to. */
    private static Map<String, Object> resolve(Map<String, Object> assignment, Registry registry) {
        Map<String, Object> result = new LinkedHashMap<>(assignment);
        Map<String, Object> start = registry.starts().get((String) assignment.get("start_id"));
        int disciplineId = start != null ? RelayRules.intOf(start.get("discipline_id")) : 0;
        result.put("start_number", start != null ? start.get("start_number") : null);
        result.put("discipline_id", start != null ? disciplineId : null);
        result.put("discipline_name", start != null ? disciplineName(registry, disciplineId) : null);
        Map<String, Object> competitor = start != null
            ? registry.competitors().get(RelayRules.intOf(start.get("competitor_id")))
            : null;
        result.put("competitor", competitor != null ? competitorSummary(competitor) : null);
        return result;
    }

    private static Map<String, Object> startSummary(Map<String, Object> start, Registry registry) {
        int disciplineId = RelayRules.intOf(start.get("discipline_id"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("start_id", start.get("start_id"));
        summary.put("start_number", start.get("start_number"));
        summary.put("discipline_id", disciplineId);
        summary.put("discipline_name", disciplineName(registry, disciplineId));
        Map<String, Object> competitor = registry.competitors().get(RelayRules.intOf(start.get("competitor_id")));
        summary.put("competitor", competitor != null ? competitorSummary(competitor) : null);
        return summary;
    }

    private static Object competitorName(Map<String, Object> summary) {
        Object competitor = summary.get("competitor");
        return competitor instanceof Map ? ((Map<?, ?>) competitor).get("name") : null;
    }

    private static String disciplineName(Registry registry, int disciplineId) {
        String name = registry.disciplineNames().get(disciplineId);
        return name != null ? name : "Discipline #" + disciplineId;
    }

    private static String shootingDistanceOfDiscipline(Registry registry, int disciplineId) {
        return registry.disciplineShootingDistances().get(disciplineId);
    }

    private static String rangeName(Map<String, Object> relays, String rangeId) {
        Map<String, Object> range = find(listOf(relays, "ranges"), rangeId);
        return range != null ? (String) range.get("name") : String.valueOf(rangeId);
    }

    private static String laneLabel(Map<String, Object> relays, Map<String, Object> assignment) {
        Map<String, Object> relay = find(listOf(relays, "relays"), assignment.get("relay_id"));
        String relayLabel = relay != null ? "relay " + relay.get("sequence_no") : String.valueOf(assignment.get("relay_id"));
        return relayLabel + " / " + rangeName(relays, (String) assignment.get("range_id")) + " / " + assignment.get("lane_no");
    }

    private void recompute(Map<String, Object> relays, Map<String, Object> day) {
        RelayRules.recomputeDaySchedule(day, relaysOfDay(relays, (String) day.get("id")), relayDuration(relays));
    }

    private static void sortDays(List<Map<String, Object>> days) {
        days.sort(Comparator.comparing((Map<String, Object> d) -> (String) d.get("date"))
            .thenComparing(d -> (String) d.get("start_time")));
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

    private static Map<String, Object> findLane(Map<String, Object> relays, String relayId, String rangeId, int laneNo) {
        for (Map<String, Object> a : listOf(relays, "assignments")) {
            if (relayId.equals(a.get("relay_id")) && rangeId.equals(a.get("range_id"))
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

    private static Map<String, Object> findRange(Map<String, Object> relays, String rangeId) {
        Map<String, Object> range = find(listOf(relays, "ranges"), rangeId);
        if (range == null) {
            throw new IllegalArgumentException("Unknown range " + rangeId);
        }
        return range;
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

    /** Competitors, their starts and discipline metadata, all read from data.json/disciplines.json. */
    private Registry registry() throws Exception {
        Map<Integer, String> disciplineNames = new LinkedHashMap<>();
        Map<Integer, String> disciplineLevels = new LinkedHashMap<>();
        Map<Integer, String> disciplineShootingDistances = new LinkedHashMap<>();
        List<Integer> activeDisciplineIds = new ArrayList<>();
        for (Discipline discipline : dataService.loadDisciplines()) {
            disciplineNames.put(discipline.getId(), discipline.getType() != null
                ? discipline.getEvent() + " (" + discipline.getType() + ")"
                : discipline.getEvent());
            if (discipline.getLevel() != null) {
                disciplineLevels.put(discipline.getId(), discipline.getLevel());
            }
            if (discipline.getShootingDistance() != null && !discipline.getShootingDistance().isBlank()) {
                disciplineShootingDistances.put(discipline.getId(), discipline.getShootingDistance());
            }
            if (discipline.isActive()) {
                activeDisciplineIds.add(discipline.getId());
            }
        }

        return dataService.read(data -> {
            Map<Integer, Map<String, Object>> competitors = new LinkedHashMap<>();
            Map<String, Map<String, Object>> starts = new LinkedHashMap<>();

            if (data.get("competitors") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> competitor = (Map<String, Object>) raw;
                    int competitorId = RelayRules.intOf(competitor.get("id"));
                    competitors.put(competitorId, competitor);

                    if (!(competitor.get("starts") instanceof Map<?, ?> startsByDiscipline)) {
                        continue;
                    }
                    for (Object startList : startsByDiscipline.values()) {
                        if (!(startList instanceof List<?> startItems)) {
                            continue;
                        }
                        for (Object startItem : startItems) {
                            if (!(startItem instanceof Map<?, ?> start)) {
                                continue;
                            }
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("start_id", start.get("generated_id"));
                            info.put("start_number", start.get("start_number"));
                            info.put("discipline_id", RelayRules.intOf(start.get("discipline_id")));
                            info.put("competitor_id", competitorId);
                            starts.put((String) start.get("generated_id"), info);
                        }
                    }
                }
            }

            return new Registry(competitors, starts, disciplineNames, disciplineLevels, disciplineShootingDistances, activeDisciplineIds);
        });
    }

    private static Map<String, Object> competitorSummary(Map<String, Object> competitor) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", competitor.get("id"));
        summary.put("name", competitor.get("name"));
        summary.put("club", competitor.get("club"));
        return summary;
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
        if (!(relays.get("ranges") instanceof List)) {
            relays.put("ranges", defaultRanges());
        }
        for (String key : new String[] {"days", "relays", "assignments"}) {
            if (!(relays.get(key) instanceof List)) {
                relays.put(key, new ArrayList<>());
            }
        }
    }

    private static List<Map<String, Object>> defaultRanges() {
        List<Map<String, Object>> ranges = new ArrayList<>();
        ranges.add(range("m25", "25m", 15));
        ranges.add(range("m50", "50m", 12));
        ranges.add(range("m100", "100m", 8));
        return ranges;
    }

    private static Map<String, Object> range(String id, String name, int laneCount) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("id", id);
        range.put("name", name);
        range.put("lane_count", laneCount);
        return range;
    }

    private void save(Map<String, Object> relays) throws IOException {
        File tempFile = new File(relaysFilePath + ".tmp");
        objectMapper.writeValue(tempFile, relays);
        Files.move(tempFile.toPath(), Paths.get(relaysFilePath),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
