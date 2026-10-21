package com.competition.portal.service;

import com.competition.portal.model.Meet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    @TempDir
    Path dataDir;

    @Test
    void aCutOffLineIsSkippedAndTheRestStaysReadable() throws Exception {
        MeetStore meets = new MeetStore(dataDir, new PortalStore(dataDir));
        Meet meet = new Meet();
        meet.key = "lm";
        meet.name = "LM";
        int meetId = meets.create(meet).id;
        AuditLog log = new AuditLog(meets, Clock.fixed(Instant.parse("2027-04-01T10:00:00Z"), ZoneOffset.UTC));

        log.append(meetId, List.of(AuditLog.entry("club:1", "starter.created", "starter_id", 1, "Anna")));
        // what a power failure (or a backup taken during the write) leaves behind
        Files.writeString(meets.auditFile(meetId), "{\"at\":\"2027-04-01T10:00:00Z\",\"act", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertEquals(List.of("starter.created"), log.read(meetId, 0, 10).stream().map(e -> e.get("action")).toList());

        // the next change starts on a new line: only the broken one is lost
        log.append(meetId, List.of(AuditLog.entry("club:1", "starter.updated", "starter_id", 1, "Anna")));
        List<Map<String, Object>> entries = log.read(meetId, 0, 10);
        assertEquals(List.of("starter.updated", "starter.created"), entries.stream().map(e -> e.get("action")).toList());
        assertEquals(List.of("starter.created"), log.read(meetId, 1, 10).stream().map(e -> e.get("action")).toList());
    }
}
