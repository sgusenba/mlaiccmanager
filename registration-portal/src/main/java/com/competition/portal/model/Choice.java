package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** A ticked ranking entry, e.g. Whitworth O/R entry 1 (WHITR); a starter may tick several entries of a ranking. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Choice {
    public int rankingId;
    public int entry;

    public Choice() {
    }

    public Choice(int rankingId, int entry) {
        this.rankingId = rankingId;
        this.entry = entry;
    }
}
