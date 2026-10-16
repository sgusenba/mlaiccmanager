package com.competition.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * A discipline of the installation's catalog (disciplines.json): something
 * that is shot, so competitors start in it and get a lane (individual), or
 * that teams are entered for (team). Disciplines are the same at every meet;
 * what is ranked, and how, is a meet's rankings (see RankingDefinition).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class Discipline {
    @JsonProperty("id")
    private int id;

    @JsonProperty("category")
    private String category;

    // "individual" or "team"
    @JsonProperty("level")
    private String level;

    // "original", "reproduction" or "open"; shown and used for the lane order only
    @JsonProperty("type")
    private String type;

    @JsonProperty("name")
    @JsonAlias("event")
    private String name;

    // A display label, e.g. KUCRS; need not be unique, everything refers to the id
    @JsonProperty("short_name")
    private String shortName;

    @JsonProperty("team_size")
    private Integer teamSize;

    // Set on a team discipline: the individual disciplines whose starts count for its teams
    @JsonProperty("team_of")
    private List<Integer> teamOf;

    @JsonProperty("shooting_distance")
    private String shootingDistance;

    // False: archived, offered for no new ranking, its history stays
    @JsonProperty("active")
    private boolean active = true;

    public Discipline() {}

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public Integer getTeamSize() { return teamSize; }
    public void setTeamSize(Integer teamSize) { this.teamSize = teamSize; }

    public List<Integer> getTeamOf() { return teamOf; }
    public void setTeamOf(List<Integer> teamOf) { this.teamOf = teamOf; }

    public String getShootingDistance() { return shootingDistance; }
    public void setShootingDistance(String shootingDistance) { this.shootingDistance = shootingDistance; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    /** The short name, else the name: how the discipline is tagged in lists. */
    public String label() {
        return shortName != null && !shortName.isBlank() ? shortName : name;
    }

    @JsonIgnore
    public boolean isTeam() {
        return "team".equalsIgnoreCase(level);
    }
}
