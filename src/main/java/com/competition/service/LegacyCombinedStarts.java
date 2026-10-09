package com.competition.service;

import com.competition.model.Discipline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Starts filed under the catalog's old "combined" disciplines (ids 21-30 and
 * 60-65), which were removed when combining became a ranking setting. Such a
 * start counts in no ranking, so at startup it is moved to its event's
 * original discipline (or the only discipline of a one-type event, e.g.
 * Mariette), keeping its start id so lanes, teams and printed labels stay
 * valid, and the event is ranked combined: whether a start was shot as an
 * original or a reproduction does not matter for a combined ranking.
 */
public final class LegacyCombinedStarts {

    /** The removed combined disciplines: id -> category and event (as named then). */
    static final Map<Integer, String[]> REMOVED = new LinkedHashMap<>();
    static {
        String[] rifle = {"Miquelet", "Maximilian", "Minie", "Whitworth", "Walkyrie",
            "Tanegashima", "Vetterli", "Hizadai", "Pennsylvania", "Lamarmora"};
        for (int i = 0; i < rifle.length; i++) {
            REMOVED.put(21 + i, new String[] {"rifle", rifle[i]});
        }
        String[] pistol = {"Cominazzo", "Kuchenreuter", "Colt", "Mariette", "Donald Malson", "Tanzutsu"};
        for (int i = 0; i < pistol.length; i++) {
            REMOVED.put(60 + i, new String[] {"pistol", pistol[i]});
        }
    }

    private LegacyCombinedStarts() {
    }

    /**
     * Moves the starts (and any results) of removed combined disciplines and
     * ranks their events combined. Does nothing if there are none.
     *
     * @return removed discipline id -> number of starts moved
     */
    public static Map<Integer, Integer> migrate(DataService dataService) throws Exception {
        List<Discipline> disciplines = dataService.loadDisciplines();
        Map<Integer, Discipline> targets = new LinkedHashMap<>();
        for (Map.Entry<Integer, String[]> removed : REMOVED.entrySet()) {
            int oldId = removed.getKey();
            if (disciplines.stream().anyMatch(d -> d.getId() == oldId)) {
                continue; // still (or again) a discipline of this competition: leave it alone
            }
            Discipline target = targetOf(removed.getValue()[0], removed.getValue()[1], disciplines);
            if (target != null) {
                targets.put(oldId, target);
            }
        }
        // Most competitions never had such starts: then data.json is not even written
        if (targets.isEmpty() || !dataService.read(data -> hasStartsIn(data, targets))) {
            return Map.of();
        }

        Map<Integer, Integer> moved = dataService.update(data -> moveStarts(data, targets));
        if (moved.isEmpty()) {
            return moved;
        }

        // Shot as before: the event's disciplines active, original and reproduction ranked combined
        dataService.updateProgram((current, combined) -> {
            Map<Integer, DisciplineService.EventPair> pairs = new LinkedHashMap<>();
            DisciplineService.eventPairsOf(current).forEach(p -> {
                pairs.put(p.originalId(), p);
                pairs.put(p.reproductionId(), p);
            });
            for (int oldId : moved.keySet()) {
                int targetId = targets.get(oldId).getId();
                DisciplineService.EventPair pair = pairs.get(targetId);
                for (Discipline d : current) {
                    if (d.getId() == targetId || (pair != null && d.getId() == pair.partnerOf(targetId))) {
                        d.setActive(true);
                    }
                }
                if (pair != null) {
                    combined.add(pair.key());
                }
            }
            return null;
        });
        return moved;
    }

    /** The event's original discipline, or its only individual discipline; null if it has none. */
    private static Discipline targetOf(String category, String event, List<Discipline> disciplines) {
        List<Discipline> ofEvent = new ArrayList<>();
        for (Discipline d : disciplines) {
            if (!TeamService.isTeamDiscipline(d) && !DisciplineService.isAggregate(d)
                    && Objects.equals(category, d.getCategory()) && DisciplineService.sameEvent(event, d.getEvent())) {
                ofEvent.add(d);
            }
        }
        return ofEvent.stream().filter(d -> "original".equals(d.getType())).findFirst()
            .orElse(ofEvent.size() == 1 ? ofEvent.get(0) : null);
    }

    private static boolean hasStartsIn(Map<String, Object> data, Map<Integer, Discipline> targets) {
        if (data.get("competitors") instanceof List<?> competitors) {
            for (Object c : competitors) {
                if (c instanceof Map<?, ?> competitor && competitor.get("starts") instanceof Map<?, ?> starts
                        && targets.keySet().stream().anyMatch(id -> starts.get(String.valueOf(id)) instanceof List<?> l && !l.isEmpty())) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer> moveStarts(Map<String, Object> data, Map<Integer, Discipline> targets) {
        Map<Integer, Integer> moved = new LinkedHashMap<>();
        if (data.get("competitors") instanceof List<?> competitors) {
            for (Object c : competitors) {
                if (!(c instanceof Map<?, ?> competitor) || !(competitor.get("starts") instanceof Map<?, ?> raw)) {
                    continue;
                }
                Map<String, Object> starts = (Map<String, Object>) raw;
                for (Map.Entry<Integer, Discipline> target : targets.entrySet()) {
                    if (!(starts.remove(String.valueOf(target.getKey())) instanceof List<?> list) || list.isEmpty()) {
                        continue;
                    }
                    int targetId = target.getValue().getId();
                    List<Object> into = starts.get(String.valueOf(targetId)) instanceof List<?> existing
                        ? (List<Object>) existing : new ArrayList<>();
                    starts.put(String.valueOf(targetId), into);
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> start) {
                            ((Map<String, Object>) start).put("discipline_id", targetId);
                        }
                        into.add(item);
                    }
                    moved.merge(target.getKey(), list.size(), Integer::sum);
                }
            }
        }
        if (data.get("results") instanceof List<?> results) {
            for (Object r : results) {
                if (r instanceof Map<?, ?> result && result.get("discipline_id") instanceof Number id
                        && targets.containsKey(id.intValue())) {
                    ((Map<String, Object>) result).put("discipline_id", targets.get(id.intValue()).getId());
                }
            }
        }
        return moved;
    }
}
