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
        DisciplineService.Snapshot catalog = disciplineService.snapshot();
        Discipline discipline = catalog.get(disciplineId);
        if (discipline == null) {
            return null;
        }
        if (TeamService.isTeamDiscipline(discipline)) {
            return teamService.getRanking(disciplineId);
        }
        if (DisciplineService.isAggregate(discipline)) {
            return aggregateEntry(discipline, catalog);
        }
        if (DisciplineService.isCombinedRanking(discipline)) {
            return detailedEntry(combinedRankingScope(discipline, catalog));
        }
        return detailedEntry(scopeOf(discipline, catalog.combinedPairs()));
    }

    private Map<String, Object> detailedEntry(Scope scope) throws Exception {
        List<Map<String, Object>> disciplineResults = getResultsForDisciplines(scope.ids);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", "individual");
        response.put("discipline", scope.info);
        response.put("rankings", disciplineResults.isEmpty()
            ? new ArrayList<>() : buildRankings(disciplineResults, scope.typeById, scope.tagById));
        return response;
    }

    /**
     * Ranking for every active discipline that currently has results (team
     * disciplines: teams), mirroring the Python get_all_ranking() handler.
     */
    public Map<Integer, Object> getAllRankings() throws Exception {
        DisciplineService.Snapshot catalog = disciplineService.snapshot();
        List<Integer> activeDisciplines = catalog.activeIds();
        Map<Integer, DisciplineService.EventPair> combinedPairs = catalog.combinedPairs();
        Map<Integer, List<Discipline>> rankedThrough = catalog.activeCombinedRankingsByMember();
        Map<Integer, Object> allRankings = new LinkedHashMap<>();
        Set<Integer> done = new HashSet<>();

        for (int disciplineId : activeDisciplines) {
            Discipline discipline = catalog.get(disciplineId);
            if (discipline == null) {
                continue;
            }
            if (TeamService.isTeamDiscipline(discipline)) {
                if (teamService.hasTeams(disciplineId)) {
                    allRankings.put(disciplineId, teamService.getRanking(disciplineId));
                }
                continue;
            }
            if (DisciplineService.isAggregate(discipline)) {
                Map<String, Object> entry = aggregateEntry(discipline, catalog);
                if (!((List<?>) entry.get("rankings")).isEmpty()) {
                    allRankings.put(disciplineId, entry);
                }
                continue;
            }

            if (rankedThrough.containsKey(disciplineId)) {
                continue; // ranked only through its combined ranking(s)
            }
            Scope scope = DisciplineService.isCombinedRanking(discipline)
                ? combinedRankingScope(discipline, catalog) : scopeOf(discipline, combinedPairs);
            if (!done.add(scope.id)) {
                continue; // the other half of a combined pair, already ranked
            }
            Map<String, Object> entry = detailedEntry(scope);
            if (((List<?>) entry.get("rankings")).isEmpty()) {
                continue;
            }
            allRankings.put(scope.id, entry);
        }

        return allRankings;
    }

    /**
     * Ranking that lists each competitor once, on their best result: ranked by
     * its score, then by their next result's score and so on (another start,
     * or another discipline of a combined ranking; another result beats none),
     * then the best result's 10s, 9s, ... 1s, then its tie-break (lower wins).
     * The other results are listed below the best one ("components"). A team
     * discipline gets the team ranking, which already has
     * one total per team. Returns null if the discipline does not exist.
     */
    public Map<String, Object> getBestResultRanking(int disciplineId) throws Exception {
        DisciplineService.Snapshot catalog = disciplineService.snapshot();
        Discipline discipline = catalog.get(disciplineId);
        if (discipline == null) {
            return null;
        }
        if (TeamService.isTeamDiscipline(discipline)) {
            return teamService.getRanking(disciplineId);
        }
        if (DisciplineService.isAggregate(discipline)) {
            return aggregateEntry(discipline, catalog);
        }
        if (DisciplineService.isCombinedRanking(discipline)) {
            return bestResultEntry(combinedRankingScope(discipline, catalog), catalog);
        }
        return bestResultEntry(scopeOf(discipline, catalog.combinedPairs()), catalog);
    }

    /** {@link #getBestResultRanking} for every active discipline that has results or starts (team disciplines: teams). */
    public Map<Integer, Object> getAllBestResultRankings() throws Exception {
        DisciplineService.Snapshot catalog = disciplineService.snapshot();
        Map<Integer, DisciplineService.EventPair> combinedPairs = catalog.combinedPairs();
        Map<Integer, List<Discipline>> rankedThrough = catalog.activeCombinedRankingsByMember();
        Map<Integer, Object> allRankings = new LinkedHashMap<>();
        Set<Integer> done = new HashSet<>();
        for (int disciplineId : catalog.activeIds()) {
            Discipline discipline = catalog.get(disciplineId);
            if (discipline == null) {
                continue;
            }
            if (TeamService.isTeamDiscipline(discipline)) {
                if (teamService.hasTeams(disciplineId)) {
                    allRankings.put(disciplineId, teamService.getRanking(disciplineId));
                }
                continue;
            }
            if (DisciplineService.isAggregate(discipline)) {
                Map<String, Object> entry = aggregateEntry(discipline, catalog);
                if (!((List<?>) entry.get("rankings")).isEmpty()) {
                    allRankings.put(disciplineId, entry);
                }
                continue;
            }
            if (rankedThrough.containsKey(disciplineId)) {
                continue; // ranked only through its combined ranking(s)
            }
            Scope scope = DisciplineService.isCombinedRanking(discipline)
                ? combinedRankingScope(discipline, catalog) : scopeOf(discipline, combinedPairs);
            if (!done.add(scope.id)) {
                continue; // the other half of a combined pair, already ranked
            }
            Map<String, Object> entry = bestResultEntry(scope, catalog);
            if (!((List<?>) entry.get("rankings")).isEmpty()) {
                allRankings.put(scope.id, entry);
            }
        }
        return allRankings;
    }

    private Map<String, Object> bestResultEntry(Scope scope, DisciplineService.Snapshot catalog) throws Exception {
        List<Map<String, Object>> disciplineResults = getResultsForDisciplines(scope.ids);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = dataService.read(
            data -> (List<Map<String, Object>>) data.get("competitors"));

        // Each competitor's results, best first; the competitors in encounter order
        Map<Integer, List<BestResult>> resultsByCompetitor = new LinkedHashMap<>();
        for (Map<String, Object> result : disciplineResults) {
            int competitorId = ((Number) result.get("competitor_id")).intValue();
            resultsByCompetitor.computeIfAbsent(competitorId, k -> new ArrayList<>()).add(new BestResult(result));
        }
        resultsByCompetitor.values().forEach(results -> results.sort((a, b) -> compareKeys(b.key, a.key)));
        Map<Integer, Discipline> disciplinesById = new HashMap<>();
        for (int id : scope.ids) {
            Discipline d = catalog.get(id);
            if (d != null) {
                disciplinesById.put(id, d);
            }
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<List<BestResult>> standings = new ArrayList<>();
        resultsByCompetitor.entrySet().stream()
            .sorted((a, b) -> compareStandings(b.getValue(), a.getValue()))
            .forEach(entry -> {
                Map<String, Object> competitorData = findCompetitor(competitorsData, entry.getKey());
                if (competitorData == null) {
                    return;
                }
                BestResult best = entry.getValue().get(0);
                Map<String, Object> competitor = new LinkedHashMap<>();
                competitor.put("id", entry.getKey());
                competitor.put("name", competitorData.get("name"));
                competitor.put("club", competitorData.get("club"));
                competitor.put("country", competitorData.get("country"));

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("competitor", competitor);
                row.put("start_id", best.result.get("start_id"));
                row.put("result_id", best.result.get("id"));
                row.put("score", best.score);
                row.put("freq_counts", best.freqCounts);
                row.put("override_value", best.overrideValue);
                row.put("notes", best.result.get("notes"));
                row.put("has_result", true);
                if (scope.typeById != null) {
                    row.put("discipline_type", scope.typeById.get(disciplineIdOf(best.result)));
                }
                if (scope.tagById != null) {
                    row.put("discipline_tag", scope.tagById.get(disciplineIdOf(best.result)));
                }
                // The competitor's other results (another start, or another discipline of a combined
                // ranking), shown below the best one
                List<Map<String, Object>> others = new ArrayList<>();
                for (BestResult other : entry.getValue().subList(1, entry.getValue().size())) {
                    Discipline d = disciplinesById.get(disciplineIdOf(other.result));
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("discipline_id", disciplineIdOf(other.result));
                    c.put("label", scope.ids.size() > 1 && d != null ? componentLabel(d) : "Further result");
                    c.put("short_name", scope.ids.size() > 1 && d != null ? d.getShortName() : null);
                    c.put("start_id", other.result.get("start_id"));
                    c.put("score", other.score);
                    c.put("freq_counts", other.freqCounts);
                    c.put("override_value", other.overrideValue);
                    others.add(c);
                }
                if (!others.isEmpty()) {
                    row.put("components", others);
                }
                rows.add(row);
                standings.add(entry.getValue());
            });

        // Competitors who are equal on every count share the same rank
        for (int i = 0; i < rows.size(); i++) {
            boolean tiedWithPrevious = i > 0 && compareStandings(standings.get(i), standings.get(i - 1)) == 0;
            rows.get(i).put("rank", tiedWithPrevious ? rows.get(i - 1).get("rank") : i + 1);
        }

        // Everyone else who started in this discipline follows without a rank
        rows.addAll(startersWithoutResult(competitorsData, scope, resultsByCompetitor.keySet()));

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "individual");
        entry.put("discipline", scope.info);
        entry.put("rankings", rows);
        return entry;
    }

    /**
     * Ranking of an aggregate discipline (e.g. Remington): everyone with a start
     * in it, ranked on the best result of each component discipline added up -
     * as soon as there is one, a missing one counts as nothing. Ties go to the
     * 10s, 9s, ... 1s of those results together, then the tie-break: the
     * furthest shot of those results (the highest of their tie-breaks, unknown
     * while one is missing), lower wins. Starters without any result follow
     * without a rank.
     */
    private Map<String, Object> aggregateEntry(Discipline aggregate, DisciplineService.Snapshot catalog) throws Exception {
        List<Discipline> components = catalog.components(aggregate);
        List<Integer> componentIds = components.stream().map(Discipline::getId).collect(Collectors.toList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> competitorsData = dataService.read(
            data -> (List<Map<String, Object>>) data.get("competitors"));

        // Each competitor's best result per component
        Map<Integer, Map<Integer, BestResult>> bestByCompetitor = new HashMap<>();
        for (Map<String, Object> result : getResultsForDisciplines(componentIds)) {
            int competitorId = ((Number) result.get("competitor_id")).intValue();
            Map<Integer, BestResult> bestByComponent = bestByCompetitor.computeIfAbsent(competitorId, k -> new HashMap<>());
            BestResult candidate = new BestResult(result);
            BestResult best = bestByComponent.get(disciplineIdOf(result));
            if (best == null || compareKeys(candidate.key, best.key) > 0) {
                bestByComponent.put(disciplineIdOf(result), candidate);
            }
        }

        List<Map<String, Object>> ranked = new ArrayList<>();
        Map<Map<String, Object>, double[]> keys = new IdentityHashMap<>();
        List<Map<String, Object>> unranked = new ArrayList<>();
        for (Map<String, Object> competitorData : competitorsData) {
            String startId = firstStartId(competitorData, aggregate.getId());
            if (startId == null) {
                continue; // not entered in the aggregate
            }
            int competitorId = ((Number) competitorData.get("id")).intValue();
            Map<Integer, BestResult> bestByComponent = bestByCompetitor.getOrDefault(competitorId, Map.of());
            // Shown below the competitor: each component's best result with its own ring counts
            List<Map<String, Object>> componentRows = new ArrayList<>();
            List<BestResult> counted = new ArrayList<>();
            for (Discipline component : components) {
                BestResult best = bestByComponent.get(component.getId());
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("discipline_id", component.getId());
                c.put("label", componentLabel(component));
                c.put("short_name", component.getShortName());
                c.put("start_id", best != null ? best.result.get("start_id") : null);
                c.put("score", best != null ? best.score : null);
                c.put("freq_counts", best != null ? best.freqCounts : null);
                c.put("override_value", best != null ? best.overrideValue : null);
                componentRows.add(c);
                if (best != null) {
                    counted.add(best);
                }
            }

            Map<String, Object> competitor = new LinkedHashMap<>();
            competitor.put("id", competitorId);
            competitor.put("name", competitorData.get("name"));
            competitor.put("club", competitorData.get("club"));
            competitor.put("country", competitorData.get("country"));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", null);
            row.put("competitor", competitor);
            row.put("start_id", startId);
            row.put("result_id", null);
            row.put("components", componentRows);
            row.put("notes", null);

            if (counted.isEmpty()) {
                row.put("score", null);
                row.put("freq_counts", null);
                row.put("override_value", null);
                row.put("has_result", false);
                unranked.add(row);
                continue;
            }
            double score = 0;
            int[] rings = Scoring.newRingCounts();
            Double tieBreak = null;
            boolean tieBreakKnown = true;
            for (BestResult best : counted) {
                score += best.score;
                Scoring.addRingCounts(best.result, rings);
                if (best.overrideValue == null) {
                    tieBreakKnown = false;
                } else if (tieBreak == null || best.overrideValue > tieBreak) {
                    tieBreak = best.overrideValue;
                }
            }
            if (!tieBreakKnown) {
                tieBreak = null;
            }
            Map<String, Integer> freqCounts = new LinkedHashMap<>();
            for (int ring = 0; ring <= Scoring.MAX_RING; ring++) {
                freqCounts.put(String.valueOf(ring), rings[ring]);
            }
            double[] key = new double[2 + Scoring.MAX_RING];
            key[0] = score;
            for (int ring = Scoring.MAX_RING; ring >= 1; ring--) {
                key[1 + Scoring.MAX_RING - ring] = rings[ring];
            }
            key[key.length - 1] = Scoring.tieBreakKey(tieBreak);
            row.put("score", score);
            row.put("freq_counts", freqCounts);
            row.put("override_value", tieBreak);
            row.put("has_result", true);
            ranked.add(row);
            keys.put(row, key);
        }

        // Highest first; competitors with an identical key share the same rank
        ranked.sort((a, b) -> compareKeys(keys.get(b), keys.get(a)));
        for (int i = 0; i < ranked.size(); i++) {
            boolean tiedWithPrevious = i > 0 && compareKeys(keys.get(ranked.get(i)), keys.get(ranked.get(i - 1))) == 0;
            ranked.get(i).put("rank", tiedWithPrevious ? ranked.get(i - 1).get("rank") : i + 1);
        }
        unranked.sort(Comparator.comparing(
            row -> Objects.toString(((Map<?, ?>) row.get("competitor")).get("name"), ""),
            String.CASE_INSENSITIVE_ORDER));
        List<Map<String, Object>> rows = new ArrayList<>(ranked);
        rows.addAll(unranked);

        Map<String, Object> info = buildDisciplineInfo(aggregate);
        List<Map<String, Object>> componentInfo = new ArrayList<>();
        for (Discipline component : components) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", component.getId());
            c.put("name", component.getEvent());
            c.put("label", componentLabel(component));
            c.put("short_name", component.getShortName());
            componentInfo.add(c);
        }
        info.put("components", componentInfo);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("kind", "individual");
        entry.put("discipline", info);
        entry.put("rankings", rows);
        return entry;
    }

    /** A component as listed below a competitor, e.g. "12_Mariette/R", "23_Donald Malson/O" or "91_Meixner/RS". */
    private static String componentLabel(Discipline component) {
        String name = DisciplineService.baseEvent(component.getEvent());
        String event = component.getEvent() != null ? component.getEvent().trim() : "";
        String variant = event.matches(".*[_ ]R[SU]$") ? event.substring(event.length() - 2) : null;
        String tag = variant != null ? variant
            : "original".equals(component.getType()) ? "O" : "reproduction".equals(component.getType()) ? "R" : null;
        return tag != null ? name + "/" + tag : name;
    }

    /** The id of the competitor's first start in the discipline, or null if there is none. */
    private static String firstStartId(Map<String, Object> competitorData, int disciplineId) {
        if (competitorData.get("starts") instanceof Map<?, ?> startsByDiscipline
                && startsByDiscipline.get(String.valueOf(disciplineId)) instanceof List<?> starts
                && !starts.isEmpty() && starts.get(0) instanceof Map<?, ?> start) {
            return Objects.toString(start.get("generated_id"), "");
        }
        return null;
    }

    /** Rows for the competitors with a start in the discipline(s) but no result yet, by name. */
    private List<Map<String, Object>> startersWithoutResult(List<Map<String, Object>> competitorsData,
            Scope scope, Set<Integer> withResult) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> competitorData : competitorsData) {
            int competitorId = ((Number) competitorData.get("id")).intValue();
            if (withResult.contains(competitorId)
                    || !(competitorData.get("starts") instanceof Map<?, ?> startsByDiscipline)) {
                continue;
            }
            Map<?, ?> firstStart = null;
            int startDisciplineId = 0;
            for (int disciplineId : scope.ids) {
                if (startsByDiscipline.get(String.valueOf(disciplineId)) instanceof List<?> starts
                        && !starts.isEmpty() && starts.get(0) instanceof Map<?, ?> start) {
                    firstStart = start;
                    startDisciplineId = disciplineId;
                    break;
                }
            }
            if (firstStart == null) {
                continue;
            }
            Map<String, Object> competitor = new LinkedHashMap<>();
            competitor.put("id", competitorId);
            competitor.put("name", competitorData.get("name"));
            competitor.put("club", competitorData.get("club"));
            competitor.put("country", competitorData.get("country"));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", null);
            row.put("competitor", competitor);
            row.put("start_id", firstStart.get("generated_id"));
            row.put("result_id", null);
            row.put("score", null);
            row.put("freq_counts", null);
            row.put("override_value", null);
            row.put("notes", null);
            row.put("has_result", false);
            if (scope.typeById != null) {
                row.put("discipline_type", scope.typeById.get(startDisciplineId));
            }
            if (scope.tagById != null) {
                row.put("discipline_tag", scope.tagById.get(startDisciplineId));
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing(
            row -> Objects.toString(((Map<?, ?>) row.get("competitor")).get("name"), ""),
            String.CASE_INSENSITIVE_ORDER));
        return rows;
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

    /**
     * What one ranking covers: a single discipline, or the original and
     * reproduction discipline of an event ranked combined. A combined ranking is
     * listed under the original's id and tags each row with the type shot.
     */
    private static class Scope {
        int id;
        List<Integer> ids;
        Map<String, Object> info;
        Map<Integer, String> typeById; // null unless combined
        Map<Integer, String> tagById;  // combined ranking only: the short name of the discipline shot
    }

    /**
     * A combined ranking (e.g. KUC = KUCO + KUCRS + KUCRU) ranks its disciplines'
     * results together under its own id and name, tagging each row with the
     * discipline shot.
     */
    private Scope combinedRankingScope(Discipline combined, DisciplineService.Snapshot catalog) {
        Scope scope = new Scope();
        scope.id = combined.getId();
        scope.ids = combined.getCombinedOf();
        scope.info = buildDisciplineInfo(combined);
        scope.info.put("combined_of", scope.ids);
        scope.typeById = new HashMap<>();
        scope.tagById = new HashMap<>();
        for (int id : scope.ids) {
            Discipline member = catalog.get(id);
            if (member != null) {
                scope.typeById.put(id, member.getType());
                scope.tagById.put(id, member.getShortName() != null ? member.getShortName() : member.getEvent());
            }
        }
        return scope;
    }

    private Scope scopeOf(Discipline discipline, Map<Integer, DisciplineService.EventPair> combinedPairs) {
        Scope scope = new Scope();
        scope.info = buildDisciplineInfo(discipline);
        DisciplineService.EventPair pair = combinedPairs.get(discipline.getId());
        if (pair == null) {
            scope.id = discipline.getId();
            scope.ids = List.of(discipline.getId());
            return scope;
        }
        scope.id = pair.originalId();
        scope.ids = List.of(pair.originalId(), pair.reproductionId());
        scope.typeById = Map.of(pair.originalId(), "original", pair.reproductionId(), "reproduction");
        scope.info.put("id", pair.originalId());
        scope.info.put("name", pair.event());
        scope.info.put("type", "combined");
        scope.info.put("combined_ids", scope.ids);
        return scope;
    }

    private static int disciplineIdOf(Map<String, Object> result) {
        return ((Number) result.get("discipline_id")).intValue();
    }

    private List<Map<String, Object>> getResultsForDisciplines(List<Integer> disciplineIds) throws Exception {
        return dataService.read(data -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> resultsData = (List<Map<String, Object>>) data.get("results");

            return resultsData.stream()
                .filter(r -> r.get("discipline_id") instanceof Number && disciplineIds.contains(disciplineIdOf(r)))
                .collect(Collectors.toList());
        });
    }

    private List<Ranking> buildRankings(List<Map<String, Object>> disciplineResults, Map<Integer, String> typeById,
            Map<Integer, String> tagById) throws Exception {
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
            CompetitorAggregate aggregate = aggregate(competitorData, entry.getValue());
            if (typeById != null) {
                // A competitor starts in only one discipline of a combined pair or combined ranking
                aggregate.disciplineType = typeById.get(disciplineIdOf(entry.getValue().get(0)));
            }
            if (tagById != null) {
                aggregate.disciplineTag = tagById.get(disciplineIdOf(entry.getValue().get(0)));
            }
            aggregates.add(aggregate);
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
        for (int i = 0; i <= Scoring.MAX_RING; i++) {
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

    /**
     * Orders two competitors' results (each best first): the best result's
     * score, then the next result's and so on (another result beats none), then
     * the best result's 10s, 9s, ... 1s, then its tie-break (lower wins).
     */
    private int compareStandings(List<BestResult> a, List<BestResult> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i >= a.size() || i >= b.size()) {
                return Integer.compare(a.size(), b.size());
            }
            int cmp = Double.compare(a.get(i).score, b.get(i).score);
            if (cmp != 0) {
                return cmp;
            }
        }
        return compareKeys(a.get(0).key, b.get(0).key);
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
        ranking.setCompetitor(competitorInfo);

        ranking.setResultTotals(aggregate.resultTotals);
        ranking.setTotalSum(aggregate.totalSum);
        ranking.setFreqCounts(aggregate.freqCounts);
        ranking.setOverrideValue(aggregate.overrideValue);
        ranking.setNotes(aggregate.notes);
        ranking.setDisciplineType(aggregate.disciplineType);
        ranking.setDisciplineTag(aggregate.disciplineTag);

        return ranking;
    }

    /** One result with its ranking key: score, then 10s ... 1s, then tie-break. */
    private static class BestResult {
        final Map<String, Object> result;
        final double score;
        final Map<String, Integer> freqCounts = new LinkedHashMap<>();
        final Double overrideValue;
        final double[] key = new double[2 + Scoring.MAX_RING];

        BestResult(Map<String, Object> result) {
            this.result = result;
            this.score = Scoring.score(result);
            this.overrideValue = Scoring.doubleOrNull(result.get("override_value"));
            int[] rings = Scoring.newRingCounts();
            Scoring.addRingCounts(result, rings);
            for (int i = 0; i <= Scoring.MAX_RING; i++) {
                freqCounts.put(String.valueOf(i), rings[i]);
            }
            key[0] = score;
            for (int ring = Scoring.MAX_RING; ring >= 1; ring--) {
                key[1 + Scoring.MAX_RING - ring] = rings[ring];
            }
            key[key.length - 1] = Scoring.tieBreakKey(overrideValue);
        }
    }

    private static class CompetitorAggregate {
        int competitorId;
        String competitorName;
        List<Double> resultTotals;
        double totalSum;
        Map<String, Integer> freqCounts;
        Double overrideValue;
        String notes;
        String disciplineType;
        String disciplineTag;
        double[] key;
    }
}
