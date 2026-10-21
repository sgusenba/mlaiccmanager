# Registration Portal: architecture and implementation plan

## Decisions (answers to the open questions)

| # | Question | Decision |
|---|---|---|
| 1 | Club codes | One code per club **per meet** |
| 2 | After the deadline | Clubs are **read-only**; only the organiser can still change entries |
| 3 | Starter email | **Optional** (off by default, can be turned on per meet) |
| 4 | Data controller | **SV Bad Zell** |
| 5 | Oracle account | Held by the organiser who runs the portal. Domain not decided yet (own domain or DuckDNS) |
| 6 | Retention | Delete registrations at meet date + 90 days; backups kept 30 days |
| 7 | One-start rule | **Removed from the Manager** (PR #81): a competitor may start in every discipline of a ranking, e.g. both O and R. The portal has no such rule either |
| 8 | Entry fees | None, out of scope |
| 9 | Several meets open at once | No: one meet open at a time; the admin UI can assume that |
| 10 | `/bulkreg/` demo page in the Manager | Remove it once the portal is live |
| 11 | Second organiser | **Yes**: a second organiser login, who also gets access to the backup key |

Phase 0 is done (the Manager no longer has the one-start rule). Phases 1 (skeleton), 2 (meets and upload), 3 (log-ins) and 4 (starters) are implemented in `registration-portal/`.

## 1. Overview and boundaries

**Summary.** `registration-portal/` is a new program that runs on its own and is the only part on the internet. Clubs log in with an access code from the organiser and enter their starters in a sheet. They can only pick from the meet's individual rankings. The organiser downloads one merged `mlaic-registrations` v1 file and imports it on the Manager's existing `/import/` page. The Manager stays offline and is not touched, apart from two optional small changes (see §8). No network link is added between the two.

| Concern | Manager (offline, at the range) | Portal (internet, OCI VM) |
|---|---|---|
| Discipline catalog, rankings | Owns them. Exports via `GET /api/disciplines/export` and `GET /api/rankings/export` | Keeps a read-only snapshot per meet, uploaded by the organiser |
| Competitors, starts, results | Owns them | Never sees them. It holds registrations only |
| Registrations | Imports them with `static/import/import.js` | Owns them and exports `mlaic-registrations` |
| Authentication | None (LAN only) | Organiser password with optional TOTP, plus per-club access codes |

**Why the Manager stays offline.** It has no authentication, CORS is open, and Danger Zone, backup/restore and import endpoints are reachable by anyone who can connect. Results are personal data. Putting it online would mean retrofitting authentication into every resource. The file exchange is the only coupling, it already exists, and it is a compatibility contract that is easy to test.

## 2. Repository layout and build

**Decision: a separate Maven project, `registration-portal/pom.xml`. Do not make it a multi-module build. Leave the root `pom.xml` untouched.**

Why. `.github/workflows/release.yml` copies `target/mlaiccmanager-*.jar` from the repo root. Installed `download-deploy.sh`, `update.ps1` and `start.bat` expect exactly that bundle layout. Turning the root into an aggregator would move the jar to `manager/target/` and break every installed updater.

```
registration-portal/
  pom.xml                     same Jetty / Jersey / Jackson / SLF4J / Logback / JUnit 5 versions; --release 17; shade plugin
  src/main/java/com/competition/portal/
    PortalMain.java           Jetty on 127.0.0.1:8080; CLI: --add-organiser, --reset-totp, --backup <file>
    config/                   binder, AuthFilter, OriginCsrfFilter, SecurityHeadersFilter, exception mappers (409, 404, 403, 429)
    format/                   read-side DTOs for mlaic-rankings / mlaic-disciplines, write-side DTO for mlaic-registrations
    model/                    Meet, Club, Starter, Choice, Organiser
    service/                  PortalStore, MeetStore, SnapshotService, RegistrationRules, StarterService, ClubService,
                              AuthService, SessionStore, RateLimiter, ExportService, AuditLog, RetentionJob
    resource/                 AuthResource, ClubResource, AdminMeetResource, AdminClubResource, AdminUserResource, HealthResource
    i18n/Messages.java        copy of the Manager's Messages; reads portal-messages*.properties
  src/main/resources/         portal-messages(.properties|_de.properties), logback.xml
  static/                     login/ sheet/ admin/ privacy/ js/{api,i18n,rules,countries,tailwind}.js i18n/{en,de}.js
  src/test/...                JUnit 5; smoke/ (Playwright for Java, profile -Psmoke); rules-cases.json
deploy/portal/                Caddyfile.example, mlaic-portal.service, backup service + timer, portal-deploy.sh, setup-oci.md
.github/workflows/portal-release.yml
```

**What to share, and how.**
- **The formats are the contract, not shared Java code.** The portal gets its own small DTOs (`ignoreUnknown = true`) matching `RankingDefinition`, `Discipline` and `DisciplineCatalog`. Compiling Manager sources into the portal would couple the two release cycles.
- **Contract test.** A portal JUnit test parses the repo's `default-rankings.json` and `default-disciplines.json`. If someone changes the export format without updating the portal, this test fails.
- **Frontend.**
  - `static/js/countries.js` is copied at build time, so country names stay identical; they are English names, which import.js passes straight to the Manager.
  - The portal gets its own `i18n.js` fork.
  - The Manager's `bulk.*` texts are the starting point for the sheet.
- **No third-party CDN.** Tailwind is downloaded once and committed as `static/js/tailwind.js`, because of GDPR (third-party IP transfer) and the CSP.

**Build for ARM.** The jar is pure Java, so it does not depend on the CPU architecture. CI builds it on x64 and it runs on Ampere A1. This only holds if no native library is added, which is one reason for JSON storage over SQLite and for a pure-Java Argon2.

**Release workflow.** `portal-release.yml` builds on changes under `registration-portal/**` and publishes `mlaic-portal-dist.tar.gz` to a separate tag `portal-latest`, never `latest`. `release.yml` gets `paths-ignore: registration-portal/**`, so portal-only commits do not redeploy range installations.

## 3. Data model

**Decision: JSON files with the Manager's persistence idiom, not SQLite.**

Reasons:
- **Scale.** At most about 50 clubs and about 1000 starters per meet, so a file of roughly 300 KB.
- **Same idiom.** One lock per file, write to `.tmp`, then an atomic move.
- **No native library.** SQLite would bring a JNI library of about 13 MB.
- **GDPR.** Deleting a meet's data means deleting its files.
- **Backups.** A backup is a tar of a directory.

Data directory `/var/lib/mlaic-portal/`:

- **`portal.json`** holds organisers (argon2id hash, optional TOTP secret), settings (`base_url`, controller details for the privacy page) and `next_ids`.
- **`meets/<id>.json`** holds everything about one meet:
  - id, key, name, date, deadline, time zone, status (draft/open/closed), `delete_after`, contact
  - **snapshot** of the uploaded rankings and the catalog disciplines they reference, with sha256 checksums
  - **clubs**: name, default country, `code_hash` (sha256), `code_hint`, revoked, contact, version
  - **starters**: club_id, name, gender, year_of_birth, club, country, email, `choices: [{ranking_id, entry}]`, version, created/updated timestamps, updated_by
  - `next_ids` and the meet-level `version`

Rules:
- **Choices store `(ranking_id, entry index)` pairs, any number per ranking, never discipline ids.** Disciplines are derived from the snapshot at export.
- **Ids are never reused.**
- **`version` per starter and per club** gives optimistic locking, as in the Manager.
- **Re-uploading a snapshot** is refused (409, listing the affected starters) if a stored choice would break.
- **Deadline.** Clubs may write only if `status == open && now < deadline`, checked on every request. No scheduler is needed.
- **Audit.** An append-only `meets/<id>.audit.jsonl`, with no full copies of personal data. It is deleted with the meet.
- **Sessions and rate-limit counters are in memory.** A restart logs everyone out, and the sheet warns about unsaved rows.

## 4. HTTP API

All bodies are JSON with snake_case fields and errors as `{error, ...}`. Backend messages are localised through the `lang` cookie or `Accept-Language`.

Status codes:
- 400: validation. Bulk saves list the failing rows.
- 401: not logged in.
- 403: wrong role, other club's row, or registration closed (body has `closed:true`).
- 404: not found.
- 409: stale version. Body is `{error, current}` and the UI reloads those rows.
- 413: body larger than 1 MB.
- 429: rate limited, with `Retry-After`.

**Public**
- `POST /api/auth/organiser {username, password, totp?}`
- `POST /api/auth/club {code}`
- `POST /api/auth/logout`
- `GET /api/session`
- `GET /api/health`
- Invite links have the form `https://host/login/#c=XXXX-XXXX-XXXX-XXXX`. The code is in the fragment, so it never appears in server logs.

**Club** (the meet and club come from the session, never from the URL)
- `GET /api/club/meet`: the meet, deadline, the open flag, the club's defaults, and the offer (individual rankings with their entries and disciplines)
- `GET /api/club/starters`
- `PUT /api/club/starters {upsert:[...], delete:[...]}`: all-or-nothing. Rules are checked first, then versions, then everything is written at once.
- `PUT /api/club/contact`
- `GET /api/club/export`: the club's own confirmation copy

**Organiser**
- Meets: list, create, edit (status, deadline), delete.
- `PUT /api/admin/meets/{id}/snapshot {rankings, catalog}`: validates the files and returns the offer plus warnings.
- Clubs:
  - create (returns the code and invite link once)
  - edit
  - delete (refused while the club has starters, unless told to delete them too)
  - rotate the code
  - revoke
- Starters: all clubs, with cross-club duplicate warnings. The organiser may also edit after the deadline.
- `GET /api/admin/meets/{id}/export`: the merged download.
- Audit view, purge of a meet's registrations, organiser accounts, password, TOTP.
- The first organiser is created on the VM with `java -jar mlaic-portal.jar --add-organiser <name>`. There is no self-sign-up.

## 5. Authentication and sessions

- **Club access code**
  - 16 characters of Crockford base32, which is 80 bits, shown as `XXXX-XXXX-XXXX-XXXX`.
  - Input is normalised: uppercase, dashes and spaces removed, O→0, I/L→1.
  - Stored as SHA-256 and shown once. Rotating or revoking a code ends the club's sessions.
  - After the deadline the code still logs in, read-only.
- **Organiser password**
  - Argon2id via Bouncy Castle (pure Java), with OWASP parameters and a minimum length of 12.
  - A hash is always computed, even for an unknown user, so timing gives nothing away.
- **TOTP (optional)**
  - RFC 6238, implemented with the JDK, so no dependency.
  - Set up with a QR code (reusing the Manager's `qrCode.js`), with replay protection.
  - The CLI `--reset-totp` is the recovery path.
- **Sessions**
  - The token is 32 random bytes; the server stores only its hash.
  - Cookie: `__Host-mlaicportal`, Secure, HttpOnly, SameSite=Strict.
  - Lifetimes: organiser 30 min idle and 12 h maximum; club 4 h idle and 7 days maximum.
- **CSRF**
  - SameSite=Strict, plus an Origin check on every write.
  - Writes must be JSON, and GET requests never change anything.
- **Rate limiting**
  - Club codes: 10 failures per IP per 15 min.
  - Organiser: 5 failures, then exponential backoff.
  - A global brake on failed logins for everyone.
- **Other**
  - Jetty binds 127.0.0.1 only.
  - Bodies are limited to 1 MB, with at most 500 starters per club and 5000 per meet.
  - Club scoping is enforced in one place.

## 6. Frontend pages

Each page is plain HTML with ES modules, the vendored Tailwind, and English and German texts. There is a small header with a language switch and logout.

- **`/login/`** has two cards, "Club access code" and "Organiser". It reads invite links.
- **`/sheet/` (club entry sheet)** is a port of the prototype form and `static/bulkreg/bulkreg.js`.
  - Header: meet, deadline countdown, club contact, defaults for club and country.
  - Grid: one row per starter and one checkbox column per ranking entry (Whitworth O/R has two columns, WHITO and WHITR), as in the current online form. Columns are grouped by rifle and pistol, and a status column shows each row's problems.
  - Enter and the arrow keys move between cells, rows can be pasted from Excel, and an empty row is always kept at the end.
  - "Save" sends the sheet, and conflicting rows are reloaded and marked. The page warns about unsaved changes and turns read-only after the deadline.
  - "Download my registrations" gives the club a confirmation copy.
- **`/admin/`** has these tabs:
  - **Meets**: create and edit, status, upload of the two files with a preview and warnings.
  - **Clubs**: create, show the code and invite link once (copy button), rotate, revoke, last login.
  - **Overview**: all starters, counts per ranking, cross-club duplicates, organiser edits.
  - **Download**.
  - **Data**: purge, delete the meet (confirmation by typing the meet key), audit view.
  - **Account**: password, TOTP.
- **`/privacy/`** has the privacy notice in English and German.
- **`static/js/rules.js`** holds the validation shared by the sheet and the admin overview.

## 7. Validation rules (in `rules.js` and `RegistrationRules.java`)

The browser and server versions are kept in sync by one table of cases, `rules-cases.json`, run in JUnit and in the Playwright smoke test. The server is authoritative.

**Snapshot upload** (rejected with 400, nothing saved):
- Correct file formats and versions.
- Every discipline id exists in the catalog.
- Individual rankings use no team disciplines.
- At least one individual ranking has entries.

Warning (does not block): a discipline is inactive. The `one_start_per_competitor` field in older ranking files is ignored.

**Row errors** (block saving):
1. Name is required, at most 100 characters.
2. Country is required and must be one of the English country names, the same list as the Manager uses.
3. Gender is male, female, other or empty. Year of birth is empty or between 1900 and this year. Email is empty or plausible.
4. Club is required and defaults to the club account's name.
5. Each choice references an offered ranking and an existing entry. Every entry is ticked on its own, so a starter may take O and R of the same ranking. Disciplines that come up twice (e.g. Colt on its own and through Remington O) are merged into one start.

**Row warnings:** no ranking chosen, duplicate name within the club, duplicate name across clubs (shown to the organiser only).

**Deadline:**
- After the deadline, or when the meet is not open, club saves get 403. The organiser can still edit, and those edits are audited.
- The deadline is entered and shown in the meet's time zone.

## 8. Export format and Manager import compatibility

The merged file stays `mlaic-registrations` version 1. Fields are only added, and import.js ignores unknown fields.

- **`contact`**: the meet's contact.
- **New `contacts`**: one entry per club, with name, email and number of starters.
- **New `registered_by`**: links each starter to its club account.
- **New `generator`**: names the program that wrote the file.
- **`starts`**: the deduplicated disciplines, with `short_name`, so import.js can still match by short name if the catalog ids have moved on.

**import.js changes.** None are required. Two are recommended, about 15 lines:
1. Reject `version > 1` with a clear message.
2. Show "N clubs" when the file contains several contacts.

**One-start rule.** Removed from the Manager in PR #81, so every start in the file is accepted on import. Nothing to do here.

## 9. Deployment runbook outline (Oracle Cloud)

1. **Account.** Home region Frankfurt or Zurich. Upgrade to Pay-As-You-Go, which prevents idle reclaim. Add a budget alert at €1.
2. **VM.** `VM.Standard.A1.Flex` with 1 OCPU and 6 GB, Ubuntu 24.04 aarch64, 50 GB boot volume, reserved public IP.
3. **Network.**
   - Security list: SSH only from your IP (or OCI Bastion), 80 and 443 open.
   - On the host, insert an ACCEPT for 80/443 before Oracle's final iptables REJECT rule, then run `netfilter-persistent save`.
4. **OS hardening.** SSH keys only, no root login, unattended-upgrades with a nightly reboot window, optionally fail2ban.
5. **Java.** `openjdk-21-jre-headless` (aarch64).
6. **App layout.**
   - System user `mlaic-portal`.
   - `/opt/mlaic-portal` read-only.
   - Data in `/var/lib/mlaic-portal` (0700).
   - Config in `/etc/mlaic-portal/portal.env`.
7. **systemd unit.** Restart on failure, sandboxed (ProtectSystem=strict, NoNewPrivileges, PrivateTmp), write access only to the data directory, 1 GB memory cap.
8. **Caddy.** Reverse proxy to 127.0.0.1:8080 with automatic Let's Encrypt. Headers: HSTS, CSP, nosniff, no-referrer. Access logs kept 14 days. Use your own domain, with DuckDNS as a fallback.
9. **Backups.**
   - A daily timer takes a consistent backup via the CLI.
   - The backup is encrypted with `age` to an offline key, so the private key is never on the VM.
   - It is uploaded to a private Object Storage bucket using instance-principal authentication, so no API keys are stored on the VM. Backups are deleted after 30 days.
   - Do a restore drill once before go-live.
10. **Updates.** A manual `portal-deploy.sh`, following the pattern of the existing `deploy/download-deploy.sh`: download, check the checksum, take a backup first, swap the jar last.
11. **Monitoring.** An external uptime check on `/api/health`, disk and memory alarms, and logs of failed logins.

## 10. Security and GDPR checklist

**Security**
- [ ] The Manager is never internet-facing.
- [ ] Only Caddy is exposed. Jetty binds 127.0.0.1 and SSH is restricted.
- [ ] TLS, HSTS, CSP, no third-party scripts.
- [ ] Argon2id, optional TOTP, generic login errors, rate limits, audit of logins.
- [ ] Secure session cookie and an Origin check on writes.
- [ ] Club scoping tested against attempts to reach another club's rows.
- [ ] Access codes: 80 bits, hashed, shown once, rotatable and revocable, never in logs.
- [ ] Limits on body size and row counts; all output escaped.
- [ ] Atomic file writes, encrypted backups, a tested restore.
- [ ] Dependencies pinned and checked monthly.

**GDPR**
- [ ] The controller, **SV Bad Zell**, is named on `/privacy/` with address and contact.
- [ ] Privacy notice in English and German covering purpose, legal basis, data, recipients (Oracle as processor, EU region), retention, rights and complaint authority.
- [ ] Data minimisation: year of birth only, starter email off by default, no phone.
- [ ] Clubs confirm on first save that they may pass on their members' data.
- [ ] Only essential cookies, so no cookie banner is needed.
- [ ] Retention: an automatic deletion job at meet date plus 90 days, backups expire after 30 days, logs after 14.
- [ ] Data subject requests: the organiser can find, export and delete a starter.
- [ ] Records of processing and a breach procedure (72 h) are documented.

## 11. Phases

| # | Phase | Effort | Tests |
|---|---|---|---|
| 0 | ~~Manager prerequisites~~ One-start rule removed (PR #81). Left over: import.js version check and "N clubs" line, can go into phase 7 | done | – |
| 1 | Skeleton: pom, main class, storage with locks and atomic writes, health endpoint, release workflow | 1.5 d | Storage round trip, atomic writes, ids never reused, concurrent writes |
| 2 | Meets, snapshot upload, offer | 1.5 d | Contract test with the default files, rejections, re-upload protection, warnings |
| 3 | Authentication: Argon2id, sessions, Origin filter, rate limiter, club codes, TOTP | 2.5 d | Hashing, backoff, code normalisation, RFC 6238 vectors, role and path matrix |
| 4 | Starters: rules, bulk save, versions, deadline, audit, purge, retention | 2 d | Rules table, all-or-nothing saves, stale versions, other club's ids refused, the exact deadline moment |
| 5 | Club sheet frontend and `rules.js` | 2.5 d | Playwright smoke |
| 6 | Organiser admin pages, privacy page | 2 d | Playwright smoke |
| 7 | Export and compatibility | 0.5 d | Golden file; end-to-end import into a local Manager, then a re-import that changes nothing |
| 8 | Deployment: VM, Caddy, backups, restore drill | 1.5 d | Restore drill, SSL Labs A, port 8080 closed from outside |
| 9 | Hardening, GDPR review, README section, OpenAPI | 1 d | Checklist walked through |

**Total: about 15 person-days** (phase 0 done).

Playwright smoke test:
1. Create an organiser and a meet, upload the two files, create two clubs.
2. Club A pastes rows and ticks a single entry, both entries of an O/R ranking and a sum entry; duplicate disciplines are merged.
3. Club B cannot see club A's rows.
4. After the deadline the sheet is read-only and a save gets 403.
5. Check the export structure.
6. Run the shared rules table in the browser.

## 12. Still open

1. **Domain:** your own domain (recommended, e.g. a subdomain of SV Bad Zell's website) or a free DuckDNS name?
2. **Clubs' role under GDPR:** do the clubs pass on their members' data to SV Bad Zell (clubs as separate controllers, the usual case), or act on its behalf? This only changes the wording of the privacy notice and the club's confirmation checkbox.
