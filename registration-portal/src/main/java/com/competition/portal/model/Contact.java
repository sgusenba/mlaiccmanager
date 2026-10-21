package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Who to ask about a meet's or a club's registrations. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Contact {
    public String name;
    public String email;
    public String club;
}
