package com.competition.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// Unknown fields (e.g. the dropped scoring_type, unit, description) are ignored, so older files still load
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Discipline {
    @JsonProperty("id")
    private int id;
    
    @JsonProperty("category")
    private String category;
    
    @JsonProperty("level")
    private String level;
    
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("event")
    private String event;

    @JsonProperty("short_name")
    private String shortName;
    
    @JsonProperty("created_at")
    private String createdAt;
    
    @JsonProperty("based_on")
    private String basedOn;
    
    @JsonProperty("team_size")
    private Integer teamSize;

    // Set on a team discipline: the individual disciplines whose starts count for its teams
    @JsonProperty("team_of")
    private List<Integer> teamOf;

    @JsonProperty("shooting_distance")
    private String shootingDistance;

    // Set on an aggregate discipline (e.g. Remington): the individual disciplines, of the
    // same category and type, whose results are added up. It takes no lane or result of its own.
    @JsonProperty("aggregate_of")
    private List<Integer> aggregateOf;

    // Set on a combined ranking (e.g. KUCR = KUCRS + KUCRU): the individual disciplines,
    // of the same category, ranked together. Competitors start in those, not in it.
    @JsonProperty("combined_of")
    private List<Integer> combinedOf;

    @JsonProperty("active")
    private boolean active = true;

    // Constructors
    public Discipline() {}

    public Discipline(String event, String category) {
        this.event = event;
        this.category = category;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }




    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getBasedOn() { return basedOn; }
    public void setBasedOn(String basedOn) { this.basedOn = basedOn; }

    public Integer getTeamSize() { return teamSize; }
    public void setTeamSize(Integer teamSize) { this.teamSize = teamSize; }

    public List<Integer> getTeamOf() { return teamOf; }
    public void setTeamOf(List<Integer> teamOf) { this.teamOf = teamOf; }

    public String getShootingDistance() { return shootingDistance; }
    public void setShootingDistance(String shootingDistance) { this.shootingDistance = shootingDistance; }

    public List<Integer> getAggregateOf() { return aggregateOf; }
    public void setAggregateOf(List<Integer> aggregateOf) { this.aggregateOf = aggregateOf; }

    public List<Integer> getCombinedOf() { return combinedOf; }
    public void setCombinedOf(List<Integer> combinedOf) { this.combinedOf = combinedOf; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}