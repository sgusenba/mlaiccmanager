package com.competition.portal.service;

import com.competition.portal.auth.AccessCodes;
import com.competition.portal.auth.SessionStore;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Club;
import com.competition.portal.model.Contact;
import com.competition.portal.model.Meet;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The clubs of a meet and their access codes. A code is shown once, when it
 * is created or replaced; only its hash is stored. Replacing or revoking a
 * code, or deleting the club, ends the club's sessions at once.
 */
public class ClubService {
    static final int MAX_TEXT = 200;

    private final MeetStore meets;
    private final SessionStore sessions;
    private final Clock clock;
    private final String baseUrl;

    public ClubService(MeetStore meets, SessionStore sessions, Clock clock, String baseUrl) {
        this.meets = meets;
        this.sessions = sessions;
        this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** A club as the organiser's pages see it: no code hash, with the number of its starters. */
    public static Map<String, Object> view(Meet meet, Club club) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", club.id);
        view.put("name", club.name);
        view.put("default_country", club.defaultCountry);
        view.put("code_hint", club.codeHint);
        view.put("code_created_at", club.codeCreatedAt);
        view.put("revoked", club.revoked);
        view.put("last_login_at", club.lastLoginAt);
        view.put("contact", club.contact);
        view.put("starters", meet.starters.stream().filter(s -> s.clubId == club.id).count());
        view.put("version", club.version);
        return view;
    }

    /** A club with its new code, which is shown only now. */
    public static class WithCode {
        public final Map<String, Object> club;
        public final String code;
        public final String inviteUrl;

        WithCode(Map<String, Object> club, String code, String inviteUrl) {
            this.club = club;
            this.code = code;
            this.inviteUrl = inviteUrl;
        }
    }

    public List<Map<String, Object>> list(int meetId) {
        Meet meet = meets.get(meetId);
        return meet.clubs.stream().map(c -> view(meet, c)).collect(Collectors.toList());
    }

    public WithCode create(int meetId, Map<String, Object> request) throws Exception {
        String name = text(request, "name");
        if (name == null) {
            throw new IllegalArgumentException(Messages.get("request.required", "name"));
        }
        String country = text(request, "default_country");
        String code = AccessCodes.generate();
        return meets.update(meetId, meet -> {
            checkNameUnused(meet, name, 0);
            Club club = new Club();
            club.id = Ids.take(meet.nextIds, "club");
            club.name = name;
            club.defaultCountry = country;
            setCode(club, code);
            club.contact = new Contact();
            club.version = 1;
            meet.clubs.add(club);
            return new WithCode(view(meet, club), code, inviteUrl(code));
        });
    }

    /** Changes name and default country. Send back the version you saw (409 with the current club otherwise). */
    public Map<String, Object> update(int meetId, int clubId, Map<String, Object> request) throws Exception {
        String name = text(request, "name");
        if (name == null) {
            throw new IllegalArgumentException(Messages.get("request.required", "name"));
        }
        String country = text(request, "default_country");
        Integer expected = request != null && request.get("version") instanceof Number n ? n.intValue() : null;
        return meets.update(meetId, meet -> {
            Club club = club(meet, clubId);
            if (expected != null && expected != club.version) {
                throw new ConflictException(Messages.get("club.changed"), view(meet, club));
            }
            checkNameUnused(meet, name, clubId);
            club.name = name;
            club.defaultCountry = country;
            club.version++;
            return view(meet, club);
        });
    }

    /** Replaces the code: the old one stops working at once and the club's sessions end. */
    public WithCode rotate(int meetId, int clubId) throws Exception {
        String code = AccessCodes.generate();
        WithCode result = meets.update(meetId, meet -> {
            Club club = club(meet, clubId);
            setCode(club, code);
            club.revoked = false;
            club.version++;
            return new WithCode(view(meet, club), code, inviteUrl(code));
        });
        endSessions(meetId, clubId);
        return result;
    }

    /** Blocks (or unblocks) the club's code; blocking ends its sessions. */
    public Map<String, Object> revoke(int meetId, int clubId, boolean revoked) throws Exception {
        Map<String, Object> view = meets.update(meetId, meet -> {
            Club club = club(meet, clubId);
            club.revoked = revoked;
            club.version++;
            return view(meet, club);
        });
        if (revoked) {
            endSessions(meetId, clubId);
        }
        return view;
    }

    /**
     * Deletes the club. Refused while it has starters, unless withStarters,
     * which deletes them too.
     */
    public void delete(int meetId, int clubId, boolean withStarters) throws Exception {
        meets.update(meetId, meet -> {
            Club club = club(meet, clubId);
            long starters = meet.starters.stream().filter(s -> s.clubId == clubId).count();
            if (starters > 0 && !withStarters) {
                throw new ConflictException(Messages.get("club.hasStarters", club.name, starters), view(meet, club));
            }
            meet.starters.removeIf(s -> s.clubId == clubId);
            meet.clubs.removeIf(c -> c.id == clubId);
            return null;
        });
        endSessions(meetId, clubId);
    }

    private void endSessions(int meetId, int clubId) {
        sessions.endWhere(s -> s.isClub() && s.meetId == meetId && s.clubId == clubId);
    }

    private void setCode(Club club, String code) {
        club.codeHash = AccessCodes.hash(code);
        club.codeHint = AccessCodes.hint(code);
        club.codeCreatedAt = clock.instant().toString();
    }

    /** The invite link: the code is in the #fragment, which browsers never send, so it stays out of server logs. */
    private String inviteUrl(String code) {
        return baseUrl + "/login/#c=" + code;
    }

    static Club club(Meet meet, int clubId) {
        return meet.clubs.stream().filter(c -> c.id == clubId).findFirst()
            .orElseThrow(() -> new RecordNotFoundException(Messages.get("club.notFound")));
    }

    private static void checkNameUnused(Meet meet, String name, int ownId) {
        if (meet.clubs.stream().anyMatch(c -> c.id != ownId && c.name.equalsIgnoreCase(name))) {
            throw new IllegalArgumentException(Messages.get("club.nameInUse", name));
        }
    }

    private static String text(Map<String, Object> r, String field) {
        Object value = r != null ? r.get(field) : null;
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException(Messages.get("request.mustBeText", field));
        }
        String trimmed = s.trim();
        if (trimmed.length() > MAX_TEXT) {
            throw new IllegalArgumentException(Messages.get("request.tooLong", field, MAX_TEXT));
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
