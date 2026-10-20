package com.competition.portal.service;

import com.competition.portal.model.Choice;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Starter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MeetServiceTest {
    @TempDir
    Path dataDir;

    private MeetStore store;
    private MeetService meets;

    @BeforeEach
    void setUp() {
        store = new MeetStore(dataDir, new PortalStore(dataDir));
        meets = new MeetService(store);
    }

    private static Map<String, Object> request(String key) {
        Map<String, Object> r = new HashMap<>();
        r.put("key", key);
        r.put("name", "OÖ VL LM 2027");
        r.put("date", "2027-05-14");
        r.put("deadline", "2027-04-30T23:59");
        return r;
    }

    private Meet withRankings(String key) throws Exception {
        Meet meet = meets.create(request(key));
        meets.uploadSnapshot(meet.id, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        return meets.get(meet.id);
    }

    private Map<String, Object> change(Meet meet, String status) {
        Map<String, Object> r = request(meet.key);
        r.put("version", meet.version);
        r.put("status", status);
        return r;
    }

    @Test
    void createsADraftWithDefaults() throws Exception {
        Meet meet = meets.create(request("ooe-lm-2027"));
        assertEquals(Meet.DRAFT, meet.status);
        assertEquals("Europe/Vienna", meet.timeZone);
        assertEquals("2027-04-30T23:59+02:00", meet.deadline, "a local time in the meet's zone, summer time");
        assertEquals("2027-08-12", meet.deleteAfter, "meet date plus 90 days");
        assertFalse(meet.collectEmail, "email is off unless turned on");
    }

    @Test
    void checksTheSettings() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> meets.create(request("Bad Key")));
        Map<String, Object> noName = request("a");
        noName.remove("name");
        assertThrows(IllegalArgumentException.class, () -> meets.create(noName));
        Map<String, Object> badZone = request("a");
        badZone.put("time_zone", "Mars/Olympus");
        assertThrows(IllegalArgumentException.class, () -> meets.create(badZone));
        Map<String, Object> badDeadline = request("a");
        badDeadline.put("deadline", "next friday");
        assertThrows(IllegalArgumentException.class, () -> meets.create(badDeadline));
        Map<String, Object> deleteTooEarly = request("a");
        deleteTooEarly.put("delete_after", "2027-01-01");
        assertThrows(IllegalArgumentException.class, () -> meets.create(deleteTooEarly));

        meets.create(request("a"));
        assertThrows(IllegalArgumentException.class, () -> meets.create(request("a")), "key in use");
    }

    @Test
    void aStaleChangeIsRefusedWithTheCurrentMeet() throws Exception {
        Meet meet = meets.create(request("a"));
        Map<String, Object> first = change(meet, null);
        first.put("name", "Renamed");
        assertEquals(2, meets.update(meet.id, first).version);

        ConflictException conflict = assertThrows(ConflictException.class, () -> meets.update(meet.id, change(meet, null)));
        @SuppressWarnings("unchecked")
        Map<String, Object> current = (Map<String, Object>) conflict.getCurrent();
        assertEquals("Renamed", current.get("name"));
        assertFalse(current.containsKey("starters") && current.get("starters") instanceof List, "no personal data in the answer");
    }

    @Test
    void openingNeedsRankingsAndADeadlineAndNoOtherOpenMeet() throws Exception {
        Meet bare = meets.create(request("bare"));
        assertThrows(IllegalArgumentException.class, () -> meets.update(bare.id, change(bare, Meet.OPEN)), "no rankings");

        Meet noDeadline = withRankings("no-deadline");
        Map<String, Object> r = change(noDeadline, Meet.OPEN);
        r.remove("deadline");
        assertThrows(IllegalArgumentException.class, () -> meets.update(noDeadline.id, r));

        Meet first = withRankings("first");
        assertEquals(Meet.OPEN, meets.update(first.id, change(first, Meet.OPEN)).status);
        Meet second = withRankings("second");
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> meets.update(second.id, change(second, Meet.OPEN)));
        assertTrue(refused.getMessage().contains("OÖ VL LM 2027"));

        Meet firstNow = meets.get(first.id);
        meets.update(first.id, change(firstNow, Meet.CLOSED));
        assertEquals(Meet.OPEN, meets.update(second.id, change(meets.get(second.id), Meet.OPEN)).status);
    }

    @Test
    void uploadingReturnsTheOfferAndRefusesToBreakTickedEntries() throws Exception {
        Meet meet = meets.create(request("a"));
        MeetService.UploadResult result = meets.uploadSnapshot(meet.id, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        assertEquals(21, result.offer.size());
        assertEquals(2, result.meet.get("version"));

        store.update(meet.id, m -> {
            Starter s = new Starter();
            s.id = Ids.take(m.nextIds, "starter");
            s.name = "Anna Huber";
            s.choices = List.of(new Choice(84, 1));
            m.starters.add(s);
            return null;
        });
        ObjectNode withoutWhitworthR = Fixtures.ooeRankings();
        for (var r : withoutWhitworthR.get("rankings")) {
            if (r.get("id").asInt() == 84) {
                ((ArrayNode) r.get("entries")).remove(1);
            }
        }
        ConflictException conflict = assertThrows(ConflictException.class,
            () -> meets.uploadSnapshot(meet.id, withoutWhitworthR, Fixtures.mlaicCatalog()));
        assertTrue(conflict.getCurrent().toString().contains("Anna Huber"));
        assertEquals(21, SnapshotService.offer(meets.get(meet.id).snapshot).size(), "nothing changed");

        // The same files again are fine: no ticked entry changes
        meets.uploadSnapshot(meet.id, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
    }

    @Test
    void noUploadWhileClosed() throws Exception {
        Meet meet = withRankings("a");
        meets.update(meet.id, change(meet, Meet.CLOSED));
        assertThrows(IllegalArgumentException.class,
            () -> meets.uploadSnapshot(meet.id, Fixtures.ooeRankings(), Fixtures.mlaicCatalog()));
    }
}
