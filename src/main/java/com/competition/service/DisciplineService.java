package com.competition.service;

import com.competition.model.Discipline;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DisciplineService {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private DataService dataService;

    public DisciplineService(DataService dataService) {
        this.dataService = dataService;
    }

    public List<Integer> getActiveDisciplines() throws Exception {
        return activeIdsOf(dataService.loadDisciplines());
    }

    /**
     * Replaces the active discipline list by flipping each catalog discipline's
     * "active" flag. If baseIds (the list the client started from) is given and
     * no longer matches, someone else changed the list meanwhile and the save
     * is rejected instead of silently undoing it.
     */
    public List<Integer> setActiveDisciplines(List<Integer> disciplineIds, List<Integer> baseIds) throws Exception {
        Set<Integer> desired = new HashSet<>(disciplineIds);
        return dataService.updateDisciplines(disciplines -> {
            List<Integer> current = activeIdsOf(disciplines);
            if (baseIds != null && !new HashSet<>(baseIds).equals(new HashSet<>(current))) {
                throw new ConflictException("Active disciplines were changed by someone else", current);
            }
            for (Discipline d : disciplines) {
                d.setActive(desired.contains(d.getId()));
            }
            return activeIdsOf(disciplines);
        });
    }

    /** Deactivates a single discipline, leaving everyone else's changes intact. */
    public List<Integer> deactivateDiscipline(int disciplineId) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            for (Discipline d : disciplines) {
                if (d.getId() == disciplineId) {
                    d.setActive(false);
                }
            }
            return activeIdsOf(disciplines);
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

    public Discipline createCatalogDiscipline(Map<String, Object> data) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            int maxId = disciplines.stream().mapToInt(Discipline::getId).max().orElse(0);
            Discipline d = new Discipline();
            d.setId(maxId + 1);
            applyCatalogFields(d, data);
            disciplines.add(d);
            return d;
        });
    }

    public Discipline updateCatalogDiscipline(int id, Map<String, Object> data) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            Discipline target = disciplines.stream()
                .filter(d -> d.getId() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Discipline not found: " + id));
            applyCatalogFields(target, data);
            return target;
        });
    }

    public void deleteCatalogDiscipline(int id) throws Exception {
        dataService.updateDisciplines(disciplines -> {
            if (!disciplines.removeIf(d -> d.getId() == id)) {
                throw new IllegalArgumentException("Discipline not found: " + id);
            }
            return null;
        });
    }

    public List<Discipline> updateShootingDistances(Map<String, String> mapping) throws Exception {
        return dataService.updateDisciplines(disciplines -> {
            for (Discipline d : disciplines) {
                String value = mapping.get(String.valueOf(d.getId()));
                if (value != null) {
                    d.setShootingDistance(value.isBlank() ? null : value);
                }
            }
            return disciplines;
        });
    }

    private static void applyCatalogFields(Discipline d, Map<String, Object> data) {
        if (data.containsKey("category")) d.setCategory((String) data.get("category"));
        if (data.containsKey("level")) d.setLevel((String) data.get("level"));
        if (data.containsKey("type")) d.setType((String) data.get("type"));
        if (data.containsKey("event")) d.setEvent((String) data.get("event"));
        if (data.containsKey("based_on")) d.setBasedOn((String) data.get("based_on"));
        if (data.containsKey("team_size")) {
            Object ts = data.get("team_size");
            d.setTeamSize(ts instanceof Number ? ((Number) ts).intValue() : null);
        }
        if (data.containsKey("shooting_distance")) {
            String sd = (String) data.get("shooting_distance");
            d.setShootingDistance(sd != null && !sd.isBlank() ? sd : null);
        }
        if (data.containsKey("active")) {
            d.setActive(Boolean.TRUE.equals(data.get("active")));
        }
    }

    private static List<Integer> activeIdsOf(List<Discipline> disciplines) {
        List<Integer> activeIds = new ArrayList<>();
        for (Discipline d : disciplines) {
            if (d.isActive()) {
                activeIds.add(d.getId());
            }
        }
        return activeIds;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> disciplinesOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("disciplines");
    }
}
