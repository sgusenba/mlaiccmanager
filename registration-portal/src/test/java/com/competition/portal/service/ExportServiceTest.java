package com.competition.portal.service;

import com.competition.portal.MutableClock;
import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.model.Meet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The file for the Manager's Import Registrations page (static/import/import.js reads format, starters, meet and contact). */
class ExportServiceTest {
    @TempDir
    Path dir;

    @Test
    @SuppressWarnings("unchecked")
    void writesTheManagersImportFormat() throws Exception {
        PortalServices s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "https://reg.example.org"),
            new MutableClock(Instant.parse("2027-04-01T10:00:00Z")));
        Map<String, Object> r = AuthServiceTest.meetRequest();
        r.put("key", "ooe-lm-2027");
        r.put("contact", Map.of("name", "Max Melder", "email", "max@example.org", "club", "SV Bad Zell"));
        int meetId = s.meetService.create(r).id;
        s.meetService.uploadSnapshot(meetId, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        s.clubService.create(meetId, Map.of("name", "SV Bad Zell", "default_country", "Austria"));
        s.clubService.create(meetId, Map.of("name", "SG Linz", "default_country", "Austria"));
        r.put("status", Meet.OPEN);
        s.meetService.update(meetId, r);
        s.starterService.save(meetId, 1, Map.of("consent", true, "upsert", List.of(
            Map.of("name", "Anna Huber", "gender", "female", "year_of_birth", 1981,
                "choices", List.of(Map.of("ranking_id", 69, "entry", 0), Map.of("ranking_id", 88, "entry", 0), Map.of("ranking_id", 84, "entry", 1))))), "club:1", false);
        s.starterService.saveContact(meetId, 1, Map.of("name", "Club Contact"), "club:1", false);
        s.starterService.save(meetId, 2, Map.of("consent", true, "upsert", List.of(Map.of("name", "Josef Gruber"))), "club:2", false);

        Map<String, Object> file = s.exportService.meet(meetId);
        assertEquals("mlaic-registrations", file.get("format"));
        assertEquals(1, file.get("version"));
        assertEquals(Map.of("key", "ooe-lm-2027", "name", "OÖ VL LM"), file.get("meet"));
        assertEquals("Max Melder", ((Map<String, Object>) file.get("contact")).get("name"), "the meet's contact");
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) file.get("contacts");
        assertEquals(2, contacts.size());
        assertEquals("Club Contact", contacts.get(0).get("name"));
        assertEquals(1L, contacts.get(0).get("starters"));

        List<Map<String, Object>> starters = (List<Map<String, Object>>) file.get("starters");
        Map<String, Object> anna = starters.get(0);
        assertEquals("Anna Huber", anna.get("name"));
        assertEquals("female", anna.get("gender"));
        assertEquals(1981, anna.get("year_of_birth"));
        assertEquals("Austria", anna.get("country"));
        assertEquals("SV Bad Zell", anna.get("club"));
        assertEquals("SV Bad Zell", anna.get("registered_by"));
        List<Map<String, Object>> starts = (List<Map<String, Object>>) anna.get("starts");
        assertEquals(List.of(52, 53, 14), starts.stream().map(x -> x.get("discipline_id")).toList(),
            "Colt once (alone and in Remington O), Donald Malson O, Whitworth R");
        assertEquals("COLT", starts.get(0).get("short_name"));
        assertEquals("7_Colt", starts.get(0).get("discipline"));
        assertEquals(List.of("Colt", "Remington O/R: REMO", "Whitworth O/R: WHITR"), anna.get("rankings"));
        assertEquals(List.of(), ((Map<String, Object>) starters.get(1)).get("starts"), "a starter without a ranking is exported too");

        Map<String, Object> own = s.exportService.club(meetId, 2);
        assertEquals(1, ((List<?>) own.get("starters")).size(), "a club's copy holds only its own starters");
        assertEquals("SG Linz", ((Map<String, Object>) own.get("contact")).get("club"));
        assertEquals("registrations-ooe-lm-2027-2027-04-01.json", s.exportService.fileName(meetId, null));
        assertEquals("registrations-ooe-lm-2027-sg-linz-2027-04-01.json", s.exportService.fileName(meetId, 2));
    }
}
