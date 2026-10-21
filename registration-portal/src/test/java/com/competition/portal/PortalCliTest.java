package com.competition.portal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PortalCliTest {
    @TempDir
    Path dir;

    private int run(PortalServices s, String... argsAndPasswords) {
        int split = argsAndPasswords[0].equals("--reset-totp") ? 2 : 2;
        String[] args = {argsAndPasswords[0], argsAndPasswords[1]};
        Deque<String> passwords = new ArrayDeque<>(List.of(argsAndPasswords).subList(split, argsAndPasswords.length));
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        return PortalCli.run(args, s, quiet, quiet, prompt -> passwords.isEmpty() ? null : passwords.poll());
    }

    @Test
    void createsAndRecoversOrganiserAccounts() throws Exception {
        PortalServices s = new PortalServices(new PortalConfig("127.0.0.1", 0, dir, dir, "http://localhost"), Clock.systemUTC());
        assertEquals(0, run(s, "--add-organiser", "stefan", "a long enough password", "a long enough password"));
        assertEquals(1, run(s, "--add-organiser", "other", "a long enough password", "a different password"), "typo in the repeat");
        assertEquals(1, run(s, "--add-organiser", "x", "a long enough password", "a long enough password"), "bad name");
        assertEquals(0, run(s, "--set-password", "stefan", "a brand new password", "a brand new password"));
        assertDoesNotThrow(() -> s.authService.organiserLogin("stefan", "a brand new password", null, "x"));
        assertEquals(0, run(s, "--reset-totp", "stefan"));
        assertEquals(1, run(s, "--reset-totp", "nobody"));
        assertEquals(2, run(s, "--unknown", "x"));
        assertEquals(1, s.organiserService.list().size());
    }
}
