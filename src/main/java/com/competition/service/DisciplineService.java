package com.competition.service;

import com.competition.model.Discipline;
import com.competition.model.DisciplineCatalog;
import com.competition.model.RankingConfig;
import com.competition.model.RankingDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The installation's discipline catalog: the same disciplines at every meet,
 * edited here, exported and imported on their own. What a meet ranks is its
 * rankings (RankingConfigService).
 */
public class DisciplineService {
    static final Set<String> LEVELS = Set.of("individual", "team");
    static final Set<String> CATEGORIES = Set.of("rifle", "pistol");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final DataService dataService;
    private final TeamService teamService;

    public DisciplineService(DataService dataService) {
        this(dataService, null);
    }

    public DisciplineService(DataService dataService, TeamService teamService) {
        this.dataService = dataService;
        this.teamService = teamService;
    }

    public List<Discipline> getAvailableDisciplines() throws Exception {
        return dataService.loadDisciplines();
    }

    public Discipline getAvailableDisciplineById(int id) throws Exception {
        for (Discipline discipline : getAvailableDisciplines()) {
            if (discipline.getId() == id) {
                return discipline;
            }
        }
        return null;
    }

    /** The meet's program: the ids of the disciplines its rankings cover, in ranking order. */
    public List<Integer> getProgramIds() throws Exception {
        return new ArrayList<>(dataService.loadProgramIds());
    }

    public static boolean isTeamDiscipline(Discipline discipline) {
        return discipline != null && discipline.isTeam();
    }

    // --- catalog settings ----------------------------------------------------

    public Map<String, Object> getSettings() throws Exception {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("max_disciplines", dataService.loadCatalog().getMaxDisciplines());
        return settings;
    }

    /** Sets how many individual disciplines a team discipline may count; refused below a team's current count. */
    public Map<String, Object> setMaxDisciplines(int max) throws Exception {
        if (max < 1) {
            throw new IllegalArgumentException("The limit must be at least 1");
        }
        dataService.updateCatalog(catalog -> {
            for (Discipline d : catalog.getDisciplines()) {
                if (d.isTeam() && d.getTeamOf() != null && d.getTeamOf().size() > max) {
                    throw new IllegalArgumentException(d.getName() + " counts " + d.getTeamOf().size()
                        + " disciplines, more than " + max);
                }
            }
            catalog.setMaxDisciplines(max);
            return null;
        });
        return getSettings();
    }

    // --- create / update / delete --------------------------------------------

    public Discipline createCatalogDiscipline(Map<String, Object> data) throws Exception {
        return dataService.updateCatalog(catalog -> {
            List<Discipline> disciplines = catalog.getDisciplines();
            Discipline d = new Discipline();
            d.setId(catalog.takeNextId());
            applyCatalogFields(d, data);
            check(d, catalog);
            disciplines.add(d);
            return d;
        });
    }

    public Discipline updateCatalogDiscipline(int id, Map<String, Object> data) throws Exception {
        Map<Integer, Integer> teamCounts = teamCounts();
        // Lock order: data.json, then catalog
        return dataService.read(stored -> dataService.updateCatalog(catalog -> {
            Discipline target = catalog.getDisciplines().stream()
                .filter(d -> d.getId() == id).findFirst()
                .orElseThrow(() -> new RecordNotFoundException("Discipline not found: " + id));
            String levelBefore = target.getLevel();
            String categoryBefore = target.getCategory();
            applyCatalogFields(target, data);
            check(target, catalog);
            if (!Objects.equals(levelBefore, target.getLevel()) || !Objects.equals(categoryBefore, target.getCategory())) {
                checkNotReferenced(target, "change its level or category",
                    usesOf(id, stored, teamCounts, dataService.loadRankingConfig(), catalog.getDisciplines()));
            }
            checkRankingsStillValid(catalog);
            return target;
        }));
    }

    /** Refused while starts, teams or rankings refer to the discipline: archive it (active = false) instead. */
    public void deleteCatalogDiscipline(int id) throws Exception {
        Map<Integer, Integer> teamCounts = teamCounts();
        // Lock order: data.json, then catalog
        dataService.read(data -> dataService.updateCatalog(catalog -> {
            Discipline target = catalog.getDisciplines().stream()
                .filter(d -> d.getId() == id).findFirst()
                .orElseThrow(() -> new RecordNotFoundException("Discipline not found: " + id));
            checkNotReferenced(target, "delete it",
                usesOf(id, data, teamCounts, dataService.loadRankingConfig(), catalog.getDisciplines()));
            catalog.getDisciplines().remove(target);
            return null;
        }));
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

    /** Teams per team discipline, read before any other lock is taken (teams.json comes first in the lock order). */
    private Map<Integer, Integer> teamCounts() throws Exception {
        return teamService != null ? teamService.teamCounts() : Map.of();
    }

    /**
     * What refers to the discipline: the meet's rankings, team disciplines that
     * count it, starts, and teams. Empty if nothing does.
     */
    static List<String> usesOf(int id, Map<String, Object> data, Map<Integer, Integer> teamCounts,
                               RankingConfig config, List<Discipline> disciplines) {
        List<String> uses = new ArrayList<>();
        for (RankingDefinition r : config.getRankings()) {
            if (r.disciplineIds().contains(id)) {
                uses.add("ranking " + r.getName());
            }
        }
        for (Discipline d : disciplines) {
            if (d.isTeam() && d.getTeamOf() != null && d.getTeamOf().contains(id)) {
                uses.add("team discipline " + d.getName());
            }
        }
        int starts = startCount(data, id);
        if (starts > 0) {
            uses.add(starts + " start(s)");
        }
        int teams = teamCounts.getOrDefault(id, 0);
        if (teams > 0) {
            uses.add(teams + " team(s)");
        }
        return uses;
    }

    private static void checkNotReferenced(Discipline discipline, String action, List<String> uses) {
        if (!uses.isEmpty()) {
            throw new ConflictException("Cannot " + action + ": " + discipline.getName() + " is used by "
                + String.join(", ", uses) + ". Archive it instead (untick Active)", uses);
        }
    }

    @SuppressWarnings("unchecked")
    private static int startCount(Map<String, Object> data, int disciplineId) {
        int count = 0;
        for (Map<String, Object> competitor : (List<Map<String, Object>>) data.get("competitors")) {
            if (competitor.get("starts") instanceof Map<?, ?> starts
                    && starts.get(String.valueOf(disciplineId)) instanceof List<?> list) {
                count += list.size();
            }
        }
        return count;
    }

    // --- export / import -----------------------------------------------------

    /** The catalog as a file of its own (format mlaic-disciplines). */
    public DisciplineCatalog exportCatalog() throws Exception {
        return dataService.loadCatalog();
    }

    /**
     * Imports a disciplines file. "merge" adds new ids and updates existing
     * ones; "replace" also removes the disciplines the file lacks, which is
     * refused for one still in use. All of it is checked before anything is
     * saved.
     *
     * @return the ids added, updated and removed
     */
    public Map<String, Object> importCatalog(JsonNode file, String mode) throws Exception {
        if (!DataService.isCatalog(file)) {
            throw new IllegalArgumentException("Not a disciplines file (format " + DisciplineCatalog.FORMAT + ")");
        }
        if (file.path("version").asInt() > DisciplineCatalog.FORMAT_VERSION) {
            throw new IllegalArgumentException("The disciplines file is of a newer version");
        }
        boolean replace = "replace".equals(mode);
        if (!replace && mode != null && !"merge".equals(mode)) {
            throw new IllegalArgumentException("Unknown import mode: " + mode);
        }
        DisciplineCatalog imported = objectMapper.treeToValue(file, DisciplineCatalog.class);
        Map<Integer, Discipline> importedById = new LinkedHashMap<>();
        for (Discipline d : imported.getDisciplines()) {
            if (d.getId() <= 0) {
                throw new IllegalArgumentException("Every discipline needs an id above 0");
            }
            if (importedById.put(d.getId(), d) != null) {
                throw new IllegalArgumentException("Discipline id " + d.getId() + " is in the file twice");
            }
        }

        Map<Integer, Integer> teamCounts = teamCounts();
        // Lock order: data.json, then catalog
        return dataService.read(data -> dataService.updateCatalog(catalog -> {
            RankingConfig config = dataService.loadRankingConfig();
            List<Integer> added = new ArrayList<>();
            List<Integer> updated = new ArrayList<>();
            List<Integer> removed = new ArrayList<>();
            Map<Integer, Discipline> current = new LinkedHashMap<>();
            catalog.getDisciplines().forEach(d -> current.put(d.getId(), d));

            List<Discipline> result = new ArrayList<>();
            for (Discipline d : catalog.getDisciplines()) {
                Discipline incoming = importedById.get(d.getId());
                if (incoming != null) {
                    result.add(incoming);
                    updated.add(d.getId());
                } else if (replace) {
                    removed.add(d.getId());
                } else {
                    result.add(d);
                }
            }
            for (Discipline d : importedById.values()) {
                if (!current.containsKey(d.getId())) {
                    result.add(d);
                    added.add(d.getId());
                }
            }

            DisciplineCatalog next = new DisciplineCatalog();
            next.setMaxDisciplines(Math.max(1, imported.getMaxDisciplines()));
            next.setDisciplines(result);
            for (Discipline d : result) {
                normalize(d);
                check(d, next);
            }
            // A discipline in use keeps its level and category, and is not removed
            List<String> problems = new ArrayList<>();
            for (int id : removed) {
                List<String> uses = usesOf(id, data, teamCounts, config, result);
                if (!uses.isEmpty()) {
                    problems.add(current.get(id).getName() + " (used by " + String.join(", ", uses) + ")");
                }
            }
            for (int id : updated) {
                Discipline before = current.get(id);
                Discipline after = importedById.get(id);
                if ((!Objects.equals(before.getLevel(), after.getLevel())
                        || !Objects.equals(before.getCategory(), after.getCategory()))
                        && !usesOf(id, data, teamCounts, config, catalog.getDisciplines()).isEmpty()) {
                    problems.add(before.getName() + " (in use, its level or category cannot change)");
                }
            }
            checkRankingsStillValid(next);
            if (!problems.isEmpty()) {
                throw new ConflictException("Nothing imported: " + String.join("; ", problems), problems);
            }

            catalog.setMaxDisciplines(next.getMaxDisciplines());
            catalog.raiseNextId(); // ids removed by a replace stay taken
            catalog.setDisciplines(result);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("added", added);
            summary.put("updated", updated);
            summary.put("removed", removed);
            return summary;
        }));
    }

    /** The meet's rankings must still fit the imported catalog (levels, team counts). */
    private void checkRankingsStillValid(DisciplineCatalog catalog) throws Exception {
        RankingConfig config = dataService.loadRankingConfig();
        for (RankingDefinition ranking : config.getRankings()) {
            RankingConfigService.check(ranking, config.getMaxDisciplinesPerRanking(), catalog.getDisciplines());
        }
    }

    // --- validation ------------------------------------------------------------

    /**
     * A discipline has a name, a known category and level. A team discipline
     * counts 1 to max_disciplines individual disciplines of its category.
     */
    static void check(Discipline d, DisciplineCatalog catalog) {
        if (d.getName() == null || d.getName().isBlank()) {
            throw new IllegalArgumentException("Discipline " + d.getId() + " needs a name");
        }
        if (d.getCategory() == null || !CATEGORIES.contains(d.getCategory())) {
            throw new IllegalArgumentException(d.getName() + ": category must be rifle or pistol");
        }
        if (d.getLevel() == null || !LEVELS.contains(d.getLevel())) {
            throw new IllegalArgumentException(d.getName() + ": level must be individual or team");
        }
        if (!d.isTeam()) {
            d.setTeamOf(null);
            d.setTeamSize(null);
            return;
        }
        d.setShootingDistance(null); // a team takes no lane
        if (d.getTeamOf() == null || d.getTeamOf().isEmpty()) {
            throw new IllegalArgumentException("Tick at least one discipline whose results count for " + d.getName());
        }
        if (d.getTeamOf().size() > catalog.getMaxDisciplines()) {
            throw new IllegalArgumentException(d.getName() + " may count at most " + catalog.getMaxDisciplines()
                + " disciplines");
        }
        Map<Integer, Discipline> byId = new HashMap<>();
        catalog.getDisciplines().forEach(x -> byId.put(x.getId(), x));
        for (int id : d.getTeamOf()) {
            Discipline counted = byId.get(id);
            if (counted == null || counted.isTeam() || !Objects.equals(counted.getCategory(), d.getCategory())) {
                throw new IllegalArgumentException("A " + d.getCategory() + " team can only count individual "
                    + d.getCategory() + " disciplines, not discipline " + id);
            }
        }
        if (d.getTeamSize() == null || d.getTeamSize() < 1) {
            d.setTeamSize(TeamService.DEFAULT_TEAM_SIZE);
        }
    }

    private static void normalize(Discipline d) {
        if (d.getShortName() != null && d.getShortName().isBlank()) {
            d.setShortName(null);
        }
        if (d.getShootingDistance() != null && d.getShootingDistance().isBlank()) {
            d.setShootingDistance(null);
        }
        if (d.getTeamOf() != null) {
            d.setTeamOf(d.getTeamOf().stream().filter(Objects::nonNull).distinct().toList());
        }
    }

    private static void applyCatalogFields(Discipline d, Map<String, Object> data) {
        if (data.containsKey("category")) d.setCategory((String) data.get("category"));
        if (data.containsKey("level")) d.setLevel((String) data.get("level"));
        if (data.containsKey("type")) d.setType((String) data.get("type"));
        if (data.containsKey("name")) d.setName(trimmed(data.get("name")));
        if (data.containsKey("short_name")) d.setShortName(trimmed(data.get("short_name")));
        if (data.containsKey("team_of")) {
            d.setTeamOf(data.get("team_of") instanceof List<?> ids
                ? ids.stream().filter(Objects::nonNull).map(id -> ((Number) id).intValue()).distinct().toList()
                : null);
        }
        if (data.containsKey("team_size")) {
            d.setTeamSize(data.get("team_size") instanceof Number n ? n.intValue() : null);
        }
        if (data.containsKey("shooting_distance")) d.setShootingDistance(trimmed(data.get("shooting_distance")));
        if (data.containsKey("active")) d.setActive(!Boolean.FALSE.equals(data.get("active")));
    }

    private static String trimmed(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }
}
