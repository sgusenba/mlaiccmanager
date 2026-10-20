package com.competition.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * One ranking (Wertung) of a meet, e.g. "Whitworth O/R": its own name, and
 * the entries that are ranked together in it. An entry is one discipline, or
 * the sum of several (e.g. Remington_O = Colt + Donald Malson_O). Each
 * competitor (team) is listed once, on their best entry result, then the next.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class RankingDefinition {
    @JsonProperty("id")
    private int id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("short_name")
    private String shortName;

    // "individual" or "team"; all its disciplines have this level
    @JsonProperty("level")
    private String level = "individual";

    @JsonProperty("entries")
    private List<Entry> entries = new ArrayList<>();

    @JsonProperty("version")
    private int version;

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }


    public List<Entry> getEntries() { return entries; }
    public void setEntries(List<Entry> entries) { this.entries = entries != null ? entries : new ArrayList<>(); }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    @JsonIgnore
    public boolean isTeam() {
        return "team".equalsIgnoreCase(level);
    }

    /** Every discipline the ranking covers, in entry order. */
    public Set<Integer> disciplineIds() {
        Set<Integer> ids = new LinkedHashSet<>();
        entries.forEach(e -> ids.addAll(e.getDisciplineIds()));
        return ids;
    }

    /** What is ranked: one discipline, or several whose results are added up. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entry {
        // Names a sum, e.g. "Remington_O"; a single discipline goes by its own name
        @JsonProperty("label")
        private String label;

        @JsonProperty("discipline_ids")
        private List<Integer> disciplineIds = new ArrayList<>();

        public Entry() {}

        public Entry(String label, List<Integer> disciplineIds) {
            this.label = label;
            setDisciplineIds(disciplineIds);
        }

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public List<Integer> getDisciplineIds() { return disciplineIds; }
        public void setDisciplineIds(List<Integer> disciplineIds) {
            this.disciplineIds = disciplineIds != null ? new ArrayList<>(disciplineIds) : new ArrayList<>();
        }

        @JsonIgnore
        public boolean isSum() {
            return disciplineIds.size() > 1;
        }
    }
}
