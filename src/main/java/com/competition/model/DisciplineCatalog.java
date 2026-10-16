package com.competition.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * The installation's discipline catalog (disciplines.json), the same for
 * every meet. Also the format of a disciplines export.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class DisciplineCatalog {
    public static final String FORMAT = "mlaic-disciplines";
    public static final int FORMAT_VERSION = 1;

    @JsonProperty("format")
    private String format = FORMAT;

    @JsonProperty("version")
    private int formatVersion = FORMAT_VERSION;

    // How many individual disciplines a team discipline may count at most; a new meet's default limit too
    @JsonProperty("max_disciplines")
    private int maxDisciplines = RankingConfig.DEFAULT_MAX_DISCIPLINES;

    @JsonProperty("disciplines")
    private List<Discipline> disciplines = new ArrayList<>();

    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }

    public int getFormatVersion() { return formatVersion; }
    public void setFormatVersion(int formatVersion) { this.formatVersion = formatVersion; }

    public int getMaxDisciplines() { return maxDisciplines; }
    public void setMaxDisciplines(int maxDisciplines) { this.maxDisciplines = maxDisciplines; }

    public List<Discipline> getDisciplines() { return disciplines; }
    public void setDisciplines(List<Discipline> disciplines) { this.disciplines = disciplines != null ? disciplines : new ArrayList<>(); }
}
