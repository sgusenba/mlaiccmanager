package com.competition.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    
    @JsonProperty("scoring_type")
    private String scoringType;
    
    @JsonProperty("unit")
    private String unit;
    
    @JsonProperty("description")
    private String description;
    
    @JsonProperty("created_at")
    private String createdAt;
    
    @JsonProperty("based_on")
    private String basedOn;
    
    @JsonProperty("team_size")
    private Integer teamSize;

    @JsonProperty("shooting_distance")
    private String shootingDistance;

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

    public String getScoringType() { return scoringType; }
    public void setScoringType(String scoringType) { this.scoringType = scoringType; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getBasedOn() { return basedOn; }
    public void setBasedOn(String basedOn) { this.basedOn = basedOn; }

    public Integer getTeamSize() { return teamSize; }
    public void setTeamSize(Integer teamSize) { this.teamSize = teamSize; }

    public String getShootingDistance() { return shootingDistance; }
    public void setShootingDistance(String shootingDistance) { this.shootingDistance = shootingDistance; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}