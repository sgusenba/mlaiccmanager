package com.competition.portal.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHasherTest {

    @Test
    void hashesAsArgon2idAndVerifies() {
        String hash = PasswordHasher.hash("correct horse battery");
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), hash);
        assertTrue(PasswordHasher.verify("correct horse battery", hash));
        assertFalse(PasswordHasher.verify("correct horse batterY", hash));
        assertNotEquals(hash, PasswordHasher.hash("correct horse battery"), "a new salt each time");
    }

    @Test
    void malformedOrMissingHashesNeverMatch() {
        assertFalse(PasswordHasher.verify("x", null));
        assertFalse(PasswordHasher.verify("x", "plain"));
        assertFalse(PasswordHasher.verify("x", "$argon2i$v=19$m=8,t=1,p=1$AAAA$AAAA"));
        assertFalse(PasswordHasher.verify(null, PasswordHasher.hash("something long")));
    }

    @Test
    void passwordsNeedTwelveCharacters() {
        assertEquals("password.tooShort", PasswordHasher.problem("short"));
        assertEquals("password.tooShort", PasswordHasher.problem(null));
        assertNull(PasswordHasher.problem("twelve chars"));
        assertEquals("password.tooLong", PasswordHasher.problem("x".repeat(257)));
    }
}
