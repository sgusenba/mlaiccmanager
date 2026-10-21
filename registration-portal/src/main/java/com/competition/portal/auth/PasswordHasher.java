package com.competition.portal.auth;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Organiser passwords as Argon2id hashes in PHC string format,
 * $argon2id$v=19$m=19456,t=2,p=1$salt$hash, with the parameters OWASP
 * recommends as minimum. A stored hash carries its own parameters, so they
 * can be raised later without breaking existing passwords.
 */
public final class PasswordHasher {
    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 256;
    private static final int MEMORY_KIB = 19 * 1024;
    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Verified against when the user name is unknown, so the answer takes as long either way. */
    private static final String DUMMY = hash("not-a-real-password-just-for-timing");

    private PasswordHasher() {
    }

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] hash = argon2(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM, HASH_BYTES);
        return "$argon2id$v=19$m=" + MEMORY_KIB + ",t=" + ITERATIONS + ",p=" + PARALLELISM
            + "$" + B64.encodeToString(salt) + "$" + B64.encodeToString(hash);
    }

    /** Whether password matches the stored hash; a null or malformed hash never matches. */
    public static boolean verify(String password, String stored) {
        if (password == null) {
            return false;
        }
        if (stored == null) {
            verify(password, DUMMY);
            return false;
        }
        try {
            String[] parts = stored.split("\\$");
            // "", "argon2id", "v=19", "m=..,t=..,p=..", salt, hash
            if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
                return false;
            }
            int m = 0, t = 0, p = 0;
            for (String param : parts[3].split(",")) {
                String[] kv = param.split("=");
                switch (kv[0]) {
                    case "m" -> m = Integer.parseInt(kv[1]);
                    case "t" -> t = Integer.parseInt(kv[1]);
                    case "p" -> p = Integer.parseInt(kv[1]);
                    default -> { return false; }
                }
            }
            byte[] salt = B64D.decode(parts[4]);
            byte[] expected = B64D.decode(parts[5]);
            byte[] actual = argon2(password, salt, m, t, p, expected.length);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Null if the password is acceptable, else the message key saying why not. */
    public static String problem(String password) {
        if (password == null || password.length() < MIN_LENGTH) {
            return "password.tooShort";
        }
        if (password.length() > MAX_LENGTH) {
            return "password.tooLong";
        }
        return null;
    }

    /** Verifies against the dummy hash, for an unknown user name. */
    public static void spendTime(String password) {
        verify(password != null ? password : "", DUMMY);
    }

    private static byte[] argon2(String password, byte[] salt, int memoryKib, int iterations, int parallelism, int length) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[length];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
        return out;
    }
}
