package com.competition.service;

import com.competition.model.Discipline;
import com.competition.model.Result;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResultService {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    
    private DataService dataService;
    private DisciplineService disciplineService;

    public ResultService(DataService dataService, DisciplineService disciplineService) {
        this.dataService = dataService;
        this.disciplineService = disciplineService;
    }

    public List<Map<String, Object>> getResults(Integer disciplineId) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = (List<Map<String, Object>>) data.get("competitors");
        
        List<Map<String, Object>> results = new ArrayList<>();
        
        for (Map<String, Object> resultData : resultsData) {
            // Filter by discipline if specified
            if (disciplineId != null && ((Number) resultData.get("discipline_id")).intValue() != disciplineId) {
                continue;
            }
            
            // Find competitor
            Map<String, Object> competitorData = null;
            for (Map<String, Object> compData : competitorsData) {
                if (((Number) compData.get("id")).intValue() == ((Number) resultData.get("competitor_id")).intValue()) {
                    competitorData = compData;
                    break;
                }
            }
            
            if (competitorData == null) {
                continue;
            }
            
            // Get discipline info
            Discipline discipline = disciplineService.getAvailableDisciplineById(
                ((Number) resultData.get("discipline_id")).intValue());
            
            if (discipline != null) {
                Map<String, Object> enrichedResult = new HashMap<>();
                enrichedResult.put("id", resultData.get("id"));
                enrichedResult.put("competitor_id", resultData.get("competitor_id"));
                enrichedResult.put("competitor_name", competitorData.get("name"));
                enrichedResult.put("discipline_id", resultData.get("discipline_id"));
                enrichedResult.put("event_name", discipline.getEvent());
                enrichedResult.put("value", resultData.get("value"));
                enrichedResult.put("entries", resultData.get("entries"));
                enrichedResult.put("override_value", resultData.get("override_value"));
                enrichedResult.put("notes", resultData.get("notes"));
                enrichedResult.put("start_id", resultData.get("start_id"));
                results.add(enrichedResult);
            }
        }
        
        return results;
    }

    public Result createResult(Result result) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        
        // Check if result already exists for this start
        if (result.getStartId() != null) {
            for (Map<String, Object> existingResult : resultsData) {
                if (result.getStartId().equals(existingResult.get("start_id"))) {
                    throw new IllegalArgumentException("Result already exists for this start");
                }
            }
        }
        
        result.setId(dataService.getNextId(resultsData));
        result.setCreatedAt(LocalDateTime.now().format(formatter));
        result.setUpdatedAt(LocalDateTime.now().format(formatter));
        
        Map<String, Object> resultMap = mapFromResult(result);
        resultsData.add(resultMap);
        
        data.put("results", resultsData);
        dataService.saveData(data);
        
        return result;
    }

    public Result updateResult(int id, Result result) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        
        Map<String, Object> existingData = null;
        for (Map<String, Object> resultData : resultsData) {
            if (((Number) resultData.get("id")).intValue() == id) {
                existingData = resultData;
                break;
            }
        }
        
        if (existingData == null) {
            throw new IllegalArgumentException("Result not found");
        }
        
        existingData.put("value", result.getValue());
        existingData.put("entries", result.getEntries());
        existingData.put("override_value", result.getOverrideValue());
        existingData.put("notes", result.getNotes());
        existingData.put("updated_at", LocalDateTime.now().format(formatter));
        
        dataService.saveData(data);
        
        return mapToResult(existingData);
    }

    public void deleteResult(int id) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        
        resultsData.removeIf(result -> ((Number) result.get("id")).intValue() == id);
        
        data.put("results", resultsData);
        dataService.saveData(data);
    }

    private Result mapToResult(Map<String, Object> data) {
        Result result = new Result();
        result.setId(((Number) data.get("id")).intValue());
        result.setCompetitorId(((Number) data.get("competitor_id")).intValue());
        result.setDisciplineId(((Number) data.get("discipline_id")).intValue());
        result.setStartId((String) data.get("start_id"));
        result.setValue(((Number) data.get("value")).doubleValue());
        @SuppressWarnings("unchecked")
        List<Double> entries = (List<Double>) data.get("entries");
        result.setEntries(entries);
        result.setOverrideValue(data.get("override_value") != null ? ((Number) data.get("override_value")).doubleValue() : null);
        result.setNotes((String) data.get("notes"));
        result.setCreatedAt((String) data.get("created_at"));
        result.setUpdatedAt((String) data.get("updated_at"));
        return result;
    }

    private Map<String, Object> mapFromResult(Result result) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", result.getId());
        data.put("competitor_id", result.getCompetitorId());
        data.put("discipline_id", result.getDisciplineId());
        data.put("start_id", result.getStartId());
        data.put("value", result.getValue());
        data.put("entries", result.getEntries());
        data.put("override_value", result.getOverrideValue());
        data.put("notes", result.getNotes());
        data.put("created_at", result.getCreatedAt());
        data.put("updated_at", result.getUpdatedAt());
        return data;
    }
}