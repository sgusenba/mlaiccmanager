package com.competition.portal.service;

import com.competition.portal.MutableClock;
import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Starter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StarterServiceTest {
    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2027-04-01T10:00:00Z"));
    private PortalServices s;
    private int meetId;
    private static final int BAD_ZELL = 1;
    private static final int LINZ = 2;

    @BeforeEach
    void setUp() throws Exception {
        s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "https://reg.example.org"), clock);
        Map<String, Object> r = AuthServiceTest.meetRequest();
        r.put("date", "2027-05-14");
        meetId = s.meetService.create(r).id;
        s.meetService.uploadSnapshot(meetId, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        s.clubService.create(meetId, Map.of("name", "SV Bad Zell", "default_country", "Austria"));
        s.clubService.create(meetId, Map.of("name", "SG Linz", "default_country", "Austria"));
        r.put("status", Meet.OPEN);
        s.meetService.update(meetId, r);
    }

    static Map<String, Object> row(String name, Object... choices) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", name);
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i + 1 < choices.length; i += 2) {
            list.add(Map.of("ranking_id", choices[i], "entry", choices[i + 1]));
        }
        row.put("choices", list);
        return row;
    }

    private Map<String, Object> body(List<Map<String, Object>> upsert, List<Map<String, Object>> delete) {
        Map<String, Object> body = new HashMap<>();
        body.put("upsert", upsert);
        body.put("delete", delete);
        body.put("consent", true);
        return body;
    }

    @SuppressWarnings("unchecked")
    private List<Starter> saved(Map<String, Object> result) {
        return (List<Starter>) result.get("starters");
    }

    @Test
    void aClubSavesItsStartersAndConfirmsConsentOnce() throws Exception {
        Map<String, Object> noConsent = new HashMap<>();
        noConsent.put("upsert", List.of(row("Anna Huber", 69, 0)));
        assertThrows(IllegalArgumentException.class, () -> s.starterService.save(meetId, BAD_ZELL, noConsent, "club:1", false));

        Map<String, Object> result = s.starterService.save(meetId, BAD_ZELL,
            body(List.of(row("Anna Huber", 69, 0, 84, 0, 84, 1), row("Josef Gruber")), List.of()), "club:1", false);
        List<Starter> starters = saved(result);
        assertEquals(2, starters.size());
        Starter anna = starters.get(0);
        assertEquals("Austria", anna.country, "the club's default country");
        assertEquals("SV Bad Zell", anna.club, "the club's name");
        assertEquals(3, anna.choices.size(), "Colt, Whitworth O and R");
        assertEquals(1, anna.version);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> warnings = (Map<String, List<String>>) result.get("warnings");
        assertTrue(warnings.get(String.valueOf(starters.get(1).id)).get(0).contains("No ranking"));

        assertNotNull(s.meetStore.get(meetId).clubs.get(0).consentAt);
        noConsent.put("upsert", List.of(row("Karl Mayr")));
        assertDoesNotThrow(() -> s.starterService.save(meetId, BAD_ZELL, noConsent, "club:1", false), "asked only once");
    }

    @Test
    void oneBadRowAndNothingIsSaved() throws Exception {
        Map<String, Object> bad = row("", 999, 0);
        bad.put("country", "Atlantis");
        bad.put("year_of_birth", 1850);
        ValidationException e = assertThrows(ValidationException.class, () -> s.starterService.save(meetId, BAD_ZELL,
            body(List.of(row("Anna Huber", 69, 0), bad), List.of()), "club:1", false));
        assertEquals(1, e.getRows().size());
        assertEquals(1, e.getRows().get(0).get("index"));
        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) e.getRows().get(0).get("errors");
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(s.meetStore.get(meetId).starters.isEmpty());
    }

    @Test
    void staleRowsAreSentBackAndUnchangedRowsKeepTheirVersion() throws Exception {
        Starter anna = saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Anna Huber", 69, 0)), List.of()), "club:1", false)).get(0);

        Map<String, Object> same = row("Anna Huber", 69, 0);
        same.put("id", anna.id);
        same.put("version", 1);
        assertEquals(1, saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(same), List.of()), "club:1", false)).get(0).version);

        Map<String, Object> changed = row("Anna Huber", 69, 0, 70, 0);
        changed.put("id", anna.id);
        changed.put("version", 1);
        assertEquals(2, saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(changed), List.of()), "club:1", false)).get(0).version);

        // Someone still holding version 1
        ConflictException conflict = assertThrows(ConflictException.class,
            () -> s.starterService.save(meetId, BAD_ZELL, body(List.of(changed), List.of()), "club:1", false));
        assertEquals(2, ((List<?>) conflict.getCurrent()).size() == 1 ? ((Starter) ((List<?>) conflict.getCurrent()).get(0)).version : -1);
        assertThrows(ConflictException.class, () -> s.starterService.save(meetId, BAD_ZELL,
            body(List.of(), List.of(Map.of("id", anna.id, "version", 1))), "club:1", false), "delete with an old version");

        s.starterService.save(meetId, BAD_ZELL, body(List.of(), List.of(Map.of("id", anna.id, "version", 2))), "club:1", false);
        assertTrue(s.meetStore.get(meetId).starters.isEmpty());
    }

    @Test
    void aClubCannotTouchAnotherClubsStarters() throws Exception {
        Starter anna = saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Anna Huber")), List.of()), "club:1", false)).get(0);
        Map<String, Object> steal = row("Hijacked");
        steal.put("id", anna.id);
        steal.put("version", 1);
        assertThrows(ValidationException.class, () -> s.starterService.save(meetId, LINZ, body(List.of(steal), List.of()), "club:2", false));
        assertThrows(ValidationException.class, () -> s.starterService.save(meetId, LINZ,
            body(List.of(), List.of(Map.of("id", anna.id, "version", 1))), "club:2", false));
        assertEquals(0, saved(s.starterService.list(meetId, LINZ)).size());
        assertEquals("Anna Huber", s.meetStore.get(meetId).starters.get(0).name);
    }

    @Test
    void afterTheDeadlineOnlyTheOrganiserCanChangeThings() throws Exception {
        clock.advance(Duration.ofDays(30)); // past 2027-04-30 23:59 Vienna
        assertThrows(ClosedException.class, () -> s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Late")), List.of()), "club:1", false));
        assertThrows(ClosedException.class, () -> s.starterService.saveContact(meetId, BAD_ZELL, Map.of("name", "X"), "club:1", false));
        assertEquals(false, s.starterService.clubMeet(meetId, BAD_ZELL).get("open"));
        assertEquals(1, saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Late entry")), List.of()), "organiser:1", true)).size());
    }

    @Test
    void namesTwiceAreWarnings() throws Exception {
        s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Anna Huber", 69, 0)), List.of()), "club:1", false);
        s.starterService.save(meetId, LINZ, body(List.of(row("anna huber", 70, 0)), List.of()), "club:2", false);
        assertTrue(((Map<?, ?>) s.starterService.list(meetId, LINZ).get("warnings")).isEmpty(), "clubs do not see other clubs");
        String all = s.starterService.listAll(meetId).get("warnings").toString();
        assertTrue(all.contains("Also registered by SV Bad Zell") && all.contains("Also registered by SG Linz"), all);
    }

    @Test
    void changesGoToTheAuditLogAndPurgingDeletesEverythingPersonal() throws Exception {
        Starter anna = saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Anna Huber", 69, 0, 88, 0)), List.of()), "club:1", false)).get(0);
        s.starterService.saveContact(meetId, BAD_ZELL, Map.of("name", "Max Melder", "email", "max@example.org"), "club:1", false);
        s.starterService.save(meetId, BAD_ZELL, body(List.of(), List.of(Map.of("id", anna.id, "version", 1))), "organiser:1", true);

        List<Map<String, Object>> log = s.auditLog.read(meetId, 0, 10);
        assertEquals(List.of("starter.deleted", "club.contact", "starter.created", "club.consent"),
            log.stream().map(e -> e.get("action")).toList(), "newest first");
        assertEquals("Anna Huber (2 start(s))", log.get(2).get("summary"), "Colt counted once: alone and in Remington O");
        assertEquals("organiser:1", log.get(0).get("actor"));
        assertFalse(Files.readString(s.meetStore.auditFile(meetId)).contains("max@example.org"), "no contact data in the log");

        s.starterService.save(meetId, BAD_ZELL, body(List.of(row("Josef Gruber")), List.of()), "club:1", false);
        assertEquals(1, s.starterService.purge(meetId, "organiser:1"));
        Meet meet = s.meetStore.get(meetId);
        assertTrue(meet.starters.isEmpty());
        assertNull(meet.clubs.get(0).contact.name);
        assertEquals(2, meet.clubs.size(), "the clubs stay");
        List<Map<String, Object>> after = s.auditLog.read(meetId, 0, 10);
        assertEquals(1, after.size());
        assertFalse(after.toString().contains("Gruber"));
    }

    @Test
    void emailIsKeptOnlyWhenTheMeetCollectsIt() throws Exception {
        Map<String, Object> r = row("Anna Huber");
        r.put("email", "anna@example.org");
        assertNull(saved(s.starterService.save(meetId, BAD_ZELL, body(List.of(r), List.of()), "club:1", false)).get(0).email);
    }
}
