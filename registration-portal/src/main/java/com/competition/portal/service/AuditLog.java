package com.competition.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who changed what at a meet, one JSON line per change in
 * meets/&lt;id&gt;.audit.jsonl: {at, actor, action, starter_id or club_id,
 * summary}. The summary names the starter but holds no other personal data.
 * Lines are appended while the meet is locked, right after the change is
 * saved, so they are in the same order as the changes. The log is deleted
 * with the meet's registrations.
 */
public class AuditLog {
    private static final Logger logger = LoggerFactory.getLogger(AuditLog.class);
    private final MeetStore meets;
    private final Clock clock;

    public AuditLog(MeetStore meets, Clock clock) {
        this.meets = meets;
        this.clock = clock;
    }

    /** One line: actor "club:3" or "organiser:1", action e.g. "starter.created". */
    public static Map<String, Object> entry(String actor, String action, String key, Integer id, String summary) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("actor", actor);
        e.put("action", action);
        if (key != null) {
            e.put(key, id);
        }
        e.put("summary", summary);
        return e;
    }

    /** Appends the entries; the caller holds the meet's lock. */
    public void append(int meetId, List<Map<String, Object>> entries) throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        String at = clock.instant().toString();
        StringBuilder lines = new StringBuilder();
        for (Map<String, Object> e : entries) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("at", at);
            line.putAll(e);
            lines.append(JsonFile.MAPPER.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(line)).append('\n');
        }
        Path file = meets.auditFile(meetId);
        Files.createDirectories(file.getParent());
        if (!endsWithNewline(file)) {
            lines.insert(0, '\n'); // after a cut-off line: start a new one, so only that one is lost
        }
        Files.writeString(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        if (!Files.exists(file) || Files.size(file) == 0) {
            return true;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            ByteBuffer last = ByteBuffer.allocate(1);
            channel.position(channel.size() - 1).read(last);
            return last.get(0) == '\n';
        }
    }

    /** Removes the log; the caller holds the meet's lock. */
    public void delete(int meetId) throws IOException {
        Files.deleteIfExists(meets.auditFile(meetId));
    }

    /**
     * The newest lines first: offset lines skipped, at most limit returned. A
     * line that cannot be read (cut off by a power failure, or by a backup
     * taken while it was written) is skipped, so the rest of the log stays
     * readable.
     */
    public List<Map<String, Object>> read(int meetId, int offset, int limit) throws IOException {
        Path file = meets.auditFile(meetId);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                entries.add(JsonFile.MAPPER.readValue(line, new TypeReference<LinkedHashMap<String, Object>>() {}));
            } catch (JsonProcessingException e) {
                logger.warn("Skipping an unreadable line in {}", file);
            }
        }
        Collections.reverse(entries);
        return new ArrayList<>(entries.subList(Math.min(offset, entries.size()), Math.min(entries.size(), offset + limit)));
    }
}
