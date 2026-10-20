package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * One registered starter. The rankings are stored as choices (ranking and
 * entry), not as disciplines: the disciplines follow from the snapshot when
 * the file for the Manager is written.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Starter {
    public int id;
    public int clubId;
    public String name;
    /** male, female, other or null */
    public String gender;
    public Integer yearOfBirth;
    public String club;
    /** English country name, as the Manager stores it. */
    public String country;
    public String email;
    public List<Choice> choices = new ArrayList<>();
    public int version;
    public String createdAt;
    public String updatedAt;
    /** "club:&lt;id&gt;" or "organiser:&lt;id&gt;" */
    public String updatedBy;
}
