package com.competition.model;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    // Only set in a combined ranking: "original" or "reproduction"
    @JsonProperty("discipline_type")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String disciplineType;

    // Only set in a combined ranking (e.g. KUC): the short name of the discipline shot, e.g. "KUCRS"
    @JsonProperty("discipline_tag")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String disciplineTag;

    // Nested class for competitor info
    public static class CompetitorInfo {
        @JsonProperty("id")
        private int id;

        @JsonProperty("name")
        private String name;

        // Getters and Setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
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

    public String getDisciplineType() { return disciplineType; }
    public void setDisciplineType(String disciplineType) { this.disciplineType = disciplineType; }

    public String getDisciplineTag() { return disciplineTag; }
    public void setDisciplineTag(String disciplineTag) { this.disciplineTag = disciplineTag; }
}
