package com.competition.service;

import com.competition.model.Discipline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class DisciplineService {
    static final int FIRST_CUSTOM_ID = 1000;

    private DataService dataService;

    public DisciplineService(DataService dataService) {
        this.dataService = dataService;
    }

    public List<Integer> getActiveDisciplines() throws Exception {
        return activeIdsOf(dataService.loadDisciplines());
    }

    /**
     * Replaces the active discipline list by flipping each catalog discipline's
     * "active" flag. If baseIds (the list the client started from) is given and
     * no longer matches, someone else changed the list meanwhile and the save
     * is rejected instead of silently undoing it.
     */
    public List<Integer> setActiveDisciplines(List<Integer> disciplineIds, List<Integer> baseIds) throws Exception {
        Set<Integer> desired = new HashSet<>(disciplineIds);
        return dataService.updateDisciplines(disciplines -> {
            List<Integer> current = activeIdsOf(disciplines);
            if (baseIds != null && !new HashSet<>(baseIds).equals(new HashSet<>(current))) {
                throw new ConflictException("Active disciplines were changed by someone else", current);
            }
            for (Discipline d : disciplines) {
                d.setActive(desired.contains(d.getId()));
            }
            return activeIdsOf(disciplines);
        });
    }

    /** Deactivates a single discipline, leaving everyone else's changes intact. */
    public List<Integer> deactivateDiscipline(int disciplineId) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            for (Discipline d : disciplines) {
                if (d.getId() == disciplineId) {
                    d.setActive(false);
                }
            }
            return activeIdsOf(disciplines);
        });
    }

    public List<Discipline> getAvailableDisciplines() throws Exception {
        return dataService.loadDisciplines();
    }

    public Discipline getAvailableDisciplineById(int id) throws Exception {
        List<Discipline> availableDisciplines = getAvailableDisciplines();
        for (Discipline discipline : availableDisciplines) {
            if (discipline.getId() == id) {
                return discipline;
            }
        }
        return null;
    }

    public Discipline createCatalogDiscipline(Map<String, Object> data) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            int maxId = disciplines.stream().mapToInt(Discipline::getId).max().orElse(0);
            Discipline d = new Discipline();
            // Added disciplines start at 1000 so a later catalog release cannot reuse their ids
            d.setId(Math.max(maxId + 1, FIRST_CUSTOM_ID));
            applyCatalogFields(d, data);
            disciplines.add(d);
            return d;
        });
    }

    public Discipline updateCatalogDiscipline(int id, Map<String, Object> data) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            Discipline target = disciplines.stream()
                .filter(d -> d.getId() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Discipline not found: " + id));
            applyCatalogFields(target, data);
            // A catalog discipline's short name goes into the catalog for every competition;
            // the competition file then no longer differs from it there
            if (data.containsKey("short_name")) {
                dataService.setCatalogShortName(id, target.getShortName());
            }
            return target;
        });
    }

    public void deleteCatalogDiscipline(int id) throws Exception {
        dataService.updateDisciplines(disciplines -> {
            if (!disciplines.removeIf(d -> d.getId() == id)) {
                throw new IllegalArgumentException("Discipline not found: " + id);
            }
            return null;
        });
    }

    public List<Discipline> updateShootingDistances(Map<String, String> mapping) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            for (Discipline d : disciplines) {
                String value = mapping.get(String.valueOf(d.getId()));
                if (value != null) {
                    d.setShootingDistance(value.isBlank() ? null : value);
                }
            }
            return disciplines;
        });
    }

    /**
     * An individual event that exists both as an original and a reproduction
     * discipline (e.g. Miquelet), so the two can be ranked together. Events with
     * just one type (Colt, Mariette) have no pair.
     */
    public record EventPair(String key, String category, String event, int originalId, int reproductionId) {
        public int partnerOf(int disciplineId) {
            return disciplineId == originalId ? reproductionId : originalId;
        }

        public String typeOf(int disciplineId) {
            return disciplineId == originalId ? "original" : "reproduction";
        }
    }

    public static String eventKey(String category, String event) {
        return category + "|" + event;
    }

    private static final Pattern TYPE_SUFFIX = Pattern.compile("[_ ](?:O/R|O|R)$");
    private static final Pattern NUMBER_PREFIX = Pattern.compile("^(?:\\d+|XX)_");

    /** The event name without its type suffix, e.g. "1_Miquelet" for "1_Miquelet_O", "1_Miquelet_R" or "1_Miquelet_O/R". */
    public static String baseEvent(String event) {
        return event == null ? null : TYPE_SUFFIX.matcher(event.trim()).replaceFirst("").trim();
    }

    /**
     * What event names are matched on (original/reproduction pairs, aggregates,
     * team based_on, lane families): also without the MLAIC number, so
     * "1_Miquelet_O", "1_Miquelet_R" and "Miquelet" are the same event.
     */
    public static String matchKey(String event) {
        return event == null ? null : NUMBER_PREFIX.matcher(baseEvent(event)).replaceFirst("").trim();
    }

    /** Whether two event names name the same event, see {@link #matchKey}. */
    public static boolean sameEvent(String a, String b) {
        return a != null && b != null && matchKey(a).equalsIgnoreCase(matchKey(b));
    }

    /** Every event with exactly one original and one reproduction individual discipline, in catalog order. */
    static List<EventPair> eventPairsOf(List<Discipline> disciplines) {
        Map<String, List<Discipline>> byEvent = new LinkedHashMap<>();
        for (Discipline d : disciplines) {
            if (!TeamService.isTeamDiscipline(d) && !isAggregate(d) && d.getEvent() != null
                    && ("original".equals(d.getType()) || "reproduction".equals(d.getType()))) {
                byEvent.computeIfAbsent(eventKey(d.getCategory(), matchKey(d.getEvent())), k -> new ArrayList<>()).add(d);
            }
        }
        List<EventPair> pairs = new ArrayList<>();
        for (Map.Entry<String, List<Discipline>> entry : byEvent.entrySet()) {
            List<Discipline> originals = new ArrayList<>();
            List<Discipline> reproductions = new ArrayList<>();
            for (Discipline d : entry.getValue()) {
                ("original".equals(d.getType()) ? originals : reproductions).add(d);
            }
            if (originals.size() == 1 && reproductions.size() == 1) {
                Discipline original = originals.get(0);
                pairs.add(new EventPair(entry.getKey(), original.getCategory(), baseEvent(original.getEvent()),
                    original.getId(), reproductions.get(0).getId()));
            }
        }
        return pairs;
    }

    /** Whether the discipline adds up the results of other disciplines instead of having starts. */
    public static boolean isAggregate(Discipline discipline) {
        return discipline != null && discipline.getAggregateOf() != null && !discipline.getAggregateOf().isEmpty();
    }

    /**
     * The disciplines an aggregate discipline adds up, in the order of its
     * aggregate_of: per named event the individual discipline of the same
     * category and type. An event without such a discipline is left out.
     */
    static List<Discipline> componentsOf(Discipline aggregate, List<Discipline> disciplines) {
        List<Discipline> components = new ArrayList<>();
        for (String event : aggregate.getAggregateOf()) {
            for (Discipline d : disciplines) {
                if (!TeamService.isTeamDiscipline(d) && !isAggregate(d)
                        && sameEvent(event, d.getEvent())
                        && Objects.equals(aggregate.getCategory(), d.getCategory())
                        && Objects.equals(aggregate.getType(), d.getType())) {
                    components.add(d);
                    break;
                }
            }
        }
        return components;
    }

    public List<Discipline> getComponents(Discipline aggregate) throws Exception {
        return componentsOf(aggregate, dataService.loadDisciplines());
    }

    /** All combinable events with whether this competition ranks them combined. */
    public List<Map<String, Object>> getCombinableEvents() throws Exception {
        Set<String> combined = dataService.loadCombinedEvents();
        List<Map<String, Object>> events = new ArrayList<>();
        for (EventPair pair : eventPairsOf(dataService.loadDisciplines())) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("key", pair.key());
            event.put("category", pair.category());
            event.put("event", pair.event());
            event.put("original_id", pair.originalId());
            event.put("reproduction_id", pair.reproductionId());
            event.put("combined", combined.contains(pair.key()));
            events.add(event);
        }
        return events;
    }

    /** The combined pairs, looked up by either of their discipline ids. */
    public Map<Integer, EventPair> getCombinedPairsByDiscipline() throws Exception {
        Set<String> combined = dataService.loadCombinedEvents();
        Map<Integer, EventPair> byDiscipline = new HashMap<>();
        if (combined.isEmpty()) {
            return byDiscipline;
        }
        for (EventPair pair : eventPairsOf(dataService.loadDisciplines())) {
            if (combined.contains(pair.key())) {
                byDiscipline.put(pair.originalId(), pair);
                byDiscipline.put(pair.reproductionId(), pair);
            }
        }
        return byDiscipline;
    }

    /**
     * Ranks an event's original and reproduction disciplines together or
     * separately. Combining is refused while a competitor starts in both, since
     * a combined ranking holds each competitor once.
     */
    public List<Map<String, Object>> setCombined(String key, boolean combined) throws Exception {
        // Lock order: data.json, then disciplines
        return dataService.read(data -> {
            dataService.updateCombinedEvents(combinedEvents -> {
                if (!combined) {
                    combinedEvents.remove(key);
                    return null;
                }
                EventPair pair = eventPairsOf(dataService.loadDisciplines()).stream()
                    .filter(p -> p.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Event cannot be combined: " + key));
                List<String> inBoth = competitorsStartingInBoth(data, pair);
                if (!inBoth.isEmpty()) {
                    throw new ConflictException("Cannot combine " + pair.event()
                        + ": these competitors start in both original and reproduction: "
                        + String.join(", ", inBoth), inBoth);
                }
                combinedEvents.add(key);
                return null;
            });
            return getCombinableEvents();
        });
    }

    @SuppressWarnings("unchecked")
    private static List<String> competitorsStartingInBoth(Map<String, Object> data, EventPair pair) {
        List<String> names = new ArrayList<>();
        List<Map<String, Object>> competitors = (List<Map<String, Object>>) data.get("competitors");
        if (competitors == null) {
            return names;
        }
        for (Map<String, Object> competitor : competitors) {
            if (startsIn(competitor, pair.originalId()) && startsIn(competitor, pair.reproductionId())) {
                names.add(String.valueOf(competitor.get("name")));
            }
        }
        return names;
    }

    /** Whether the competitor (a data.json record) has a start in the discipline. */
    static boolean startsIn(Map<String, Object> competitor, int disciplineId) {
        return competitor.get("starts") instanceof Map<?, ?> starts
            && starts.get(String.valueOf(disciplineId)) instanceof List<?> list
            && !list.isEmpty();
    }

    private static void applyCatalogFields(Discipline d, Map<String, Object> data) {
        if (data.containsKey("category")) d.setCategory((String) data.get("category"));
        if (data.containsKey("level")) d.setLevel((String) data.get("level"));
        if (data.containsKey("type")) d.setType((String) data.get("type"));
        if (data.containsKey("event")) d.setEvent((String) data.get("event"));
        if (data.containsKey("short_name")) {
            String sn = (String) data.get("short_name");
            d.setShortName(sn != null && !sn.isBlank() ? sn.trim() : null);
        }
        if (data.containsKey("based_on")) d.setBasedOn((String) data.get("based_on"));
        if (data.containsKey("team_size")) {
            Object ts = data.get("team_size");
            d.setTeamSize(ts instanceof Number ? ((Number) ts).intValue() : null);
        }
        if (data.containsKey("shooting_distance")) {
            String sd = (String) data.get("shooting_distance");
            d.setShootingDistance(sd != null && !sd.isBlank() ? sd : null);
        }
        if (data.containsKey("active")) {
            d.setActive(Boolean.TRUE.equals(data.get("active")));
        }
    }

    private static List<Integer> activeIdsOf(List<Discipline> disciplines) {
        List<Integer> activeIds = new ArrayList<>();
        for (Discipline d : disciplines) {
            if (d.isActive()) {
                activeIds.add(d.getId());
            }
        }
        return activeIds;
    }
}
