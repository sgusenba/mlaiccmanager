package com.competition.service;

import com.competition.model.Discipline;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Teams of the MLAIC team disciplines, stored in teams.json. A team member is
 * one registered start in the individual discipline the team discipline is
 * based on (e.g. "Gustav Adolph" is scored from "Miquelet"); the
 * team's score is the sum of its members' individual results.
 *
 * <p>Ranking: total, then the countback over all members' shots (most 10s,
 * then 9s, ... 1s), then the manual tie-break value, where the lower value
 * wins (distance of the furthest shot from the centre).
 */
public class TeamService {
    private static final Logger logger = LoggerFactory.getLogger(TeamService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static final int DEFAULT_TEAM_SIZE = 3;

    // One lock serializes every access to teams.json. Lock order is always
    // teams.json first, then data.json (read only), like RelayService.
    private final ReentrantLock lock = new ReentrantLock();

    private final String teamsFilePath;
    private final DataService dataService;

    @FunctionalInterface
    private interface TeamFunction<T> {
        T apply(Map<String, Object> teams) throws Exception;
    }

    /** Competitors, their starts, results by start id and the discipline catalog, read from data.json/disciplines.json. */
    private record Registry(Map<Integer, Map<String, Object>> competitors,
                            Map<String, Map<String, Object>> starts,
                            Map<String, Map<String, Object>> resultsByStart,
                            Map<Integer, Discipline> disciplines) {}

    public TeamService(String teamsFilePath, DataService dataService) {
        this.teamsFilePath = teamsFilePath;
        this.dataService = dataService;
    }

    // --- disciplines -------------------------------------------------------

    /** Every team discipline with its team size and the individual disciplines its members may come from. */
    public List<Map<String, Object>> getTeamDisciplines() throws Exception {
        List<Discipline> catalog = dataService.loadDisciplines();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Discipline discipline : catalog) {
            if (!isTeamDiscipline(discipline)) {
                continue;
            }
            Map<String, Object> info = disciplineInfo(discipline, catalog);
            List<Map<String, Object>> eligible = new ArrayList<>();
            for (Discipline individual : eligibleDisciplines(discipline, catalog)) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", individual.getId());
                e.put("name", disciplineName(individual));
                eligible.add(e);
            }
            info.put("eligible_disciplines", eligible);
            result.add(info);
        }
        return result;
    }

    public static boolean isTeamDiscipline(Discipline discipline) {
        return discipline != null && "team".equalsIgnoreCase(discipline.getLevel());
    }

    public static int teamSize(Discipline discipline) {
        return discipline.getTeamSize() != null && discipline.getTeamSize() > 0 ? discipline.getTeamSize() : DEFAULT_TEAM_SIZE;
    }

    /**
     * Individual disciplines a team discipline is scored from: those ticked as
     * its "results that count" (team_of). None set: no start counts.
     */
    static List<Discipline> eligibleDisciplines(Discipline team, List<Discipline> catalog) {
        List<Discipline> listed = new ArrayList<>();
        if (team.getTeamOf() != null) {
            for (Discipline d : catalog) {
                if (team.getTeamOf().contains(d.getId()) && !isTeamDiscipline(d)) {
                    listed.add(d);
                }
            }
        }
        return listed;
    }

    /**
     * The team_of an older team discipline had implicitly through its based_on
     * text: the disciplines of that event (e.g. "No 14 Tanegashima" or
     * "14_Tanegashima_O/R") and the team's category, original teams the
     * original, reproduction teams the reproduction, other teams all of them.
     * Null if based_on names no single event (e.g. "Gustav Adolph + Pauly").
     */
    static List<Integer> teamOfFromBasedOn(Discipline team, List<Discipline> catalog) {
        List<Discipline> basedOn = new ArrayList<>();
        for (Discipline d : catalog) {
            if (!isTeamDiscipline(d) && !DisciplineService.isAggregate(d)
                    && Objects.equals(d.getCategory(), team.getCategory())
                    && DisciplineService.sameEvent(d.getEvent(), team.getBasedOn())) {
                basedOn.add(d);
            }
        }
        if (basedOn.isEmpty()) {
            return null;
        }
        List<Discipline> sameType = basedOn.stream()
            .filter(d -> team.getType() != null && team.getType().equalsIgnoreCase(d.getType())).toList();
        return (sameType.isEmpty() ? basedOn : sameType).stream().map(Discipline::getId).toList();
    }

    /**
     * One-time step at startup: team disciplines without "results that count"
     * get them from their old based_on text where it names an event, plus the
     * disciplines their entered team members start in, so no stored member
     * stops counting. Writes the competition file only if something was set.
     *
     * @return the ids of the team disciplines that got a composition
     */
    public List<Integer> migrateCompositions() throws Exception {
        return read(teams -> {
            Registry registry = registry();
            // team discipline -> disciplines its stored members start in
            Map<Integer, Set<Integer>> memberDisciplines = new HashMap<>();
            for (Map<String, Object> team : listOf(teams)) {
                for (String startId : membersOf(team)) {
                    Map<String, Object> start = registry.starts().get(startId);
                    if (start != null) {
                        memberDisciplines.computeIfAbsent(RelayRules.intOf(team.get("discipline_id")), k -> new HashSet<>())
                            .add(RelayRules.intOf(start.get("discipline_id")));
                    }
                }
            }
            List<Discipline> current = new ArrayList<>(registry.disciplines().values());
            if (current.stream().noneMatch(d -> isTeamDiscipline(d) && d.getTeamOf() == null
                    && derivedComposition(d, current, memberDisciplines) != null)) {
                return List.<Integer>of();
            }
            return dataService.updateDisciplines(disciplines -> {
                List<Integer> migrated = new ArrayList<>();
                for (Discipline d : disciplines) {
                    if (isTeamDiscipline(d) && d.getTeamOf() == null) {
                        List<Integer> teamOf = derivedComposition(d, disciplines, memberDisciplines);
                        if (teamOf != null) {
                            d.setTeamOf(teamOf);
                            migrated.add(d.getId());
                        }
                    }
                }
                return migrated;
            });
        });
    }

    /** based_on's disciplines plus the members' existing individual disciplines, in catalog order; null if none. */
    private static List<Integer> derivedComposition(Discipline team, List<Discipline> catalog,
            Map<Integer, Set<Integer>> memberDisciplines) {
        Set<Integer> ids = new HashSet<>(memberDisciplines.getOrDefault(team.getId(), Set.of()));
        List<Integer> fromBasedOn = teamOfFromBasedOn(team, catalog);
        if (fromBasedOn != null) {
            ids.addAll(fromBasedOn);
        }
        List<Integer> ordered = catalog.stream()
            .filter(d -> ids.contains(d.getId()) && !isTeamDiscipline(d) && !DisciplineService.isAggregate(d))
            .map(Discipline::getId).toList();
        return ordered.isEmpty() ? null : ordered;
    }

    // --- teams -------------------------------------------------------------

    /** Teams, optionally of one discipline, with their members resolved from data.json. */
    public List<Map<String, Object>> getTeams(Integer disciplineId) throws Exception {
        return read(teams -> {
            Registry registry = registry();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> team : listOf(teams)) {
                if (disciplineId == null || RelayRules.intOf(team.get("discipline_id")) == disciplineId) {
                    result.add(enrich(team, registry));
                }
            }
            return result;
        });
    }

    public Map<String, Object> getTeam(int id) throws Exception {
        return read(teams -> enrich(requireTeam(teams, id), registry()));
    }

    /**
     * Competitors that may be picked for a team of the discipline, each with
     * their eligible starts (first start first, the default when picked) and
     * the team they already belong to in this discipline, if any.
     */
    public List<Map<String, Object>> getCandidates(int disciplineId) throws Exception {
        return read(teams -> {
            Registry registry = registry();
            Discipline discipline = requireTeamDiscipline(registry, disciplineId);
            Set<Integer> eligible = eligibleIds(discipline, registry);

            Map<Integer, Map<String, Object>> teamOfCompetitor = new HashMap<>();
            for (Map<String, Object> team : listOf(teams)) {
                if (RelayRules.intOf(team.get("discipline_id")) != disciplineId) {
                    continue;
                }
                for (String startId : membersOf(team)) {
                    Map<String, Object> start = registry.starts().get(startId);
                    if (start != null) {
                        teamOfCompetitor.put(RelayRules.intOf(start.get("competitor_id")), team);
                    }
                }
            }

            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Map<String, Object> competitor : registry.competitors().values()) {
                int competitorId = RelayRules.intOf(competitor.get("id"));
                List<Map<String, Object>> starts = new ArrayList<>();
                for (Map<String, Object> start : registry.starts().values()) {
                    if (RelayRules.intOf(start.get("competitor_id")) == competitorId
                            && eligible.contains(RelayRules.intOf(start.get("discipline_id")))) {
                        starts.add(startInfo(start, registry));
                    }
                }
                if (starts.isEmpty()) {
                    continue;
                }
                starts.sort(START_ORDER);

                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("competitor_id", competitorId);
                candidate.put("name", competitor.get("name"));
                candidate.put("club", competitor.get("club"));
                candidate.put("country", competitor.get("country"));
                Map<String, Object> team = teamOfCompetitor.get(competitorId);
                candidate.put("team_id", team != null ? team.get("id") : null);
                candidate.put("team_name", team != null ? team.get("name") : null);
                candidate.put("starts", starts);
                candidates.add(candidate);
            }
            candidates.sort(Comparator.comparing(c -> String.valueOf(c.get("name")), String.CASE_INSENSITIVE_ORDER));
            return candidates;
        });
    }

    public Map<String, Object> createTeam(Map<String, Object> request) throws Exception {
        int disciplineId = requireInt(request, "discipline_id");
        return update(teams -> {
            Registry registry = registry();
            Discipline discipline = requireTeamDiscipline(registry, disciplineId);
            List<Map<String, Object>> list = listOf(teams);

            Map<String, Object> team = new LinkedHashMap<>();
            team.put("id", nextId(list));
            team.put("discipline_id", disciplineId);
            applyRequest(teams, team, discipline, request, registry);
            team.put("created_at", LocalDateTime.now().toString());
            team.put("version", 0);
            list.add(team);
            return enrich(team, registry);
        });
    }

    public Map<String, Object> updateTeam(int id, Map<String, Object> request) throws Exception {
        return update(teams -> {
            Registry registry = registry();
            Map<String, Object> team = requireTeam(teams, id);
            DataService.checkVersion(team, versionOf(request),
                "Someone else changed this team in the meantime", enrich(team, registry));
            Discipline discipline = requireTeamDiscipline(registry, RelayRules.intOf(team.get("discipline_id")));
            applyRequest(teams, team, discipline, request, registry);
            DataService.bumpVersion(team);
            return enrich(team, registry);
        });
    }

    public void deleteTeam(int id, Integer version) throws Exception {
        update(teams -> {
            Map<String, Object> team = requireTeam(teams, id);
            DataService.checkVersion(team, version,
                "Someone else changed this team in the meantime", enrich(team, registry()));
            listOf(teams).remove(team);
            return null;
        });
    }

    /** Validates and copies name, members, tie-break and notes from the request onto the team. */
    private void applyRequest(Map<String, Object> teams, Map<String, Object> team, Discipline discipline,
                              Map<String, Object> request, Registry registry) {
        List<String> members = requireMembers(request);
        int size = teamSize(discipline);
        if (members.size() > size) {
            throw new IllegalArgumentException("A team of " + discipline.getEvent() + " has at most " + size + " members");
        }

        Set<Integer> eligible = eligibleIds(discipline, registry);
        Set<Integer> competitorsInTeam = new LinkedHashSet<>();
        for (String startId : members) {
            Map<String, Object> start = registry.starts().get(startId);
            if (start == null) {
                throw new IllegalArgumentException("Start " + startId + " does not exist");
            }
            if (!eligible.contains(RelayRules.intOf(start.get("discipline_id")))) {
                throw new IllegalArgumentException("Start " + startId + " is not a start of "
                    + compositionText(discipline, registry) + " and cannot count for " + discipline.getEvent());
            }
            int competitorId = RelayRules.intOf(start.get("competitor_id"));
            if (!competitorsInTeam.add(competitorId)) {
                throw new IllegalArgumentException(competitorName(registry, competitorId) + " is in this team twice");
            }
        }

        int teamId = RelayRules.intOf(team.get("id"));
        for (Map<String, Object> other : listOf(teams)) {
            if (RelayRules.intOf(other.get("id")) == teamId
                    || RelayRules.intOf(other.get("discipline_id")) != discipline.getId()) {
                continue;
            }
            for (String startId : membersOf(other)) {
                Map<String, Object> start = registry.starts().get(startId);
                if (start != null && competitorsInTeam.contains(RelayRules.intOf(start.get("competitor_id")))) {
                    throw new ConflictException(competitorName(registry, RelayRules.intOf(start.get("competitor_id")))
                        + " is already in team " + other.get("name") + " of " + discipline.getEvent(), null);
                }
            }
        }

        team.put("members", new ArrayList<>(members));
        String name = request.get("name") instanceof String s ? s.trim() : "";
        team.put("name", name.isEmpty() ? defaultName(team, registry) : name);
        team.put("tie_break", Scoring.doubleOrNull(request.get("tie_break")));
        team.put("notes", request.get("notes") instanceof String s ? s : "");
    }

    /** The club all members share, else the country they share, else "Team <id>". */
    private static String defaultName(Map<String, Object> team, Registry registry) {
        List<Map<String, Object>> competitors = new ArrayList<>();
        for (String startId : membersOf(team)) {
            Map<String, Object> start = registry.starts().get(startId);
            Map<String, Object> competitor = start != null
                ? registry.competitors().get(RelayRules.intOf(start.get("competitor_id"))) : null;
            if (competitor != null) {
                competitors.add(competitor);
            }
        }
        for (String field : new String[] {"club", "country"}) {
            String shared = sharedValue(competitors, field);
            if (shared != null) {
                return shared;
            }
        }
        return "Team " + team.get("id");
    }

    private static String sharedValue(List<Map<String, Object>> competitors, String field) {
        String shared = null;
        for (Map<String, Object> competitor : competitors) {
            String value = competitor.get(field) instanceof String s ? s.trim() : "";
            if (value.isEmpty() || (shared != null && !shared.equalsIgnoreCase(value))) {
                return null;
            }
            shared = value;
        }
        return shared;
    }

    // --- ranking -----------------------------------------------------------

    /** Ranked teams of a team discipline; null if the discipline does not exist or is no team discipline. */
    public Map<String, Object> getRanking(int disciplineId) throws Exception {
        return read(teams -> {
            Registry registry = registry();
            Discipline discipline = registry.disciplines().get(disciplineId);
            if (!isTeamDiscipline(discipline)) {
                return null;
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("kind", "team");
            response.put("discipline", disciplineInfo(discipline, new ArrayList<>(registry.disciplines().values())));
            response.put("rankings", rank(teams, discipline, registry));
            return response;
        });
    }

    /** Whether any team was entered for the discipline. */
    public boolean hasTeams(int disciplineId) throws Exception {
        return read(teams -> listOf(teams).stream()
            .anyMatch(t -> RelayRules.intOf(t.get("discipline_id")) == disciplineId));
    }

    private List<Map<String, Object>> rank(Map<String, Object> teams, Discipline discipline, Registry registry) {
        int size = teamSize(discipline);
        List<Map<String, Object>> entries = new ArrayList<>();
        List<double[]> keys = new ArrayList<>();

        for (Map<String, Object> team : listOf(teams)) {
            if (RelayRules.intOf(team.get("discipline_id")) != discipline.getId()) {
                continue;
            }
            double total = 0.0;
            int[] rings = Scoring.newRingCounts();
            boolean complete = membersOf(team).size() == size;
            for (String startId : membersOf(team)) {
                Map<String, Object> result = registry.resultsByStart().get(startId);
                if (result == null || !registry.starts().containsKey(startId)) {
                    complete = false;
                    continue;
                }
                total += Scoring.score(result);
                Scoring.addRingCounts(result, rings);
            }
            Double tieBreak = Scoring.doubleOrNull(team.get("tie_break"));

            Map<String, Object> entry = enrich(team, registry);
            entry.put("total", total);
            entry.put("freq_counts", freqCounts(rings));
            entry.put("complete", complete);
            entries.add(entry);
            keys.add(rankingKey(total, rings, tieBreak));
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> compareKeys(keys.get(b), keys.get(a)));

        // Teams with an identical key share the rank, the next rank skips accordingly
        List<Map<String, Object>> ranked = new ArrayList<>();
        int rank = 1;
        for (int i = 0; i < order.size(); i++) {
            if (i > 0 && compareKeys(keys.get(order.get(i)), keys.get(order.get(i - 1))) != 0) {
                rank = i + 1;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("rank", rank);
            entry.putAll(entries.get(order.get(i)));
            ranked.add(entry);
        }
        return ranked;
    }

    /** Higher is better: total, then 10s down to 1s, then the tie-break (lower value wins). */
    static double[] rankingKey(double total, int[] rings, Double tieBreak) {
        double[] key = new double[Scoring.MAX_RING + 2];
        key[0] = total;
        for (int ring = Scoring.MAX_RING; ring >= 1; ring--) {
            key[Scoring.MAX_RING - ring + 1] = rings[ring];
        }
        key[Scoring.MAX_RING + 1] = Scoring.tieBreakKey(tieBreak);
        return key;
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

    private static Map<String, Integer> freqCounts(int[] rings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int ring = Scoring.MAX_RING; ring >= 0; ring--) {
            counts.put(String.valueOf(ring), rings[ring]);
        }
        return counts;
    }

    // --- views -------------------------------------------------------------

    private static final Comparator<Map<String, Object>> START_ORDER = Comparator
        .comparingInt((Map<String, Object> s) -> RelayRules.intOf(s.get("discipline_id")))
        .thenComparingInt(s -> RelayRules.intOf(s.get("start_number")))
        .thenComparing(s -> String.valueOf(s.get("start_id")));

    private Map<String, Object> enrich(Map<String, Object> team, Registry registry) {
        Map<String, Object> view = new LinkedHashMap<>(team);
        Discipline discipline = registry.disciplines().get(RelayRules.intOf(team.get("discipline_id")));
        view.put("discipline_name", discipline != null ? discipline.getEvent() : null);
        view.put("team_size", discipline != null ? teamSize(discipline) : DEFAULT_TEAM_SIZE);
        view.put("default_name", defaultName(team, registry));

        List<Map<String, Object>> members = new ArrayList<>();
        for (String startId : membersOf(team)) {
            Map<String, Object> start = registry.starts().get(startId);
            if (start == null) {
                Map<String, Object> missing = new LinkedHashMap<>();
                missing.put("start_id", startId);
                missing.put("missing", true);
                missing.put("has_result", false);
                missing.put("score", null);
                members.add(missing);
                continue;
            }
            Map<String, Object> member = startInfo(start, registry);
            Map<String, Object> competitor = registry.competitors().get(RelayRules.intOf(start.get("competitor_id")));
            member.put("competitor_id", start.get("competitor_id"));
            member.put("name", competitor != null ? competitor.get("name") : null);
            member.put("club", competitor != null ? competitor.get("club") : null);
            member.put("country", competitor != null ? competitor.get("country") : null);
            member.put("missing", false);
            members.add(member);
        }
        view.put("members", members);
        return view;
    }

    private static Map<String, Object> startInfo(Map<String, Object> start, Registry registry) {
        String startId = (String) start.get("start_id");
        int disciplineId = RelayRules.intOf(start.get("discipline_id"));
        Map<String, Object> result = registry.resultsByStart().get(startId);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("start_id", startId);
        info.put("start_number", start.get("start_number"));
        info.put("discipline_id", disciplineId);
        Discipline discipline = registry.disciplines().get(disciplineId);
        info.put("discipline_name", discipline != null ? disciplineName(discipline) : null);
        info.put("has_result", result != null);
        info.put("score", result != null ? Scoring.score(result) : null);
        return info;
    }

    private static Map<String, Object> disciplineInfo(Discipline discipline, List<Discipline> catalog) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", discipline.getId());
        info.put("name", discipline.getEvent());
        info.put("category", discipline.getCategory());
        info.put("type", discipline.getType());
        info.put("level", discipline.getLevel());
        // The results that count, by short name where there is one, e.g. ["TANO", "TANR"]
        List<String> composition = new ArrayList<>();
        for (Discipline d : eligibleDisciplines(discipline, catalog)) {
            composition.add(d.getShortName() != null ? d.getShortName() : d.getEvent());
        }
        info.put("composition", composition);
        info.put("team_size", teamSize(discipline));
        info.put("active", discipline.isActive());
        info.put("unit", "points");
        return info;
    }

    private static String disciplineName(Discipline discipline) {
        return discipline.getType() != null ? discipline.getEvent() + " (" + discipline.getType() + ")" : discipline.getEvent();
    }

    private static String competitorName(Registry registry, int competitorId) {
        Map<String, Object> competitor = registry.competitors().get(competitorId);
        return competitor != null && competitor.get("name") != null ? competitor.get("name").toString() : "Competitor " + competitorId;
    }

    // --- lookups -----------------------------------------------------------

    private static Discipline requireTeamDiscipline(Registry registry, int disciplineId) {
        Discipline discipline = registry.disciplines().get(disciplineId);
        if (discipline == null) {
            throw new IllegalArgumentException("Discipline " + disciplineId + " does not exist");
        }
        if (!isTeamDiscipline(discipline)) {
            throw new IllegalArgumentException(discipline.getEvent() + " is not a team discipline");
        }
        return discipline;
    }

    /** The disciplines a team discipline counts, as text, e.g. "14_Tanegashima_O (original), 14_Tanegashima_R (reproduction)". */
    private static String compositionText(Discipline team, Registry registry) {
        List<String> names = new ArrayList<>();
        for (Discipline d : eligibleDisciplines(team, new ArrayList<>(registry.disciplines().values()))) {
            names.add(disciplineName(d));
        }
        return names.isEmpty() ? "no discipline" : String.join(", ", names);
    }

    /**
     * Runs save (which stores a new team_of for a team discipline) while no team
     * can change, unless a stored team has a member whose start would no longer
     * count: then a ConflictException lists them and nothing is saved.
     */
    public <T> T changeComposition(int disciplineId, List<Integer> teamOf, Callable<T> save) throws Exception {
        return read(teams -> {
            if (teamOf != null) {
                Registry registry = registry();
                List<String> outside = new ArrayList<>();
                for (Map<String, Object> team : listOf(teams)) {
                    if (RelayRules.intOf(team.get("discipline_id")) != disciplineId) {
                        continue;
                    }
                    for (String startId : membersOf(team)) {
                        Map<String, Object> start = registry.starts().get(startId);
                        if (start != null && !teamOf.contains(RelayRules.intOf(start.get("discipline_id")))) {
                            outside.add(team.get("name") + ": " + startId + " ("
                                + competitorName(registry, RelayRules.intOf(start.get("competitor_id"))) + ")");
                        }
                    }
                }
                if (!outside.isEmpty()) {
                    throw new ConflictException("These team members' starts would no longer count; "
                        + "remove them from their teams first: " + String.join(", ", outside), outside);
                }
            }
            return save.call();
        });
    }

    /** How many teams are entered per team discipline. */
    public Map<Integer, Integer> teamCounts() throws Exception {
        return read(teams -> {
            Map<Integer, Integer> counts = new HashMap<>();
            for (Map<String, Object> team : listOf(teams)) {
                counts.merge(RelayRules.intOf(team.get("discipline_id")), 1, Integer::sum);
            }
            return counts;
        });
    }

    private static Set<Integer> eligibleIds(Discipline team, Registry registry) {
        Set<Integer> ids = new LinkedHashSet<>();
        for (Discipline d : eligibleDisciplines(team, new ArrayList<>(registry.disciplines().values()))) {
            ids.add(d.getId());
        }
        return ids;
    }

    private static Map<String, Object> requireTeam(Map<String, Object> teams, int id) {
        for (Map<String, Object> team : listOf(teams)) {
            if (RelayRules.intOf(team.get("id")) == id) {
                return team;
            }
        }
        throw new RecordNotFoundException("Team " + id + " not found");
    }

    private static List<String> membersOf(Map<String, Object> team) {
        List<String> members = new ArrayList<>();
        if (team.get("members") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    members.add(item.toString());
                }
            }
        }
        return members;
    }

    /** Start ids from the request; empty slots are left out, a start listed twice is rejected. */
    private static List<String> requireMembers(Map<String, Object> request) {
        Object raw = request != null ? request.get("members") : null;
        if (raw != null && !(raw instanceof List)) {
            throw new IllegalArgumentException("members must be a list of start ids");
        }
        List<String> members = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String startId = item instanceof Map<?, ?> m ? Objects.toString(m.get("start_id"), "") : Objects.toString(item, "");
                startId = startId.trim();
                if (startId.isEmpty()) {
                    continue;
                }
                if (members.contains(startId)) {
                    throw new IllegalArgumentException("Start " + startId + " is listed twice");
                }
                members.add(startId);
            }
        }
        return members;
    }

    private static int requireInt(Map<String, Object> request, String key) {
        Object value = request != null ? request.get(key) : null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        throw new IllegalArgumentException(key + " is required");
    }

    private static Integer versionOf(Map<String, Object> request) {
        return request != null && request.get("version") instanceof Number n ? n.intValue() : null;
    }

    private static int nextId(List<Map<String, Object>> teams) {
        int max = 0;
        for (Map<String, Object> team : teams) {
            max = Math.max(max, RelayRules.intOf(team.get("id")));
        }
        return max + 1;
    }

    private Registry registry() throws Exception {
        Map<Integer, Discipline> disciplines = new LinkedHashMap<>();
        for (Discipline discipline : dataService.loadDisciplines()) {
            disciplines.put(discipline.getId(), discipline);
        }

        return dataService.read(data -> {
            Map<Integer, Map<String, Object>> competitors = new LinkedHashMap<>();
            Map<String, Map<String, Object>> starts = new LinkedHashMap<>();
            Map<String, Map<String, Object>> resultsByStart = new HashMap<>();

            if (data.get("competitors") instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> raw)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> competitor = (Map<String, Object>) raw;
                    int competitorId = RelayRules.intOf(competitor.get("id"));
                    competitors.put(competitorId, competitor);

                    if (!(competitor.get("starts") instanceof Map<?, ?> startsByDiscipline)) {
                        continue;
                    }
                    for (Object startList : startsByDiscipline.values()) {
                        if (!(startList instanceof List<?> startItems)) {
                            continue;
                        }
                        for (Object startItem : startItems) {
                            if (!(startItem instanceof Map<?, ?> start) || start.get("generated_id") == null) {
                                continue;
                            }
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("start_id", start.get("generated_id").toString());
                            info.put("start_number", start.get("start_number"));
                            info.put("discipline_id", RelayRules.intOf(start.get("discipline_id")));
                            info.put("competitor_id", competitorId);
                            starts.put(start.get("generated_id").toString(), info);
                        }
                    }
                }
            }

            if (data.get("results") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> raw && raw.get("start_id") != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = (Map<String, Object>) raw;
                        resultsByStart.putIfAbsent(result.get("start_id").toString(), result);
                    }
                }
            }

            return new Registry(competitors, starts, resultsByStart, disciplines);
        });
    }

    // --- danger zone -------------------------------------------------------

    /** Deletes the teams of the given disciplines, or all of them. Returns how many were deleted. */
    public int deleteTeams(Predicate<Integer> disciplineIds) throws Exception {
        return update(teams -> {
            List<Map<String, Object>> list = listOf(teams);
            int before = list.size();
            list.removeIf(team -> disciplineIds.test(RelayRules.intOf(team.get("discipline_id"))));
            return before - list.size();
        });
    }

    /** Takes the given starts out of every team; the teams stay. Returns how many members were removed. */
    public int removeMembers(Predicate<String> startIds) throws Exception {
        return update(teams -> {
            int removed = 0;
            for (Map<String, Object> team : listOf(teams)) {
                List<String> members = membersOf(team);
                List<String> kept = members.stream().filter(startId -> !startIds.test(startId)).toList();
                if (kept.size() < members.size()) {
                    removed += members.size() - kept.size();
                    team.put("members", new ArrayList<>(kept));
                    DataService.bumpVersion(team);
                }
            }
            return removed;
        });
    }

    // --- storage -----------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Map<String, Object> teams) {
        return (List<Map<String, Object>>) teams.get("teams");
    }

    /** Runs action while holding the teams.json lock, so the file can be copied or replaced as a whole. */
    public <T> T exclusive(Callable<T> action) throws Exception {
        lock.lock();
        try {
            return action.call();
        } finally {
            lock.unlock();
        }
    }

    private <T> T read(TeamFunction<T> fn) throws Exception {
        lock.lock();
        try {
            return fn.apply(load());
        } finally {
            lock.unlock();
        }
    }

    /** Nothing is saved if fn throws. */
    private <T> T update(TeamFunction<T> fn) throws Exception {
        lock.lock();
        try {
            Map<String, Object> teams = load();
            T result = fn.apply(teams);
            save(teams);
            return result;
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> load() throws IOException {
        File file = new File(teamsFilePath);
        Map<String, Object> teams;
        if (file.exists()) {
            // A broken file is not replaced: fail loudly and keep it for repair
            teams = objectMapper.readValue(file, new TypeReference<LinkedHashMap<String, Object>>() {});
        } else {
            logger.debug("Teams file not found, starting with no teams");
            teams = new LinkedHashMap<>();
        }
        if (!(teams.get("teams") instanceof List)) {
            teams.put("teams", new ArrayList<>());
        }
        return teams;
    }

    private void save(Map<String, Object> teams) throws IOException {
        File tempFile = new File(teamsFilePath + ".tmp");
        objectMapper.writeValue(tempFile, teams);
        Files.move(tempFile.toPath(), Paths.get(teamsFilePath),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
