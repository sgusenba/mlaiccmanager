package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** An organiser account. There is no sign-up: accounts are created on the server (--add-organiser). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Organiser {
    public int id;
    public String username;
    /** Argon2id in PHC string format; never the password itself. */
    public String passwordHash;
    /** Base32 TOTP secret, null while the second factor is off. */
    public String totpSecret;
    public boolean totpConfirmed;
    /** The last 30-second step a TOTP code was accepted for, so a code cannot be used twice. */
    public long totpLastStep;
    public String createdAt;
    public int version;
}
