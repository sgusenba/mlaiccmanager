package com.competition.service;

import com.competition.model.Discipline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The meet's program, set on the Meet page: which disciplines of each event
 * are shot (original and/or reproduction), whether an event's original and
 * reproduction are ranked combined, and which team rankings there are. Stored
 * as the active disciplines and combined events in competition.json.
 */
public class ProgramService {
    private final DataService dataService;
    private final DisciplineService disciplineService;
    private final TeamService teamService;

    public ProgramService(DataService dataService, DisciplineService disciplineService, TeamService teamService) {
        this.dataService = dataService;
        this.disciplineService = disciplineService;
        this.teamService = teamService;
    }

    /**
     * The individual events (their disciplines grouped by category and event,
     * e.g. 15_Vetterli_O and 15_Vetterli_R) with which of them are shot and
     * whether they are combined, the team disciplines, and the base to send
     * back when saving.
     */
    public Map<String, Object> getProgram() throws Exception {
        Map<Integer, Integer> teamCounts = teamService.teamCounts();
        return dataService.read(data -> {
            List<Discipline> disciplines = dataService.loadDisciplines();
            Set<String> combined = dataService.loadCombinedEvents();
            return program(disciplines, combined, startCounts(data), teamCounts);
        });
    }

    /**
     * Sets the program: request "events" maps event keys to
     * {"active": [discipline ids shot], "combined": true/false} (events left out
     * stay as they are; combined needs both original and reproduction shot),
     * "teams" lists the team disciplines to rank. "base" is the program the
     * client started from; if it was changed meanwhile, a ConflictException
     * carries the current one.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> setProgram(Map<String, Object> request) throws Exception {
        Map<String, Object> events = request != null && request.get("events") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();
        Set<Integer> teams = request != null && request.get("teams") instanceof List<?> list
            ? toIds(list) : null;
        Map<?, ?> base = request != null && request.get("base") instanceof Map<?, ?> b ? b : null;
        Map<Integer, Integer> teamCounts = teamService.teamCounts();

        // Lock order: data.json, then disciplines
        return dataService.read(data -> dataService.updateProgram((disciplines, combined) -> {
            if (base != null) {
                Set<Integer> baseActive = base.get("active_ids") instanceof List<?> ids ? toIds(ids) : Set.of();
                Set<String> baseCombined = base.get("combined_keys") instanceof List<?> keys ? toKeys(keys) : Set.of();
                if (!baseActive.equals(activeIds(disciplines)) || !baseCombined.equals(combined)) {
                    throw new ConflictException("The program was changed by someone else meanwhile",
                        program(disciplines, combined, startCounts(data), teamCounts));
                }
            }

            Map<String, List<Discipline>> byEvent = eventsOf(disciplines);
            Map<String, DisciplineService.EventPair> pairs = new HashMap<>();
            DisciplineService.eventPairsOf(disciplines).forEach(p -> pairs.put(p.key(), p));
            for (Map.Entry<String, Object> entry : events.entrySet()) {
                String key = entry.getKey();
                List<Discipline> members = byEvent.get(key);
                if (members == null) {
                    throw new IllegalArgumentException("Unknown event: " + key);
                }
                if (!(entry.getValue() instanceof Map<?, ?> choice) || !(choice.get("active") instanceof List<?> ids)) {
                    throw new IllegalArgumentException(key + " needs the list of its disciplines that are shot");
                }
                Set<Integer> shot = toIds(ids);
                for (int id : shot) {
                    if (members.stream().noneMatch(d -> d.getId() == id)) {
                        throw new IllegalArgumentException("Discipline " + id + " is not part of " + key);
                    }
                }
                DisciplineService.EventPair pair = pairs.get(key);
                if (Boolean.TRUE.equals(choice.get("combined"))) {
                    if (pair == null) {
                        throw new IllegalArgumentException(key + " has no original and reproduction to combine");
                    }
                    if (!shot.contains(pair.originalId()) || !shot.contains(pair.reproductionId())) {
                        throw new IllegalArgumentException(pair.event()
                            + " can only be combined when both original and reproduction are shot");
                    }
                    List<String> inBoth = DisciplineService.competitorsStartingInBoth(data, pair);
                    if (!combined.contains(key) && !inBoth.isEmpty()) {
                        throw new ConflictException("Cannot combine " + pair.event()
                            + ": these competitors start in both original and reproduction: "
                            + String.join(", ", inBoth), inBoth);
                    }
                    combined.add(key);
                } else {
                    combined.remove(key);
                }
                members.forEach(d -> d.setActive(shot.contains(d.getId())));
            }
            if (teams != null) {
                for (Discipline d : disciplines) {
                    if (TeamService.isTeamDiscipline(d)) {
                        d.setActive(teams.contains(d.getId()));
                    }
                }
            }
            return program(disciplines, combined, startCounts(data), teamCounts);
        }));
    }

    private Map<String, Object> program(List<Discipline> disciplines, Set<String> combined,
            Map<Integer, Integer> starts, Map<Integer, Integer> teamCounts) {
        Set<String> combinable = new HashSet<>();
        DisciplineService.eventPairsOf(disciplines).forEach(p -> combinable.add(p.key()));

        List<Map<String, Object>> events = new ArrayList<>();
        for (Map.Entry<String, List<Discipline>> entry : eventsOf(disciplines).entrySet()) {
            List<Discipline> members = entry.getValue();

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("key", entry.getKey());
            event.put("category", members.get(0).getCategory());
            event.put("name", DisciplineService.baseEvent(members.get(0).getEvent()));
            event.put("combinable", combinable.contains(entry.getKey()));
            event.put("aggregate", DisciplineService.isAggregate(members.get(0)));
            event.put("combined", combined.contains(entry.getKey()) && combinable.contains(entry.getKey()));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Discipline d : members) {
                list.add(disciplineEntry(d, starts.getOrDefault(d.getId(), 0)));
            }
            event.put("disciplines", list);
            events.add(event);
        }

        List<Map<String, Object>> teams = new ArrayList<>();
        for (Discipline d : disciplines) {
            if (!TeamService.isTeamDiscipline(d)) {
                continue;
            }
            Map<String, Object> team = new LinkedHashMap<>();
            team.put("id", d.getId());
            team.put("category", d.getCategory());
            team.put("name", d.getEvent());
            team.put("type", d.getType());
            team.put("active", d.isActive());
            team.put("teams", teamCounts.getOrDefault(d.getId(), 0));
            List<String> composition = new ArrayList<>();
            for (Discipline individual : TeamService.eligibleDisciplines(d, disciplines)) {
                composition.add(individual.getShortName() != null ? individual.getShortName() : individual.getEvent());
            }
            team.put("composition", composition);
            teams.add(team);
        }

        Map<String, Object> base = new LinkedHashMap<>();
        base.put("active_ids", new ArrayList<>(activeIds(disciplines)));
        base.put("combined_keys", new ArrayList<>(combined));

        Map<String, Object> program = new LinkedHashMap<>();
        program.put("events", events);
        program.put("teams", teams);
        program.put("base", base);
        return program;
    }

    private static Map<String, Object> disciplineEntry(Discipline d, int starts) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", d.getId());
        entry.put("name", d.getEvent());
        entry.put("short_name", d.getShortName());
        entry.put("type", d.getType());
        entry.put("active", d.isActive());
        entry.put("starts", starts);
        return entry;
    }

    /** Individual disciplines by event ("category|event" as in DisciplineService.EventPair), in catalog order. */
    static Map<String, List<Discipline>> eventsOf(List<Discipline> disciplines) {
        Map<String, List<Discipline>> byEvent = new LinkedHashMap<>();
        for (Discipline d : disciplines) {
            if (!TeamService.isTeamDiscipline(d) && d.getEvent() != null) {
                byEvent.computeIfAbsent(DisciplineService.eventKey(d.getCategory(), DisciplineService.matchKey(d.getEvent())),
                    k -> new ArrayList<>()).add(d);
            }
        }
        return byEvent;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer> startCounts(Map<String, Object> data) {
        Map<Integer, Integer> counts = new HashMap<>();
        if (data.get("competitors") instanceof List<?> competitors) {
            for (Object c : competitors) {
                if (c instanceof Map<?, ?> competitor && competitor.get("starts") instanceof Map<?, ?> starts) {
                    starts.forEach((id, list) -> {
                        if (list instanceof List<?> l && !l.isEmpty()) {
                            counts.merge(Integer.parseInt(String.valueOf(id)), l.size(), Integer::sum);
                        }
                    });
                }
            }
        }
        return counts;
    }

    private static Set<Integer> activeIds(List<Discipline> disciplines) {
        Set<Integer> ids = new HashSet<>();
        disciplines.stream().filter(Discipline::isActive).forEach(d -> ids.add(d.getId()));
        return ids;
    }

    private static Set<Integer> toIds(List<?> list) {
        Set<Integer> ids = new HashSet<>();
        list.forEach(id -> ids.add(((Number) id).intValue()));
        return ids;
    }

    private static Set<String> toKeys(List<?> list) {
        Set<String> keys = new HashSet<>();
        list.forEach(key -> keys.add(String.valueOf(key)));
        return keys;
    }
}
