package com.competition.service;

import com.competition.i18n.Messages;
import com.competition.model.Discipline;
import com.competition.model.RankingDefinition;
import com.competition.model.Start;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StartService {
    private DataService dataService;
    private DisciplineService disciplineService;

    public StartService(DataService dataService, DisciplineService disciplineService) {
        this.dataService = dataService;
        this.disciplineService = disciplineService;
    }

    public Start createStart(int competitorId, int disciplineId) throws Exception {
        return createStart(competitorId, disciplineId, null);
    }

    public Start createStart(int competitorId, int disciplineId, String caliber) throws Exception {
        if (disciplineId == 0) {
            throw new IllegalArgumentException(Messages.get("request.required", "discipline_id"));
        }
        String trimmedCaliber = caliber != null && !caliber.isBlank() ? caliber.trim() : null;

        return dataService.update(data -> {
            // Checked under the data.json lock (lock order: data.json, then catalog/rankings),
            // so the discipline cannot change between the check and the save
            checkTakesStarts(disciplineId);
            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new RecordNotFoundException(Messages.get("competitor.notFound"));
            }

            Map<String, Object> newStartData = addStartData(data, competitorData, competitorId, disciplineId, trimmedCaliber, null);
            return mapToStart(newStartData);
        });
    }

    /** Adds a new start in the discipline to the competitor's starts and returns it. */
    private static Map<String, Object> addStartData(Map<String, Object> data, Map<String, Object> competitorData,
                                                    int competitorId, int disciplineId, String trimmedCaliber,
                                                    List<Integer> rankingIds) {
        // Initialize starts object if it doesn't exist
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) competitorData.get("starts");
        if (startsData == null) {
            startsData = new HashMap<>();
            competitorData.put("starts", startsData);
        }

        // Initialize starts for this discipline if it doesn't exist
        List<Map<String, Object>> existingStarts = startsData.computeIfAbsent(String.valueOf(disciplineId), k -> new ArrayList<>());

        int startNumber = nextStartNumber(competitorData, disciplineId, existingStarts);
        // Separated so e.g. 1/12/3 and 11/2/3 cannot produce the same id. Old
        // data.json files still contain unseparated ids, which stay as they are.
        String generatedId = competitorId + "-" + disciplineId + "-" + startNumber;
        if (startIdExists(data, generatedId)) {
            throw new ConflictException(Messages.get("start.idInUse", generatedId), null);
        }

        Map<String, Object> newStartData = new HashMap<>();
        newStartData.put("generated_id", generatedId);
        newStartData.put("start_number", startNumber);
        newStartData.put("discipline_id", disciplineId);
        newStartData.put("status", "registered");
        if (trimmedCaliber != null) {
            newStartData.put("caliber", trimmedCaliber);
        }
        if (rankingIds != null) {
            newStartData.put("ranking_ids", new ArrayList<>(rankingIds));
        }

        existingStarts.add(newStartData);

        // Sort starts by start number
        existingStarts.sort((a, b) -> Integer.compare(
            ((Number) a.get("start_number")).intValue(),
            ((Number) b.get("start_number")).intValue()));

        return newStartData;
    }

    /**
     * Registers the competitor for one entry of a ranking (a discipline, or for
     * a sum like Remington_O = Colt + Donald Malson_O each of its disciplines).
     * A start the competitor already has in a discipline is used for the
     * ranking too (one Colt start counts in 7_Colt and in Remington_O);
     * otherwise a new start is added with the caliber given. Returns the starts
     * registered, in the entry's order. Refused if the competitor is already
     * registered for the entry.
     */
    public List<Start> registerForRanking(int competitorId, int rankingId, int entryIndex, String caliber)
            throws Exception {
        String trimmedCaliber = caliber != null && !caliber.isBlank() ? caliber.trim() : null;
        return dataService.update(data -> {
            RankingDefinition ranking = dataService.loadRankingConfig().getRankings().stream()
                .filter(r -> r.getId() == rankingId).findFirst()
                .orElseThrow(() -> new RecordNotFoundException(Messages.get("ranking.notFound", rankingId)));
            if (entryIndex < 0 || entryIndex >= ranking.getEntries().size()) {
                throw new IllegalArgumentException(Messages.get("start.noSuchEntry", ranking.getName()));
            }
            List<Integer> disciplineIds = ranking.getEntries().get(entryIndex).getDisciplineIds();
            for (int disciplineId : disciplineIds) {
                checkTakesStarts(disciplineId);
            }
            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new RecordNotFoundException(Messages.get("competitor.notFound"));
            }

            // Per discipline: a start registered for the ranking already, else one to use for it
            List<Map<String, Object>> registered = new ArrayList<>();
            boolean anyNew = false;
            for (int disciplineId : disciplineIds) {
                List<Map<String, Object>> existing = startsIn(competitorData, disciplineId);
                Map<String, Object> start = existing.stream()
                    .filter(st -> st.get("ranking_ids") instanceof List<?> && Start.countsIn(st.get("ranking_ids"), rankingId))
                    .findFirst().orElse(null);
                if (start == null) {
                    anyNew = true;
                    // A start already registered for another ranking (or for none in particular) is shared
                    start = existing.isEmpty() ? null : existing.get(0);
                    if (start == null) {
                        start = addStartData(data, competitorData, competitorId, disciplineId, trimmedCaliber,
                            List.of(rankingId));
                    } else {
                        List<Integer> ids = Start.rankingIdsOf(start.get("ranking_ids"));
                        Set<Integer> merged = new LinkedHashSet<>(ids != null ? ids : List.of());
                        merged.add(rankingId);
                        start.put("ranking_ids", new ArrayList<>(merged));
                        if (trimmedCaliber != null && start.get("caliber") == null) {
                            start.put("caliber", trimmedCaliber);
                        }
                    }
                }
                registered.add(start);
            }
            if (!anyNew) {
                throw new IllegalArgumentException(Messages.get("start.alreadyRegistered", ranking.getName()));
            }
            return registered.stream().map(this::mapToStart).toList();
        });
    }

    /**
     * Withdraws the competitor from a ranking: the ranking is taken off their
     * starts, and starts left in no ranking are deleted with their results.
     * Returns the ids of the deleted starts; their lanes and team memberships
     * are left to the caller, see CascadingDeletes.
     */
    public Set<String> withdrawFromRanking(int competitorId, int rankingId) throws Exception {
        return dataService.update(data -> {
            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new RecordNotFoundException(Messages.get("competitor.notFound"));
            }
            Set<String> deleted = new LinkedHashSet<>();
            boolean found = false;
            if (competitorData.get("starts") instanceof Map<?, ?> startsData) {
                for (Object list : new ArrayList<>(startsData.values())) {
                    if (!(list instanceof List<?> starts)) {
                        continue;
                    }
                    for (Object item : new ArrayList<>(starts)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> start = (Map<String, Object>) item;
                        List<Integer> ids = Start.rankingIdsOf(start.get("ranking_ids"));
                        if (ids == null || !ids.contains(rankingId)) {
                            continue;
                        }
                        found = true;
                        List<Integer> rest = new ArrayList<>(ids);
                        rest.remove(Integer.valueOf(rankingId));
                        if (rest.isEmpty()) {
                            starts.remove(item);
                            deleted.add((String) start.get("generated_id"));
                        } else {
                            start.put("ranking_ids", rest);
                        }
                    }
                }
                startsData.values().removeIf(list -> list instanceof List<?> l && l.isEmpty());
            }
            if (!found) {
                throw new RecordNotFoundException(Messages.get("start.notRegistered"));
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
            if (resultsData != null) {
                resultsData.removeIf(result -> deleted.contains(result.get("start_id")));
            }
            return deleted;
        });
    }

    /** The competitor's starts in the discipline, by start number. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> startsIn(Map<String, Object> competitorData, int disciplineId) {
        if (competitorData.get("starts") instanceof Map<?, ?> startsData
                && startsData.get(String.valueOf(disciplineId)) instanceof List<?> list) {
            return (List<Map<String, Object>>) list;
        }
        return List.of();
    }

    /**
     * One higher than any start number the competitor ever had in the
     * discipline (counted in the competitor's "next_start_numbers"), so a
     * deleted start's id is never handed out again: a team, a lane or a
     * printed label may still name it. DataService raises the counter above
     * every stored start on load, so it also holds for older files.
     */
    private static int nextStartNumber(Map<String, Object> competitorData, int disciplineId,
                                       List<Map<String, Object>> existingStarts) {
        @SuppressWarnings("unchecked")
        Map<String, Object> counters = competitorData.get(DataService.NEXT_START_NUMBERS) instanceof Map<?, ?> m
            ? (Map<String, Object>) m : new HashMap<>();
        competitorData.put(DataService.NEXT_START_NUMBERS, counters);
        String key = String.valueOf(disciplineId);
        int next = counters.get(key) instanceof Number n ? n.intValue() : 1;
        for (Map<String, Object> startData : existingStarts) {
            next = Math.max(next, ((Number) startData.get("start_number")).intValue() + 1);
        }
        counters.put(key, next + 1);
        return next;
    }

    /**
     * Competitors start in the individual disciplines of the meet's program (the
     * disciplines its rankings cover), not in team disciplines.
     */
    private void checkTakesStarts(int disciplineId) throws Exception {
        Discipline discipline = disciplineService.getAvailableDisciplineById(disciplineId);
        if (discipline == null) {
            throw new IllegalArgumentException(Messages.get("discipline.doesNotExist", disciplineId));
        }
        if (discipline.isTeam()) {
            throw new IllegalArgumentException(Messages.get("start.teamDiscipline", discipline.getName()));
        }
        if (!dataService.loadProgramIds().contains(disciplineId)) {
            throw new IllegalArgumentException(Messages.get("discipline.notInProgram", discipline.getName()));
        }
    }

    /**
     * Deletes the start with its result. Lanes and team memberships naming it
     * are left to the caller, see CascadingDeletes.
     */
    public void deleteStart(int competitorId, String generatedId) throws Exception {
        dataService.update(data -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");

            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new RecordNotFoundException(Messages.get("competitor.notFound"));
            }

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) competitorData.get("starts");

            if (startsData == null || startsData.isEmpty()) {
                throw new RecordNotFoundException(Messages.get("start.notFound"));
            }

            boolean startFound = false;
            for (Map.Entry<String, List<Map<String, Object>>> entry : new ArrayList<>(startsData.entrySet())) {
                List<Map<String, Object>> starts = entry.getValue();
                int originalLength = starts.size();
                starts.removeIf(start -> generatedId.equals(start.get("generated_id")));

                if (starts.size() < originalLength) {
                    startFound = true;

                    // Remove discipline entry if no starts left
                    if (starts.isEmpty()) {
                        startsData.remove(entry.getKey());
                    }

                    // Remove associated results
                    resultsData.removeIf(result -> generatedId.equals(result.get("start_id")));
                    break;
                }
            }

            if (!startFound) {
                throw new RecordNotFoundException(Messages.get("start.notFound"));
            }
            return null;
        });
    }

    // Results link to starts only by id, so an id counts as taken if any
    // competitor's start or any (possibly orphaned) result already uses it
    @SuppressWarnings("unchecked")
    private static boolean startIdExists(Map<String, Object> data, String generatedId) {
        List<Map<String, Object>> competitorsData = (List<Map<String, Object>>) data.get("competitors");
        if (competitorsData != null) {
            for (Map<String, Object> compData : competitorsData) {
                Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) compData.get("starts");
                if (startsData == null) {
                    continue;
                }
                for (List<Map<String, Object>> starts : startsData.values()) {
                    for (Map<String, Object> start : starts) {
                        if (generatedId.equals(start.get("generated_id"))) {
                            return true;
                        }
                    }
                }
            }
        }
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        if (resultsData != null) {
            for (Map<String, Object> result : resultsData) {
                if (generatedId.equals(result.get("start_id"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Object> findCompetitor(Map<String, Object> data, int competitorId) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = (List<Map<String, Object>>) data.get("competitors");
        for (Map<String, Object> compData : competitorsData) {
            if (((Number) compData.get("id")).intValue() == competitorId) {
                return compData;
            }
        }
        return null;
    }

    private Start mapToStart(Map<String, Object> data) {
        Start start = new Start();
        start.setGeneratedId((String) data.get("generated_id"));
        start.setStartNumber(((Number) data.get("start_number")).intValue());
        start.setDisciplineId(((Number) data.get("discipline_id")).intValue());
        start.setStatus((String) data.get("status"));
        start.setCaliber((String) data.get("caliber"));
        start.setRankingIds(Start.rankingIdsOf(data.get("ranking_ids")));
        return start;
    }
}
