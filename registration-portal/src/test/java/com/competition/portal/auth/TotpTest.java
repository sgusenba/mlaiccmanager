package com.competition.portal.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    // RFC 6238, appendix B: the SHA-1 test key and its 8-digit codes
    private static final byte[] RFC_KEY = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesTheRfc6238TestVectors() {
        assertEquals("94287082", Totp.code(RFC_KEY, Totp.step(59), 8));
        assertEquals("07081804", Totp.code(RFC_KEY, Totp.step(1111111109), 8));
        assertEquals("14050471", Totp.code(RFC_KEY, Totp.step(1111111111), 8));
        assertEquals("89005924", Totp.code(RFC_KEY, Totp.step(1234567890), 8));
        assertEquals("69279037", Totp.code(RFC_KEY, Totp.step(2000000000), 8));
        assertEquals("005924", Totp.code(RFC_KEY, Totp.step(1234567890), 6), "the last six digits, as the apps show them");
    }

    @Test
    void acceptsTheCurrentCodeAndOneStepEitherSideButNeverTwice() {
        String secret = Totp.base32(RFC_KEY);
        long now = 1234567890;
        long step = Totp.step(now);
        assertEquals(step, Totp.verify(secret, "005924", now, 0));
        assertEquals(step, Totp.verify(secret, "005 924", now, 0), "spaces do not matter");
        assertEquals(-1, Totp.verify(secret, "005924", now, step), "already used");
        String previous = Totp.code(RFC_KEY, step - 1, 6);
        assertEquals(step - 1, Totp.verify(secret, previous, now, 0), "clock drift of one step");
        String old = Totp.code(RFC_KEY, step - 3, 6);
        assertEquals(-1, Totp.verify(secret, old, now, 0));
        assertEquals(-1, Totp.verify(secret, "12345", now, 0));
        assertEquals(-1, Totp.verify(null, "005924", now, 0));
    }

    @Test
    void base32RoundTripsAndTheUriNamesTheAccount() {
        String secret = Totp.newSecret();
        assertEquals(32, secret.length());
        assertEquals(secret, Totp.base32(Totp.decodeBase32(secret)));
        String uri = Totp.uri("MLAIC Anmeldeportal", "stefan", "ABC");
        assertEquals("otpauth://totp/MLAIC%20Anmeldeportal:stefan?secret=ABC&issuer=MLAIC%20Anmeldeportal&algorithm=SHA1&digits=6&period=30", uri);
    }
}
