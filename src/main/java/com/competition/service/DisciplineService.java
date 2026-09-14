package com.competition.service;

import com.competition.model.Discipline;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DisciplineService {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    
    private DataService dataService;

    public DisciplineService(DataService dataService) {
        this.dataService = dataService;
    }

    public List<Integer> getActiveDisciplines() throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Integer> activeDisciplines = (List<Integer>) data.get("active_disciplines");
        return activeDisciplines != null ? activeDisciplines : new ArrayList<>();
    }

    public List<Integer> setActiveDisciplines(List<Integer> disciplineIds) throws Exception {
        Map<String, Object> data = dataService.loadData();
        data.put("active_disciplines", disciplineIds);
        dataService.saveData(data);
        return disciplineIds;
    }

    public List<Discipline> getAvailableDisciplines() throws Exception {
        return dataService.loadDisciplines();
    }

    // Custom disciplines are stored separately from the available-disciplines
    // catalog (disciplines.json) and use a "name" key rather than "event",
    // mirroring the Python create_discipline/update_discipline handlers.
    public List<Map<String, Object>> getCustomDisciplines() throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disciplinesData = (List<Map<String, Object>>) data.get("disciplines");
        return disciplinesData != null ? disciplinesData : new ArrayList<>();
    }

    public Map<String, Object> createDiscipline(Map<String, Object> requestData) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disciplinesData = (List<Map<String, Object>>) data.get("disciplines");

        Map<String, Object> discipline = new LinkedHashMap<>();
        discipline.put("id", dataService.getNextId(disciplinesData));
        discipline.put("name", requestData.get("name"));
        discipline.put("description", requestData.get("description"));
        discipline.put("scoring_type", requestData.containsKey("scoring_type") ? requestData.get("scoring_type") : "time");
        discipline.put("unit", requestData.get("unit"));
        discipline.put("created_at", LocalDateTime.now().format(formatter));

        disciplinesData.add(discipline);
        data.put("disciplines", disciplinesData);
        dataService.saveData(data);

        return discipline;
    }

    public Map<String, Object> updateDiscipline(int id, Map<String, Object> requestData) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disciplinesData = (List<Map<String, Object>>) data.get("disciplines");

        Map<String, Object> existingData = null;
        for (Map<String, Object> discData : disciplinesData) {
            if (((Number) discData.get("id")).intValue() == id) {
                existingData = discData;
                break;
            }
        }

        if (existingData == null) {
            throw new IllegalArgumentException("Discipline not found");
        }

        existingData.put("name", requestData.containsKey("name") ? requestData.get("name") : existingData.get("name"));
        existingData.put("description", requestData.containsKey("description") ? requestData.get("description") : existingData.get("description"));
        existingData.put("scoring_type", requestData.containsKey("scoring_type") ? requestData.get("scoring_type") : existingData.get("scoring_type"));
        existingData.put("unit", requestData.containsKey("unit") ? requestData.get("unit") : existingData.get("unit"));

        dataService.saveData(data);

        return existingData;
    }

    public void deleteDiscipline(int id) throws Exception {
        Map<String, Object> data = dataService.loadData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> disciplinesData = (List<Map<String, Object>>) data.get("disciplines");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");
        
        // Remove discipline
        disciplinesData.removeIf(disc -> ((Number) disc.get("id")).intValue() == id);
        
        // Remove associated results
        resultsData.removeIf(result -> ((Number) result.get("discipline_id")).intValue() == id);
        
        data.put("disciplines", disciplinesData);
        data.put("results", resultsData);
        dataService.saveData(data);
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
}