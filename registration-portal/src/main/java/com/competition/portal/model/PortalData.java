package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** portal.json: the organiser accounts, the portal's settings and the id counters. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PortalData {
    public List<Organiser> organisers = new ArrayList<>();
    public Settings settings = new Settings();
    /** The next id per kind ("organiser", "meet"); only ever goes up, so an id is never handed out twice. */
    public Map<String, Integer> nextIds = new LinkedHashMap<>();
}
