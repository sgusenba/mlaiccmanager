package com.competition.service;

import com.competition.model.Discipline;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
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
        return dataService.read(DisciplineService::activeDisciplinesOf);
    }

    /**
     * Replaces the active discipline list. If baseIds (the list the client
     * started from) is given and no longer matches, someone else changed the
     * list meanwhile and the save is rejected instead of silently undoing it.
     */
    public List<Integer> setActiveDisciplines(List<Integer> disciplineIds, List<Integer> baseIds) throws Exception {
        return dataService.update(data -> {
            List<Integer> current = activeDisciplinesOf(data);
            if (baseIds != null && !new HashSet<>(baseIds).equals(new HashSet<>(current))) {
                throw new ConflictException("Active disciplines were changed by someone else", current);
            }
            data.put("active_disciplines", disciplineIds);
            return disciplineIds;
        });
    }

    /** Removes a single discipline from the active list, leaving everyone else's changes intact. */
    public List<Integer> deactivateDiscipline(int disciplineId) throws Exception {
        return dataService.update(data -> {
            List<Integer> active = new ArrayList<>(activeDisciplinesOf(data));
            active.removeIf(id -> id == disciplineId);
            data.put("active_disciplines", active);
            return active;
        });
    }

    public List<Discipline> getAvailableDisciplines() throws Exception {
        return dataService.loadDisciplines();
    }

    // Custom disciplines are stored separately from the available-disciplines
    // catalog (disciplines.json) and use a "name" key rather than "event",
    // mirroring the Python create_discipline/update_discipline handlers.
    public List<Map<String, Object>> getCustomDisciplines() throws Exception {
        return dataService.read(data -> {
            List<Map<String, Object>> disciplinesData = disciplinesOf(data);
            return disciplinesData != null ? disciplinesData : new ArrayList<>();
        });
    }

    public Map<String, Object> createDiscipline(Map<String, Object> requestData) throws Exception {
        return dataService.update(data -> {
            List<Map<String, Object>> disciplinesData = disciplinesOf(data);

            Map<String, Object> discipline = new LinkedHashMap<>();
            discipline.put("id", dataService.getNextId(disciplinesData));
            discipline.put("name", requestData.get("name"));
            discipline.put("description", requestData.get("description"));
            discipline.put("scoring_type", requestData.containsKey("scoring_type") ? requestData.get("scoring_type") : "time");
            discipline.put("unit", requestData.get("unit"));
            discipline.put("created_at", LocalDateTime.now().format(formatter));

            disciplinesData.add(discipline);
            return discipline;
        });
    }

    public Map<String, Object> updateDiscipline(int id, Map<String, Object> requestData) throws Exception {
        return dataService.update(data -> {
            Map<String, Object> existingData = null;
            for (Map<String, Object> discData : disciplinesOf(data)) {
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

            return existingData;
        });
    }

    public void deleteDiscipline(int id) throws Exception {
        dataService.update(data -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");

            // Remove discipline and associated results
            disciplinesOf(data).removeIf(disc -> ((Number) disc.get("id")).intValue() == id);
            resultsData.removeIf(result -> ((Number) result.get("discipline_id")).intValue() == id);
            return null;
        });
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

    private static List<Integer> activeDisciplinesOf(Map<String, Object> data) {
        List<Integer> activeDisciplines = new ArrayList<>();
        if (data.get("active_disciplines") instanceof List<?> rawIds) {
            for (Object id : rawIds) {
                if (id instanceof Number) {
                    activeDisciplines.add(((Number) id).intValue());
                }
            }
        }
        return activeDisciplines;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> disciplinesOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("disciplines");
    }
}
