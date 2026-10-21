package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A club registering for one meet, with its access code (stored only as a hash). */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Club {
    public int id;
    public String name;
    /** English country name, as in the Manager; the default for the club's rows. */
    public String defaultCountry;
    /** SHA-256 of the access code; the code itself is shown once and never stored. */
    public String codeHash;
    /** The code's last characters, so the organiser can tell codes apart. */
    public String codeHint;
    public String codeCreatedAt;
    public boolean revoked;
    public String lastLoginAt;
    /** When the club confirmed it may pass on its members' data (asked on its first save). */
    public String consentAt;
    public Contact contact = new Contact();
    public int version;
}
