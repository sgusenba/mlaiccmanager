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
        return dataService.read(data -> {
            List<Map<String, Object>> resultsData = resultsOf(data);
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
                    enrichedResult.put("version", DataService.getVersion(resultData));
                    results.add(enrichedResult);
                }
            }

            return results;
        });
    }

    public Result createResult(Result result) throws Exception {
        return dataService.update(data -> {
            List<Map<String, Object>> resultsData = resultsOf(data);

            // Only one result per start: if another user entered it first, hand theirs back
            if (result.getStartId() != null) {
                for (Map<String, Object> existingResult : resultsData) {
                    if (result.getStartId().equals(existingResult.get("start_id"))) {
                        throw new ConflictException("A result for this start was already entered by someone else",
                            mapToResult(existingResult));
                    }
                }
            }

            result.setId(dataService.getNextId(resultsData));
            result.setCreatedAt(LocalDateTime.now().format(formatter));
            result.setUpdatedAt(LocalDateTime.now().format(formatter));
            result.setVersion(1);

            resultsData.add(mapFromResult(result));
            return result;
        });
    }

    public Result updateResult(int id, Result result) throws Exception {
        return dataService.update(data -> {
            Map<String, Object> existingData = findById(resultsOf(data), id);
            if (existingData == null) {
                throw new RecordNotFoundException("Result not found");
            }
            DataService.checkVersion(existingData, result.getVersion(),
                "Result was changed by someone else", mapToResult(existingData));

            existingData.put("value", result.getValue());
            existingData.put("entries", result.getEntries());
            existingData.put("override_value", result.getOverrideValue());
            existingData.put("notes", result.getNotes());
            existingData.put("updated_at", LocalDateTime.now().format(formatter));
            DataService.bumpVersion(existingData);

            return mapToResult(existingData);
        });
    }

    public void deleteResult(int id, Integer expectedVersion) throws Exception {
        dataService.update(data -> {
            List<Map<String, Object>> resultsData = resultsOf(data);
            Map<String, Object> existingData = findById(resultsData, id);
            if (existingData == null) {
                throw new RecordNotFoundException("Result not found");
            }
            DataService.checkVersion(existingData, expectedVersion,
                "Result was changed by someone else", mapToResult(existingData));

            resultsData.remove(existingData);
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("results");
    }

    private static Map<String, Object> findById(List<Map<String, Object>> resultsData, int id) {
        for (Map<String, Object> resultData : resultsData) {
            if (((Number) resultData.get("id")).intValue() == id) {
                return resultData;
            }
        }
        return null;
    }

    private Result mapToResult(Map<String, Object> data) {
        Result result = new Result();
        result.setId(((Number) data.get("id")).intValue());
        result.setCompetitorId(((Number) data.get("competitor_id")).intValue());
        result.setDisciplineId(((Number) data.get("discipline_id")).intValue());
        result.setStartId((String) data.get("start_id"));
        result.setValue(((Number) data.get("value")).doubleValue());
        List<Double> entries = new ArrayList<>();
        if (data.get("entries") instanceof List<?> rawEntries) {
            for (Object entry : rawEntries) {
                entries.add(entry instanceof Number ? ((Number) entry).doubleValue() : 0.0);
            }
        }
        result.setEntries(entries);
        result.setOverrideValue(data.get("override_value") != null ? ((Number) data.get("override_value")).doubleValue() : null);
        result.setNotes((String) data.get("notes"));
        result.setCreatedAt((String) data.get("created_at"));
        result.setUpdatedAt((String) data.get("updated_at"));
        result.setVersion(DataService.getVersion(data));
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
        data.put("version", result.getVersion() != null ? result.getVersion() : 0);
        return data;
    }
}
