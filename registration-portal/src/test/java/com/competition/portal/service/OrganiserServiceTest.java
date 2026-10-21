package com.competition.portal.service;

import com.competition.portal.MutableClock;
import com.competition.portal.PortalConfig;
import com.competition.portal.PortalServices;
import com.competition.portal.auth.AuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OrganiserServiceTest {
    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(Instant.parse("2027-04-01T10:00:00Z"));
    private PortalServices s;

    @BeforeEach
    void setUp() {
        s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "https://reg.example.org"), clock);
    }

    @Test
    void accountsNeedAGoodNameAndPassword() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> s.organiserService.create("a", "a long enough password"));
        assertThrows(IllegalArgumentException.class, () -> s.organiserService.create("stefan", "short"));
        s.organiserService.create("Stefan", "a long enough password");
        assertThrows(IllegalArgumentException.class, () -> s.organiserService.create("stefan", "another long password"));
        assertEquals("stefan", s.organiserService.list().get(0).get("username"));
        assertFalse(Files.readString(dir.resolve("portal.json")).contains("a long enough password"));
    }

    @Test
    void theLastAccountStays() throws Exception {
        int first = s.organiserService.create("stefan", "a long enough password").id;
        int second = s.organiserService.create("second", "another long password").id;
        String token = s.sessions.createOrganiser(second);
        s.organiserService.delete(second);
        assertNull(s.sessions.find(token), "the deleted account is logged out");
        assertThrows(IllegalArgumentException.class, () -> s.organiserService.delete(first));
    }

    @Test
    void changingThePasswordEndsTheOtherSessions() throws Exception {
        int id = s.organiserService.create("stefan", "a long enough password").id;
        String other = s.sessions.createOrganiser(id);
        assertThrows(AuthException.class, () -> s.organiserService.changePassword(id, "wrong current pw", "a brand new password"));
        String fresh = s.organiserService.changePassword(id, "a long enough password", "a brand new password");
        assertNull(s.sessions.find(other));
        assertNotNull(s.sessions.find(fresh));
        assertDoesNotThrow(() -> s.authService.organiserLogin("stefan", "a brand new password", null, "x"));
    }

    @Test
    void theAuthenticatorAppIsOnOnlyAfterAMatchingCode() throws Exception {
        int id = s.organiserService.create("stefan", "a long enough password").id;
        String secret = (String) s.organiserService.totpSetup(id).get("secret");
        assertDoesNotThrow(() -> s.authService.organiserLogin("stefan", "a long enough password", null, "x"),
            "not asked before it is confirmed");
        assertThrows(IllegalArgumentException.class, () -> s.organiserService.totpConfirm(id, "000000"));
        s.organiserService.totpConfirm(id, AuthServiceTest.code(secret, clock.instant().getEpochSecond()));
        assertEquals(true, s.organiserService.list().get(0).get("totp_enabled"));
        assertThrows(IllegalArgumentException.class, () -> s.organiserService.totpSetup(id), "already on");

        s.organiserService.resetTotp(id);
        assertEquals(false, s.organiserService.list().get(0).get("totp_enabled"));
    }
}
