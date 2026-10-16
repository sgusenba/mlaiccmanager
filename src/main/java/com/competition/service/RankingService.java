package com.competition.service;

import com.competition.i18n.Messages;
import com.competition.model.Discipline;
import com.competition.model.RankingDefinition;

import java.util.*;

/**
 * The results of the meet's rankings. An individual ranking lists each
 * competitor once: their entry results (a result of one of its disciplines,
 * or the sum of a sum entry) best first, ranked by the best result's score,
 * then the next result's and so on (another result beats none), then the best
 * result's 10s, 9s, ... 1s, then its tie-break (lower wins). The other results
 * are listed below the best one. Competitors with a start but no result follow
 * without a rank. A team ranking ranks the teams of its team disciplines
 * together.
 */
public class RankingService {

    private final DataService dataService;
    private final TeamService teamService;

    public RankingService(DataService dataService, TeamService teamService) {
        this.dataService = dataService;
        this.teamService = teamService;
    }

    /** One ranking's result; null if the meet has no such ranking. */
    public Map<String, Object> getRankingResult(int rankingId) throws Exception {
        RankingDefinition ranking = dataService.loadRankingConfig().getRankings().stream()
            .filter(r -> r.getId() == rankingId).findFirst().orElse(null);
        return ranking != null ? result(ranking, disciplinesById()) : null;
    }

    /** Every ranking's result, in the meet's order; those with no starts, results or teams yet are left out. */
    public List<Map<String, Object>> getAllRankingResults() throws Exception {
        Map<Integer, Discipline> disciplines = disciplinesById();
        List<Map<String, Object>> all = new ArrayList<>();
        for (RankingDefinition ranking : dataService.loadRankingConfig().getRankings()) {
            Map<String, Object> entry = result(ranking, disciplines);
            if (!((List<?>) entry.get("rankings")).isEmpty()) {
                all.add(entry);
            }
        }
        return all;
    }

    private Map<String, Object> result(RankingDefinition ranking, Map<Integer, Discipline> disciplines) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("kind", ranking.isTeam() ? "team" : "individual");
        response.put("ranking", info(ranking, disciplines));
        response.put("rankings", ranking.isTeam()
            ? teamService.rankTogether(new ArrayList<>(ranking.disciplineIds()))
            : individualRows(ranking, disciplines));
        return response;
    }

    private Map<Integer, Discipline> disciplinesById() throws Exception {
        Map<Integer, Discipline> byId = new HashMap<>();
        dataService.loadDisciplines().forEach(d -> byId.put(d.getId(), d));
        return byId;
    }

    /** What the ranking covers, for its heading: entries with their disciplines' short names. */
    private static Map<String, Object> info(RankingDefinition ranking, Map<Integer, Discipline> disciplines) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", ranking.getId());
        info.put("name", ranking.getName());
        info.put("short_name", ranking.getShortName());
        info.put("level", ranking.getLevel());
        info.put("pooled", ranking.getEntries().size() > 1);
        Set<String> categories = new LinkedHashSet<>();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RankingDefinition.Entry e : ranking.getEntries()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", entryLabel(e, disciplines));
            entry.put("discipline_ids", e.getDisciplineIds());
            List<String> shortNames = new ArrayList<>();
            for (int id : e.getDisciplineIds()) {
                Discipline d = disciplines.get(id);
                shortNames.add(d != null ? d.label() : "#" + id);
                if (d != null && d.getCategory() != null) {
                    categories.add(d.getCategory());
                }
            }
            entry.put("short_names", shortNames);
            entries.add(entry);
        }
        info.put("entries", entries);
        info.put("category", String.join(" / ", categories));
        if (ranking.isTeam()) {
            // The individual disciplines that count for its teams, and the team size
            Set<String> composition = new LinkedHashSet<>();
            Integer teamSize = null;
            for (int id : ranking.disciplineIds()) {
                Discipline team = disciplines.get(id);
                if (team == null) {
                    continue;
                }
                teamSize = teamSize == null ? TeamService.teamSize(team) : teamSize;
                if (team.getTeamOf() != null) {
                    team.getTeamOf().forEach(c -> composition.add(disciplines.containsKey(c) ? disciplines.get(c).label() : "#" + c));
                }
            }
            info.put("composition", new ArrayList<>(composition));
            info.put("team_size", teamSize != null ? teamSize : TeamService.DEFAULT_TEAM_SIZE);
        }
        return info;
    }

    /** A sum's label, else the discipline's short name (or name). */
    private static String entryLabel(RankingDefinition.Entry entry, Map<Integer, Discipline> disciplines) {
        if (entry.getLabel() != null) {
            return entry.getLabel();
        }
        Discipline d = entry.getDisciplineIds().isEmpty() ? null : disciplines.get(entry.getDisciplineIds().get(0));
        return d != null ? d.label() : "?";
    }

    // --- individual ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> individualRows(RankingDefinition ranking, Map<Integer, Discipline> disciplines)
            throws Exception {
        Set<Integer> ids = ranking.disciplineIds();
        boolean pooled = ranking.getEntries().size() > 1;
        return dataService.read(data -> {
            List<Map<String, Object>> competitors = (List<Map<String, Object>>) data.get("competitors");
            // Each competitor's results in the ranking's disciplines, by discipline
            Map<Integer, Map<Integer, List<BestResult>>> resultsByCompetitor = new LinkedHashMap<>();
            for (Map<String, Object> result : (List<Map<String, Object>>) data.get("results")) {
                if (!(result.get("discipline_id") instanceof Number d) || !ids.contains(d.intValue())
                        || !(result.get("competitor_id") instanceof Number c)) {
                    continue;
                }
                resultsByCompetitor.computeIfAbsent(c.intValue(), k -> new HashMap<>())
                    .computeIfAbsent(d.intValue(), k -> new ArrayList<>()).add(new BestResult(result));
            }

            // Each competitor's candidates (entry results), best first
            Map<Integer, List<Candidate>> standings = new LinkedHashMap<>();
            for (Map.Entry<Integer, Map<Integer, List<BestResult>>> competitor : resultsByCompetitor.entrySet()) {
                List<Candidate> candidates = new ArrayList<>();
                for (RankingDefinition.Entry entry : ranking.getEntries()) {
                    candidates.addAll(candidatesOf(entry, competitor.getValue(), disciplines));
                }
                if (!candidates.isEmpty()) {
                    candidates.sort((a, b) -> compareKeys(b.key, a.key));
                    standings.put(competitor.getKey(), candidates);
                }
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            List<List<Candidate>> ordered = new ArrayList<>();
            standings.entrySet().stream()
                .sorted((a, b) -> compareStandings(b.getValue(), a.getValue()))
                .forEach(entry -> {
                    Map<String, Object> competitorData = findCompetitor(competitors, entry.getKey());
                    if (competitorData == null) {
                        return;
                    }
                    rows.add(row(competitorData, entry.getValue(), pooled));
                    ordered.add(entry.getValue());
                });
            // Competitors who are equal on every count share the same rank
            for (int i = 0; i < rows.size(); i++) {
                boolean tied = i > 0 && compareStandings(ordered.get(i), ordered.get(i - 1)) == 0;
                rows.get(i).put("rank", tied ? rows.get(i - 1).get("rank") : i + 1);
            }
            rows.addAll(startersWithoutResult(competitors, ranking, disciplines, standings.keySet(), pooled));
            return rows;
        });
    }

    /**
     * The competitor's results that count in an entry: every result of a single
     * discipline, or for a sum the best result of each of its disciplines added
     * up, as soon as there is one (a missing one counts as nothing).
     */
    private static List<Candidate> candidatesOf(RankingDefinition.Entry entry, Map<Integer, List<BestResult>> results,
                                                Map<Integer, Discipline> disciplines) {
        String label = entryLabel(entry, disciplines);
        List<Candidate> candidates = new ArrayList<>();
        if (!entry.isSum()) {
            for (BestResult result : results.getOrDefault(entry.getDisciplineIds().get(0), List.of())) {
                candidates.add(Candidate.of(result, label));
            }
            return candidates;
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        List<BestResult> counted = new ArrayList<>();
        for (int id : entry.getDisciplineIds()) {
            BestResult best = results.getOrDefault(id, List.of()).stream()
                .max((a, b) -> compareKeys(a.key, b.key)).orElse(null);
            Discipline d = disciplines.get(id);
            Map<String, Object> part = new LinkedHashMap<>();
            part.put("discipline_id", id);
            part.put("label", d != null ? d.getName() : "#" + id);
            part.put("short_name", d != null ? d.getShortName() : null);
            part.put("start_id", best != null ? best.result.get("start_id") : null);
            part.put("score", best != null ? best.score : null);
            part.put("freq_counts", best != null ? best.freqCounts : null);
            part.put("override_value", best != null ? best.overrideValue : null);
            parts.add(part);
            if (best != null) {
                counted.add(best);
            }
        }
        if (!counted.isEmpty()) {
            candidates.add(Candidate.sum(counted, parts, label));
        }
        return candidates;
    }

    private static Map<String, Object> row(Map<String, Object> competitorData, List<Candidate> candidates, boolean pooled) {
        Candidate best = candidates.get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("competitor", competitorInfo(competitorData));
        row.put("start_id", best.startId);
        row.put("result_id", best.resultId);
        row.put("score", best.score);
        row.put("freq_counts", best.freqCounts);
        row.put("override_value", best.overrideValue);
        row.put("notes", best.notes);
        row.put("has_result", true);
        if (pooled) {
            row.put("discipline_tag", best.label);
        }
        // Below the competitor: the disciplines added up in the best result, then their other results
        List<Map<String, Object>> components = new ArrayList<>(best.parts);
        for (Candidate other : candidates.subList(1, candidates.size())) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("label", pooled ? other.label : Messages.get("ranking.furtherResult"));
            c.put("short_name", pooled ? other.label : null);
            c.put("start_id", other.startId);
            c.put("score", other.score);
            c.put("freq_counts", other.freqCounts);
            c.put("override_value", other.overrideValue);
            c.put("further", true);
            components.add(c);
        }
        if (!components.isEmpty()) {
            row.put("components", components);
        }
        return row;
    }

    /** Rows for the competitors with a start in the ranking's disciplines but no result yet, by name. */
    private static List<Map<String, Object>> startersWithoutResult(List<Map<String, Object>> competitors,
            RankingDefinition ranking, Map<Integer, Discipline> disciplines, Set<Integer> withResult, boolean pooled) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> competitorData : competitors) {
            int competitorId = ((Number) competitorData.get("id")).intValue();
            if (withResult.contains(competitorId) || !(competitorData.get("starts") instanceof Map<?, ?> starts)) {
                continue;
            }
            Map<?, ?> firstStart = null;
            String label = null;
            for (RankingDefinition.Entry entry : ranking.getEntries()) {
                for (int id : entry.getDisciplineIds()) {
                    if (firstStart == null && starts.get(String.valueOf(id)) instanceof List<?> list
                            && !list.isEmpty() && list.get(0) instanceof Map<?, ?> start) {
                        firstStart = start;
                        label = entryLabel(entry, disciplines);
                    }
                }
            }
            if (firstStart == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", null);
            row.put("competitor", competitorInfo(competitorData));
            row.put("start_id", firstStart.get("generated_id"));
            row.put("result_id", null);
            row.put("score", null);
            row.put("freq_counts", null);
            row.put("override_value", null);
            row.put("notes", null);
            row.put("has_result", false);
            if (pooled) {
                row.put("discipline_tag", label);
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing(
            row -> Objects.toString(((Map<?, ?>) row.get("competitor")).get("name"), ""),
            String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private static Map<String, Object> competitorInfo(Map<String, Object> competitorData) {
        Map<String, Object> competitor = new LinkedHashMap<>();
        competitor.put("id", competitorData.get("id"));
        competitor.put("name", competitorData.get("name"));
        competitor.put("club", competitorData.get("club"));
        competitor.put("country", competitorData.get("country"));
        return competitor;
    }

    private static Map<String, Object> findCompetitor(List<Map<String, Object>> competitors, int competitorId) {
        for (Map<String, Object> competitorData : competitors) {
            if (((Number) competitorData.get("id")).intValue() == competitorId) {
                return competitorData;
            }
        }
        return null;
    }

    /**
     * Orders two competitors' candidates (each best first): the best one's
     * score, then the next one's and so on (another result beats none), then
     * the best one's 10s, 9s, ... 1s, then its tie-break (lower wins).
     */
    private static int compareStandings(List<Candidate> a, List<Candidate> b) {
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

    private static int compareKeys(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            int cmp = Double.compare(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    /** Ranking key: score, then 10s ... 1s, then tie-break (higher is better throughout). */
    private static double[] key(double score, int[] rings, Double tieBreak) {
        double[] key = new double[2 + Scoring.MAX_RING];
        key[0] = score;
        for (int ring = Scoring.MAX_RING; ring >= 1; ring--) {
            key[1 + Scoring.MAX_RING - ring] = rings[ring];
        }
        key[key.length - 1] = Scoring.tieBreakKey(tieBreak);
        return key;
    }

    private static Map<String, Integer> freqCounts(int[] rings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i <= Scoring.MAX_RING; i++) {
            counts.put(String.valueOf(i), rings[i]);
        }
        return counts;
    }

    /** One stored result with its ranking key. */
    private static class BestResult {
        final Map<String, Object> result;
        final double score;
        final int[] rings = Scoring.newRingCounts();
        final Map<String, Integer> freqCounts;
        final Double overrideValue;
        final double[] key;

        BestResult(Map<String, Object> result) {
            this.result = result;
            this.score = Scoring.score(result);
            this.overrideValue = Scoring.doubleOrNull(result.get("override_value"));
            Scoring.addRingCounts(result, rings);
            this.freqCounts = freqCounts(rings);
            this.key = key(score, rings, overrideValue);
        }
    }

    /** A result that counts in the ranking: one discipline's result, or a sum entry's. */
    private static class Candidate {
        String label;
        Object startId;
        Object resultId;
        Object notes;
        double score;
        Map<String, Integer> freqCounts;
        Double overrideValue;
        double[] key;
        List<Map<String, Object>> parts = List.of();

        static Candidate of(BestResult result, String label) {
            Candidate c = new Candidate();
            c.label = label;
            c.startId = result.result.get("start_id");
            c.resultId = result.result.get("id");
            c.notes = result.result.get("notes");
            c.score = result.score;
            c.freqCounts = result.freqCounts;
            c.overrideValue = result.overrideValue;
            c.key = result.key;
            return c;
        }

        /**
         * The best results of a sum's disciplines added up: their scores and ring
         * counts; the tie-break is the furthest shot of them (the highest
         * tie-break), unknown while one of them has none.
         */
        static Candidate sum(List<BestResult> counted, List<Map<String, Object>> parts, String label) {
            Candidate c = new Candidate();
            c.label = label;
            c.startId = counted.get(0).result.get("start_id");
            c.parts = parts;
            int[] rings = Scoring.newRingCounts();
            Double tieBreak = null;
            boolean tieBreakKnown = true;
            for (BestResult best : counted) {
                c.score += best.score;
                for (int i = 0; i < rings.length; i++) {
                    rings[i] += best.rings[i];
                }
                if (best.overrideValue == null) {
                    tieBreakKnown = false;
                } else if (tieBreak == null || best.overrideValue > tieBreak) {
                    tieBreak = best.overrideValue;
                }
            }
            c.overrideValue = tieBreakKnown ? tieBreak : null;
            c.freqCounts = freqCounts(rings);
            c.key = key(c.score, rings, c.overrideValue);
            return c;
        }
    }
}
