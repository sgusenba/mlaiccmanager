package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Portal-wide settings: who is responsible for the data, shown on the privacy page. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Settings {
    public Controller controller = new Controller();

    /** The data controller under the GDPR, e.g. the organising association. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Controller {
        public String name;
        public String address;
        public String email;
    }
}
