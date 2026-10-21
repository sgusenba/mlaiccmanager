package com.competition.portal.service;

import com.competition.portal.MutableClock;
import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.auth.AccessCodes;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Starter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClubServiceTest {
    @TempDir
    Path dir;

    private PortalServices s;
    private int meetId;

    @BeforeEach
    void setUp() throws Exception {
        s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "https://reg.example.org/"),
            new MutableClock(Instant.parse("2027-04-01T10:00:00Z")));
        meetId = s.meetService.create(AuthServiceTest.meetRequest()).id;
    }

    @Test
    void aNewClubGetsACodeThatIsStoredOnlyAsHash() throws Exception {
        ClubService.WithCode created = s.clubService.create(meetId, Map.of("name", "SV Bad Zell", "default_country", "Austria"));
        assertTrue(created.code.matches("[0-9A-Z]{4}(-[0-9A-Z]{4}){3}"));
        assertEquals("https://reg.example.org/login/#c=" + created.code, created.inviteUrl);
        assertFalse(created.club.containsKey("code_hash"));
        assertEquals(AccessCodes.hint(created.code), created.club.get("code_hint"));

        String file = Files.readString(dir.resolve("meets/" + meetId + ".json"));
        assertFalse(file.contains(created.code), "the code itself is never stored");
        assertTrue(file.contains(AccessCodes.hash(created.code)));

        assertThrows(IllegalArgumentException.class, () -> s.clubService.create(meetId, Map.of("name", "sv bad zell")), "name in use");
    }

    @Test
    void replacingTheCodeEndsTheOldOneAndTheSessions() throws Exception {
        ClubService.WithCode created = s.clubService.create(meetId, Map.of("name", "SV Bad Zell"));
        String token = s.sessions.createClub(meetId, 1);
        ClubService.WithCode rotated = s.clubService.rotate(meetId, 1);
        assertNotEquals(created.code, rotated.code);
        assertEquals(AccessCodes.hash(rotated.code), s.meetStore.get(meetId).clubs.get(0).codeHash);
        assertNull(s.sessions.find(token));
    }

    @Test
    void aClubWithStartersIsDeletedOnlyWithThem() throws Exception {
        s.clubService.create(meetId, Map.of("name", "SV Bad Zell"));
        s.meetStore.update(meetId, m -> {
            Starter st = new Starter();
            st.id = Ids.take(m.nextIds, "starter");
            st.clubId = 1;
            st.name = "Anna Huber";
            m.starters.add(st);
            return null;
        });
        assertThrows(ConflictException.class, () -> s.clubService.delete(meetId, 1, false));
        s.clubService.delete(meetId, 1, true);
        Meet meet = s.meetStore.get(meetId);
        assertTrue(meet.clubs.isEmpty());
        assertTrue(meet.starters.isEmpty());
    }

    @Test
    void staleChangesAreRefused() throws Exception {
        s.clubService.create(meetId, Map.of("name", "SV Bad Zell"));
        s.clubService.update(meetId, 1, Map.of("name", "SV Bad Zell 1890", "version", 1));
        assertThrows(ConflictException.class, () -> s.clubService.update(meetId, 1, Map.of("name", "X", "version", 1)));
        assertThrows(RecordNotFoundException.class, () -> s.clubService.rotate(meetId, 9));
    }
}
