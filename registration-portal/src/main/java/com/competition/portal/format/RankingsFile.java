package com.competition.portal.format;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The Manager's rankings export (GET /api/rankings/export, format
 * "mlaic-rankings"), as far as the portal reads it. The shapes follow the
 * Manager's model/RankingDefinition.java; unknown fields (e.g. the removed
 * one_start_per_competitor of older files) are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RankingsFile {
    public static final String FORMAT = "mlaic-rankings";
    public static final int VERSION = 1;

    public String format;
    public Integer version;
    public List<Ranking> rankings = new ArrayList<>();

    /** A ranking (Wertung): one or more entries ranked together. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ranking {
        public int id;
        public String name;
        public String shortName;
        /** "individual" or "team" */
        public String level = "individual";
        public List<Entry> entries = new ArrayList<>();

        public boolean isTeam() {
            return "team".equalsIgnoreCase(level);
        }
    }

    /** What is ranked: one discipline, or several whose results are added up (e.g. Remington O = Colt + Donald Malson O). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        /** Names a sum, e.g. "REMO"; a single discipline goes by its own name. */
        public String label;
        public List<Integer> disciplineIds = new ArrayList<>();
        /** Written by the Manager for the reader; the ids are what counts. */
        public List<String> disciplineShortNames;
    }
}
