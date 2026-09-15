package com.competition.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class Result {
    @JsonProperty("id")
    private int id;
    
    @JsonProperty("competitor_id")
    private int competitorId;
    
    @JsonProperty("discipline_id")
    private int disciplineId;
    
    @JsonProperty("start_id")
    private String startId;
    
    @JsonProperty("value")
    private double value;
    
    @JsonProperty("entries")
    private List<Double> entries;
    
    @JsonProperty("override_value")
    private Double overrideValue;
    
    @JsonProperty("notes")
    private String notes;
    
    @JsonProperty("created_at")
    private String createdAt;
    
    @JsonProperty("updated_at")
    private String updatedAt;

    // Incremented on every update; clients send it back so stale saves can be rejected
    @JsonProperty("version")
    private Integer version;

    // Constructors
    public Result() {}

    public Result(int competitorId, int disciplineId, double value) {
        this.competitorId = competitorId;
        this.disciplineId = disciplineId;
        this.value = value;
    }

    // Getters and Setters
    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public int getCompetitorId() { return competitorId; }
    public void setCompetitorId(int competitorId) { this.competitorId = competitorId; }

    public int getDisciplineId() { return disciplineId; }
    public void setDisciplineId(int disciplineId) { this.disciplineId = disciplineId; }

    public String getStartId() { return startId; }
    public void setStartId(String startId) { this.startId = startId; }

    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }

    public List<Double> getEntries() { return entries; }
    public void setEntries(List<Double> entries) { this.entries = entries; }

    public Double getOverrideValue() { return overrideValue; }
    public void setOverrideValue(Double overrideValue) { this.overrideValue = overrideValue; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}