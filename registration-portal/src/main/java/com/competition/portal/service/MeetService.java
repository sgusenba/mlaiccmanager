package com.competition.portal.service;

import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Contact;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Offer;
import com.competition.portal.model.Snapshot;
import com.competition.portal.model.Starter;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The organiser's side of the meets: create, change, open and close them,
 * upload the rankings and catalog from the Manager, delete them.
 *
 * <p>Only one meet is open for registration at a time. Checks that look at
 * all meets (that rule, unique keys) run under one service lock, taken before
 * any meet file's lock, so two requests cannot both pass them.
 */
public class MeetService {
    public static final String DEFAULT_TIME_ZONE = "Europe/Vienna";
    /** Registrations are deleted this many days after the meet unless set otherwise. */
    public static final int DEFAULT_RETENTION_DAYS = 90;
    static final int MAX_TEXT = 200;
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

    private final MeetStore store;
    private final ReentrantLock acrossMeets = new ReentrantLock();

    public MeetService(MeetStore store) {
        this.store = store;
    }

    public List<Meet> list() throws Exception {
        return store.list();
    }

    public Meet get(int id) {
        return store.get(id);
    }

    /** A new meet in status draft. */
    public Meet create(Map<String, Object> request) throws Exception {
        Meet meet = new Meet();
        apply(meet, request);
        acrossMeets.lock();
        try {
            checkKeyUnused(meet.key, 0);
            return store.create(meet);
        } finally {
            acrossMeets.unlock();
        }
    }

    /**
     * Changes the meet's settings and status. Send back the version you saw; if
     * someone saved in the meantime, a ConflictException carries the current meet.
     * Opening needs the uploaded rankings and a deadline, and no other open meet.
     */
    public Meet update(int id, Map<String, Object> request) throws Exception {
        Integer expectedVersion = request != null && request.get("version") instanceof Number n ? n.intValue() : null;
        String status = request != null && request.get("status") != null ? request.get("status").toString() : null;
        if (status != null && !List.of(Meet.DRAFT, Meet.OPEN, Meet.CLOSED).contains(status)) {
            throw new IllegalArgumentException(Messages.get("meet.badStatus", status));
        }
        acrossMeets.lock();
        try {
            Meet changed = new Meet();
            apply(changed, request);
            checkKeyUnused(changed.key, id);
            if (Meet.OPEN.equals(status)) {
                for (Meet other : store.list()) {
                    if (other.id != id && Meet.OPEN.equals(other.status)) {
                        throw new IllegalArgumentException(Messages.get("meet.otherOpen", other.name));
                    }
                }
            }
            return store.update(id, meet -> {
                if (expectedVersion != null && expectedVersion != meet.version) {
                    throw new ConflictException(Messages.get("meet.changed"), view(meet));
                }
                meet.key = changed.key;
                meet.name = changed.name;
                meet.date = changed.date;
                meet.timeZone = changed.timeZone;
                meet.deadline = changed.deadline;
                meet.deleteAfter = changed.deleteAfter;
                meet.collectEmail = changed.collectEmail;
                meet.contact = changed.contact;
                if (status != null) {
                    if (Meet.OPEN.equals(status) && meet.snapshot == null) {
                        throw new IllegalArgumentException(Messages.get("meet.openWithoutRankings"));
                    }
                    if (Meet.OPEN.equals(status) && meet.deadline == null) {
                        throw new IllegalArgumentException(Messages.get("meet.openWithoutDeadline"));
                    }
                    meet.status = status;
                }
                meet.version++;
                return meet;
            });
        } finally {
            acrossMeets.unlock();
        }
    }

    /** Deletes the meet with all its clubs, starters and audit log. */
    public void delete(int id) throws Exception {
        store.delete(id);
    }

    /** What an upload did: the meet, the offer clubs now see and hints that did not stop it. */
    public static class UploadResult {
        public final Map<String, Object> meet;
        public final List<Offer.Ranking> offer;
        public final List<String> warnings;

        UploadResult(Meet meet, List<Offer.Ranking> offer, List<String> warnings) {
            this.meet = view(meet);
            this.offer = offer;
            this.warnings = warnings;
        }
    }

    /**
     * Replaces the meet's rankings and disciplines with the files exported from
     * the Manager. Refused while the meet is closed, and with a ConflictException
     * listing the starters concerned if the new rankings would drop or change an
     * entry someone has already ticked.
     */
    public UploadResult uploadSnapshot(int id, JsonNode rankings, JsonNode catalog) throws Exception {
        Snapshot snapshot = SnapshotService.parse(rankings, catalog);
        Meet meet = store.update(id, stored -> {
            if (Meet.CLOSED.equals(stored.status)) {
                throw new IllegalArgumentException(Messages.get("meet.closedNoUpload"));
            }
            List<Starter> broken = SnapshotService.brokenBy(stored.snapshot, snapshot, stored.starters);
            if (!broken.isEmpty()) {
                List<Map<String, Object>> current = broken.stream().map(s -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", s.id);
                    row.put("name", s.name);
                    row.put("club", s.club);
                    return row;
                }).collect(Collectors.toList());
                throw new ConflictException(Messages.get("snapshot.breaksChoices", broken.size()), current);
            }
            stored.snapshot = snapshot;
            stored.version++;
            return stored;
        });
        return new UploadResult(meet, SnapshotService.offer(snapshot), SnapshotService.warnings(snapshot));
    }

    /**
     * The meet as the organiser's pages see it: its settings, a summary of the
     * uploaded rankings and the number of clubs and starters, but not the clubs'
     * code hashes or the starters' data.
     */
    public static Map<String, Object> view(Meet meet) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", meet.id);
        view.put("key", meet.key);
        view.put("name", meet.name);
        view.put("date", meet.date);
        view.put("deadline", meet.deadline);
        view.put("time_zone", meet.timeZone);
        view.put("status", meet.status);
        view.put("delete_after", meet.deleteAfter);
        view.put("collect_email", meet.collectEmail);
        view.put("contact", meet.contact);
        Map<String, Object> snapshot = null;
        if (meet.snapshot != null) {
            snapshot = new LinkedHashMap<>();
            snapshot.put("uploaded_at", meet.snapshot.uploadedAt);
            snapshot.put("rankings_sha256", meet.snapshot.rankingsSha256);
            snapshot.put("catalog_sha256", meet.snapshot.catalogSha256);
            snapshot.put("rankings", meet.snapshot.rankings.size());
            snapshot.put("offered", SnapshotService.offer(meet.snapshot).size());
        }
        view.put("snapshot", snapshot);
        view.put("clubs", meet.clubs.size());
        view.put("starters", meet.starters.size());
        view.put("version", meet.version);
        return view;
    }

    // --- input -------------------------------------------------------------

    /** Copies the request's settings into meet, checking each; IllegalArgumentException says what to fix. */
    static void apply(Meet meet, Map<String, Object> request) {
        Map<String, Object> r = request != null ? request : Map.of();
        String key = text(r, "key", 40);
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException(Messages.get("meet.badKey"));
        }
        meet.key = key;
        meet.name = text(r, "name", MAX_TEXT);
        if (meet.name == null) {
            throw new IllegalArgumentException(Messages.get("request.required", "name"));
        }
        meet.date = date(r, "date");
        String zone = text(r, "time_zone", 64);
        try {
            meet.timeZone = ZoneId.of(zone != null ? zone : DEFAULT_TIME_ZONE).getId();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException(Messages.get("meet.badTimeZone", zone));
        }
        meet.deadline = deadline(r, meet.timeZone);
        meet.deleteAfter = date(r, "delete_after");
        if (meet.deleteAfter == null && meet.date != null) {
            meet.deleteAfter = LocalDate.parse(meet.date).plusDays(DEFAULT_RETENTION_DAYS).toString();
        }
        if (meet.date != null && meet.deleteAfter != null && meet.deleteAfter.compareTo(meet.date) < 0) {
            throw new IllegalArgumentException(Messages.get("meet.deleteBeforeMeet"));
        }
        meet.collectEmail = Boolean.TRUE.equals(r.get("collect_email"));
        Contact contact = new Contact();
        if (r.get("contact") instanceof Map<?, ?> c) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cm = (Map<String, Object>) c;
            contact.name = text(cm, "name", MAX_TEXT);
            contact.email = text(cm, "email", MAX_TEXT);
            contact.club = text(cm, "club", MAX_TEXT);
        }
        meet.contact = contact;
    }

    private void checkKeyUnused(String key, int ownId) throws Exception {
        for (Meet other : store.list()) {
            if (other.id != ownId && key.equals(other.key)) {
                throw new IllegalArgumentException(Messages.get("meet.keyInUse", key));
            }
        }
    }

    /** Trimmed text, null when missing or blank. */
    private static String text(Map<String, Object> r, String field, int max) {
        Object value = r.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(Messages.get("request.mustBeText", field));
        }
        String trimmed = s.trim();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(Messages.get("request.tooLong", field, max));
        }
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** A YYYY-MM-DD date, or null. */
    private static String date(Map<String, Object> r, String field) {
        String value = text(r, field, 10);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value).toString();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(Messages.get("request.date", field));
        }
    }

    /**
     * The deadline as an instant with the zone's offset, e.g. 2027-04-30T23:59+02:00.
     * Accepts a local date-time in the meet's time zone (as a date-time field sends
     * it) or one with an offset.
     */
    private static String deadline(Map<String, Object> r, String timeZone) {
        String value = text(r, "deadline", 40);
        if (value == null) {
            return null;
        }
        ZoneId zone = ZoneId.of(timeZone);
        try {
            OffsetDateTime withOffset = OffsetDateTime.parse(value);
            return withOffset.atZoneSameInstant(zone).toOffsetDateTime().toString();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value).atZone(zone).toOffsetDateTime().toString();
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException(Messages.get("meet.badDeadline"));
            }
        }
    }
}
