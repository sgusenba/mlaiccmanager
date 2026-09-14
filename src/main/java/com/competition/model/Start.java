package com.competition.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Start {
    @JsonProperty("generated_id")
    private String generatedId;
    
    @JsonProperty("start_number")
    private int startNumber;
    
    @JsonProperty("discipline_id")
    private int disciplineId;
    
    @JsonProperty("status")
    private String status;

    // Constructors
    public Start() {}

    public Start(String generatedId, int startNumber, int disciplineId) {
        this.generatedId = generatedId;
        this.startNumber = startNumber;
        this.disciplineId = disciplineId;
        this.status = "registered";
    }

    // Getters and Setters
    public String getGeneratedId() { return generatedId; }
    public void setGeneratedId(String generatedId) { this.generatedId = generatedId; }

    public int getStartNumber() { return startNumber; }
    public void setStartNumber(int startNumber) { this.startNumber = startNumber; }

    public int getDisciplineId() { return disciplineId; }
    public void setDisciplineId(int disciplineId) { this.disciplineId = disciplineId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}