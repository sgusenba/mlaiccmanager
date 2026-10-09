package com.competition.service;

import com.competition.model.Start;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StartService {
    private DataService dataService;
    private DisciplineService disciplineService;

    public StartService(DataService dataService) {
        this(dataService, null);
    }

    public StartService(DataService dataService, DisciplineService disciplineService) {
        this.dataService = dataService;
        this.disciplineService = disciplineService;
    }

    public Start createStart(int competitorId, int disciplineId) throws Exception {
        return createStart(competitorId, disciplineId, null);
    }

    public Start createStart(int competitorId, int disciplineId, String caliber) throws Exception {
        if (disciplineId == 0) {
            throw new IllegalArgumentException("discipline_id is required");
        }
        String trimmedCaliber = caliber != null && !caliber.isBlank() ? caliber.trim() : null;

        return dataService.update(data -> {
            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new IllegalArgumentException("Competitor not found");
            }
            checkNotStartingInCombinedPartner(competitorData, disciplineId);

            // Initialize starts object if it doesn't exist
            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) competitorData.get("starts");
            if (startsData == null) {
                startsData = new HashMap<>();
                competitorData.put("starts", startsData);
            }

            // Initialize starts for this discipline if it doesn't exist
            List<Map<String, Object>> existingStarts = startsData.computeIfAbsent(String.valueOf(disciplineId), k -> new ArrayList<>());

            // Next number after the highest existing one, so deleting an earlier
            // start never makes the new start collide with a later one
            int startNumber = 1;
            for (Map<String, Object> startData : existingStarts) {
                startNumber = Math.max(startNumber, ((Number) startData.get("start_number")).intValue() + 1);
            }
            // Separated so e.g. 1/12/3 and 11/2/3 cannot produce the same id. Old
            // data.json files still contain unseparated ids, which stay as they are.
            String generatedId = competitorId + "-" + disciplineId + "-" + startNumber;
            if (startIdExists(data, generatedId)) {
                throw new ConflictException("Start ID " + generatedId + " is already in use", null);
            }

            Map<String, Object> newStartData = new HashMap<>();
            newStartData.put("generated_id", generatedId);
            newStartData.put("start_number", startNumber);
            newStartData.put("discipline_id", disciplineId);
            newStartData.put("status", "registered");
            if (trimmedCaliber != null) {
                newStartData.put("caliber", trimmedCaliber);
            }

            existingStarts.add(newStartData);

            // Sort starts by start number
            existingStarts.sort((a, b) -> Integer.compare(
                ((Number) a.get("start_number")).intValue(),
                ((Number) b.get("start_number")).intValue()));

            return mapToStart(newStartData);
        });
    }

    /**
     * A combined ranking holds each competitor once, so while an event is ranked
     * combined a competitor may start in its original or its reproduction
     * discipline, not both.
     */
    private void checkNotStartingInCombinedPartner(Map<String, Object> competitorData, int disciplineId) throws Exception {
        if (disciplineService == null) {
            return;
        }
        DisciplineService.EventPair pair = disciplineService.getCombinedPairsByDiscipline().get(disciplineId);
        if (pair != null && DisciplineService.startsIn(competitorData, pair.partnerOf(disciplineId))) {
            throw new ConflictException(competitorData.get("name") + " already starts in " + pair.event()
                + " (" + pair.typeOf(pair.partnerOf(disciplineId)) + "), which is ranked combined with "
                + pair.typeOf(disciplineId), null);
        }
    }

    /**
     * Files a start under the other type of its event, original to
     * reproduction or back, keeping its id (lanes, teams and labels refer to
     * it) and its results. While the event is ranked combined, a competitor's
     * starts must all be of one type, so the competitor's other starts of the
     * event have to be switched first.
     */
    @SuppressWarnings("unchecked")
    public Start switchType(int competitorId, String generatedId) throws Exception {
        if (disciplineService == null) {
            throw new IllegalStateException("Switching needs the discipline catalog");
        }
        List<com.competition.model.Discipline> disciplines = disciplineService.getAvailableDisciplines();
        Map<Integer, DisciplineService.EventPair> combinedPairs = disciplineService.getCombinedPairsByDiscipline();
        return dataService.update(data -> {
            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new IllegalArgumentException("Competitor not found");
            }
            Map<String, List<Map<String, Object>>> startsData =
                (Map<String, List<Map<String, Object>>>) competitorData.get("starts");
            String fromKey = null;
            Map<String, Object> start = null;
            if (startsData != null) {
                for (Map.Entry<String, List<Map<String, Object>>> entry : startsData.entrySet()) {
                    for (Map<String, Object> candidate : entry.getValue()) {
                        if (generatedId.equals(candidate.get("generated_id"))) {
                            fromKey = entry.getKey();
                            start = candidate;
                        }
                    }
                }
            }
            if (start == null) {
                throw new IllegalArgumentException("Start not found");
            }
            int fromId = Integer.parseInt(fromKey);
            DisciplineService.EventPair pair = DisciplineService.eventPairsOf(disciplines).stream()
                .filter(p -> p.originalId() == fromId || p.reproductionId() == fromId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                    "This start's discipline has no original and reproduction to switch between"));
            int toId = pair.partnerOf(fromId);
            if (combinedPairs.containsKey(fromId) && startsData.get(fromKey).size() > 1) {
                // Switching one would leave the competitor in both types of a combined event
                throw new ConflictException(competitorData.get("name") + " has " + startsData.get(fromKey).size()
                    + " starts in " + pair.event() + " (" + pair.typeOf(fromId) + "), which is ranked combined: "
                    + "delete the others before switching this one", null);
            }

            startsData.get(fromKey).remove(start);
            if (startsData.get(fromKey).isEmpty()) {
                startsData.remove(fromKey);
            }
            start.put("discipline_id", toId);
            startsData.computeIfAbsent(String.valueOf(toId), k -> new ArrayList<>()).add(start);
            if (data.get("results") instanceof List<?> results) {
                for (Object r : results) {
                    if (r instanceof Map<?, ?> result && generatedId.equals(result.get("start_id"))) {
                        ((Map<String, Object>) result).put("discipline_id", toId);
                    }
                }
            }
            return mapToStart(start);
        });
    }

    public void deleteStart(int competitorId, String generatedId) throws Exception {
        dataService.update(data -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");

            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new IllegalArgumentException("Competitor not found");
            }

            @SuppressWarnings("unchecked")
            Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) competitorData.get("starts");

            if (startsData == null || startsData.isEmpty()) {
                throw new IllegalArgumentException("No starts found for competitor");
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
                throw new IllegalArgumentException("Start not found");
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
        return start;
    }
}
