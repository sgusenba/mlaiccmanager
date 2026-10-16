package com.competition.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/** A meet's rankings (rankings.json), in the order they are listed and printed. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RankingConfig {
    public static final int DEFAULT_MAX_DISCIPLINES = 4;

    // How many disciplines one ranking may cover at most
    @JsonProperty("max_disciplines_per_ranking")
    private int maxDisciplinesPerRanking = DEFAULT_MAX_DISCIPLINES;

    @JsonProperty("rankings")
    private List<RankingDefinition> rankings = new ArrayList<>();

    @JsonProperty("version")
    private int version;

    // The id the next ranking gets: a deleted ranking's id is never handed out again
    @JsonProperty("next_id")
    private int nextId = 1;

    public int getMaxDisciplinesPerRanking() { return maxDisciplinesPerRanking; }
    public void setMaxDisciplinesPerRanking(int maxDisciplinesPerRanking) { this.maxDisciplinesPerRanking = maxDisciplinesPerRanking; }

    public List<RankingDefinition> getRankings() { return rankings; }
    public void setRankings(List<RankingDefinition> rankings) { this.rankings = rankings != null ? rankings : new ArrayList<>(); }

    public int getNextId() { return nextId; }
    public void setNextId(int nextId) { this.nextId = nextId; }

    /** Raises next_id above every ranking's id, so an id stays taken after its ranking is deleted. */
    public void raiseNextId() {
        rankings.forEach(r -> nextId = Math.max(nextId, r.getId() + 1));
    }

    /** The id for a new ranking, counted in next_id. */
    public int takeNextId() {
        raiseNextId();
        return nextId++;
    }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
}
