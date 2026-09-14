package com.competition.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public class Ranking {
    @JsonProperty("rank")
    private int rank;

    @JsonProperty("competitor")
    private CompetitorInfo competitor;

    @JsonProperty("result_totals")
    private List<Double> resultTotals;

    @JsonProperty("total_sum")
    private double totalSum;

    @JsonProperty("freq_counts")
    private Map<String, Integer> freqCounts;

    @JsonProperty("override_value")
    private Double overrideValue;

    @JsonProperty("notes")
    private String notes;

    // Nested class for competitor info
    public static class CompetitorInfo {
        @JsonProperty("id")
        private int id;

        @JsonProperty("name")
        private String name;

        @JsonProperty("team")
        private String team;

        // Getters and Setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getTeam() { return team; }
        public void setTeam(String team) { this.team = team; }
    }

    // Constructors
    public Ranking() {}

    // Getters and Setters
    public int getRank() { return rank; }
    public void setRank(int rank) { this.rank = rank; }

    public CompetitorInfo getCompetitor() { return competitor; }
    public void setCompetitor(CompetitorInfo competitor) { this.competitor = competitor; }

    public List<Double> getResultTotals() { return resultTotals; }
    public void setResultTotals(List<Double> resultTotals) { this.resultTotals = resultTotals; }

    public double getTotalSum() { return totalSum; }
    public void setTotalSum(double totalSum) { this.totalSum = totalSum; }

    public Map<String, Integer> getFreqCounts() { return freqCounts; }
    public void setFreqCounts(Map<String, Integer> freqCounts) { this.freqCounts = freqCounts; }

    public Double getOverrideValue() { return overrideValue; }
    public void setOverrideValue(Double overrideValue) { this.overrideValue = overrideValue; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
