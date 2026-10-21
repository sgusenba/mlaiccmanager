# MLAIC Registration Portal

Online registration for MLAIC meets: clubs log in with an access code and enter their starters, the organiser downloads them as one `mlaic-registrations` file and imports it on the MLAICC Manager's **Import Registrations** page (`/import/`). The portal is the only part on the internet; the Manager has no login and stays offline at the range. The two only exchange files.

The plan, with the decisions taken so far, is in [`../docs/registration-portal-plan.md`](../docs/registration-portal-plan.md).

**Status:** phases 1 to 5: server, storage, health check, the organiser's meets with the upload of the rankings and catalog from the Manager, the log-ins (organiser accounts, club access codes), the starters (saving, checks, deadline, audit log, deletion) and the club's pages (log-in, entry sheet). The organiser's pages and the export for the Manager follow in the next phases.

## Build and run

The same stack as the Manager (Java 17 bytecode, Jetty, Jersey, Jackson, JUnit 5), but a Maven project of its own: the Manager's build and release bundle stay untouched.

```bash
cd registration-portal
mvn clean verify                      # build and test
java -jar target/mlaic-portal-1.0.0.jar
```

The portal then listens on `http://127.0.0.1:8080` and keeps its data in `portal-data/` (git-ignored: it holds personal data). `GET /api/health` answers `{"ok":true}` while it runs and can read its data, else 503.

The jar is pure Java, so the one built on x64 runs unchanged on Oracle Cloud's ARM (Ampere) VMs.

## Pages

Plain HTML and JavaScript modules with one stylesheet (`static/css/portal.css`), in German and English (`static/i18n/`), no framework and nothing loaded from other sites, so the strict Content-Security-Policy of the reverse proxy needs no exceptions (`StaticPagesTest` checks that no page has inline scripts or foreign sources).

- **`/login/`**: clubs log in with their access code, typed or from the invite link (`/login/#c=CODE`; the code is removed from the address bar at once); organisers with name and password, then the authenticator code if it is on.
- **`/sheet/`**: the club's entry sheet. One row per starter (name, gender, year of birth, club, country, email if the meet collects it), one checkbox column per ranking entry, grouped into rifle and pistol (O and R of a ranking are two columns). Rows are checked while typing (`static/js/rules.js`, the same rules as the server); changed rows are marked and saved in one go with the Save button; the server's answer is shown on the rows (errors per row, rows someone else changed meanwhile, registration closed). Enter and the arrow keys move between rows like in Excel, rows copied from Excel (name, gender, year, club, country) can be pasted into the name column, the countdown to the deadline is shown in the meet's time zone, and after the deadline the sheet is read-only. The club's contact person is saved separately; on the first save the club confirms that it may pass on its members' data.
- **`/admin/`**: placeholder until the organiser's pages (phase 6).

## Organiser accounts

There is no sign-up. Create the first account on the server, as the portal's user:

```bash
java -jar mlaic-portal.jar --add-organiser stefan        # asks for the password twice
java -jar mlaic-portal.jar --set-password stefan         # forgotten password
java -jar mlaic-portal.jar --reset-totp stefan           # phone with the authenticator app lost
```

Without a terminal (in a script) the password is read as one line from standard input. A logged-in organiser can create further accounts (e.g. a second organiser) and delete them, except the last one.

## Log-in and security

- **Organisers** log in with user name and password (at least 12 characters, stored as an Argon2id hash). Each can turn on an authenticator app (TOTP, e.g. Google Authenticator, Aegis); from then on a 6-digit code is needed too, and each code works only once.
- **Clubs** log in with their access code: 16 characters, `XXXX-XXXX-XXXX-XXXX`, 80 random bits, shown once when the organiser creates the club or replaces the code, stored only as a SHA-256 hash. The invite link `https://<host>/login/#c=<code>` carries the code after `#`, which browsers never send to a server, so it stays out of logs. Typing is forgiving: case, dashes and spaces do not matter, O/I/L count as 0/1/1. Replacing or blocking a code logs the club out at once. A club can log in only once its meet is open (after the deadline: read-only, in phase 4).
- **Sessions** are kept in memory (a restart logs everyone out): organisers 30 minutes idle / 12 hours at most, clubs 4 hours / 7 days. The cookie is `__Host-mlaicportal` with Secure, HttpOnly and SameSite=Strict when the public address is HTTPS (`mlaicportal` without Secure for local HTTP tests).
- **Cross-site requests:** every write must be JSON and carry the portal's own `Origin` (or `Referer`); GET requests never change anything.
- **Guessing** is slowed down: 10 wrong club codes per address in 15 minutes; after 5 wrong organiser log-ins per user name or address a wait of 1 minute, doubling up to 1 hour; with more than 50 failed log-ins a minute, every log-in takes a second longer. Failures are logged with the address, never with what was typed.
- The client address comes from the reverse proxy's `X-Forwarded-For`. That is only safe because the portal listens on 127.0.0.1: never bind it to a public address.

## Configuration

Each setting is a system property or an environment variable (the systemd unit sets the latter):

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `portal.bind` | `PORTAL_BIND` | `127.0.0.1` | Address to listen on. Keep it on 127.0.0.1 behind the reverse proxy |
| `portal.port` | `PORTAL_PORT` | `8080` | HTTP port |
| `portal.data.dir` | `PORTAL_DATA_DIR` | `portal-data` | Where `portal.json` and `meets/` are kept |
| `portal.static.dir` | `PORTAL_STATIC_DIR` | `static` | The pages |
| `portal.base.url` | `PORTAL_BASE_URL` | `http://localhost:<port>` | The public address; writes must come from this origin |

## Data

JSON files, written like the Manager's: one lock per file, a `.tmp` file moved over the old one in one step, and a file that cannot be read is an error and is never overwritten.

- **`portal.json`**: organiser accounts, settings (the data controller for the privacy page), id counters.
- **`meets/<id>.json`**: one meet each, with its settings, the rankings and disciplines uploaded from the Manager, the clubs (access codes stored only as hashes) and their starters. Deleting the file deletes all of the meet's personal data. Starters store the ticked ranking entries, not disciplines; the disciplines follow from the uploaded rankings when the file for the Manager is written.
- **`meets/<id>.audit.jsonl`**: the meet's audit log, deleted with the meet.

Ids are never handed out twice, also after deletions.

## API so far

All JSON, snake_case; errors are `{"error": "..."}` in the language of the request (`lang` cookie or `Accept-Language`). `/api/admin/...` needs an organiser log-in, `/api/club/...` a club log-in; health, log-in and session are open.

| Method | Path | |
|---|---|---|
| GET | `/api/health` | `{"ok":true}`, 503 if the data cannot be read |
| POST | `/api/auth/organiser` | `{username, password, totp?}` → 204 with the session cookie; 401 with `totp_required: true` when the app code is needed; 429 with `Retry-After` |
| POST | `/api/auth/club` | `{code}` → 204 with the session cookie; 403 while the meet is still a draft |
| POST | `/api/auth/logout` | Ends the session |
| GET | `/api/session` | Who is logged in: organiser (name) or club (meet and club); 401 if nobody |
| GET, POST, DELETE | `/api/admin/organisers` | Organiser accounts: list, create `{username, password}`, delete `/{id}` (not the last) |
| PUT | `/api/admin/password` | `{current, password}`; ends the account's other sessions |
| POST | `/api/admin/totp/setup`, `/confirm`, `/disable` | Authenticator app: get secret and `otpauth://` address, confirm with `{code}`, turn off with `{password}` |
| GET, POST | `/api/admin/meets/{id}/clubs` | The meet's clubs / create one `{name, default_country}`, answering `code` and `invite_url` once |
| PUT, DELETE | `/api/admin/meets/{id}/clubs/{cid}` | Change name and default country (with `version`) / delete (409 while it has starters, unless `?with_starters=true`) |
| POST | `/api/admin/meets/{id}/clubs/{cid}/code`, `/revoke` | New code (shown once, the old one stops working) / block `{revoked: true}` or unblock |
| PUT | `/api/admin/meets/{id}/clubs/{cid}/starters`, `/contact` | Change a club's starters (same body as the club's save, also after the deadline) / its contact person |
| GET, DELETE | `/api/admin/meets/{id}/starters` | All starters with warnings (incl. a name registered by two clubs) / delete all registrations of the meet: `?confirm=<meet key>` |
| GET | `/api/admin/meets/{id}/audit` | The audit log, newest first (`?offset=&limit=`) |
| GET | `/api/club/meet` | For the logged-in club: the meet, its deadline, `open` (changes still possible), the club's details and the offer |
| GET, PUT | `/api/club/starters` | The club's own starters with warnings / save the sheet: `{upsert: [rows], delete: [{id, version}], consent: true}` |
| PUT | `/api/club/contact` | The club's contact person `{name, email, version}` |
| GET | `/api/countries` | `[{code, name}]`: the countries a starter can have, English names as the Manager stores them |
| GET, POST | `/api/admin/meets` | List the meets / create one: `{key, name, date, deadline, time_zone, delete_after, collect_email, contact:{name,email,club}}`. The deadline is a local date-time in the meet's time zone (default Europe/Vienna) or one with an offset; `delete_after` defaults to the meet date plus 90 days |
| GET, PUT, DELETE | `/api/admin/meets/{id}` | One meet / change it (all settings plus `status` draft, open or closed, and the `version` you saw: 409 with the current meet if someone saved in between) / delete it with all its data |
| PUT | `/api/admin/meets/{id}/snapshot` | `{rankings: <Manager's rankings export>, catalog: <its discipline catalog export>}`; answers the meet, the `offer` and `warnings`. 400 if the files are not the Manager's or do not fit together, 409 listing the starters concerned if the new rankings would drop or change an entry they ticked |
| GET | `/api/admin/meets/{id}/offer` | What clubs can register for: the individual rankings with their entries (a ranking with O and R has two) and the disciplines behind each |

**Saving the sheet** is all or nothing: every row is checked first (400 with `rows: [{index, id, errors}]`), then the versions (409 with the rows someone changed meanwhile as `current`), then it is written. A row with an `id` changes that starter and needs its `version`; a row without one is new; unchanged rows stay as they are. Rules: name (at most 100 characters) and country (from `/api/countries`; empty means the club's default) are required; gender male, female, other or empty; year of birth 1900 to this year; email only when the meet collects it; club defaults to the club's name; every ticked entry must be offered (O and R of a ranking may both be ticked). Warnings (not blocking): no ranking ticked, a name twice. A club confirms on its first save (`consent: true`) that it may pass on its members' data. Clubs can save only while the meet is open and before the deadline (else 403 with `closed: true`); the organiser can still change everything. Every change goes to the meet's audit log (who, what, the starter's name; no other personal data).

**Deletion:** the day after a meet's `delete_after` (default: meet date + 90 days), a background job deletes its starters, the clubs' contact persons and the audit log; the meet, its clubs and the uploaded rankings stay. The organiser can do the same at any time (`DELETE /api/admin/meets/{id}/starters?confirm=<key>`).

Rules for meets: only one meet can be open at a time; opening needs uploaded rankings and a deadline; a closed meet takes no new upload. Team rankings are kept in the upload but not offered.

## Release

`.github/workflows/portal-release.yml` builds the portal on every push to `main` that touches `registration-portal/` and publishes `mlaic-portal-dist.tar.gz` (jar and `static/`) on the release tag `portal-latest`. The Manager's release (`release.yml`, tag `latest`) skips portal-only changes, so they never redeploy the installations at the ranges.
