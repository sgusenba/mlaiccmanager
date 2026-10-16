package com.competition.service;

import com.competition.model.Discipline;
import com.competition.model.DisciplineCatalog;
import com.competition.model.RankingConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

public class DataService {
    private static final Logger logger = LoggerFactory.getLogger(DataService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Works on the loaded data.json contents; may throw to abort. */
    @FunctionalInterface
    public interface DataFunction<T> {
        T apply(Map<String, Object> data) throws Exception;
    }

    // One lock for all access to data.json. Every read-modify-write runs under
    // it, so concurrent requests can no longer overwrite each other's changes.
    // A plain reentrant lock rather than a read/write lock: loadData() may itself
    // write a default file, and only a handful of users are expected.
    private final ReentrantLock lock = new ReentrantLock();
    // One lock for the catalog and the meet's rankings, which are checked against each other
    private final ReentrantLock configLock = new ReentrantLock();

    private final String dataFilePath;
    // This installation's discipline catalog, the same for every meet. Git-ignored, kept on deploys.
    private final String disciplinesFilePath;
    // The shipped catalog (tracked in git, replaced on deploys): where disciplines.json starts from.
    private final String defaultCatalogFilePath;
    // This meet's rankings. Git-ignored.
    private final String rankingsFilePath;

    /** The default catalog is default-disciplines.json next to the disciplines file. */
    public DataService(String dataFilePath, String disciplinesFilePath, String rankingsFilePath) {
        this(dataFilePath, disciplinesFilePath, rankingsFilePath,
             Paths.get(disciplinesFilePath).resolveSibling(DEFAULT_CATALOG_FILE).toString());
    }

    public DataService(String dataFilePath, String disciplinesFilePath, String rankingsFilePath,
                       String defaultCatalogFilePath) {
        this.dataFilePath = dataFilePath;
        this.disciplinesFilePath = disciplinesFilePath;
        this.rankingsFilePath = rankingsFilePath;
        this.defaultCatalogFilePath = defaultCatalogFilePath;
    }

    public static final String DEFAULT_CATALOG_FILE = "default-disciplines.json";

    /** Runs fn against the current data without saving. */
    public <T> T read(DataFunction<T> fn) throws Exception {
        lock.lock();
        try {
            return fn.apply(loadData());
        } finally {
            lock.unlock();
        }
    }

    /** Runs fn against the current data and saves it afterwards. Nothing is saved if fn throws. */
    public <T> T update(DataFunction<T> fn) throws Exception {
        lock.lock();
        try {
            Map<String, Object> data = loadData();
            T result = fn.apply(data);
            saveData(data);
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs action while holding both the data.json and the catalog/rankings
     * lock, so the files can be copied or replaced as a whole (backup/restore).
     * Lock order: data.json, then catalog/rankings.
     */
    public <T> T exclusive(Callable<T> action) throws Exception {
        lock.lock();
        try {
            configLock.lock();
            try {
                return action.call();
            } finally {
                configLock.unlock();
            }
        } finally {
            lock.unlock();
        }
    }

    // Private on purpose: all access goes through read()/update() so it stays serialized.
    private Map<String, Object> loadData() throws IOException {
        File dataFile = new File(dataFilePath);

        if (!dataFile.exists()) {
            logger.info("Data file not found, creating default structure");
            Map<String, Object> defaultData = createDefaultData();
            saveData(defaultData);
            return defaultData;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(dataFile);
            if (!rootNode.isObject() || rootNode.size() == 0) {
                logger.warn("Data file is empty or corrupted, creating default structure");
                Map<String, Object> defaultData = createDefaultData();
                saveData(defaultData);
                return defaultData;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = objectMapper.convertValue(rootNode, Map.class);

            // Ensure all required keys exist
            String[] requiredKeys = {"competitors", "results"};
            for (String key : requiredKeys) {
                if (!data.containsKey(key)) {
                    data.put(key, new ArrayList<>());
                }
            }
            // Leftovers of removed features; dropped on the next save
            for (String key : OBSOLETE_KEYS) {
                data.remove(key);
            }

            // Add override_value and version to existing results if missing
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
            if (results != null) {
                for (Map<String, Object> result : results) {
                    if (!result.containsKey("override_value")) {
                        result.put("override_value", null);
                    }
                    result.putIfAbsent("version", 0);
                }
            }

            // Add version to existing competitors if missing
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> competitors = (List<Map<String, Object>>) data.get("competitors");
            if (competitors != null) {
                for (Map<String, Object> competitor : competitors) {
                    competitor.putIfAbsent("version", 0);
                    for (String key : OBSOLETE_COMPETITOR_KEYS) {
                        competitor.remove(key);
                    }
                }
            }

            restoreDerivedIds(data);
            return data;
        } catch (IOException e) {
            logger.error("Error reading data file, creating default structure", e);
            Map<String, Object> defaultData = createDefaultData();
            saveData(defaultData);
            return defaultData;
        }
    }

    private void saveData(Map<String, Object> data) throws IOException {
        // Create a temporary file and write to it, then rename to original
        File tempFile = new File(dataFilePath + ".tmp");
        objectMapper.writeValue(tempFile, withoutDerivedIds(data));

        // Atomic rename
        Files.move(tempFile.toPath(), Paths.get(dataFilePath),
                   StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        logger.debug("Data saved successfully");
    }

    private static final String[] OBSOLETE_KEYS = {"active_disciplines", "teams", "disciplines"};
    private static final String[] OBSOLETE_COMPETITOR_KEYS = {"disciplines", "team_id", "relay_number"};

    /**
     * Ids that can be derived are not stored: a start's discipline_id is the key
     * of the starts map it sits in, and a result's competitor_id/discipline_id
     * follow from its start. They are filled back in here so callers keep
     * seeing them. Older files that still store them load unchanged.
     */
    @SuppressWarnings("unchecked")
    private static void restoreDerivedIds(Map<String, Object> data) {
        Map<String, int[]> startOwners = new HashMap<>();
        for (Map<String, Object> competitor : (List<Map<String, Object>>) data.get("competitors")) {
            if (!(competitor.get("starts") instanceof Map)) {
                continue;
            }
            int competitorId = ((Number) competitor.get("id")).intValue();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) competitor.get("starts")).entrySet()) {
                int disciplineId;
                try {
                    disciplineId = Integer.parseInt(entry.getKey());
                } catch (NumberFormatException e) {
                    continue;
                }
                for (Map<String, Object> start : (List<Map<String, Object>>) entry.getValue()) {
                    start.putIfAbsent("discipline_id", disciplineId);
                    if (start.get("generated_id") != null) {
                        startOwners.put(start.get("generated_id").toString(), new int[] {competitorId, disciplineId});
                    }
                }
            }
        }
        for (Map<String, Object> result : (List<Map<String, Object>>) data.get("results")) {
            int[] owner = result.get("start_id") != null ? startOwners.get(result.get("start_id").toString()) : null;
            if (owner != null) {
                result.putIfAbsent("competitor_id", owner[0]);
                result.putIfAbsent("discipline_id", owner[1]);
            }
        }
    }

    /** A copy of data without the ids restoreDerivedIds() can rebuild; data itself is left untouched. */
    private static JsonNode withoutDerivedIds(Map<String, Object> data) {
        ObjectNode root = objectMapper.valueToTree(data);
        Map<String, int[]> startOwners = new HashMap<>();
        for (JsonNode competitor : root.path("competitors")) {
            int competitorId = competitor.path("id").asInt();
            for (Iterator<Map.Entry<String, JsonNode>> it = competitor.path("starts").fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                for (JsonNode start : entry.getValue()) {
                    int disciplineId = start.path("discipline_id").asInt();
                    // Only dropped where the map key really is that discipline
                    if (String.valueOf(disciplineId).equals(entry.getKey())) {
                        ((ObjectNode) start).remove("discipline_id");
                        if (start.hasNonNull("generated_id")) {
                            startOwners.put(start.get("generated_id").asText(), new int[] {competitorId, disciplineId});
                        }
                    }
                }
            }
        }
        for (JsonNode result : root.path("results")) {
            // Kept when the start is gone or disagrees: nothing could restore them then
            int[] owner = startOwners.get(result.path("start_id").asText());
            if (owner != null && result.path("competitor_id").asInt() == owner[0]
                    && result.path("discipline_id").asInt() == owner[1]) {
                ((ObjectNode) result).remove("competitor_id");
                ((ObjectNode) result).remove("discipline_id");
            }
        }
        return root;
    }

    // --- discipline catalog (disciplines.json) -------------------------------

    public List<Discipline> loadDisciplines() throws IOException {
        return loadCatalog().getDisciplines();
    }

    public DisciplineCatalog loadCatalog() throws IOException {
        configLock.lock();
        try {
            return loadCatalogInternal();
        } finally {
            configLock.unlock();
        }
    }

    /** Loads, modifies and saves the disciplines atomically. Nothing is saved if fn throws. */
    public <T> T updateDisciplines(DisciplineFunction<T> fn) throws Exception {
        return updateCatalog(catalog -> fn.apply(catalog.getDisciplines()));
    }

    /** Loads, modifies and saves the catalog atomically. Nothing is saved if fn throws. */
    public <T> T updateCatalog(CatalogFunction<T> fn) throws Exception {
        configLock.lock();
        try {
            DisciplineCatalog catalog = loadCatalogInternal();
            T result = fn.apply(catalog);
            saveCatalogInternal(catalog);
            return result;
        } finally {
            configLock.unlock();
        }
    }

    /**
     * The catalog. Without a disciplines.json, or with one of the old layout
     * (a plain list, from before the catalog was this installation's own), it
     * starts as the shipped default-disciplines.json; an old file is kept as
     * disciplines.legacy.json.
     */
    private DisciplineCatalog loadCatalogInternal() throws IOException {
        File file = new File(disciplinesFilePath);
        if (file.exists()) {
            JsonNode root = objectMapper.readTree(file);
            if (isCatalog(root)) {
                return objectMapper.treeToValue(root, DisciplineCatalog.class);
            }
            Path legacy = Paths.get(disciplinesFilePath.replaceFirst("\\.json$", "") + ".legacy.json");
            Files.move(file.toPath(), legacy, StandardCopyOption.REPLACE_EXISTING);
            logger.warn("{} has the old layout; kept as {}, starting from the default catalog", file, legacy);
        }
        DisciplineCatalog catalog = defaultCatalog();
        if (catalog != null) {
            saveCatalogInternal(catalog);
            logger.info("Created {} from {} ({} disciplines)", file, defaultCatalogFilePath, catalog.getDisciplines().size());
            return catalog;
        }
        return new DisciplineCatalog();
    }

    /** Whether the JSON is a discipline catalog (or a disciplines export) of the current format. */
    public static boolean isCatalog(JsonNode root) {
        return root != null && root.isObject() && DisciplineCatalog.FORMAT.equals(root.path("format").asText())
            && root.path("disciplines").isArray();
    }

    /** The shipped default catalog, or null if there is none. */
    public DisciplineCatalog defaultCatalog() throws IOException {
        File file = new File(defaultCatalogFilePath);
        if (!file.exists()) {
            return null;
        }
        JsonNode root = objectMapper.readTree(file);
        if (!isCatalog(root)) {
            logger.warn("{} is not a discipline catalog", file);
            return null;
        }
        return objectMapper.treeToValue(root, DisciplineCatalog.class);
    }

    private void saveCatalogInternal(DisciplineCatalog catalog) throws IOException {
        catalog.setFormat(DisciplineCatalog.FORMAT);
        catalog.setFormatVersion(DisciplineCatalog.FORMAT_VERSION);
        writeJson(disciplinesFilePath, catalog);
    }

    // --- the meet's rankings (rankings.json) --------------------------------

    public RankingConfig loadRankingConfig() throws IOException {
        configLock.lock();
        try {
            return loadRankingConfigInternal();
        } finally {
            configLock.unlock();
        }
    }

    /**
     * Loads, modifies and saves the meet's rankings atomically, with the catalog
     * at hand to check them against. Nothing is saved if fn throws.
     */
    public <T> T updateRankingConfig(RankingConfigFunction<T> fn) throws Exception {
        configLock.lock();
        try {
            RankingConfig config = loadRankingConfigInternal();
            T result = fn.apply(config, loadCatalogInternal());
            writeJson(rankingsFilePath, config);
            return result;
        } finally {
            configLock.unlock();
        }
    }

    /** No rankings.json yet: no rankings, and the catalog's limit of disciplines per ranking. */
    private RankingConfig loadRankingConfigInternal() throws IOException {
        File file = new File(rankingsFilePath);
        if (file.exists()) {
            return objectMapper.readValue(file, RankingConfig.class);
        }
        RankingConfig config = new RankingConfig();
        config.setMaxDisciplinesPerRanking(loadCatalogInternal().getMaxDisciplines());
        return config;
    }

    /**
     * The meet's program: the disciplines its rankings cover. Only these take
     * starts (individual) or teams (team).
     */
    public Set<Integer> loadProgramIds() throws IOException {
        Set<Integer> ids = new LinkedHashSet<>();
        loadRankingConfig().getRankings().forEach(r -> ids.addAll(r.disciplineIds()));
        return ids;
    }

    private static void writeJson(String path, Object value) throws IOException {
        File tempFile = new File(path + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, value);
        Files.move(tempFile.toPath(), Paths.get(path), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @FunctionalInterface
    public interface DisciplineFunction<T> {
        T apply(List<Discipline> disciplines) throws Exception;
    }

    @FunctionalInterface
    public interface CatalogFunction<T> {
        T apply(DisciplineCatalog catalog) throws Exception;
    }

    @FunctionalInterface
    public interface RankingConfigFunction<T> {
        T apply(RankingConfig config, DisciplineCatalog catalog) throws Exception;
    }

    private Map<String, Object> createDefaultData() {
        Map<String, Object> defaultData = new HashMap<>();
        defaultData.put("competitors", new ArrayList<>());
        defaultData.put("results", new ArrayList<>());
        return defaultData;
    }

    public int getNextId(List<?> items) {
        if (items == null || items.isEmpty()) {
            return 1;
        }
        int maxId = 0;
        for (Object item : items) {
            if (item instanceof Map) {
                Object id = ((Map<?, ?>) item).get("id");
                if (id instanceof Integer) {
                    maxId = Math.max(maxId, (Integer) id);
                }
            } else if (item instanceof Integer) {
                maxId = Math.max(maxId, (Integer) item);
            }
        }
        return maxId + 1;
    }

    public static int getVersion(Map<String, Object> record) {
        Object version = record.get("version");
        return version instanceof Number ? ((Number) version).intValue() : 0;
    }

    /**
     * Throws ConflictException if the client based its change on an older version
     * of the record. A null expectedVersion skips the check (e.g. scripted API use).
     */
    public static void checkVersion(Map<String, Object> stored, Integer expectedVersion, String message, Object current) {
        if (expectedVersion != null && getVersion(stored) != expectedVersion) {
            throw new ConflictException(message, current);
        }
    }

    public static void bumpVersion(Map<String, Object> record) {
        record.put("version", getVersion(record) + 1);
    }
}
