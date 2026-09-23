package com.competition.service;

import com.competition.model.Discipline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private final ReentrantLock disciplinesLock = new ReentrantLock();

    private String dataFilePath;
    // The shipped MLAIC catalog: tracked in git, replaced on every deploy, never written at runtime.
    private String disciplinesFilePath;
    // What this competition changed on top of the catalog (active list, overrides,
    // added and removed disciplines). Git-ignored, so deploys leave it alone.
    private String competitionFilePath;

    public DataService(String dataFilePath, String disciplinesFilePath, String competitionFilePath) {
        this.dataFilePath = dataFilePath;
        this.disciplinesFilePath = disciplinesFilePath;
        this.competitionFilePath = competitionFilePath;
    }

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

    public List<Discipline> loadDisciplines() throws IOException {
        disciplinesLock.lock();
        try {
            return loadDisciplinesInternal();
        } finally {
            disciplinesLock.unlock();
        }
    }

    /**
     * The catalog with this competition's settings applied: removed disciplines
     * dropped, overridden fields replaced, added disciplines appended and the
     * active flags set. Without a competition file the catalog is used as is.
     */
    private List<Discipline> loadDisciplinesInternal() throws IOException {
        List<ObjectNode> catalog = loadCatalog();
        JsonNode settings = loadCompetitionSettings();
        if (settings == null) {
            return toDisciplines(catalog);
        }

        Set<Integer> removed = new HashSet<>();
        settings.path("removed_disciplines").forEach(id -> removed.add(id.asInt()));
        JsonNode overrides = settings.path("discipline_overrides");

        List<ObjectNode> merged = new ArrayList<>();
        for (ObjectNode entry : catalog) {
            int id = entry.path("id").asInt();
            if (removed.contains(id)) {
                continue;
            }
            ObjectNode copy = entry.deepCopy();
            for (Iterator<Map.Entry<String, JsonNode>> it = overrides.path(String.valueOf(id)).fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                copy.set(field.getKey(), field.getValue());
            }
            merged.add(copy);
        }
        settings.path("custom_disciplines").forEach(entry -> merged.add(((ObjectNode) entry).deepCopy()));

        Set<Integer> active = new HashSet<>();
        settings.path("active_disciplines").forEach(id -> active.add(id.asInt()));
        List<Discipline> disciplines = toDisciplines(merged);
        for (Discipline d : disciplines) {
            d.setActive(active.contains(d.getId()));
        }
        return disciplines;
    }

    private List<ObjectNode> loadCatalog() throws IOException {
        File disciplinesFile = new File(disciplinesFilePath);

        if (!disciplinesFile.exists()) {
            logger.warn("Disciplines file not found");
            return new ArrayList<>();
        }

        JsonNode rootNode = objectMapper.readTree(disciplinesFile);

        // Handle both old nested structure and new flat structure
        if (rootNode.isObject() && rootNode.has("disciplines")) {
            rootNode = rootNode.get("disciplines");
        }
        List<ObjectNode> catalog = new ArrayList<>();
        if (rootNode.isArray()) {
            rootNode.forEach(entry -> catalog.add((ObjectNode) entry));
        }
        return catalog;
    }

    private JsonNode loadCompetitionSettings() throws IOException {
        File competitionFile = new File(competitionFilePath);
        return competitionFile.exists() ? objectMapper.readTree(competitionFile) : null;
    }

    private static List<Discipline> toDisciplines(List<ObjectNode> nodes) {
        return objectMapper.convertValue(nodes,
            objectMapper.getTypeFactory().constructCollectionType(List.class, Discipline.class));
    }

    /** Stores the given list as the difference to the catalog in the competition file; the catalog is never written. */
    public void saveDisciplines(List<Discipline> disciplines) throws IOException {
        disciplinesLock.lock();
        try {
            saveDisciplinesInternal(disciplines);
        } finally {
            disciplinesLock.unlock();
        }
    }

    /** Loads, modifies and saves disciplines atomically. */
    public <T> T updateDisciplines(DisciplineFunction<T> fn) throws Exception {
        disciplinesLock.lock();
        try {
            List<Discipline> disciplines = loadDisciplinesInternal();
            T result = fn.apply(disciplines);
            saveDisciplinesInternal(disciplines);
            return result;
        } finally {
            disciplinesLock.unlock();
        }
    }

    /** Whether the competition file exists yet; see ApplicationBinder for the one-time migration. */
    public boolean hasCompetitionSettings() {
        return new File(competitionFilePath).exists();
    }

    private void saveDisciplinesInternal(List<Discipline> disciplines) throws IOException {
        Map<Integer, ObjectNode> catalogById = new LinkedHashMap<>();
        for (ObjectNode entry : loadCatalog()) {
            catalogById.put(entry.path("id").asInt(), entry);
        }

        List<Integer> active = new ArrayList<>();
        Map<String, ObjectNode> overrides = new LinkedHashMap<>();
        List<ObjectNode> custom = new ArrayList<>();
        Set<Integer> present = new HashSet<>();
        for (Discipline d : disciplines) {
            present.add(d.getId());
            if (d.isActive()) {
                active.add(d.getId());
            }
            ObjectNode node = objectMapper.valueToTree(d);
            node.remove("active");
            ObjectNode base = catalogById.get(d.getId());
            if (base == null) {
                custom.add(node);
                continue;
            }
            ObjectNode diff = objectMapper.createObjectNode();
            Set<String> fields = new HashSet<>();
            base.fieldNames().forEachRemaining(fields::add);
            node.fieldNames().forEachRemaining(fields::add);
            fields.remove("id");
            fields.remove("active");
            for (String field : fields) {
                JsonNode value = node.get(field);
                if (!Objects.equals(base.get(field), value)) {
                    // A field cleared here but set in the catalog is stored as an explicit null
                    diff.set(field, value != null ? value : NullNode.getInstance());
                }
            }
            if (diff.size() > 0) {
                overrides.put(String.valueOf(d.getId()), diff);
            }
        }
        List<Integer> removed = new ArrayList<>();
        for (Integer id : catalogById.keySet()) {
            if (!present.contains(id)) {
                removed.add(id);
            }
        }

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("active_disciplines", active);
        settings.put("discipline_overrides", overrides);
        settings.put("custom_disciplines", custom);
        settings.put("removed_disciplines", removed);

        File tempFile = new File(competitionFilePath + ".tmp");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile, settings);
        Files.move(tempFile.toPath(), Paths.get(competitionFilePath),
                   StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        logger.debug("Competition settings saved successfully");
    }

    @FunctionalInterface
    public interface DisciplineFunction<T> {
        T apply(List<Discipline> disciplines) throws Exception;
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
