package com.competition.portal.service;

import com.competition.portal.MutableClock;
import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.auth.AuthException;
import com.competition.portal.auth.Session;
import com.competition.portal.auth.Totp;
import com.competition.portal.model.Meet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AuthServiceTest {
    private static final String IP = "203.0.113.5";
    private static final String PASSWORD = "a long enough password";

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2027-04-01T10:00:00Z"));
    private PortalServices s;

    @BeforeEach
    void setUp() throws Exception {
        s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "https://reg.example.org"), clock);
        s.organiserService.create("stefan", PASSWORD);
    }

    @Test
    void organisersLogInWithTheirPassword() throws Exception {
        String token = s.authService.organiserLogin("Stefan", PASSWORD, null, IP);
        Session session = s.sessions.find(token);
        assertTrue(session.isOrganiser());
    }

    @Test
    void wrongPasswordAndUnknownUserGetTheSameAnswer() {
        AuthException wrong = assertThrows(AuthException.class, () -> s.authService.organiserLogin("stefan", "wrong password!", null, IP));
        AuthException unknown = assertThrows(AuthException.class, () -> s.authService.organiserLogin("nobody", "wrong password!", null, IP));
        assertEquals(401, wrong.getStatus());
        assertEquals(wrong.getMessage(), unknown.getMessage());
    }

    @Test
    void repeatedFailuresAreSlowedDown() {
        for (int i = 0; i < 6; i++) {
            assertThrows(AuthException.class, () -> s.authService.organiserLogin("stefan", "wrong password!", null, IP));
        }
        AuthException blocked = assertThrows(AuthException.class, () -> s.authService.organiserLogin("stefan", PASSWORD, null, IP));
        assertEquals(429, blocked.getStatus(), "even the right password waits");
        assertNotNull(blocked.getRetryAfter());
        clock.advance(Duration.ofMinutes(2));
        assertDoesNotThrow(() -> s.authService.organiserLogin("stefan", PASSWORD, null, IP));
    }

    @Test
    void withTheAuthenticatorAppACodeIsNeededOnce() throws Exception {
        int id = s.organiserService.idOf("stefan");
        String secret = (String) s.organiserService.totpSetup(id).get("secret");
        long now = clock.instant().getEpochSecond();
        s.organiserService.totpConfirm(id, code(secret, now));

        AuthException missing = assertThrows(AuthException.class, () -> s.authService.organiserLogin("stefan", PASSWORD, null, IP));
        assertTrue(missing.isTotpRequired());

        clock.advance(Duration.ofSeconds(30));
        String current = code(secret, clock.instant().getEpochSecond());
        assertNotNull(s.authService.organiserLogin("stefan", PASSWORD, current, IP));
        AuthException replay = assertThrows(AuthException.class, () -> s.authService.organiserLogin("stefan", PASSWORD, current, IP));
        assertTrue(replay.isTotpRequired(), "a code works only once");
    }

    @Test
    void clubsLogInWithTheirCodeOnceTheMeetIsOpen() throws Exception {
        Meet meet = s.meetService.create(meetRequest());
        ClubService.WithCode club = s.clubService.create(meet.id, Map.of("name", "SV Bad Zell"));

        AuthException draft = assertThrows(AuthException.class, () -> s.authService.clubLogin(club.code, IP));
        assertEquals(403, draft.getStatus());

        open(meet.id);
        String token = s.authService.clubLogin(club.code.toLowerCase().replace("-", " "), IP);
        Session session = s.sessions.find(token);
        assertTrue(session.isClub());
        assertEquals(meet.id, session.meetId);
        assertNotNull(s.meetStore.get(meet.id).clubs.get(0).lastLoginAt);

        AuthException wrong = assertThrows(AuthException.class, () -> s.authService.clubLogin("0000-0000-0000-0000", IP));
        assertEquals(401, wrong.getStatus());

        s.clubService.revoke(meet.id, 1, true);
        assertThrows(AuthException.class, () -> s.authService.clubLogin(club.code, IP), "revoked");
        assertNull(s.sessions.find(token), "and logged out");
    }

    private void open(int meetId) throws Exception {
        s.meetService.uploadSnapshot(meetId, Fixtures.ooeRankings(), Fixtures.mlaicCatalog());
        Map<String, Object> r = meetRequest();
        r.put("status", Meet.OPEN);
        s.meetService.update(meetId, r);
    }

    static Map<String, Object> meetRequest() {
        Map<String, Object> r = new HashMap<>();
        r.put("key", "ooe-lm");
        r.put("name", "OÖ VL LM");
        r.put("deadline", "2027-04-30T23:59");
        return r;
    }

    static String code(String secret, long epochSeconds) {
        return Totp.code(decode(secret), Totp.step(epochSeconds), 6);
    }

    private static byte[] decode(String base32) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
