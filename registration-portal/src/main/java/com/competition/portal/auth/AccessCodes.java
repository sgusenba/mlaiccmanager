package com.competition.portal.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The clubs' access codes: 16 characters of Crockford base32 (no I, L, O, U),
 * 80 random bits, shown as XXXX-XXXX-XXXX-XXXX. Typed codes are forgiving:
 * case, dashes and spaces do not matter, and O, I and L are read as 0, 1, 1.
 * Only the SHA-256 of the code is stored; at 80 random bits a slow hash is
 * not needed to keep it from being guessed.
 */
public final class AccessCodes {
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AccessCodes() {
    }

    /** A new code, formatted with dashes. */
    public static String generate() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                code.append('-');
            }
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** The code as typed reduced to its 16 characters, or null if it cannot be a code. */
    public static String normalize(String typed) {
        if (typed == null) {
            return null;
        }
        String code = typed.toUpperCase().replaceAll("[\\s-]", "")
            .replace('O', '0').replace('I', '1').replace('L', '1');
        if (code.length() != LENGTH) {
            return null;
        }
        for (char c : code.toCharArray()) {
            if (ALPHABET.indexOf(c) < 0) {
                return null;
            }
        }
        return code;
    }

    /** SHA-256 (hex) of the normalized code, or null if it cannot be a code. */
    public static String hash(String typed) {
        String code = normalize(typed);
        if (code == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The code's last four characters, so the organiser can tell codes apart. */
    public static String hint(String code) {
        String normalized = normalize(code);
        return "…-" + normalized.substring(LENGTH - 4);
    }
}
