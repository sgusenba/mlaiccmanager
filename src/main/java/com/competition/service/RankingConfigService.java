package com.competition.service;

import com.competition.model.Discipline;
import com.competition.model.RankingConfig;
import com.competition.model.RankingDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The meet's rankings (Wertungen), stored in rankings.json. A ranking has its
 * own name and ranks one or more entries together; an entry is one discipline
 * or the sum of several. The disciplines the rankings cover are the meet's
 * program: only they take starts and teams. Exported and imported on their own.
 */
public class RankingConfigService {
    public static final String FORMAT = "mlaic-rankings";
    public static final int FORMAT_VERSION = 1;

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DataService dataService;
    private final TeamService teamService;
    // The shipped default rankings (default-rankings.json), loaded by "Load defaults"
    private final String defaultRankingsFilePath;

    public RankingConfigService(DataService dataService, TeamService teamService, String defaultRankingsFilePath) {
        this.dataService = dataService;
        this.teamService = teamService;
        this.defaultRankingsFilePath = defaultRankingsFilePath;
    }

    public RankingConfig getConfig() throws Exception {
        return dataService.loadRankingConfig();
    }

    public RankingDefinition getRanking(int id) throws Exception {
        return findRanking(getConfig(), id);
    }

    /** The meet's rankings by the id of each discipline they cover, e.g. for the start cards. */
    public Map<Integer, List<Map<String, Object>>> getRankingsByDiscipline() throws Exception {
        Map<Integer, List<Map<String, Object>>> byDiscipline = new LinkedHashMap<>();
        for (RankingDefinition ranking : getConfig().getRankings()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", ranking.getId());
            info.put("name", ranking.getName());
            info.put("short_name", ranking.getShortName());
            for (int id : ranking.disciplineIds()) {
                byDiscipline.computeIfAbsent(id, k -> new ArrayList<>()).add(info);
            }
        }
        return byDiscipline;
    }

    // --- changes ---------------------------------------------------------------

    public RankingDefinition createRanking(Map<String, Object> request) throws Exception {
        RankingDefinition ranking = parse(request);
        return change(ranking, (config, disciplines) -> {
            ranking.setId(config.takeNextId());
            ranking.setVersion(0);
            check(ranking, config.getMaxDisciplinesPerRanking(), disciplines);
            config.getRankings().add(ranking);
            return ranking;
        });
    }

    public RankingDefinition updateRanking(int id, Map<String, Object> request) throws Exception {
        RankingDefinition ranking = parse(request);
        Integer version = request != null && request.get("version") instanceof Number n ? n.intValue() : null;
        return change(ranking, (config, disciplines) -> {
            RankingDefinition stored = findRanking(config, id);
            if (version != null && stored.getVersion() != version) {
                throw new ConflictException("Someone else changed this ranking in the meantime", stored);
            }
            ranking.setId(id);
            ranking.setVersion(stored.getVersion() + 1);
            check(ranking, config.getMaxDisciplinesPerRanking(), disciplines);
            config.getRankings().set(config.getRankings().indexOf(stored), ranking);
            return ranking;
        });
    }

    public void deleteRanking(int id, Integer version) throws Exception {
        change(null, (config, disciplines) -> {
            RankingDefinition stored = findRanking(config, id);
            if (version != null && stored.getVersion() != version) {
                throw new ConflictException("Someone else changed this ranking in the meantime", stored);
            }
            config.getRankings().remove(stored);
            return null;
        });
    }

    /** Puts the rankings in the given order; ids left out keep their order after the listed ones. */
    public RankingConfig reorder(List<Integer> ids) throws Exception {
        return dataService.updateRankingConfig((config, catalog) -> {
            List<RankingDefinition> ordered = new ArrayList<>();
            for (int id : ids) {
                ordered.add(findRanking(config, id));
            }
            for (RankingDefinition r : config.getRankings()) {
                if (!ordered.contains(r)) {
                    ordered.add(r);
                }
            }
            config.setRankings(ordered);
            config.setVersion(config.getVersion() + 1);
            return config;
        });
    }

    /** Sets how many disciplines one ranking may cover; refused below what a ranking covers now. */
    public RankingConfig setMaxDisciplines(int max) throws Exception {
        if (max < 1) {
            throw new IllegalArgumentException("The limit must be at least 1");
        }
        return dataService.updateRankingConfig((config, catalog) -> {
            for (RankingDefinition r : config.getRankings()) {
                check(r, max, catalog.getDisciplines());
            }
            config.setMaxDisciplinesPerRanking(max);
            config.setVersion(config.getVersion() + 1);
            return config;
        });
    }

    // --- export / import / defaults --------------------------------------------

    /** The rankings as a file of their own (format mlaic-rankings); each entry also names its disciplines for the reader. */
    public ObjectNode exportRankings() throws Exception {
        RankingConfig config = getConfig();
        Map<Integer, Discipline> byId = new HashMap<>();
        dataService.loadDisciplines().forEach(d -> byId.put(d.getId(), d));

        ObjectNode file = objectMapper.createObjectNode();
        file.put("format", FORMAT);
        file.put("version", FORMAT_VERSION);
        file.put("max_disciplines_per_ranking", config.getMaxDisciplinesPerRanking());
        ArrayNode rankings = file.putArray("rankings");
        for (RankingDefinition r : config.getRankings()) {
            ObjectNode node = objectMapper.valueToTree(r);
            node.remove("version");
            for (JsonNode entry : node.path("entries")) {
                ArrayNode names = ((ObjectNode) entry).putArray("discipline_short_names");
                entry.path("discipline_ids").forEach(id -> {
                    Discipline d = byId.get(id.asInt());
                    names.add(d != null ? d.label() : "?");
                });
            }
            rankings.add(node);
        }
        return file;
    }

    /**
     * Replaces the meet's rankings with those of a rankings file. Everything is
     * checked first; an unknown discipline id or a ranking that does not fit
     * changes nothing.
     */
    public RankingConfig importRankings(JsonNode file) throws Exception {
        RankingConfig imported = parseFile(file);
        return replaceAll(imported, false);
    }

    /**
     * Replaces the meet's rankings with the shipped default rankings. Disciplines
     * this installation's catalog lacks are left out, and so is a ranking left
     * with nothing.
     */
    public RankingConfig loadDefaults() throws Exception {
        JsonNode file = DataService.readShipped(defaultRankingsFilePath);
        if (file == null) {
            throw new IllegalArgumentException("There are no default rankings ("
                + new File(defaultRankingsFilePath).getName() + " is missing)");
        }
        return replaceAll(parseFile(file), true);
    }

    private RankingConfig replaceAll(RankingConfig incoming, boolean dropUnknown) throws Exception {
        Map<Integer, Integer> teamCounts = teamService != null ? teamService.teamCounts() : Map.of();
        return dataService.read(data -> dataService.updateRankingConfig((config, catalog) -> {
            if (dropUnknown) {
                Set<Integer> known = new HashSet<>();
                catalog.getDisciplines().forEach(d -> known.add(d.getId()));
                for (RankingDefinition r : incoming.getRankings()) {
                    r.getEntries().forEach(e -> e.getDisciplineIds().removeIf(id -> !known.contains(id)));
                    r.getEntries().removeIf(e -> e.getDisciplineIds().isEmpty());
                }
                incoming.getRankings().removeIf(r -> r.getEntries().isEmpty());
            }
            List<String> problems = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            for (RankingDefinition r : incoming.getRankings()) {
                if (r.getId() <= 0 || !ids.add(r.getId())) {
                    r.setId(0); // numbered below
                }
                r.setVersion(0);
                normalize(r);
                try {
                    check(r, incoming.getMaxDisciplinesPerRanking(), catalog.getDisciplines());
                } catch (IllegalArgumentException e) {
                    problems.add(e.getMessage());
                }
            }
            for (RankingDefinition r : incoming.getRankings()) {
                if (r.getId() == 0) {
                    int id = config.takeNextId();
                    while (!ids.add(id)) {
                        id = config.takeNextId();
                    }
                    r.setId(id);
                }
            }
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("Nothing imported: " + String.join("; ", problems));
            }
            checkProgramKeepsEntries(config, incoming.getRankings(), data, teamCounts, catalog.getDisciplines());
            checkOneStartRules(incoming.getRankings(), data);

            config.setMaxDisciplinesPerRanking(incoming.getMaxDisciplinesPerRanking());
            config.raiseNextId(); // the replaced rankings' ids stay taken
            config.setRankings(incoming.getRankings());
            config.setVersion(config.getVersion() + 1);
            return config;
        }));
    }

    private static RankingConfig parseFile(JsonNode file) throws Exception {
        if (file == null || !file.isObject() || !FORMAT.equals(file.path("format").asText())
                || !file.path("rankings").isArray()) {
            throw new IllegalArgumentException("Not a rankings file (format " + FORMAT + ")");
        }
        if (file.path("version").asInt() > FORMAT_VERSION) {
            throw new IllegalArgumentException("The rankings file is of a newer version");
        }
        RankingConfig config = new RankingConfig();
        config.setMaxDisciplinesPerRanking(file.path("max_disciplines_per_ranking").asInt(RankingConfig.DEFAULT_MAX_DISCIPLINES));
        List<RankingDefinition> rankings = new ArrayList<>();
        for (JsonNode node : file.path("rankings")) {
            rankings.add(objectMapper.treeToValue(node, RankingDefinition.class));
        }
        config.setRankings(rankings);
        if (config.getMaxDisciplinesPerRanking() < 1) {
            throw new IllegalArgumentException("max_disciplines_per_ranking must be at least 1");
        }
        return config;
    }

    // --- checks ----------------------------------------------------------------

    @FunctionalInterface
    private interface Change<T> {
        T apply(RankingConfig config, List<Discipline> disciplines) throws Exception;
    }

    /**
     * Runs a change of one ranking (changed: the ranking as it will be, null when
     * deleting), then refuses it if it leaves starts or teams outside the
     * program, or breaks the ranking's one-start rule.
     */
    private <T> T change(RankingDefinition changed, Change<T> fn) throws Exception {
        Map<Integer, Integer> teamCounts = teamService != null ? teamService.teamCounts() : Map.of();
        List<String> inSeveralTeams = changed != null && changed.isTeam() && changed.isOneStartPerCompetitor()
            && teamService != null ? teamService.competitorsInSeveralTeams(changed.disciplineIds()) : List.of();
        // Lock order: data.json, then rankings
        return dataService.read(data -> dataService.updateRankingConfig((config, catalog) -> {
            List<RankingDefinition> before = new ArrayList<>(config.getRankings());
            T result = fn.apply(config, catalog.getDisciplines());
            RankingConfig previous = new RankingConfig();
            previous.setRankings(before);
            checkProgramKeepsEntries(previous, config.getRankings(), data, teamCounts, catalog.getDisciplines());
            if (changed != null) {
                checkOneStartRules(List.of(changed), data);
                if (!inSeveralTeams.isEmpty()) {
                    throw new ConflictException(changed.getName() + " allows one team per competitor, but these are in "
                        + "more than one of its teams: " + String.join(", ", inSeveralTeams), inSeveralTeams);
                }
            }
            config.setVersion(config.getVersion() + 1);
            return result;
        }));
    }

    /**
     * A discipline that leaves the program must not keep starts or teams: they
     * could no longer be ranked. Lists them instead.
     */
    private static void checkProgramKeepsEntries(RankingConfig before, List<RankingDefinition> after,
            Map<String, Object> data, Map<Integer, Integer> teamCounts, List<Discipline> disciplines) {
        Set<Integer> remaining = new HashSet<>();
        after.forEach(r -> remaining.addAll(r.disciplineIds()));
        Map<Integer, Discipline> byId = new HashMap<>();
        disciplines.forEach(d -> byId.put(d.getId(), d));

        Set<Integer> leaving = new LinkedHashSet<>();
        before.getRankings().forEach(r -> leaving.addAll(r.disciplineIds()));
        leaving.removeAll(remaining);

        Map<Integer, Integer> starts = startCounts(data);
        List<String> problems = new ArrayList<>();
        for (int id : leaving) {
            String name = byId.containsKey(id) ? byId.get(id).getName() : "Discipline " + id;
            if (starts.getOrDefault(id, 0) > 0) {
                problems.add(name + " (" + starts.get(id) + " start(s))");
            }
            if (teamCounts.getOrDefault(id, 0) > 0) {
                problems.add(name + " (" + teamCounts.get(id) + " team(s))");
            }
        }
        if (!problems.isEmpty()) {
            throw new ConflictException("These disciplines would no longer be in any ranking, but have entries; "
                + "keep them in a ranking or remove the entries first: " + String.join(", ", problems), problems);
        }
    }

    /** An individual ranking with one start per competitor must not have anyone starting in two of its disciplines. */
    private static void checkOneStartRules(List<RankingDefinition> rankings, Map<String, Object> data) {
        for (RankingDefinition r : rankings) {
            if (!r.isOneStartPerCompetitor() || r.isTeam()) {
                continue;
            }
            List<String> names = competitorsStartingInMoreThanOne(data, r.disciplineIds());
            if (!names.isEmpty()) {
                throw new ConflictException(r.getName() + " allows one start per competitor, but these start in "
                    + "more than one of its disciplines: " + String.join(", ", names), names);
            }
        }
    }

    /**
     * A ranking has a name and at least one entry. Each entry is one discipline
     * or a labelled sum of several, all of the ranking's level; a discipline is
     * in a ranking once; a ranking covers at most max disciplines. A team
     * ranking pools team disciplines only (teams exist per discipline, so they
     * cannot be added up), each counting at most max individual disciplines.
     */
    public static void check(RankingDefinition r, int max, List<Discipline> disciplines) {
        if (r.getName() == null || r.getName().isBlank()) {
            throw new IllegalArgumentException("A ranking needs a name");
        }
        if (!DisciplineService.LEVELS.contains(r.getLevel())) {
            throw new IllegalArgumentException(r.getName() + ": level must be individual or team");
        }
        if (r.getEntries().isEmpty()) {
            throw new IllegalArgumentException(r.getName() + ": add at least one discipline");
        }
        Map<Integer, Discipline> byId = new HashMap<>();
        disciplines.forEach(d -> byId.put(d.getId(), d));
        Set<Integer> seen = new HashSet<>();
        for (RankingDefinition.Entry entry : r.getEntries()) {
            if (entry.getDisciplineIds().isEmpty()) {
                throw new IllegalArgumentException(r.getName() + ": an entry has no discipline");
            }
            if (entry.isSum() && r.isTeam()) {
                throw new IllegalArgumentException(r.getName() + ": a team ranking cannot add up team disciplines");
            }
            if (entry.isSum() && (entry.getLabel() == null || entry.getLabel().isBlank())) {
                throw new IllegalArgumentException(r.getName() + ": name the sum of several disciplines");
            }
            for (int id : entry.getDisciplineIds()) {
                Discipline d = byId.get(id);
                if (d == null) {
                    throw new IllegalArgumentException(r.getName() + ": there is no discipline " + id);
                }
                if (d.isTeam() != r.isTeam()) {
                    throw new IllegalArgumentException(r.getName() + ": " + d.getName() + " is "
                        + (d.isTeam() ? "a team" : "an individual") + " discipline, the ranking is "
                        + (r.isTeam() ? "a team" : "an individual") + " ranking");
                }
                if (!seen.add(id)) {
                    throw new IllegalArgumentException(r.getName() + ": " + d.getName() + " is in it twice");
                }
                if (d.isTeam() && d.getTeamOf() != null && d.getTeamOf().size() > max) {
                    throw new IllegalArgumentException(r.getName() + ": " + d.getName() + " counts "
                        + d.getTeamOf().size() + " disciplines, more than this meet's limit of " + max);
                }
            }
        }
        if (seen.size() > max) {
            throw new IllegalArgumentException(r.getName() + " covers " + seen.size()
                + " disciplines, more than this meet's limit of " + max);
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static RankingDefinition parse(Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("The ranking is missing");
        }
        Map<String, Object> copy = new LinkedHashMap<>(request);
        copy.remove("id");
        copy.remove("version");
        RankingDefinition ranking;
        try {
            ranking = objectMapper.convertValue(copy, RankingDefinition.class);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The ranking is not valid: " + e.getMessage());
        }
        normalize(ranking);
        return ranking;
    }

    private static void normalize(RankingDefinition r) {
        r.setName(trimmed(r.getName()));
        r.setShortName(trimmed(r.getShortName()));
        if (r.getLevel() == null) {
            r.setLevel("individual");
        }
        for (RankingDefinition.Entry e : r.getEntries()) {
            e.setLabel(trimmed(e.getLabel()));
            e.setDisciplineIds(e.getDisciplineIds().stream().filter(Objects::nonNull).toList());
        }
    }

    private static String trimmed(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static RankingDefinition findRanking(RankingConfig config, int id) {
        return config.getRankings().stream().filter(r -> r.getId() == id).findFirst()
            .orElseThrow(() -> new RecordNotFoundException("Ranking " + id + " not found"));
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Integer> startCounts(Map<String, Object> data) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Map<String, Object> competitor : (List<Map<String, Object>>) data.get("competitors")) {
            if (competitor.get("starts") instanceof Map<?, ?> starts) {
                starts.forEach((key, list) -> {
                    if (list instanceof List<?> l && !l.isEmpty()) {
                        try {
                            counts.merge(Integer.parseInt(String.valueOf(key)), l.size(), Integer::sum);
                        } catch (NumberFormatException ignored) {
                            // not a discipline id
                        }
                    }
                });
            }
        }
        return counts;
    }

    /** Names of the competitors who start in more than one of the disciplines. */
    @SuppressWarnings("unchecked")
    static List<String> competitorsStartingInMoreThanOne(Map<String, Object> data, Collection<Integer> disciplineIds) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> competitor : (List<Map<String, Object>>) data.get("competitors")) {
            if (disciplineIds.stream().filter(id -> startsIn(competitor, id)).count() > 1) {
                names.add(String.valueOf(competitor.get("name")));
            }
        }
        return names;
    }

    /** Whether the competitor (a data.json record) has a start in the discipline. */
    static boolean startsIn(Map<String, Object> competitor, int disciplineId) {
        return competitor.get("starts") instanceof Map<?, ?> starts
            && starts.get(String.valueOf(disciplineId)) instanceof List<?> list
            && !list.isEmpty();
    }
}
