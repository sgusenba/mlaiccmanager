package com.competition.portal.model;

import com.competition.portal.format.CatalogFile;
import com.competition.portal.format.RankingsFile;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A meet's rankings and disciplines as uploaded from the Manager. The
 * starters' choices refer to its rankings and entries; the disciplines the
 * export lists follow from it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Snapshot {
    public String uploadedAt;
    /** Checksums of the uploaded files, to tell uploads apart. */
    public String rankingsSha256;
    public String catalogSha256;
    /** Every ranking as uploaded, team rankings included (they are only not offered). */
    public List<RankingsFile.Ranking> rankings = new ArrayList<>();
    /** Only the catalog disciplines the rankings refer to. */
    public List<CatalogFile.Discipline> disciplines = new ArrayList<>();
}
