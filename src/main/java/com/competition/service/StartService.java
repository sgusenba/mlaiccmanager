package com.competition.service;

import com.competition.model.Start;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StartService {
    private DataService dataService;

    public StartService(DataService dataService) {
        this.dataService = dataService;
    }

    public Start createStart(int competitorId, int disciplineId) throws Exception {
        if (disciplineId == 0) {
            throw new IllegalArgumentException("discipline_id is required");
        }

        return dataService.update(data -> {
            Map<String, Object> competitorData = findCompetitor(data, competitorId);
            if (competitorData == null) {
                throw new IllegalArgumentException("Competitor not found");
            }

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
            String generatedId = competitorId + "" + disciplineId + startNumber;

            Map<String, Object> newStartData = new HashMap<>();
            newStartData.put("generated_id", generatedId);
            newStartData.put("start_number", startNumber);
            newStartData.put("discipline_id", disciplineId);
            newStartData.put("status", "registered");

            existingStarts.add(newStartData);

            // Sort starts by start number
            existingStarts.sort((a, b) -> Integer.compare(
                ((Number) a.get("start_number")).intValue(),
                ((Number) b.get("start_number")).intValue()));

            return mapToStart(newStartData);
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
        return start;
    }
}
