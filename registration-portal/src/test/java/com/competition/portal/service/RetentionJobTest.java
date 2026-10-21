package com.competition.portal.service;

import com.competition.portal.MutableClock;
import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.model.Meet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RetentionJobTest {
    @TempDir
    Path dir;

    @Test
    void deletesTheRegistrationsTheDayAfterDeleteAfter() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2027-04-01T10:00:00Z"));
        PortalServices s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "https://reg.example.org"), clock);
        Map<String, Object> r = AuthServiceTest.meetRequest();
        r.put("date", "2027-05-14");
        r.put("delete_after", "2027-08-12");
        int meetId = s.meetService.create(r).id;
        s.meetService.uploadSnapshot(meetId, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        s.clubService.create(meetId, Map.of("name", "SV Bad Zell", "default_country", "Austria"));
        r.put("status", Meet.OPEN);
        s.meetService.update(meetId, r);
        s.starterService.save(meetId, 1, Map.of("upsert", List.of(StarterServiceTest.row("Anna Huber", 69, 0)), "consent", true), "club:1", false);
        s.starterService.saveContact(meetId, 1, Map.of("name", "Max Melder"), "club:1", false);

        clock.advance(Duration.between(clock.instant(), Instant.parse("2027-08-12T21:59:00Z"))); // 23:59 in Vienna, still the day
        assertEquals(0, s.retentionJob.run());
        assertEquals(1, s.meetStore.get(meetId).starters.size());

        clock.advance(Duration.ofMinutes(2)); // 00:01 on 13 August in Vienna
        assertEquals(1, s.retentionJob.run());
        Meet meet = s.meetStore.get(meetId);
        assertTrue(meet.starters.isEmpty());
        assertNull(meet.clubs.get(0).contact.name);
        assertEquals(1, meet.clubs.size(), "the club stays");
        assertNotNull(meet.snapshot, "the rankings stay");
        assertFalse(Files.exists(s.meetStore.auditFile(meetId)));

        assertEquals(0, s.retentionJob.run(), "nothing left to delete");
    }
}
