package com.competition.portal.auth;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AccessCodesTest {

    @Test
    void codesAreSixteenCrockfordCharactersInGroupsOfFour() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = AccessCodes.generate();
            assertTrue(code.matches("[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){3}"), code);
            assertTrue(seen.add(code));
        }
    }

    @Test
    void typedCodesAreForgiving() {
        String code = "7Q2K-01AB-CDEF-GH1J";
        String hash = AccessCodes.hash(code);
        assertEquals(hash, AccessCodes.hash("7q2k01abcdefgh1j"));
        assertEquals(hash, AccessCodes.hash(" 7Q2K OLAB CDEF GHIJ "), "O, L and I read as 0, 1, 1");
        assertNull(AccessCodes.hash("too-short"));
        assertNull(AccessCodes.hash("7Q2K-01AB-CDEF-GH1U"), "U is not in the alphabet");
        assertNull(AccessCodes.hash(null));
        assertEquals("…-GH1J", AccessCodes.hint(code));
    }
}
