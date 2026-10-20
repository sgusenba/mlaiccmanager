# MLAICC Manager

A Java backend for managing historical firearms shooting competitions (MLAIC events). It handles competitors, starts, results, and rankings for historical rifle and pistol competitions, with support for original and reproduction categories and rankings that rank disciplines together or add them up.

## Features

- **Competitor management** — add, edit, and delete competitors with full details (name, gender, club, email, phone, address, year of birth)
- **Start management** — register competitors for specific disciplines with unique start IDs
- **Disciplines and rankings** — the disciplines (what is shot, rifle and pistol, original/reproduction/open, individual/team) are the installation's catalog, the same at every meet; a meet's **rankings** (Wertungen) say what is ranked: one discipline, several ranked together (e.g. Whitworth O/R), or sums of disciplines (e.g. Remington_O = Colt + Donald Malson_O). Both are exported and imported as files of their own
- **Results management** — record detailed results with individual scoring entries and override values
- **Results-only port** — port `5001` serves nothing but the Enter Results page (`/results/`) and the API calls it needs, so result entry stations can be given that port without reaching the rest of the app
- **Rankings** — automatically sorted rankings with tie-breaking support
- **Team management** — separate page at `/tmgmt` for building the teams of the team disciplines (e.g. *Gustav Adolph*) from registered starts, whose team ranking shows up on the Ranking page
- **Discipline management** — separate page at `/dmgmt` for CRUD on the discipline catalog, including shooting distance, an optional short name (a label for lists and the lane assignments CSV; it need not be unique, everything refers to the id), archiving (a discipline in use cannot be deleted) and, for team disciplines, which individual disciplines' starts count for a team ("Results that count", 1 to the catalog's limit, default 4, e.g. Nagashino = Tanegashima O + R, Nobunaga = Tanegashima O only); exports and imports the catalog (merge or replace)
- **Ranking setup** — separate page at `/rankings` for the meet's rankings: name, short name, individual or team, what is ranked (entries: one discipline each, or a named sum of several), at most a per-meet number of disciplines per ranking (default 4); loads the shipped default rankings (one per discipline, plus Remington and Schulhof), exports and imports them. The disciplines the rankings cover are the meet's program: only they take starts and teams
- **Ranking page** — separate page at `/ranking` that shows the meet's rankings, each competitor once on their best result (then the next, see below), lists everyone who started without a result yet below the ranked competitors, shows the ring counts 10 to 0 (misses) and prints cleanly or exports it as a Word or Excel file, optionally one ranking per page
- **Todos page** — separate page at `/todos` that lists the ranking entries still needing a tie-break value: same result and same number of 10s, 9s, … 1s as another entry of the ranking, with the tie-break missing or the same; laid out like the Ranking page without the rank, empty when there is nothing to do
- **Relay management** — separate page at `/rmgmt` for planning meet days, relays (Durchgänge) and which registered start shoots on which lane of the 25m/50m/100m ranges; above each range a discipline filter narrows the starts offered for its lanes; an optional auto mode (off until switched on) fills all unlocked days at the click of a button, keeping a discipline in consecutive relays and the disciplines in the meet's ranking order
- **Meet details and printouts** — separate page at `/meet` for the meet's name, venue, host and dates, which prints or exports as a Word document a start card (one A4 page per starter with their relays and lanes, the rankings each of their disciplines counts in, and their teams with their team rankings) and a race bib (A4 landscape) per starter, exports all lane assignments as a CSV file, and prints or exports as a Word document the target labels (one per lane assignment, or a left and a right one, tiled on A4 in an adjustable size, default 105 x 48 mm, with the start ID as a 10 x 10 mm QR code); the ranking can be printed or exported with a cover page and a statistics page (starters and starts per country and discipline)
- **Backup & restore** — separate page at `/backup` to download all data as one zip file and to restore it from one
- **Danger Zone** — separate page at `/danger` to clear results, lane assignments, starts, competitors, teams, meet days and relays, the meet details or the rankings, or to reset the discipline catalog; locked until its master switch is on, and each button needs its own switch as well
- **JSON file storage** — simple file-based storage, no database required

## Technology Stack

- **Java 17** (compiled with `--release 17`; requires JDK 26 to build)
- **Jetty** — embedded HTTP server
- **Jersey (JAX-RS)** — REST API layer
- **Jackson** — JSON serialization
- **SLF4J / Logback** — logging
- **Maven** — build and dependency management
- **JUnit 5** — testing

## Prerequisites

- JDK 26
- Maven 3.6+

## Setup and Running

```bash
mvn clean package
mvn exec:java -Dexec.mainClass="com.competition.Main"
```

Or on Windows, simply run:

```bash
run.bat
```

The server starts on `http://localhost:5000`. Static frontend assets are served from `static/`, and the REST API is mounted under `/api`.

It also listens on `http://localhost:5001`, which shows just the Enter Results page. On that port every other page and API call answers 404; only these pass: the page itself (`/results/`, redirected to from `/`), `/js/*`, `/style.css`, reading `/api/competitors`, `/api/available-disciplines`, `/api/active-disciplines` and `/api/results`, and saving (`POST /api/results`) or deleting (`DELETE /api/results/{id}`) a result. The page reloads the starts and results before every search, so it can stay open all day, and puts the cursor back in the start ID field after every save, delete or cancel, ready for the next barcode scan. This only separates what the port offers: to keep a station off the full app, let it reach port 5001 only (e.g. in the firewall).

## Frontend

The frontend is plain HTML, vanilla JavaScript, and Tailwind (via CDN) — no build step, no framework. It lives entirely under `static/` and is served as-is by Jetty.

Every page shares one sidebar (`static/js/appNav.js`, styles in `static/style.css`), grouped in the order a competition runs in, with the one-off setup (Management) last. On narrow screens it folds into a menu button:

| Group | Entry | Page |
|---|---|---|
| **Starters** | Competitors | `/#competitors` |
| | Starts | `/#starts` |
| | Import Registrations | `/import/` |
| | Teams | `/tmgmt/` |
| | Lane Assignment | `/rmgmt/#assignment` |
| | Starter Overview | `/rmgmt/#overview` |
| **Results** | Enter Results | `/#results` |
| **Rankings** | Ranking | `/ranking/` |
| | Todos | `/todos/` |
| **Management** | Meet | `/meet/` |
| | Disciplines | `/dmgmt/` |
| | Ranges & Relays | `/rmgmt/#settings` |
| | Meet Days | `/rmgmt/#schedule` |
| | Registration (Demo) | `/register/` |
| | Bulk Registration (Demo) | `/bulkreg/` |
| | Backup & Restore | `/backup/` |
| | Danger Zone | `/danger/` |

A new entry is one line in `GROUPS` in `appNav.js`; a page takes part by putting `class="has-sidebar"` on `<body>` and loading `appNav.js`.

- **`/`** (`static/index.html` + `static/js/`) — competitors, starts and result entry, split into modules under `static/js/modules/`
- **`/results`** (`static/results/`) — just the result entry of `/#results`, without the sidebar; the only page on port 5001
- **`/ranking`** (`static/ranking/`) — the ranking: printable, one row per competitor and ranking, team rankings with their teams (`static/js/teamRanking.js`); its Export button offers Print, Word (.docx) or Excel (.xlsx) and whether to add the cover and statistics pages (`static/ranking/printPages.js`, files built by `static/ranking/rankingExport.js`) and whether "Intermediate Result" or "Final Result" is printed as a watermark across the ranking pages (not the cover and statistics pages); the start id is shown in small print below each name
- **`/todos`** (`static/todos/`) — the entries that still need a tie-break, drawn with the Ranking page's cards (`static/ranking/rankingCard.js`)
- **`/dmgmt`** (`static/dmgmt/`) — discipline management page
- **`/rankings`** (`static/rankings/`) — the meet's rankings (ranking setup)
- **`/rmgmt`** (`static/rmgmt/`) — relay management page
- **`/tmgmt`** (`static/tmgmt/`) — team management page
- **`/meet`** (`static/meet/`) — meet details, start cards, race bibs (printed or as Word documents, `static/meet/wordExport.js`) the lane assignments CSV (`static/meet/laneExport.js`) and the target labels (`static/meet/labelExport.js`, QR codes by `static/js/qrCode.js`); the meet details and dates for all printouts come from `static/js/meet.js`; Word and Excel files are written in the browser without any library by `static/js/officeFiles.js`, so the export also works offline
- **`/import`** (`static/import/`) — imports a registration file (format `mlaic-registrations`, as written by an online bulk registration form): shows each starter with their starts, then creates the competitors and starts through the regular API. A competitor already registered under the same name only gets the starts they lack (several with that name: skipped, to be added by hand), starts outside the meet's program are skipped, and the API's own checks (e.g. one start per ranking) are reported per start, so importing a file twice changes nothing
- **`/register`** (`static/register/`) — a demo of an online registration form for starters: offers the meet's individual rankings (combined rankings such as Remington register for all their disciplines) in three layouts to compare (list, cards per firearm, step by step). It only reads the meet, its rankings and the discipline catalog and saves nothing
- **`/bulkreg`** (`static/bulkreg/`) — a demo of a bulk registration: many starters and their starts at once, e.g. a whole club. Starters are rows of a table whose rankings are typed as short names (`WHITO COLT`), ticked in a matrix (starters × rankings) or pasted from Excel; club and country for new rows are set once, rows are checked inline (missing name or country, unknown short name, duplicate, already registered). It only reads the rankings, the discipline catalog and the competitors and saves nothing
- **`/backup`** (`static/backup/`) — backup download and restore
- **`/danger`** (`static/danger/`) — the Danger Zone: clears one kind of data at a time

Each page talks to the backend directly via `fetch` calls to the `/api` endpoints described below.

### Languages

The whole application is translated; English and German are shipped. The language is picked at the bottom of the sidebar (on the Enter Results page next to its title) and remembered per browser; until then the browser's preferred language is used if there is a translation for it, else English. Exports and printouts (rankings, start cards, race bibs, target labels, the lane assignments CSV) come out in the chosen language too. Data such as discipline and ranking names is never translated; countries are stored by their English name and shown in the chosen language.

- **Frontend** — `static/js/i18n.js` picks the language and provides `t('key', { placeholder })`; the texts are in `static/i18n/en.js` (the reference, every key is there) and `static/i18n/de.js`. Static HTML is marked with `data-i18n="key"` (also `data-i18n-html`, `data-i18n-placeholder`, `data-i18n-title`, `data-i18n-aria-label`). A key missing in a language falls back to English.
- **Backend** — the error messages the API returns come from `src/main/resources/messages.properties` (English) and `messages_de.properties`; `com.competition.i18n.LocaleFilter` takes the language from the `lang` cookie the frontend sets, else from `Accept-Language`.
- **Adding a language** — copy `static/i18n/en.js` and `messages.properties` (as `messages_<code>.properties`), translate them, and add the language to `LANGUAGES` in `static/js/i18n.js` and `Messages.LANGUAGES`.

## Data Storage

- **`data.json`** — competitors (with their starts) and results. Created automatically on first run. Contains real personal data, so it is git-ignored — never commit it. Ids that follow from other data are not stored: a start's discipline comes from the key it is filed under, and a result's competitor and discipline from its start.
- **`default-disciplines.json`** — the shipped MLAIC discipline catalog (names, short names, categories, levels, types, default shooting distance, team compositions). Tracked in the repo and replaced on every deploy; the app only reads it.
- **`default-rankings.json`** — the shipped default rankings: one per discipline, plus Remington_O/R and Schulhof_O/RS/RU as sums. Loaded on the Ranking Setup page (Load Defaults); disciplines the installation's catalog lacks are left out.
- **`catalog.json`** — this installation's discipline catalog (format `mlaic-disciplines`, also the export format), the same for every meet: the disciplines and the limit of disciplines a team may count. Created on first start from a `disciplines.json` of that format next to it (where the first release with rankings kept it), else from `default-disciplines.json` (or the copy built into the jar), and from then on edited on the Disciplines page; deploys leave it alone. Git-ignored.
- **`disciplines.json`** — no longer read. The release bundle still contains one (the default catalog) because deploy scripts installed before the rankings release copy it and abort without it; current deploy scripts update themselves from the bundle.
- **`rankings.json`** — this meet's rankings and its limit of disciplines per ranking. Created on the first change on the Ranking Setup page; git-ignored. The disciplines its rankings cover are the meet's program.
- **`teams.json`** — the teams of the team disciplines (name, member start ids, tie-break value, notes). Created on the first saved team; git-ignored like `data.json`. Members are only referenced by start id.
- **`appsettings.json`** — machine-specific settings (the external backup's receiver URL and token, or the backup receiver's token). Created on the first save on the Backup page; git-ignored, not part of the backup zip. Missing means everything is off.
- **`meet.json`** — the meet's name, venue, host and optional first/last day. Created on the first save on the Meet page; git-ignored like `data.json`.
- **`relays.json`** — everything about relays (meet days, relays, lane assignments, ranges with their lane counts, relay duration and break, locked days), kept separate from `data.json`. Created automatically on first use of the relay management page; git-ignored like `data.json`. Competitors and their starts are not copied into it, only referenced by id.

### Backup and restore

The **Backup & Restore** page (`/backup`) downloads `data.json`, `rankings.json`, `teams.json`, `relays.json`, `meet.json` and the discipline catalog (`catalog.json`) as one zip file (`mlaiccmanager-backup-<date>-<time>.zip`, plus a `backup-info.json` with the time it was taken). The zip contains the competitors' personal data, so keep it safe.

Restoring a backup replaces all these files; a file the backup does not contain is removed, so the app is exactly in the state the backup was taken in. The one exception is `catalog.json`: a backup without it keeps the current catalog (a backup of the first release with rankings, which named it `disciplines.json`, restores it too). Backups taken before disciplines and rankings were separated cannot bring back their catalog (it has the old layout). Before that, the current files are saved to `backups/pre-restore-<date>-<time>.zip` next to `data.json`, so a restore can be undone by restoring that file. An upload that is not a zip, has no `data.json` or holds a file that is not a JSON object (or, for `catalog.json`, a discipline catalog of the current format) is rejected and changes nothing. Backup and restore hold every file's lock, so they never see or leave a half-saved state. The page does not refresh other open pages: reload them after a restore.

### Automatic external backup

A backup on the same disk does not survive the loss of the machine, so the app can push its backup zip to a **backup receiver** on another machine after every change. It is off by default and configured on the Backup page; the settings are kept in `appsettings.json` next to `data.json` (git-ignored, not part of the backup zip):

```json
{ "externalBackup": { "enabled": true, "url": "http://192.168.1.20:5100", "token": "…", "debounceSeconds": 60, "maxDelaySeconds": 300 } }
```

- **When:** the app checks the five data files every 10 seconds. A backup is sent once the data has been quiet for `debounceSeconds` (default 60), and at the latest `maxDelaySeconds` (default 300) after the first change that has not been backed up, so steady editing is still backed up every 5 minutes. Nothing is sent while nothing changed. After a restart, or when the URL changes, one backup is sent right away.
- **Failures:** if the receiver is down or rejects a backup, the Backup page shows the error and the app retries every minute, sending the newest data once the receiver is back. The app itself is never affected.
- **Page:** *Test connection* checks URL and token without saving, *Back up now* skips the quiet time. The token is write-only: the page shows only whether one is saved, and leaving the field empty keeps it.

Start the receiver on the other machine with the same jar (see `deploy/mlaiccmanager-backup.service` for a systemd unit):

```
java -jar mlaiccmanager.jar --backup-server [--port 5100] [--dir ./received-backups]
```

It needs `{ "backupReceiver": { "token": "…" } }` in the `appsettings.json` of its working directory, the same token as in the app, and does not start without one. Every request must carry it as `Authorization: Bearer <token>`.

- **Storage:** each backup is checked like a restore (a zip with a valid `data.json`, plus the SHA-256 the app sends) and written atomically to `--dir`. Retention: every backup of the last 24 hours, the newest of each day for the 30 days before, the newest of each month beyond that.
- **Restore:** download a zip from the receiver (`GET /api/backups/<name>`) and restore it on the Backup page like any other backup.
- **Security:** the token and the backups travel unencrypted over plain HTTP. Use it on a trusted network or VPN, and put an HTTPS reverse proxy (Caddy, nginx) in front for anything else; the Backup page warns for plain-HTTP addresses outside private networks. Anyone with the token can read and write the backups, so keep `appsettings.json` readable only by the service user.

### Danger Zone

The **Danger Zone** page (`/danger`), the last entry under Management, clears one kind of data at a time. Everything on it is locked until the switch at the top is turned on; each clear button also needs its own switch, which turns off again after every clear. Leaving or reloading the page locks it again. Next to each entry the page shows how much there is of it.

| Button | Clears | Also clears |
|---|---|---|
| Results | every result | — |
| Lane Assignments | every lane of every relay, locked days included | — |
| Starts | every start | their results, lane assignments and team memberships (teams stay, empty) |
| Competitors | every competitor | their starts, results, lane assignments and team memberships |
| Teams | every team | — |
| Meet Days & Relays | every meet day and relay, locked days included | their lane assignments (ranges, relay duration and break stay) |
| Meet Details | the meet's name, venue, host and dates | — |
| Rankings | every ranking of the meet | — (starts, results and teams stay; no discipline is in the program until rankings are set up again) |
| Discipline Catalog (reset) | the installation's catalog, back to the shipped one like on a fresh install | disciplines the shipped catalog lacks: from the rankings, with their starts (results, lanes, team memberships) and teams |

Before every clear, all data files are saved to `backups/pre-clear-<what>-<date>-<time>.zip`, so a clear can be undone by restoring that file on the Backup & Restore page. Like backup and restore, a clear holds every file's lock.

## Multiple Users

Several people can use the app at the same time:

- All reads and writes of `data.json` are serialized on the server, so simultaneous saves never overwrite each other.
- Competitors and results carry a `version` number. Updates and deletes send back the version the user saw; if someone else saved the record in the meantime, the server answers **409 Conflict** with `{"error": ..., "current": <latest record>}` and the UI loads the latest data so the user can redo the change. Requests without `version` skip this check.
- Entering a second result for the same start also returns 409 with the existing result.
- Updating or deleting a record that no longer exists returns **404**.
- Ids are never handed out twice: a deleted competitor's, start's, result's, team's, lane's, discipline's or ranking's id stays taken (the files keep `next_ids` / `next_id` counters), so a stale page can never change a different record that happens to get the old id.
- Deleting a start or competitor also clears their lanes and team memberships. A discipline that still has starts or teams, or that a ranking or team discipline counts, cannot be deleted (409); archive it instead.

If `data.json` cannot be read (e.g. after a hand edit), the server refuses to work with it instead of starting empty; repair the file or restore a backup.

Browsers do not refresh on their own; other users' changes show up when switching sections or reloading the page.

## API Endpoints

The full OpenAPI 3 description is in [`static/openapi.yaml`](static/openapi.yaml); with the server running, browse it with Swagger UI at `http://localhost:5000/swagger/`.

### Competitors (`/api/competitors`)
- `GET /api/competitors` — list all competitors
- `POST /api/competitors` — create a competitor (or update it when `id` is set; send `version`)
- `PUT /api/competitors/{id}` — update a competitor's details (send `version`; starts are not changed here)
- `DELETE /api/competitors/{id}?version=N` — delete a competitor

### Starts (`/api/competitors/{competitorId}/starts`)
- `POST /api/competitors/{competitorId}/starts` — create a start for a competitor. The start ID is `competitorId-disciplineId-startNumber` (e.g. `1-52-1`); starts created before this format keep their old unseparated IDs. Returns `409` if the ID is already used by any start or result.
- `DELETE /api/competitors/{competitorId}/starts/{generatedId}` — delete a start

A start is only taken for an individual discipline of the meet's program (one of its rankings covers it).

### Disciplines (the installation's catalog)
- `GET /api/available-disciplines` — every discipline of the catalog
- `POST /api/available-disciplines` — add a discipline (the next free id)
- `PUT /api/available-disciplines/{id}` — change a discipline; a team discipline's `team_of` change is refused while a stored team member's start would stop counting, a level or category change while the discipline is in use
- `DELETE /api/available-disciplines/{id}` — delete a discipline; 409 while rankings, team disciplines, starts or teams use it (archive it instead: `active: false`)
- `PUT /api/available-disciplines/shooting-distances` — map disciplines to the range they fire on, e.g. `{"shooting_distances": {"52": "m25"}}`; only the listed disciplines change, and an empty string clears the mapping so the discipline may be assigned to any range
- `GET|PUT /api/disciplines/settings` — `{"max_disciplines": 4}`: how many disciplines a team discipline may count (and a new meet's default limit per ranking)
- `GET /api/disciplines/export` — the catalog as a file (`{"format": "mlaic-disciplines", "version": 1, "max_disciplines": 4, "disciplines": [...]}`)
- `POST /api/disciplines/import?mode=merge|replace` — import such a file: merge adds and updates by id, replace also removes what the file lacks; all checked first, nothing changes on an error
- `GET /api/active-disciplines` — the meet's program: the ids of the disciplines its rankings cover

### Rankings (`/api/rankings`, the meet's)
- `GET /api/rankings` — `{"max_disciplines_per_ranking": 4, "rankings": [...]}`, each ranking `{id, name, short_name, level, entries: [{label, discipline_ids}], version}`
- `POST /api/rankings`, `PUT /api/rankings/{id}` (send `version`), `DELETE /api/rankings/{id}?version=N` — change one; refused if it leaves a discipline with starts or teams out of every ranking
- `PUT /api/rankings/order` — `{"ids": [...]}`: the order they are listed, printed and lanes are filled in
- `PUT /api/rankings/settings` — `{"max_disciplines_per_ranking": 4}`
- `GET /api/rankings/by-discipline` — the rankings each discipline counts in (for the start cards)
- `GET /api/rankings/export`, `POST /api/rankings/import` — the rankings as a file of their own (`{"format": "mlaic-rankings", ...}`, by discipline id; each entry also lists `discipline_short_names` for the reader); an import replaces the meet's rankings and is refused as a whole on an unknown discipline id
- `POST /api/rankings/defaults` — replace the meet's rankings with the shipped default rankings
- `GET /api/rankings/results` — every ranking's result in the meet's order (those with nothing to rank yet left out)
- `GET /api/rankings/{id}/result` — one ranking's result

### Results (`/api/results`)
- `GET /api/results` — list results (optional discipline filter)
- `POST /api/results` — create a result (or update it when `id` is set; send `version`)
- `PUT /api/results/{id}` — update a result (send `version`)
- `DELETE /api/results/{id}?version=N` — delete a result

### Scoring and tie-breaks

- An individual result's score is the sum of its shots (`entries`); `value` is stored alongside. The result's `override_value` is a **tie-break value, and the lower value wins** (distance of the furthest shot from the centre); it no longer replaces the score. A result without it loses a tie against one with it.
- The results page records a result as the number of shots per ring (10, 9, … 0 for a miss), which must add up to 10 shots. It is saved as the list of the 10 shots, best ring first, so `entries` keeps its format.
- Individual ranking: each competitor once. Their results that count are every result in one of the ranking's single disciplines, and per sum entry the best result of each of its disciplines added up (as soon as there is one; a missing one counts as nothing; ring counts added up, tie-break the furthest shot of them). Ranked by the best of those results, then the next one (another start, or another entry of the ranking; another result beats none), then the best one's 10s, 9s, … 1s, then its tie-break (lower wins). Identical standings share the rank. The other results are listed below the competitor.

### Teams (`/api/teams`)

Backs the standalone page at `/tmgmt` and stores everything in `teams.json`.

- A team belongs to a team discipline (`level: "team"`) and has up to `team_size` members (3 unless the catalog says otherwise). A member is one registered start in one of the individual disciplines ticked as the team discipline's "results that count" (`team_of`, set on the Disciplines page), e.g. Nagashino takes Tanegashima original and reproduction starts, Nobunaga only original ones. Teams are entered only for the team disciplines of the meet's team rankings. A team ranking ranks the teams of one or more team disciplines together (team disciplines are not added up: teams exist per discipline).
- A competitor is in at most one team per team discipline, and at most once per team. The same start may count for different team disciplines that count the same discipline (e.g. *Gustav Adolph* and *Halikko*).
- A blank name becomes the club all members share, else the country they share, else `Team <id>`.
- Team ranking: the sum of the members' individual scores (max 300 for 3 × 100), then the number of 10s over all members' shots, then 9s, … 1s, then the team's `tie_break` (lower wins). Teams with identical values share the rank. A member without a result counts 0 and the team is flagged `complete: false`.

- `GET /api/teams?discipline_id=` — teams (optionally of one discipline) with their members resolved: competitor, club, country, score
- `GET /api/teams/disciplines` — every team discipline with its team size and the individual disciplines its members may come from
- `GET /api/teams/candidates?discipline_id=` — competitors with eligible starts (first start first), plus the team they are already in
- `POST /api/teams` — create a team: `discipline_id`, `name`, `members` (start ids), `tie_break`, `notes`
- `PUT /api/teams/{id}` — update name, members, tie-break and notes (send `version`; 409 if someone else saved first)
- `DELETE /api/teams/{id}?version=N` — delete a team
- `GET /api/teams/ranking/{disciplineId}` — ranked teams of a team discipline; `GET /api/ranking/{id}` returns the same (with `"kind": "team"`) for team disciplines

### Relay management (`/api/rmgmt`)

Backs the standalone page at `/rmgmt` and stores everything in `relays.json`.

- A **relay** (Durchgang) is one time slot in which all **ranges** fire at once. A range is a lane block: 25m with 15 lanes, 50m with 12, 100m with 8 (ids `m25`/`m50`/`m100`, lane counts editable in `relays.json`). Ranges are not the MLAIC disciplines — those keep living in `catalog.json`.
- The relay duration and the break between relays (default 15 min) are single meet-wide values, so a day's relay times follow from its start time. Files from before the break was meet-wide take the first day's break.
- A lane holds one **registered start** (e.g. `1-52-1`), created as usual in the competition management. `relays.json` only stores the start id; competitor and discipline are resolved from `data.json` on read.
- Each MLAIC discipline can be mapped to the range it fires on. A lane then only offers starts of that range; a discipline left on "any range" is offered everywhere. A range cannot be deleted while a discipline is still mapped to it.

Two rules are enforced on the server and also drive the lane dropdowns, so conflicting starts are not offered:

1. A registered start takes at most one lane (it is shot once).
2. A competitor has at most one lane per relay (all ranges fire simultaneously).

- `GET /api/rmgmt` — config, ranges, days, relays and assignments in one response (each discipline's range is its `shooting_distance` in `/api/available-disciplines`)
- `PUT /api/rmgmt/config` — set `relay_duration_min` and/or `break_min` (recalculates every day's start times)
- `POST /api/rmgmt/days`, `PUT /api/rmgmt/days/{id}`, `DELETE /api/rmgmt/days/{id}` — meet days with `date` and `start_time`; deleting a day removes its relays and assignments
- `PUT /api/rmgmt/days/{id}/lock` — lock or unlock a whole day, e.g. `{"locked": true}`; a locked day's date/time, relays and lane assignments cannot be changed
- `POST /api/rmgmt/days/{id}/relays` — append `count` relays to a day (no maximum per day)
- `GET /api/rmgmt/relays/{id}` — relay detail: one lane block per range with its current assignments
- `DELETE /api/rmgmt/relays/{id}` — delete a relay and its assignments; later relays of the day move up
- `GET /api/rmgmt/relays/{id}/available-starts?range_id={id}` — registered starts that may take a lane here
- `POST /api/rmgmt/assignments` — put a start in a lane (`relay_id`, `range_id`, `lane_no`, `start_id`); `409` with the reason on a rule violation, `400` if the start's discipline fires on another range. Send `expected_assignment_id` (the assignment the user saw, `null` for an empty lane) and the save is rejected with `409` if someone else changed that lane meanwhile
- `DELETE /api/rmgmt/assignments/{id}` — clear a lane
- `GET /api/rmgmt/competitors/{id}/schedule` — one competitor's lanes over the whole meet
- `GET /api/rmgmt/overview` — every competitor's starts, where each is scheduled, which still need a lane, plus any rule violation found in the stored data (safety net for a hand-edited `relays.json`, a deleted start, or a discipline remapped to another range afterwards)

### Meet (`/api/meet`)

Backs the page at `/meet` and stores everything in `meet.json`.

- `GET /api/meet` — `name`, `location`, `host`, `date_from`, `date_to` (`YYYY-MM-DD` or `null`) and `version`; empty until first saved
- `PUT /api/meet` — replace all of them (send `version`; 409 if someone else saved first, 400 for a bad date or a first day after the last)

When the dates are empty, the printouts use the first and last meet day of the relay management. The start card lists every start of a starter with day, relay, time, range and lane, then the starts that have no lane yet; below each discipline it names the meet's rankings the discipline counts in (e.g. Whitworth_O: Whitworth O/R, Whitworth O), and a second table lists the starter's teams with their team rankings. The starter ID printed on the start card and race bib is the competitor ID. Start cards and race bibs are printed sorted by country, then club, then name (starters without a country or club come last). The search field above each starter list filters it by starter ID, name, club or country (case- and accent-insensitive, every word must match); with "All matching starters" selected, only the matching starters are printed, and a search with a single match selects that starter.

The **Export Start Cards** and **Export Race Bibs** buttons ask for the format: **Print** opens the browser's print dialog (choose "Save as PDF" there for a PDF file), **Word (.docx)** downloads the same pages as a Word document to edit or print. The Ranking page's **Export** button offers Print, Word and Excel: the Word document has the ranking laid out like the printout (with the cover and statistics pages when chosen, and one discipline per page when that option is on); the Excel workbook has one sheet per discipline (team disciplines one row per team with its members) with numbers as numbers, and a Statistics sheet when the statistics are chosen. The last chosen format is remembered.

The **lane assignments CSV** (built in the browser from `/api/rmgmt/overview`) has one row per start, sorted by date, time, range and lane, with the columns Date (`YYYY-MM-DD`), Weekday, Relay, Start time, End time, Range, Lane, Start ID, Barcode (EAN-13), Starter ID, Name, Club, Country, Discipline, Event, Type, Category, Meet and Venue. It is UTF-8 with a byte order mark and semicolons (or commas) between the fields, so Excel and mail-merge label programs open it directly; starts without a lane can be added at the end with empty relay and lane fields.

The **Barcode (EAN-13)** column holds the start ID as a 13-digit EAN code for label programs to print as a barcode (e.g. Word's mail merge `{ MERGEBARCODE "Barcode (EAN-13)" EAN13 }`, P-touch Editor or BarTender): `2`, the competitor ID in 5 digits, the discipline ID in 3, the start number in 3 and the check digit, so `1-52-1` becomes `2000010520015`. The leading 2 is the GS1 prefix for in-house numbers. Scanning the barcode into the start search of the Results page finds the start, as if its start ID had been typed. Start IDs from old data files without dashes get no barcode. Excel shows 13-digit numbers in scientific notation; format the column as a number with 0 decimals to see all digits.

### Backup (`/api/backup`)
- `GET /api/backup` — all runtime data as a zip file (`Content-Disposition: attachment`)
- `POST /api/backup/restore` — restore from a backup; the request body is the zip file itself (e.g. `Content-Type: application/zip`). Returns `{"restored_files": [...], "safety_copy": "backups/pre-restore-….zip"}`; `400` with the reason if the file is not a valid backup

### External backup (`/api/backup/external`)
- `GET` — the settings (the token is never returned, only `token_set`) and the status: `last_success`, `last_backup_name`, `last_backup_size`, `last_error`, `last_error_at`, `pending`
- `PUT` — save `{enabled, url, token, debounceSeconds, maxDelaySeconds}` to `appsettings.json`; an empty `token` keeps the stored one; `400` with the reason if invalid
- `POST /test` — check that the receiver at the posted `url` accepts the `token` (the saved one if empty); `502` with the reason if not
- `POST /run` — push a backup now; `502` with the reason if the backup is off or the push failed

### Backup receiver (`--backup-server`, port 5100)
All requests need `Authorization: Bearer <token>`; `401` otherwise.
- `PUT /api/backups/mlaiccmanager-backup-<yyyyMMdd>-<HHmmss>.zip` — store a backup; optional `X-Content-SHA256` header; `400` if the name, checksum or zip is invalid, `413` above 50 MB
- `GET /api/backups` — the stored backups, newest first: `[{"name": ..., "size": ..., "time": ...}]`
- `GET /api/backups/{name}` — download one

### Danger zone (`/api/danger-zone`)
- `GET /api/danger-zone` — how much there is of each kind of data: `results`, `starts`, `competitors`, `lanes`, `days`, `relays`, `teams`, `meet` (1 if saved), `active_disciplines`, `added_disciplines`
- `POST /api/danger-zone/clear/{target}` — clear `results`, `lanes`, `starts`, `competitors`, `teams`, `days`, `meet` or `disciplines` (reset to the catalog) and what depends on it (see [Danger Zone](#danger-zone)). Returns `{"target": ..., "cleared": {"starts": 12, "results": 8, ...}, "safety_copy": "backups/pre-clear-….zip"}`; `400` for an unknown target

## Project Structure

```
src/main/java/com/competition/
├── Main.java              # Jetty server bootstrap
├── config/                 # Jersey/CORS wiring
├── model/                  # Competitor, Discipline, Result, Start, Ranking
├── resource/                # JAX-RS REST endpoints
└── service/                 # Business logic / data access
src/main/resources/
├── application.properties
└── logback.xml
static/                     # Frontend assets served at / (plain HTML/CSS/JS, Tailwind via CDN)
├── index.html               # Competitors, starts and result entry
├── js/                       # Vanilla JS: shared sidebar (appNav.js) and modules per feature (competitors, starts, results)
├── ranking/                  # Printable one-result-per-discipline ranking page, served at /ranking
├── results/                  # Enter Results page on its own, served at /results and on port 5001
├── dmgmt/                    # Discipline management page, served at /dmgmt
├── rmgmt/                    # Relay management page, served at /rmgmt
├── meet/                     # Meet details, start cards, race bibs and lane CSV, served at /meet
├── backup/                   # Backup & restore page, served at /backup
├── danger/                   # Danger Zone page (clear data), served at /danger
└── tmgmt/                    # Team management page, served at /tmgmt
```

## Deployment

Releases are built by GitHub Actions and deployed to a Proxmox LXC container, which auto-pulls new builds. See [deploy/setup.sh](deploy/setup.sh) for one-time container setup.

Useful commands on the container:

```bash
systemctl status mlaiccmanager
systemctl start mlaiccmanager-deploy.service   # deploy right now
journalctl -u mlaiccmanager-deploy.service -f  # watch a deploy run
journalctl -u mlaiccmanager -f                 # app logs
```

### Windows (portable)

Every release also includes a self-contained Windows zip with Java bundled. Nothing needs to be installed and no admin rights are needed:

1. Download [mlaiccmanager-windows.zip](https://github.com/sgusenba/mlaiccmanager/releases/download/latest/mlaiccmanager-windows.zip). If Windows blocks it, right-click the zip, choose **Properties → Unblock**, then unzip it.
2. Unzip it to a local folder such as `C:\mlaiccmanager`. Don't use a OneDrive-synced Desktop or Documents folder: sync locks can break the app's saves.
3. Double-click `start.bat`. The app opens in the browser at http://localhost:5000. Closing the console window stops it. If tablets on the network need access, allow Java in the Windows Firewall prompt. Result entry stations can use port 5001, which shows only the Enter Results page.

On every start, `start.bat` checks for a newer build and swaps it in. It only replaces `mlaiccmanager.jar`, `static\` and the shipped defaults (`default-disciplines.json`, `default-rankings.json`), so your data, catalog and rankings are never touched. When offline, it simply starts the current version. To freeze the version, for example during a competition, create an empty file named `no-auto-update` in the folder. All data (`data.json`, `disciplines.json`, `rankings.json`, `logs\`, …) lives in that folder. To upgrade the bundled Java, download the zip again and copy your data files over.

## License

This project is open source and available under the MIT License.
