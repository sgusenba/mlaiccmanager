package com.competition.portal.service;

import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Choice;
import com.competition.portal.model.Club;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Snapshot;
import com.competition.portal.model.Starter;

import java.time.Year;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a starter row must look like. Errors stop the save; warnings are only
 * shown. The portal's pages check the same rules for immediate feedback; the
 * server is the one that counts.
 *
 * <p>Errors: name (required, at most 100 characters), country (one of
 * {@link Countries}), gender (male, female, other or none), year of birth
 * (1900 to this year, or none), email (empty or plausible; dropped when the
 * meet does not collect email), club (defaults to the club account's name),
 * and every ticked ranking entry must be offered at the meet. A starter may
 * tick several entries of a ranking, e.g. O and R.
 *
 * <p>Warnings: no ranking ticked, a name that appears twice.
 */
public final class RegistrationRules {
    public static final int MAX_NAME = 100;
    public static final int MAX_TEXT = 200;
    public static final int MAX_STARTERS_PER_CLUB = 500;
    public static final int MAX_STARTERS_PER_MEET = 5000;
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private RegistrationRules() {
    }

    /** A checked row: the cleaned-up values, and the errors if any. */
    public static class Checked {
        public final Starter values = new Starter();
        public final List<String> errors = new ArrayList<>();
    }

    /**
     * Checks one row as sent by a page ({name, gender, year_of_birth, club,
     * country, email, choices: [{ranking_id, entry}]}) and cleans it up:
     * trimmed texts, the country as stored, each choice once.
     */
    public static Checked check(Map<String, Object> row, Meet meet, Club club, ZoneId zone) {
        Checked c = new Checked();
        Starter v = c.values;

        v.name = text(row, "name", c);
        if (v.name == null) {
            c.errors.add(Messages.get("starter.nameRequired"));
        } else if (v.name.length() > MAX_NAME) {
            c.errors.add(Messages.get("starter.nameTooLong", MAX_NAME));
        }

        String country = text(row, "country", c);
        if (country == null && club.defaultCountry != null) {
            country = club.defaultCountry;
        }
        v.country = Countries.canonical(country);
        if (country == null) {
            c.errors.add(Messages.get("starter.countryRequired"));
        } else if (v.country == null) {
            c.errors.add(Messages.get("starter.countryUnknown", country));
        }

        String gender = text(row, "gender", c);
        if (gender != null && !List.of("male", "female", "other").contains(gender.toLowerCase(Locale.ROOT))) {
            c.errors.add(Messages.get("starter.badGender"));
        } else {
            v.gender = gender == null ? null : gender.toLowerCase(Locale.ROOT);
        }

        Object year = row.get("year_of_birth");
        if (year != null && !(year instanceof String s && s.isBlank())) {
            int thisYear = Year.now(zone).getValue();
            Integer value = null;
            if (year instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                value = n.intValue();
            } else if (year instanceof String s && s.trim().matches("\\d{4}")) {
                value = Integer.parseInt(s.trim());
            }
            if (value == null || value < 1900 || value > thisYear) {
                c.errors.add(Messages.get("starter.badYear", thisYear));
            } else {
                v.yearOfBirth = value;
            }
        }

        String email = text(row, "email", c);
        if (meet.collectEmail && email != null) {
            if (email.length() > MAX_TEXT || !EMAIL.matcher(email).matches()) {
                c.errors.add(Messages.get("starter.badEmail"));
            } else {
                v.email = email;
            }
        }

        v.club = text(row, "club", c);
        if (v.club == null) {
            v.club = club.name;
        } else if (v.club.length() > MAX_TEXT) {
            c.errors.add(Messages.get("request.tooLong", "club", MAX_TEXT));
        }

        v.choices = choices(row.get("choices"), meet.snapshot, c);
        return c;
    }

    /** The ticked entries, each once, in the order sent; an entry the meet does not offer is an error. */
    private static List<Choice> choices(Object raw, Snapshot snapshot, Checked c) {
        List<Choice> choices = new ArrayList<>();
        if (raw == null) {
            return choices;
        }
        if (!(raw instanceof List<?> list)) {
            c.errors.add(Messages.get("request.listExpected", "choices"));
            return choices;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m) || !(m.get("ranking_id") instanceof Number r) || !(m.get("entry") instanceof Number e)) {
                c.errors.add(Messages.get("starter.badChoice", String.valueOf(item)));
                continue;
            }
            Choice choice = new Choice(r.intValue(), e.intValue());
            if (SnapshotService.disciplinesOf(snapshot, choice) == null) {
                c.errors.add(Messages.get("starter.badChoice", choice.rankingId + "/" + choice.entry));
            } else if (seen.add(choice.rankingId + "/" + choice.entry)) {
                choices.add(choice);
            }
        }
        return choices;
    }

    /**
     * The warnings per starter id: no ranking ticked; the name twice in the
     * club; and with acrossClubs (for the organiser) the name also at another
     * club.
     */
    public static Map<String, List<String>> warnings(Meet meet, List<Starter> starters, boolean acrossClubs) {
        Map<String, List<String>> warnings = new LinkedHashMap<>();
        for (Starter s : starters) {
            List<String> list = new ArrayList<>();
            if (s.choices.isEmpty()) {
                list.add(Messages.get("starter.warnNoRankings"));
            }
            String name = s.name == null ? "" : s.name.trim().toLowerCase(Locale.ROOT);
            for (Starter other : meet.starters) {
                if (other.id != s.id && other.name != null && other.name.trim().toLowerCase(Locale.ROOT).equals(name)) {
                    if (other.clubId == s.clubId) {
                        list.add(Messages.get("starter.warnDuplicate"));
                    } else if (acrossClubs) {
                        Club club = meet.clubs.stream().filter(cl -> cl.id == other.clubId).findFirst().orElse(null);
                        list.add(Messages.get("starter.warnOtherClub", club != null ? club.name : "?"));
                    }
                }
            }
            if (!list.isEmpty()) {
                warnings.put(String.valueOf(s.id), list.stream().distinct().toList());
            }
        }
        return warnings;
    }

    /** Whether the row's values differ from the stored starter (an unchanged row is not saved again). */
    static boolean differs(Starter stored, Starter values) {
        return !java.util.Objects.equals(stored.name, values.name)
            || !java.util.Objects.equals(stored.gender, values.gender)
            || !java.util.Objects.equals(stored.yearOfBirth, values.yearOfBirth)
            || !java.util.Objects.equals(stored.club, values.club)
            || !java.util.Objects.equals(stored.country, values.country)
            || !java.util.Objects.equals(stored.email, values.email)
            || !choiceKeys(stored).equals(choiceKeys(values));
    }

    private static List<String> choiceKeys(Starter s) {
        return s.choices.stream().map(ch -> ch.rankingId + "/" + ch.entry).toList();
    }

    private static String text(Map<String, Object> row, String field, Checked c) {
        Object value = row.get(field);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            c.errors.add(Messages.get("request.mustBeText", field));
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
