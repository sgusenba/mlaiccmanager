package com.competition.service;

import com.competition.model.Competitor;
import com.competition.model.Start;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CompetitorService {
    private static final DateTimeFormatter formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private DataService dataService;

    public CompetitorService(DataService dataService) {
        this.dataService = dataService;
    }

    public List<Competitor> getAllCompetitors() throws Exception {
        return dataService.read(data -> {
            List<Map<String, Object>> competitorsData = competitorsOf(data);

            List<Competitor> competitors = new ArrayList<>();
            if (competitorsData != null) {
                for (Map<String, Object> competitorData : competitorsData) {
                    competitors.add(mapToCompetitor(competitorData));
                }
            }
            return competitors;
        });
    }

    public Competitor createCompetitor(Competitor competitor) throws Exception {
        return dataService.update(data -> {
            List<Map<String, Object>> competitorsData = competitorsOf(data);

            competitor.setId(dataService.getNextId(competitorsData));
            competitor.setCreatedAt(LocalDateTime.now().format(formatter));
            competitor.setStarts(new HashMap<>());
            competitor.setVersion(1);

            competitorsData.add(mapFromCompetitor(competitor));
            return competitor;
        });
    }

    /**
     * Updates the competitor's personal details. Starts and relay number are not
     * taken from the payload: starts are managed only through StartService, so a
     * form save can never wipe starts another user added meanwhile.
     */
    public Competitor updateCompetitor(int id, Competitor competitor) throws Exception {
        return dataService.update(data -> {
            Map<String, Object> existingData = findById(competitorsOf(data), id);
            if (existingData == null) {
                throw new RecordNotFoundException("Competitor not found");
            }
            DataService.checkVersion(existingData, competitor.getVersion(),
                "Competitor was changed by someone else", mapToCompetitor(existingData));

            existingData.put("name", competitor.getName());
            existingData.put("gender", competitor.getGender());
            existingData.put("year_of_birth", competitor.getYearOfBirth());
            existingData.put("club", competitor.getClub());
            existingData.put("email", competitor.getEmail());
            existingData.put("phone", competitor.getPhone());
            existingData.put("address", competitor.getAddress());
            existingData.put("country", competitor.getCountry());
            DataService.bumpVersion(existingData);

            return mapToCompetitor(existingData);
        });
    }

    public void deleteCompetitor(int id, Integer expectedVersion) throws Exception {
        dataService.update(data -> {
            List<Map<String, Object>> competitorsData = competitorsOf(data);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");

            Map<String, Object> existingData = findById(competitorsData, id);
            if (existingData == null) {
                throw new RecordNotFoundException("Competitor not found");
            }
            DataService.checkVersion(existingData, expectedVersion,
                "Competitor was changed by someone else", mapToCompetitor(existingData));

            // Remove competitor and associated results
            competitorsData.remove(existingData);
            resultsData.removeIf(result -> ((Number) result.get("competitor_id")).intValue() == id);
            return null;
        });
    }

    public Competitor getCompetitorById(int id) throws Exception {
        return dataService.read(data -> {
            Map<String, Object> compData = findById(competitorsOf(data), id);
            return compData != null ? mapToCompetitor(compData) : null;
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> competitorsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("competitors");
    }

    private static Map<String, Object> findById(List<Map<String, Object>> competitorsData, int id) {
        for (Map<String, Object> compData : competitorsData) {
            if (((Number) compData.get("id")).intValue() == id) {
                return compData;
            }
        }
        return null;
    }

    private Competitor mapToCompetitor(Map<String, Object> data) {
        Competitor competitor = new Competitor();
        competitor.setId(((Number) data.get("id")).intValue());
        competitor.setName((String) data.get("name"));
        competitor.setGender((String) data.get("gender"));
        competitor.setClub((String) data.get("club"));
        competitor.setEmail((String) data.get("email"));
        competitor.setPhone((String) data.get("phone"));
        competitor.setAddress((String) data.get("address"));
        competitor.setCountry((String) data.get("country"));
        competitor.setYearOfBirth(data.get("year_of_birth") != null ? (String) data.get("year_of_birth") : "");
        competitor.setCreatedAt((String) data.get("created_at"));
        competitor.setVersion(DataService.getVersion(data));

        // Map starts
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> startsData = (Map<String, List<Map<String, Object>>>) data.get("starts");
        if (startsData != null) {
            Map<String, List<Start>> starts = new HashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> entry : startsData.entrySet()) {
                List<Start> startList = new ArrayList<>();
                for (Map<String, Object> startData : entry.getValue()) {
                    startList.add(mapToStart(startData));
                }
                starts.put(entry.getKey(), startList);
            }
            competitor.setStarts(starts);
        }

        return competitor;
    }

    private Map<String, Object> mapFromCompetitor(Competitor competitor) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", competitor.getId());
        data.put("name", competitor.getName());
        data.put("gender", competitor.getGender());
        data.put("club", competitor.getClub());
        data.put("email", competitor.getEmail());
        data.put("phone", competitor.getPhone());
        data.put("address", competitor.getAddress());
        data.put("country", competitor.getCountry());
        data.put("year_of_birth", competitor.getYearOfBirth());
        data.put("created_at", competitor.getCreatedAt());
        data.put("version", competitor.getVersion() != null ? competitor.getVersion() : 0);

        // Map starts
        if (competitor.getStarts() != null) {
            Map<String, List<Map<String, Object>>> startsData = new HashMap<>();
            for (Map.Entry<String, List<Start>> entry : competitor.getStarts().entrySet()) {
                List<Map<String, Object>> startList = new ArrayList<>();
                for (Start start : entry.getValue()) {
                    startList.add(mapFromStart(start));
                }
                startsData.put(entry.getKey(), startList);
            }
            data.put("starts", startsData);
        } else {
            data.put("starts", new HashMap<>());
        }

        return data;
    }

    private Start mapToStart(Map<String, Object> data) {
        Start start = new Start();
        start.setGeneratedId((String) data.get("generated_id"));
        start.setStartNumber(((Number) data.get("start_number")).intValue());
        start.setDisciplineId(((Number) data.get("discipline_id")).intValue());
        start.setStatus((String) data.get("status"));
        return start;
    }

    private Map<String, Object> mapFromStart(Start start) {
        Map<String, Object> data = new HashMap<>();
        data.put("generated_id", start.getGeneratedId());
        data.put("start_number", start.getStartNumber());
        data.put("discipline_id", start.getDisciplineId());
        data.put("status", start.getStatus());
        return data;
    }
}
