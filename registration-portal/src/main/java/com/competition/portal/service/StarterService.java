package com.competition.portal.service;

import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Club;
import com.competition.portal.model.Contact;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Starter;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The starters of a meet. A club saves its sheet in one go: new rows,
 * changed rows and deleted rows, all or nothing. First every row is checked
 * ({@link RegistrationRules}, 400 listing the rows with errors), then the
 * versions (409 listing the rows someone else changed meanwhile), then it is
 * written, and the changes go to the audit log.
 *
 * <p>Clubs can save only while the meet is open and before its deadline, and
 * on their first save they confirm that they may pass on their members' data.
 * The organiser can change any club's starters at any time (late entries),
 * and delete all of a meet's registrations after the meet.
 */
public class StarterService {
    private final MeetStore meets;
    private final AuditLog audit;
    private final Clock clock;

    public StarterService(MeetStore meets, AuditLog audit, Clock clock) {
        this.meets = meets;
        this.audit = audit;
        this.clock = clock;
    }

    /** Whether clubs may change their registrations now: the meet is open and the deadline not reached. */
    public boolean isOpen(Meet meet) {
        return Meet.OPEN.equals(meet.status) && meet.deadline != null
            && Instant.now(clock).isBefore(OffsetDateTime.parse(meet.deadline).toInstant());
    }

    /** The meet as a club sees it: settings, its own club, whether it can still change things, and the offer. */
    public Map<String, Object> clubMeet(int meetId, int clubId) {
        Meet meet = meets.get(meetId);
        Club club = ClubService.club(meet, clubId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("name", meet.name);
        view.put("date", meet.date);
        view.put("deadline", meet.deadline);
        view.put("time_zone", meet.timeZone);
        view.put("status", meet.status);
        view.put("open", isOpen(meet));
        view.put("collect_email", meet.collectEmail);
        view.put("club", clubView(club));
        view.put("offer", SnapshotService.offer(meet.snapshot));
        return view;
    }

    /** A club's starters with their warnings: {starters, warnings: {id: [...]}}. */
    public Map<String, Object> list(int meetId, int clubId) {
        Meet meet = meets.get(meetId);
        ClubService.club(meet, clubId);
        return result(meet, clubId);
    }

    /** All starters of the meet for the organiser, warnings including names that appear at another club too. */
    public Map<String, Object> listAll(int meetId) {
        Meet meet = meets.get(meetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("starters", meet.starters);
        result.put("warnings", RegistrationRules.warnings(meet, meet.starters, true));
        return result;
    }

    /**
     * Saves a club's sheet: body {"upsert": [rows], "delete": [{"id", "version"}],
     * "consent": true}. A row with an id changes that starter and needs the
     * version it was loaded with; a row without one is new. Unchanged rows are
     * left as they are. Returns the club's starters as stored now.
     *
     * @param byOrganiser the organiser saving for the club: no deadline, no consent needed
     */
    public Map<String, Object> save(int meetId, int clubId, Map<String, Object> body, String actor, boolean byOrganiser)
            throws Exception {
        List<Map<String, Object>> upserts = maps(body, "upsert");
        List<Map<String, Object>> deletes = maps(body, "delete");
        boolean consent = body != null && Boolean.TRUE.equals(body.get("consent"));
        List<Map<String, Object>> auditEntries = new ArrayList<>();

        return meets.update(meetId, meet -> {
            Club club = ClubService.club(meet, clubId);
            if (!byOrganiser) {
                if (!isOpen(meet)) {
                    throw new ClosedException(Messages.get("meet.registrationClosed"));
                }
                if (club.consentAt == null && !consent) {
                    throw new IllegalArgumentException(Messages.get("starter.consentRequired"));
                }
            }
            ZoneId zone = ZoneId.of(meet.timeZone != null ? meet.timeZone : MeetService.DEFAULT_TIME_ZONE);

            // 1. Every row checked; nothing is saved if one has errors
            List<Map<String, Object>> rowErrors = new ArrayList<>();
            List<RegistrationRules.Checked> checked = new ArrayList<>();
            for (int i = 0; i < upserts.size(); i++) {
                Map<String, Object> row = upserts.get(i);
                RegistrationRules.Checked c = RegistrationRules.check(row, meet, club, zone);
                Integer id = intOrNull(row.get("id"));
                if (id != null && starter(meet, clubId, id) == null) {
                    c.errors.add(Messages.get("starter.notFound", id));
                }
                checked.add(c);
                if (!c.errors.isEmpty()) {
                    rowErrors.add(rowError(i, id, c.errors));
                }
            }
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (Map<String, Object> row : upserts) {
                Integer id = intOrNull(row.get("id"));
                if (id != null) {
                    seen.add(id);
                }
            }
            for (int i = 0; i < deletes.size(); i++) {
                Integer id = intOrNull(deletes.get(i).get("id"));
                if (id == null || starter(meet, clubId, id) == null) {
                    rowErrors.add(rowError(upserts.size() + i, id, List.of(Messages.get("starter.notFound", id))));
                } else if (!seen.add(id)) {
                    rowErrors.add(rowError(upserts.size() + i, id, List.of(Messages.get("starter.twice", id))));
                }
            }
            if (!rowErrors.isEmpty()) {
                throw new ValidationException(Messages.get("starter.rowsInvalid", rowErrors.size()), rowErrors);
            }

            // 2. Versions: rows someone else changed meanwhile are sent back as they are now
            List<Starter> stale = new ArrayList<>();
            for (Map<String, Object> row : upserts) {
                Integer id = intOrNull(row.get("id"));
                if (id != null) {
                    Starter stored = starter(meet, clubId, id);
                    if (!Objects.equals(intOrNull(row.get("version")), stored.version)) {
                        stale.add(stored);
                    }
                }
            }
            for (Map<String, Object> row : deletes) {
                Starter stored = starter(meet, clubId, intOrNull(row.get("id")));
                if (!Objects.equals(intOrNull(row.get("version")), stored.version)) {
                    stale.add(stored);
                }
            }
            if (!stale.isEmpty()) {
                throw new ConflictException(Messages.get("starter.changed", stale.size()), stale);
            }

            // 3. Limits
            long newRows = upserts.stream().filter(r -> intOrNull(r.get("id")) == null).count();
            long clubCount = meet.starters.stream().filter(s -> s.clubId == clubId).count() + newRows - deletes.size();
            if (clubCount > RegistrationRules.MAX_STARTERS_PER_CLUB) {
                throw new IllegalArgumentException(Messages.get("starter.tooManyForClub", RegistrationRules.MAX_STARTERS_PER_CLUB));
            }
            if (meet.starters.size() + newRows - deletes.size() > RegistrationRules.MAX_STARTERS_PER_MEET) {
                throw new IllegalArgumentException(Messages.get("starter.meetFull", RegistrationRules.MAX_STARTERS_PER_MEET));
            }

            // 4. Write
            String now = clock.instant().toString();
            if (!byOrganiser && club.consentAt == null) {
                club.consentAt = now;
                auditEntries.add(AuditLog.entry(actor, "club.consent", "club_id", clubId, club.name));
            }
            for (int i = 0; i < upserts.size(); i++) {
                Starter values = checked.get(i).values;
                Integer id = intOrNull(upserts.get(i).get("id"));
                if (id == null) {
                    values.id = Ids.take(meet.nextIds, "starter");
                    values.clubId = clubId;
                    values.version = 1;
                    values.createdAt = now;
                    values.updatedAt = now;
                    values.updatedBy = actor;
                    meet.starters.add(values);
                    auditEntries.add(AuditLog.entry(actor, "starter.created", "starter_id", values.id, summary(meet, values)));
                } else {
                    Starter stored = starter(meet, clubId, id);
                    if (RegistrationRules.differs(stored, values)) {
                        stored.name = values.name;
                        stored.gender = values.gender;
                        stored.yearOfBirth = values.yearOfBirth;
                        stored.club = values.club;
                        stored.country = values.country;
                        stored.email = values.email;
                        stored.choices = values.choices;
                        stored.version++;
                        stored.updatedAt = now;
                        stored.updatedBy = actor;
                        auditEntries.add(AuditLog.entry(actor, "starter.changed", "starter_id", id, summary(meet, stored)));
                    }
                }
            }
            for (Map<String, Object> row : deletes) {
                Starter stored = starter(meet, clubId, intOrNull(row.get("id")));
                meet.starters.remove(stored);
                auditEntries.add(AuditLog.entry(actor, "starter.deleted", "starter_id", stored.id, stored.name));
            }
            return result(meet, clubId);
        }, result -> {
            audit.append(meetId, auditEntries);
            return null;
        });
    }

    /** Changes the club's contact person: body {"name", "email", "version"}; clubs only while the registration is open. */
    public Map<String, Object> saveContact(int meetId, int clubId, Map<String, Object> body, String actor, boolean byOrganiser)
            throws Exception {
        String name = text(body, "name");
        String email = text(body, "email");
        Integer expected = body != null ? intOrNull(body.get("version")) : null;
        return meets.update(meetId, meet -> {
            Club club = ClubService.club(meet, clubId);
            if (!byOrganiser && !isOpen(meet)) {
                throw new ClosedException(Messages.get("meet.registrationClosed"));
            }
            if (expected != null && expected != club.version) {
                throw new ConflictException(Messages.get("club.changed"), clubView(club));
            }
            Contact contact = new Contact();
            contact.name = name;
            contact.email = email;
            contact.club = club.name;
            club.contact = contact;
            club.version++;
            return clubView(club);
        }, result -> {
            audit.append(meetId, List.of(AuditLog.entry(actor, "club.contact", "club_id", clubId, null)));
            return null;
        });
    }

    /**
     * Deletes all of the meet's registrations (after the meet, for data
     * protection): the starters, the clubs' contact persons and the audit log,
     * which names starters. The meet and its clubs stay. Returns how many
     * starters were deleted.
     */
    public int purge(int meetId, String actor) throws Exception {
        return meets.update(meetId, meet -> {
            int count = meet.starters.size();
            clearPersonalData(meet);
            return count;
        }, count -> {
            audit.delete(meetId);
            audit.append(meetId, List.of(AuditLog.entry(actor, "meet.purged", null, null,
                count + " starter(s) deleted")));
            return null;
        });
    }

    /** Removes the starters and the clubs' contact persons; the caller saves. */
    static void clearPersonalData(Meet meet) {
        meet.starters.clear();
        for (Club club : meet.clubs) {
            club.contact = new Contact();
        }
    }

    private static Map<String, Object> clubView(Club club) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", club.id);
        c.put("name", club.name);
        c.put("default_country", club.defaultCountry);
        c.put("contact", club.contact);
        c.put("consent_given", club.consentAt != null);
        c.put("version", club.version);
        return c;
    }

    private static Map<String, Object> result(Meet meet, int clubId) {
        List<Starter> own = meet.starters.stream().filter(s -> s.clubId == clubId).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("starters", own);
        result.put("warnings", RegistrationRules.warnings(meet, own, false));
        return result;
    }

    /** "Anna Huber (3 starts)" for the audit log. */
    private static String summary(Meet meet, Starter s) {
        return s.name + " (" + Messages.format(Messages.DEFAULT, "audit.starts", SnapshotService.startsOf(meet.snapshot, s).size()) + ")";
    }

    private static Starter starter(Meet meet, int clubId, Integer id) {
        if (id == null) {
            return null;
        }
        return meet.starters.stream().filter(s -> s.id == id && s.clubId == clubId).findFirst().orElse(null);
    }

    private static Map<String, Object> rowError(int index, Integer id, List<String> errors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index);
        row.put("id", id);
        row.put("errors", errors);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Map<String, Object> body, String field) {
        Object value = body != null ? body.get(field) : null;
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof Map))) {
            throw new IllegalArgumentException(Messages.get("request.listExpected", field));
        }
        if (list.size() > RegistrationRules.MAX_STARTERS_PER_CLUB) {
            throw new IllegalArgumentException(Messages.get("starter.tooManyForClub", RegistrationRules.MAX_STARTERS_PER_CLUB));
        }
        return (List<Map<String, Object>>) list;
    }

    private static Integer intOrNull(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body != null ? body.get(field) : null;
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(Messages.get("request.mustBeText", field));
        }
        String trimmed = s.trim();
        if (trimmed.length() > RegistrationRules.MAX_TEXT) {
            throw new IllegalArgumentException(Messages.get("request.tooLong", field, RegistrationRules.MAX_TEXT));
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
