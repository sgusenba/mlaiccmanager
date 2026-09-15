package com.competition.service;

import com.competition.model.Discipline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private String dataFilePath;
    private String disciplinesFilePath;

    public DataService(String dataFilePath, String disciplinesFilePath) {
        this.dataFilePath = dataFilePath;
        this.disciplinesFilePath = disciplinesFilePath;
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
            String[] requiredKeys = {"competitors", "disciplines", "teams", "active_disciplines", "results"};
            for (String key : requiredKeys) {
                if (!data.containsKey(key)) {
                    data.put(key, new ArrayList<>());
                }
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
                }
            }

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
        objectMapper.writeValue(tempFile, data);

        // Atomic rename
        Files.move(tempFile.toPath(), Paths.get(dataFilePath),
                   StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

        logger.debug("Data saved successfully");
    }

    public List<Discipline> loadDisciplines() throws IOException {
        File disciplinesFile = new File(disciplinesFilePath);

        if (!disciplinesFile.exists()) {
            logger.warn("Disciplines file not found");
            return new ArrayList<>();
        }

        JsonNode rootNode = objectMapper.readTree(disciplinesFile);

        // Handle both old nested structure and new flat structure
        if (rootNode.isObject() && rootNode.has("disciplines")) {
            JsonNode disciplinesNode = rootNode.get("disciplines");
            return objectMapper.convertValue(disciplinesNode,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Discipline.class));
        } else if (rootNode.isArray()) {
            return objectMapper.convertValue(rootNode,
                objectMapper.getTypeFactory().constructCollectionType(List.class, Discipline.class));
        } else {
            return new ArrayList<>();
        }
    }

    private Map<String, Object> createDefaultData() {
        Map<String, Object> defaultData = new HashMap<>();
        defaultData.put("competitors", new ArrayList<>());
        defaultData.put("disciplines", new ArrayList<>());
        defaultData.put("teams", new ArrayList<>());
        defaultData.put("active_disciplines", new ArrayList<>());
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
