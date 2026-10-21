package com.competition.portal.service;

import com.competition.portal.format.CatalogFile;
import com.competition.portal.format.RankingsFile;
import com.competition.portal.model.Club;
import com.competition.portal.model.Contact;
import com.competition.portal.model.Meet;
import com.competition.portal.model.Starter;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The file for the MLAICC Manager's Import Registrations page (format
 * "mlaic-registrations", version 1): the meet's starters with the disciplines
 * they start in, each once. The organiser downloads all clubs in one file, a
 * club its own starters as a confirmation copy.
 *
 * <p>Compared with the files of the online form, fields are only added
 * ("contacts", "registered_by", "generator"), so the Manager reads both:
 * it ignores fields it does not know.
 */
public class ExportService {
    public static final String FORMAT = "mlaic-registrations";
    public static final int VERSION = 1;
    public static final String GENERATOR = "mlaic-portal 1.0";

    private final MeetStore meets;
    private final Clock clock;

    public ExportService(MeetStore meets, Clock clock) {
        this.meets = meets;
        this.clock = clock;
    }

    /** All clubs' starters. */
    public Map<String, Object> meet(int meetId) {
        Meet meet = meets.get(meetId);
        return build(meet, meet.contact, meet.clubs, meet.starters);
    }

    /** One club's starters, with the club's contact person as "contact". */
    public Map<String, Object> club(int meetId, int clubId) {
        Meet meet = meets.get(meetId);
        Club club = ClubService.club(meet, clubId);
        Contact contact = new Contact();
        contact.name = club.contact != null ? club.contact.name : null;
        contact.email = club.contact != null ? club.contact.email : null;
        contact.club = club.name;
        List<Starter> own = meet.starters.stream().filter(s -> s.clubId == clubId).collect(Collectors.toList());
        return build(meet, contact, List.of(club), own);
    }

    /** "registrations-ooe-lm-2027-2027-04-30.json", plus "-sv-bad-zell" for a club's copy. */
    public String fileName(int meetId, Integer clubId) {
        Meet meet = meets.get(meetId);
        ZoneId zone = ZoneId.of(meet.timeZone != null ? meet.timeZone : MeetService.DEFAULT_TIME_ZONE);
        String club = clubId == null ? "" : "-" + slug(ClubService.club(meet, clubId).name);
        return "registrations-" + slug(meet.key) + club + "-" + LocalDate.now(clock.withZone(zone)) + ".json";
    }

    private Map<String, Object> build(Meet meet, Contact contact, List<Club> clubs, List<Starter> starters) {
        Map<Integer, CatalogFile.Discipline> disciplines = new LinkedHashMap<>();
        if (meet.snapshot != null) {
            meet.snapshot.disciplines.forEach(d -> disciplines.put(d.id, d));
        }
        Map<Integer, Club> clubById = new LinkedHashMap<>();
        meet.clubs.forEach(c -> clubById.put(c.id, c));

        Map<String, Object> file = new LinkedHashMap<>();
        file.put("format", FORMAT);
        file.put("version", VERSION);
        file.put("meet", Map.of("key", meet.key, "name", meet.name));
        file.put("created_at", clock.instant().toString());
        file.put("generator", GENERATOR);
        file.put("contact", contactMap(contact));

        List<Map<String, Object>> contacts = new ArrayList<>();
        for (Club club : clubs) {
            long count = starters.stream().filter(s -> s.clubId == club.id).count();
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("club", club.name);
            c.put("name", club.contact != null ? club.contact.name : null);
            c.put("email", club.contact != null ? club.contact.email : null);
            c.put("starters", count);
            contacts.add(c);
        }
        file.put("contacts", contacts);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Starter s : starters) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", s.name);
            row.put("gender", s.gender);
            row.put("year_of_birth", s.yearOfBirth);
            row.put("club", s.club);
            row.put("country", s.country);
            row.put("email", s.email);
            Club account = clubById.get(s.clubId);
            row.put("registered_by", account != null ? account.name : null);
            List<Map<String, Object>> starts = new ArrayList<>();
            for (int id : SnapshotService.startsOf(meet.snapshot, s)) {
                CatalogFile.Discipline d = disciplines.get(id);
                Map<String, Object> start = new LinkedHashMap<>();
                start.put("discipline_id", id);
                start.put("discipline", d != null ? d.name : null);
                start.put("short_name", d != null ? d.shortName : null);
                starts.add(start);
            }
            row.put("starts", starts);
            row.put("rankings", rankingNames(meet, s));
            rows.add(row);
        }
        file.put("starters", rows);
        return file;
    }

    /** "Colt", or "Whitworth O/R: WHITR" for an entry of a ranking with several. */
    private static List<String> rankingNames(Meet meet, Starter s) {
        List<String> names = new ArrayList<>();
        if (meet.snapshot == null) {
            return names;
        }
        for (var choice : s.choices) {
            for (RankingsFile.Ranking r : meet.snapshot.rankings) {
                if (r.id == choice.rankingId && choice.entry < r.entries.size()) {
                    if (r.entries.size() > 1) {
                        RankingsFile.Entry e = r.entries.get(choice.entry);
                        String label = e.label != null ? e.label : e.disciplineIds.stream()
                            .map(id -> meet.snapshot.disciplines.stream().filter(d -> d.id == id).map(d -> d.shortName != null ? d.shortName : d.name).findFirst().orElse("#" + id))
                            .collect(Collectors.joining("+"));
                        names.add(r.name + ": " + label);
                    } else {
                        names.add(r.name);
                    }
                }
            }
        }
        return names;
    }

    private static Map<String, Object> contactMap(Contact c) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", c != null ? c.name : null);
        map.put("email", c != null ? c.email : null);
        map.put("club", c != null ? c.club : null);
        return map;
    }

    static String slug(String text) {
        String ascii = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.isEmpty() ? "x" : slug;
    }
}
