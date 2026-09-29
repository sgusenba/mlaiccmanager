package com.competition.service;

import com.competition.model.Discipline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Clears one kind of data at a time for the Danger Zone page, e.g. all results
 * or all lane assignments. What depends on the cleared data is cleared with it,
 * so nothing is left pointing at a start that is gone: clearing starts also
 * clears their results, lanes and team memberships.
 *
 * <p>Every clear holds all data files' locks (see BackupService) and first
 * saves the current data to backups/pre-clear-&lt;what&gt;-&lt;time&gt;.zip, so it
 * can be undone on the Backup &amp; Restore page.
 */
public class DangerZoneService {
    private static final Logger logger = LoggerFactory.getLogger(DangerZoneService.class);

    /** What can be cleared, in the order the page lists it. */
    public static final List<String> TARGETS = List.of(
        "results", "lanes", "starts", "competitors", "teams", "days", "meet", "disciplines");

    private final DataService dataService;
    private final TeamService teamService;
    private final RelayService relayService;
    private final MeetService meetService;
    private final BackupService backupService;

    public DangerZoneService(DataService dataService, TeamService teamService, RelayService relayService,
                             MeetService meetService, BackupService backupService) {
        this.dataService = dataService;
        this.teamService = teamService;
        this.relayService = relayService;
        this.meetService = meetService;
        this.backupService = backupService;
    }

    /** How much there is of each kind of data, so the page can show what a clear would remove. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> summary() throws Exception {
        return backupService.locked(() -> {
            Map<String, Object> counts = new LinkedHashMap<>();
            dataService.read(data -> {
                List<Map<String, Object>> competitors = (List<Map<String, Object>>) data.get("competitors");
                counts.put("results", ((List<?>) data.get("results")).size());
                counts.put("starts", startIdsOf(competitors, id -> true).size());
                counts.put("competitors", competitors.size());
                return null;
            });
            Map<String, Object> relays = relayService.getAll();
            counts.put("lanes", ((List<?>) relays.get("assignments")).size());
            counts.put("days", ((List<?>) relays.get("days")).size());
            counts.put("relays", ((List<?>) relays.get("relays")).size());
            counts.put("teams", teamService.getTeams(null).size());
            Map<String, Object> meet = meetService.get();
            counts.put("meet", DataService.getVersion(meet) > 0 ? 1 : 0);
            List<Discipline> disciplines = dataService.loadDisciplines();
            counts.put("active_disciplines", disciplines.stream().filter(Discipline::isActive).count());
            counts.put("added_disciplines",
                disciplines.stream().filter(d -> d.getId() >= DisciplineService.FIRST_CUSTOM_ID).count());
            return counts;
        });
    }

    /**
     * Clears the given kind of data and what depends on it.
     *
     * @return the target, how many records of each kind were cleared and the safety copy's path
     */
    public Map<String, Object> clear(String target) throws Exception {
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("Unknown data to clear: " + target);
        }
        return backupService.locked(() -> {
            String safetyCopy = backupService.saveSafetyCopy("pre-clear-" + target);
            Map<String, Integer> cleared = new LinkedHashMap<>();
            switch (target) {
                case "results" -> cleared.put("results", clearResults());
                case "lanes" -> cleared.put("lane assignments", relayService.clearAssignments(id -> true));
                case "starts" -> clearStarts(id -> true, cleared);
                case "competitors" -> clearCompetitors(cleared);
                case "teams" -> cleared.put("teams", teamService.deleteTeams(id -> true));
                case "days" -> relayService.clearDays().forEach((key, n) -> cleared.put(label(key), n));
                case "meet" -> cleared.put("meet details", meetService.clear() ? 1 : 0);
                case "disciplines" -> resetDisciplines(cleared);
                default -> throw new IllegalStateException(target);
            }
            logger.warn("Danger zone: cleared {} ({}); previous data saved to {}", target, cleared, safetyCopy);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("target", target);
            result.put("cleared", cleared);
            result.put("safety_copy", safetyCopy);
            return result;
        });
    }

    private int clearResults() throws Exception {
        return dataService.update(data -> {
            List<?> results = (List<?>) data.get("results");
            int n = results.size();
            results.clear();
            return n;
        });
    }

    /** Removes the starts of the matching disciplines with their results, lanes and team memberships. */
    @SuppressWarnings("unchecked")
    private void clearStarts(IntPredicate disciplineIds, Map<String, Integer> cleared) throws Exception {
        Set<String> startIds = new HashSet<>();
        int results = dataService.update(data -> {
            List<Map<String, Object>> competitors = (List<Map<String, Object>>) data.get("competitors");
            startIds.addAll(startIdsOf(competitors, disciplineIds));
            for (Map<String, Object> competitor : competitors) {
                if (competitor.get("starts") instanceof Map<?, ?> starts) {
                    starts.keySet().removeIf(key -> disciplineIds.test(disciplineIdOf(key)));
                }
            }
            return removeResults(data, startIds);
        });
        cleared.put("starts", startIds.size());
        cleared.put("results", results);
        clearStartReferences(startIds, cleared);
    }

    @SuppressWarnings("unchecked")
    private void clearCompetitors(Map<String, Integer> cleared) throws Exception {
        Set<String> startIds = new HashSet<>();
        int[] counts = dataService.update(data -> {
            List<Map<String, Object>> competitors = (List<Map<String, Object>>) data.get("competitors");
            startIds.addAll(startIdsOf(competitors, id -> true));
            int n = competitors.size();
            competitors.clear();
            List<?> results = (List<?>) data.get("results");
            int r = results.size();
            results.clear();
            return new int[] {n, r};
        });
        cleared.put("competitors", counts[0]);
        cleared.put("starts", startIds.size());
        cleared.put("results", counts[1]);
        clearStartReferences(startIds, cleared);
    }

    /** Back to the shipped catalog; starts and teams of disciplines that are gone then go with them. */
    private void resetDisciplines(Map<String, Integer> cleared) throws Exception {
        Set<Integer> remaining = dataService.resetDisciplines();
        cleared.put("discipline settings", 1);
        clearStarts(id -> id >= 0 && !remaining.contains(id), cleared);
        cleared.put("teams", teamService.deleteTeams(id -> !remaining.contains(id)));
    }

    private void clearStartReferences(Set<String> startIds, Map<String, Integer> cleared) throws Exception {
        cleared.put("lane assignments", relayService.clearAssignments(startIds::contains));
        cleared.put("team members", teamService.removeMembers(startIds::contains));
    }

    @SuppressWarnings("unchecked")
    private static int removeResults(Map<String, Object> data, Set<String> startIds) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        int before = results.size();
        results.removeIf(result -> startIds.contains(String.valueOf(result.get("start_id"))));
        return before - results.size();
    }

    /** The generated ids of the competitors' starts in the matching disciplines. */
    @SuppressWarnings("unchecked")
    private static Set<String> startIdsOf(List<Map<String, Object>> competitors, IntPredicate disciplineIds) {
        Set<String> startIds = new HashSet<>();
        for (Map<String, Object> competitor : competitors) {
            if (!(competitor.get("starts") instanceof Map<?, ?> starts)) {
                continue;
            }
            for (Map.Entry<?, ?> entry : starts.entrySet()) {
                if (!disciplineIds.test(disciplineIdOf(entry.getKey())) || !(entry.getValue() instanceof List<?> list)) {
                    continue;
                }
                for (Object start : list) {
                    if (start instanceof Map<?, ?> s && s.get("generated_id") != null) {
                        startIds.add(s.get("generated_id").toString());
                    }
                }
            }
        }
        return startIds;
    }

    /** The discipline id a starts map key stands for; -1 for a key that is not a number. */
    private static int disciplineIdOf(Object key) {
        try {
            return Integer.parseInt(String.valueOf(key));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String label(String key) {
        return switch (key) {
            case "days" -> "meet days";
            case "assignments" -> "lane assignments";
            default -> key;
        };
    }
}
