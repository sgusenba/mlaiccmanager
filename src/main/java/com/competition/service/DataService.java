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

public class DataService {
    private static final Logger logger = LoggerFactory.getLogger(DataService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private String dataFilePath;
    private String disciplinesFilePath;

    public DataService(String dataFilePath, String disciplinesFilePath) {
        this.dataFilePath = dataFilePath;
        this.disciplinesFilePath = disciplinesFilePath;
    }

    public Map<String, Object> loadData() throws IOException {
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

            // Add override_value to existing results if missing
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
            if (results != null) {
                for (Map<String, Object> result : results) {
                    if (!result.containsKey("override_value")) {
                        result.put("override_value", null);
                    }
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

    public void saveData(Map<String, Object> data) throws IOException {
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

    public String generateStartId(int competitorId, int disciplineId, List<?> existingStarts) {
        int startNumber = existingStarts.size() + 1;
        return competitorId + "" + disciplineId + startNumber;
    }
}