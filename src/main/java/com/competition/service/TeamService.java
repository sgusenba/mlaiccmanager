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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

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
            Map<String, Object> info = disciplineInfo(discipline);
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
     * Individual disciplines a team discipline is scored from: the based_on
     * event of the same category, original teams from original starts,
     * reproduction teams from reproduction starts, open teams from any. If
     * based_on names no event (e.g. an aggregate), every individual
     * discipline of the category qualifies.
     */
    static List<Discipline> eligibleDisciplines(Discipline team, List<Discipline> catalog) {
        List<Discipline> sameCategory = new ArrayList<>();
        List<Discipline> basedOn = new ArrayList<>();
        for (Discipline d : catalog) {
            if (isTeamDiscipline(d) || !Objects.equals(d.getCategory(), team.getCategory())) {
                continue;
            }
            sameCategory.add(d);
            if (d.getEvent() != null && d.getEvent().equalsIgnoreCase(team.getBasedOn())) {
                basedOn.add(d);
            }
        }
        if (basedOn.isEmpty()) {
            return sameCategory;
        }
        if ("open".equalsIgnoreCase(team.getType()) || team.getType() == null) {
            return basedOn;
        }
        List<Discipline> sameType = new ArrayList<>();
        for (Discipline d : basedOn) {
            if (team.getType().equalsIgnoreCase(d.getType())) {
                sameType.add(d);
            }
        }
        return sameType.isEmpty() ? basedOn : sameType;
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
                    + discipline.getBasedOn() + " and cannot count for " + discipline.getEvent());
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
            response.put("discipline", disciplineInfo(discipline));
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
        for (int ring = Scoring.MAX_RING; ring >= 1; ring--) {
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

    private static Map<String, Object> disciplineInfo(Discipline discipline) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", discipline.getId());
        info.put("name", discipline.getEvent());
        info.put("category", discipline.getCategory());
        info.put("type", discipline.getType());
        info.put("level", discipline.getLevel());
        info.put("based_on", discipline.getBasedOn());
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
