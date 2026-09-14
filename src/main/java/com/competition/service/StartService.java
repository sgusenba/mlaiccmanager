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
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = (List<Map<String, Object>>) data.get("competitors");
        
        Map<String, Object> competitorData = null;
        for (Map<String, Object> compData : competitorsData) {
            if (((Number) compData.get("id")).intValue() == competitorId) {
                competitorData = compData;
                break;
            }
        }
        
        if (competitorData == null) {
            throw new IllegalArgumentException("Competitor not found");
        }
        
        if (disciplineId == 0) {
            throw new IllegalArgumentException("discipline_id is required");
        }
        
        // Initialize starts object if it doesn't exist
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) competitorData.get("starts");
        if (startsData == null) {
            startsData = new HashMap<>();
            competitorData.put("starts", startsData);
        }
        
        // Initialize starts for this discipline if it doesn't exist
        String disciplineKey = String.valueOf(disciplineId);
        if (!startsData.containsKey(disciplineKey)) {
            startsData.put(disciplineKey, new ArrayList<>());
        }
        
        List<Map<String, Object>> existingStarts = startsData.get(disciplineKey);
        
        // Generate start ID
        String generatedId = dataService.generateStartId(competitorId, disciplineId, existingStarts);
        
        // Check if this start ID already exists
        for (Map<String, Object> startData : existingStarts) {
            if (generatedId.equals(startData.get("generated_id"))) {
                throw new IllegalArgumentException("Start already exists for this competitor and discipline");
            }
        }
        
        // Create new start
        Map<String, Object> newStartData = new HashMap<>();
        newStartData.put("generated_id", generatedId);
        newStartData.put("start_number", existingStarts.size() + 1);
        newStartData.put("discipline_id", disciplineId);
        newStartData.put("status", "registered");
        
        existingStarts.add(newStartData);
        
        // Sort starts by start number
        existingStarts.sort((a, b) -> {
            Integer numA = (Integer) a.get("start_number");
            Integer numB = (Integer) b.get("start_number");
            return numA.compareTo(numB);
        });
        
        dataService.saveData(data);
        
        return mapToStart(newStartData);
    }

    public void deleteStart(int competitorId, String generatedId) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = (List<Map<String, Object>>) data.get("competitors");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        
        Map<String, Object> competitorData = null;
        for (Map<String, Object> compData : competitorsData) {
            if (((Number) compData.get("id")).intValue() == competitorId) {
                competitorData = compData;
                break;
            }
        }
        
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
        
        dataService.saveData(data);
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