package com.competition.portal.format;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * The Manager's discipline catalog export (GET /api/disciplines/export,
 * format "mlaic-disciplines"), as far as the portal reads it; the shapes
 * follow the Manager's model/Discipline.java.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogFile {
    public static final String FORMAT = "mlaic-disciplines";
    public static final int VERSION = 1;

    public String format;
    public Integer version;
    public List<Discipline> disciplines = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Discipline {
        public int id;
        /** "rifle" or "pistol" */
        public String category;
        /** "individual" or "team" */
        public String level = "individual";
        /** "original", "reproduction" or "open" */
        public String type;
        public String name;
        public String shortName;
        public String shootingDistance;
        public boolean active = true;

        public boolean isTeam() {
            return "team".equalsIgnoreCase(level);
        }
    }
}
