package com.competition.portal.service;

import com.competition.portal.model.Meet;
import com.competition.portal.model.Starter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class MeetStoreTest {
    @TempDir
    Path dataDir;

    private PortalStore portalStore;
    private MeetStore meets;

    @BeforeEach
    void setUp() {
        portalStore = new PortalStore(dataDir);
        meets = new MeetStore(dataDir, portalStore);
    }

    private static Meet meet(String name) {
        Meet meet = new Meet();
        meet.key = name.toLowerCase().replace(' ', '-');
        meet.name = name;
        return meet;
    }

    @Test
    void createdMeetsGetIdsAndComeBack() throws Exception {
        Meet first = meets.create(meet("OÖ VL LM"));
        Meet second = meets.create(meet("Freischießen VL"));
        assertEquals(1, first.id);
        assertEquals(2, second.id);
        assertEquals(1, first.version);
        assertEquals(Meet.DRAFT, first.status);

        MeetStore reopened = new MeetStore(dataDir, new PortalStore(dataDir));
        assertEquals("OÖ VL LM", reopened.get(1).name);
        assertEquals(List.of(1, 2), reopened.list().stream().map(m -> m.id).toList());
    }

    @Test
    void idsAreNeverHandedOutTwice() throws Exception {
        meets.create(meet("A"));
        meets.create(meet("B"));
        meets.delete(2);
        assertEquals(3, meets.create(meet("C")).id, "the deleted meet's id stays used");
        assertEquals(4, new MeetStore(dataDir, new PortalStore(dataDir)).create(meet("D")).id, "also after a restart");
    }

    @Test
    void aMissingMeetIsNotFound() throws Exception {
        assertThrows(RecordNotFoundException.class, () -> meets.get(7));
        assertThrows(RecordNotFoundException.class, () -> meets.update(7, m -> null));
        assertThrows(RecordNotFoundException.class, () -> meets.delete(7));
        assertFalse(Files.exists(dataDir.resolve("meets/7.json")), "update of a missing meet creates no file");
    }

    @Test
    void deletingAMeetRemovesItsFileAndAuditLog() throws Exception {
        Meet meet = meets.create(meet("A"));
        Files.writeString(meets.auditFile(meet.id), "{\"action\":\"test\"}\n");
        meets.delete(meet.id);
        assertFalse(Files.exists(dataDir.resolve("meets/1.json")));
        assertFalse(Files.exists(meets.auditFile(meet.id)));
        assertTrue(meets.list().isEmpty());
    }

    @Test
    void concurrentChangesToAMeetAreSerialized() throws Exception {
        int id = meets.create(meet("A")).id;
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        meets.update(id, m -> {
                            Starter s = new Starter();
                            s.id = Ids.take(m.nextIds, "starter");
                            s.name = "Starter " + s.id;
                            m.starters.add(s);
                            return null;
                        });
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }
        Meet stored = meets.get(id);
        int total = threads * perThread;
        assertEquals(total, stored.starters.size(), "no change lost");
        assertEquals(total, stored.starters.stream().map(s -> s.id).distinct().count(), "no id handed out twice");
        assertEquals(total + 1, stored.nextIds.get("starter"));
    }

    @Test
    void anUnreadableMeetFileIsAHardError() throws Exception {
        meets.create(meet("A"));
        Files.writeString(dataDir.resolve("meets/1.json"), "[broken");
        assertThrows(StorageException.class, () -> meets.get(1));
        assertThrows(StorageException.class, () -> meets.update(1, m -> null));
        assertEquals("[broken", Files.readString(dataDir.resolve("meets/1.json")));
    }
}
