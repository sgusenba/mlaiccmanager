package com.competition.portal.service;

import com.competition.portal.auth.AuthException;
import com.competition.portal.auth.PasswordHasher;
import com.competition.portal.auth.Session;
import com.competition.portal.auth.SessionStore;
import com.competition.portal.auth.Totp;
import com.competition.portal.i18n.Messages;
import com.competition.portal.model.Organiser;
import com.competition.portal.model.PortalData;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The organiser accounts. There is no sign-up: the first account is created
 * on the server (java -jar mlaic-portal.jar --add-organiser NAME), further
 * ones by a logged-in organiser. An organiser can change their password and
 * turn on a second factor (authenticator app); if they lose the app, the
 * server command --reset-totp turns it off again.
 */
public class OrganiserService {
    public static final String TOTP_ISSUER = "MLAIC Anmeldeportal";
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{2,39}");

    private final PortalStore store;
    private final SessionStore sessions;
    private final Clock clock;

    public OrganiserService(PortalStore store, SessionStore sessions, Clock clock) {
        this.store = store;
        this.sessions = sessions;
        this.clock = clock;
    }

    /** The organiser with the user name (case does not matter), or null. */
    static Organiser find(PortalData data, String username) {
        if (username == null) {
            return null;
        }
        String wanted = username.trim().toLowerCase();
        return data.organisers.stream().filter(o -> o.username.equals(wanted)).findFirst().orElse(null);
    }

    static Organiser byId(PortalData data, int id) {
        return data.organisers.stream().filter(o -> o.id == id).findFirst().orElse(null);
    }

    public static Map<String, Object> view(Organiser o) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", o.id);
        view.put("username", o.username);
        view.put("totp_enabled", o.totpConfirmed);
        view.put("created_at", o.createdAt);
        return view;
    }

    public List<Map<String, Object>> list() {
        return store.get().organisers.stream().map(OrganiserService::view).collect(Collectors.toList());
    }

    public Organiser get(int id) {
        Organiser o = byId(store.get(), id);
        if (o == null) {
            throw new RecordNotFoundException(Messages.get("organiser.notFound"));
        }
        return o;
    }

    /** A new organiser account. */
    public Organiser create(String username, String password) throws Exception {
        String name = username == null ? "" : username.trim().toLowerCase();
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException(Messages.get("organiser.badUsername"));
        }
        checkPassword(password);
        String hash = PasswordHasher.hash(password);
        return store.update(data -> {
            if (find(data, name) != null) {
                throw new IllegalArgumentException(Messages.get("organiser.exists", name));
            }
            Organiser o = new Organiser();
            o.id = Ids.take(data.nextIds, "organiser");
            o.username = name;
            o.passwordHash = hash;
            o.createdAt = clock.instant().toString();
            o.version = 1;
            data.organisers.add(o);
            return o;
        });
    }

    /** Deletes an account and ends its sessions; the last account cannot be deleted. */
    public void delete(int id) throws Exception {
        store.update(data -> {
            if (byId(data, id) == null) {
                throw new RecordNotFoundException(Messages.get("organiser.notFound"));
            }
            if (data.organisers.size() <= 1) {
                throw new IllegalArgumentException(Messages.get("organiser.last"));
            }
            data.organisers.removeIf(o -> o.id == id);
            return null;
        });
        sessions.endWhere(s -> s.isOrganiser() && s.organiserId == id);
    }

    /**
     * Changes the own password after checking the current one, and ends the
     * account's other sessions. Returns a new token for the caller's session.
     */
    public String changePassword(int id, String current, String password) throws Exception {
        Organiser o = get(id);
        if (!PasswordHasher.verify(current, o.passwordHash)) {
            throw new AuthException(403, Messages.get("organiser.wrongPassword"));
        }
        setPassword(id, password);
        return sessions.createOrganiser(id);
    }

    /** Sets a new password (server command or after the check above) and ends all the account's sessions. */
    public void setPassword(int id, String password) throws Exception {
        checkPassword(password);
        String hash = PasswordHasher.hash(password);
        store.update(data -> {
            Organiser stored = byId(data, id);
            if (stored == null) {
                throw new RecordNotFoundException(Messages.get("organiser.notFound"));
            }
            stored.passwordHash = hash;
            stored.version++;
            return null;
        });
        sessions.endWhere(s -> s.isOrganiser() && s.organiserId == id);
    }

    /**
     * Starts setting up the authenticator app: a new secret, not yet required
     * at log-in until confirmed with a code from the app.
     */
    public Map<String, Object> totpSetup(int id) throws Exception {
        String secret = Totp.newSecret();
        Organiser o = store.update(data -> {
            Organiser stored = byId(data, id);
            if (stored == null) {
                throw new RecordNotFoundException(Messages.get("organiser.notFound"));
            }
            if (stored.totpConfirmed) {
                throw new IllegalArgumentException(Messages.get("totp.alreadyOn"));
            }
            stored.totpSecret = secret;
            stored.totpLastStep = 0;
            return stored;
        });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("secret", secret);
        result.put("otpauth_uri", Totp.uri(TOTP_ISSUER, o.username, secret));
        return result;
    }

    /** Turns the second factor on once a code from the app matches the new secret. */
    public void totpConfirm(int id, String code) throws Exception {
        long now = clock.instant().getEpochSecond();
        store.update(data -> {
            Organiser stored = byId(data, id);
            if (stored == null || stored.totpSecret == null || stored.totpConfirmed) {
                throw new IllegalArgumentException(Messages.get("totp.noSetup"));
            }
            long step = Totp.verify(stored.totpSecret, code, now, stored.totpLastStep);
            if (step < 0) {
                throw new IllegalArgumentException(Messages.get("totp.wrongCode"));
            }
            stored.totpConfirmed = true;
            stored.totpLastStep = step;
            stored.version++;
            return null;
        });
    }

    /** Turns the second factor off after checking the password. */
    public void totpDisable(int id, String password) throws Exception {
        if (!PasswordHasher.verify(password, get(id).passwordHash)) {
            throw new AuthException(403, Messages.get("organiser.wrongPassword"));
        }
        resetTotp(id);
    }

    /** Turns the second factor off (also the server command for a lost app). */
    public void resetTotp(int id) throws Exception {
        store.update(data -> {
            Organiser stored = byId(data, id);
            if (stored == null) {
                throw new RecordNotFoundException(Messages.get("organiser.notFound"));
            }
            stored.totpSecret = null;
            stored.totpConfirmed = false;
            stored.totpLastStep = 0;
            stored.version++;
            return null;
        });
    }

    /** The id of the account with the user name, for the server commands; RecordNotFoundException if none. */
    public int idOf(String username) {
        Organiser o = find(store.get(), username);
        if (o == null) {
            throw new RecordNotFoundException(Messages.get("organiser.notFound"));
        }
        return o.id;
    }

    private static void checkPassword(String password) {
        String problem = PasswordHasher.problem(password);
        if (problem != null) {
            throw new IllegalArgumentException(Messages.get(problem, PasswordHasher.MIN_LENGTH, PasswordHasher.MAX_LENGTH));
        }
    }

    /** Whether the session belongs to an organiser account that still exists. */
    public boolean exists(Session session) {
        return session != null && session.isOrganiser() && byId(store.get(), session.organiserId) != null;
    }
}
