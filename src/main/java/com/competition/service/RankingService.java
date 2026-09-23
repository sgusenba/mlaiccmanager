package com.competition.service;

import com.competition.model.Discipline;
import com.competition.model.Ranking;

import java.util.*;
import java.util.stream.Collectors;

public class RankingService {

    private DataService dataService;
    private DisciplineService disciplineService;
    private TeamService teamService;

    public RankingService(DataService dataService, DisciplineService disciplineService, TeamService teamService) {
        this.dataService = dataService;
        this.disciplineService = disciplineService;
        this.teamService = teamService;
    }

    /**
     * Detailed ranking for a single discipline, mirroring the Python
     * get_ranking() handler: top-4 results per competitor, frequency
     * counts (rings 1-10), and override-value tie-breaking (lower wins).
     * A team discipline gets the team ranking instead.
     * Returns null if the discipline does not exist (caller maps to 404).
     */
    public Map<String, Object> getRanking(int disciplineId) throws Exception {
        Discipline discipline = disciplineService.getAvailableDisciplineById(disciplineId);
        if (discipline == null) {
            return null;
        }
        if (TeamService.isTeamDiscipline(discipline)) {
            return teamService.getRanking(disciplineId);
        }

        List<Map<String, Object>> disciplineResults = getResultsForDiscipline(disciplineId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "individual");
        response.put("discipline", buildDisciplineInfo(discipline));
        response.put("rankings", disciplineResults.isEmpty() ? new ArrayList<>() : buildRankings(disciplineResults));
        return response;
    }

    /**
     * Ranking for every active discipline that currently has results (team
     * disciplines: teams), mirroring the Python get_all_ranking() handler.
     */
    public Map<Integer, Object> getAllRankings() throws Exception {
        List<Integer> activeDisciplines = disciplineService.getActiveDisciplines();
        Map<Integer, Object> allRankings = new LinkedHashMap<>();

        for (int disciplineId : activeDisciplines) {
            Discipline discipline = disciplineService.getAvailableDisciplineById(disciplineId);
            if (discipline == null) {
                continue;
            }
            if (TeamService.isTeamDiscipline(discipline)) {
                if (teamService.hasTeams(disciplineId)) {
                    allRankings.put(disciplineId, teamService.getRanking(disciplineId));
                }
                continue;
            }

            List<Map<String, Object>> disciplineResults = getResultsForDiscipline(disciplineId);
            if (disciplineResults.isEmpty()) {
                continue;
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", "individual");
            entry.put("discipline", buildDisciplineInfo(discipline));
            entry.put("rankings", buildRankings(disciplineResults));

            allRankings.put(disciplineId, entry);
        }

        return allRankings;
    }

    private Map<String, Object> buildDisciplineInfo(Discipline discipline) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", discipline.getId());
        info.put("name", discipline.getEvent());
        info.put("category", discipline.getCategory());
        info.put("type", discipline.getType());
        info.put("unit", "points");
        return info;
    }

    private List<Map<String, Object>> getResultsForDiscipline(int disciplineId) throws Exception {
        return dataService.read(data -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");

            return resultsData.stream()
                .filter(r -> ((Number) r.get("discipline_id")).intValue() == disciplineId)
                .collect(Collectors.toList());
        });
    }

    private List<Ranking> buildRankings(List<Map<String, Object>> disciplineResults) throws Exception {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = dataService.read(
            data -> (List<Map<String, Object>>) data.get("competitors"));

        // Group results by competitor, preserving encounter order
        Map<Integer, List<Map<String, Object>>> byCompetitor = new LinkedHashMap<>();
        for (Map<String, Object> result : disciplineResults) {
            int competitorId = ((Number) result.get("competitor_id")).intValue();
            byCompetitor.computeIfAbsent(competitorId, k -> new ArrayList<>()).add(result);
        }

        List<CompetitorAggregate> aggregates = new ArrayList<>();
        for (Map.Entry<Integer, List<Map<String, Object>>> entry : byCompetitor.entrySet()) {
            Map<String, Object> competitorData = findCompetitor(competitorsData, entry.getKey());
            if (competitorData == null) {
                continue;
            }
            aggregates.add(aggregate(competitorData, entry.getValue()));
        }

        // Sort by composite ranking key, highest first
        aggregates.sort((a, b) -> compareKeys(b.key, a.key));

        // Assign ranks; competitors with an identical key share the same rank
        List<Ranking> rankings = new ArrayList<>();
        int currentRank = 1;
        int i = 0;
        while (i < aggregates.size()) {
            int j = i + 1;
            while (j < aggregates.size() && compareKeys(aggregates.get(j).key, aggregates.get(i).key) == 0) {
                j++;
            }
            for (int k = i; k < j; k++) {
                rankings.add(toRanking(aggregates.get(k), currentRank));
            }
            currentRank += (j - i);
            i = j;
        }

        return rankings;
    }

    private Map<String, Object> findCompetitor(List<Map<String, Object>> competitorsData, int competitorId) {
        for (Map<String, Object> competitorData : competitorsData) {
            if (((Number) competitorData.get("id")).intValue() == competitorId) {
                return competitorData;
            }
        }
        return null;
    }

    private CompetitorAggregate aggregate(Map<String, Object> competitorData, List<Map<String, Object>> results) {
        // Sort this competitor's results by value, highest first
        List<Map<String, Object>> sortedResults = results.stream()
            .sorted((a, b) -> Double.compare(Scoring.score(b), Scoring.score(a)))
            .collect(Collectors.toList());

        List<Map<String, Object>> topResults = sortedResults.stream().limit(4).collect(Collectors.toList());

        List<Double> resultTotals = topResults.stream()
            .map(Scoring::score)
            .collect(Collectors.toCollection(ArrayList::new));
        while (resultTotals.size() < 4) {
            resultTotals.add(0.0);
        }

        double totalSum = resultTotals.stream().mapToDouble(Double::doubleValue).sum();

        int[] rings = Scoring.newRingCounts();
        for (Map<String, Object> result : results) {
            Scoring.addRingCounts(result, rings);
        }
        Map<String, Integer> freqCounts = new LinkedHashMap<>();
        for (int i = 1; i <= Scoring.MAX_RING; i++) {
            freqCounts.put(String.valueOf(i), rings[i]);
        }

        Map<String, Object> bestResult = topResults.isEmpty() ? null : topResults.get(0);
        Double overrideValue = bestResult != null && bestResult.get("override_value") != null
            ? ((Number) bestResult.get("override_value")).doubleValue()
            : null;
        String notes = bestResult != null ? (String) bestResult.get("notes") : null;

        CompetitorAggregate aggregate = new CompetitorAggregate();
        aggregate.competitorId = ((Number) competitorData.get("id")).intValue();
        aggregate.competitorName = (String) competitorData.get("name");
        aggregate.competitorTeam = (String) competitorData.get("team");
        aggregate.resultTotals = resultTotals;
        aggregate.totalSum = totalSum;
        aggregate.freqCounts = freqCounts;
        aggregate.overrideValue = overrideValue;
        aggregate.notes = notes;
        aggregate.key = buildKey(resultTotals, freqCounts, overrideValue);

        return aggregate;
    }

    private double[] buildKey(List<Double> resultTotals, Map<String, Integer> freqCounts, Double overrideValue) {
        double[] key = new double[15];
        for (int i = 0; i < 4; i++) {
            key[i] = resultTotals.get(i);
        }
        int idx = 4;
        for (int i = 10; i >= 1; i--) {
            key[idx++] = freqCounts.getOrDefault(String.valueOf(i), 0);
        }
        // Tie-break (distance of the furthest shot): the lower value wins
        key[14] = Scoring.tieBreakKey(overrideValue);
        return key;
    }

    private int compareKeys(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            int cmp = Double.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private Ranking toRanking(CompetitorAggregate aggregate, int rank) {
        Ranking ranking = new Ranking();
        ranking.setRank(rank);

        Ranking.CompetitorInfo competitorInfo = new Ranking.CompetitorInfo();
        competitorInfo.setId(aggregate.competitorId);
        competitorInfo.setName(aggregate.competitorName);
        competitorInfo.setTeam(aggregate.competitorTeam);
        ranking.setCompetitor(competitorInfo);

        ranking.setResultTotals(aggregate.resultTotals);
        ranking.setTotalSum(aggregate.totalSum);
        ranking.setFreqCounts(aggregate.freqCounts);
        ranking.setOverrideValue(aggregate.overrideValue);
        ranking.setNotes(aggregate.notes);

        return ranking;
    }

    private static class CompetitorAggregate {
        int competitorId;
        String competitorName;
        String competitorTeam;
        List<Double> resultTotals;
        double totalSum;
        Map<String, Integer> freqCounts;
        Double overrideValue;
        String notes;
        double[] key;
    }
}
