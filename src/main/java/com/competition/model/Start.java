package com.competition.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public class Start {
    @JsonProperty("generated_id")
    private String generatedId;
    
    @JsonProperty("start_number")
    private int startNumber;
    
    @JsonProperty("discipline_id")
    private int disciplineId;
    
    @JsonProperty("status")
    private String status;

    // Free text entered by the user, e.g. ".36" or ".56"; null if none given
    @JsonProperty("caliber")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String caliber;

    // The ids of the rankings the competitor is registered for with this start,
    // e.g. 7_Colt and 38_Remington_O for one Colt start. The start counts only
    // in these rankings. Null for starts added by discipline alone (older data,
    // API clients): they count in every ranking of their discipline.
    @JsonProperty("ranking_ids")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<Integer> rankingIds;

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

    public String getCaliber() { return caliber; }
    public void setCaliber(String caliber) { this.caliber = caliber; }

    public List<Integer> getRankingIds() { return rankingIds; }
    public void setRankingIds(List<Integer> rankingIds) { this.rankingIds = rankingIds; }

    /** Whether the start counts in the ranking: it is registered for it, or for no ranking in particular. */
    public static boolean countsIn(Object rankingIds, int rankingId) {
        if (!(rankingIds instanceof List<?> ids)) {
            return true;
        }
        return ids.stream().anyMatch(id -> id instanceof Number n && n.intValue() == rankingId);
    }

    @SuppressWarnings("unchecked")
    public static List<Integer> rankingIdsOf(Object value) {
        if (!(value instanceof List<?> ids)) {
            return null;
        }
        return ((List<Object>) ids).stream().filter(Number.class::isInstance)
            .map(n -> ((Number) n).intValue()).toList();
    }
}