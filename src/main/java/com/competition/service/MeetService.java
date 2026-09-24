package com.competition.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The meet itself: its name (e.g. "Staatsmeisterschaft"), where it takes
 * place, who hosts it and its dates. Stored in meet.json and printed on the
 * cover page, start cards and race bibs.
 *
 * <p>The dates are optional; the pages fall back to the first and last meet
 * day of the relay management when they are empty.
 */
public class MeetService {
    private static final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    static final String[] TEXT_FIELDS = {"name", "location", "host"};
    static final String[] DATE_FIELDS = {"date_from", "date_to"};
    static final int MAX_LENGTH = 200;

    private final ReentrantLock lock = new ReentrantLock();
    private final String meetFilePath;

    public MeetService(String meetFilePath) {
        this.meetFilePath = meetFilePath;
    }

    public Map<String, Object> get() throws Exception {
        return exclusive(this::load);
    }

    /**
     * Replaces the meet details with the request's. Send back the version you
     * saw; if someone else saved in the meantime, a ConflictException carries
     * the current details.
     */
    public Map<String, Object> update(Map<String, Object> request) throws Exception {
        Map<String, Object> meet = new LinkedHashMap<>();
        for (String field : TEXT_FIELDS) {
            meet.put(field, text(request, field));
        }
        for (String field : DATE_FIELDS) {
            meet.put(field, date(request, field));
        }
        if (meet.get("date_from") != null && meet.get("date_to") != null
                && ((String) meet.get("date_from")).compareTo((String) meet.get("date_to")) > 0) {
            throw new IllegalArgumentException("date_from must not be after date_to");
        }
        Integer expectedVersion = request != null && request.get("version") instanceof Number n ? n.intValue() : null;

        return exclusive(() -> {
            Map<String, Object> stored = load();
            DataService.checkVersion(stored, expectedVersion,
                "The meet details were changed by someone else meanwhile", stored);
            meet.put("version", DataService.getVersion(stored));
            DataService.bumpVersion(meet);
            save(meet);
            return meet;
        });
    }

    /** Runs action while holding the meet.json lock, so the file can be copied or replaced as a whole. */
    public <T> T exclusive(Callable<T> action) throws Exception {
        lock.lock();
        try {
            return action.call();
        } finally {
            lock.unlock();
        }
    }

    private static String text(Map<String, Object> request, String field) {
        Object value = request != null ? request.get(field) : null;
        if (value == null) {
            return "";
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(field + " must be text");
        }
        String trimmed = s.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    /** A YYYY-MM-DD date, or null when missing or blank. */
    private static String date(Map<String, Object> request, String field) {
        Object value = request != null ? request.get(field) : null;
        if (value == null || (value instanceof String s && s.isBlank())) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString().trim()).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be YYYY-MM-DD");
        }
    }

    // --- storage -----------------------------------------------------------

    /** The stored details with every field present; defaults while meet.json does not exist. Caller holds the lock. */
    private Map<String, Object> load() throws IOException {
        File file = new File(meetFilePath);
        Map<String, Object> stored = file.exists()
            ? objectMapper.readValue(file, new TypeReference<LinkedHashMap<String, Object>>() {})
            : new LinkedHashMap<>();
        Map<String, Object> meet = new LinkedHashMap<>();
        for (String field : TEXT_FIELDS) {
            meet.put(field, stored.get(field) instanceof String s ? s : "");
        }
        for (String field : DATE_FIELDS) {
            meet.put(field, stored.get(field) instanceof String s && !s.isBlank() ? s : null);
        }
        meet.put("version", DataService.getVersion(stored));
        return meet;
    }

    private void save(Map<String, Object> meet) throws IOException {
        File tempFile = new File(meetFilePath + ".tmp");
        objectMapper.writeValue(tempFile, meet);
        Files.move(tempFile.toPath(), Paths.get(meetFilePath),
            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
