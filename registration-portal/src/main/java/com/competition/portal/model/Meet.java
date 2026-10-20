package com.competition.portal.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything about one meet, stored in meets/&lt;id&gt;.json: its settings, the
 * rankings and disciplines uploaded from the Manager (the snapshot), the
 * clubs with their access codes and the starters they registered. Deleting
 * the file deletes all of the meet's personal data.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Meet {
    public static final String DRAFT = "draft";
    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    public int id;
    /** Short, URL-safe name, also used in the export's file name. */
    public String key;
    public String name;
    /** YYYY-MM-DD */
    public String date;
    /** ISO-8601 instant with offset; clubs may write only before it. */
    public String deadline;
    /** The zone the deadline is entered and shown in, e.g. Europe/Vienna. */
    public String timeZone;
    public String status = DRAFT;
    /** YYYY-MM-DD; the registrations are deleted after this day. */
    public String deleteAfter;
    public Contact contact = new Contact();
    /** Whether clubs may enter the starters' email addresses (off: none are collected). */
    public boolean collectEmail;
    /** The rankings and disciplines uploaded from the Manager; null until uploaded. */
    public Snapshot snapshot;
    public List<Club> clubs = new ArrayList<>();
    public List<Starter> starters = new ArrayList<>();
    /** The next id per kind ("club", "starter"); only ever goes up. */
    public Map<String, Integer> nextIds = new LinkedHashMap<>();
    /** Version of the meet's own settings, for optimistic locking. */
    public int version;
}
