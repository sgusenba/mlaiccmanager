# MLAICC Manager

A Java backend for managing historical firearms shooting competitions (MLAIC events). It handles competitors, starts, results, and rankings for historical rifle and pistol competitions, with support for original, reproduction, and combined categories.

This is a Java/Jetty/Jersey implementation of the same competition-management concept as the [Python/Flask version](https://github.com/sgusenba/windsurf-project).

## Features

- **Competitor management** — add, edit, and delete competitors with full details (name, gender, club, email, phone, address, year of birth)
- **Start management** — register competitors for specific disciplines with unique start IDs
- **Discipline configuration** — pre-configured historical firearms disciplines (rifle and pistol, original/reproduction/combined, individual/team)
- **Results management** — record detailed results with individual scoring entries and override values
- **Rankings** — automatically sorted rankings with tie-breaking support
- **Team management** — separate page at `/tmgmt` for building the teams of the team disciplines (e.g. *Gustav Adolph*) from registered starts, whose team ranking shows up on the Ranking page
- **Discipline management** — separate page at `/dmgmt` for CRUD on the discipline catalog, including shooting distance
- **Ranking page** — separate page at `/ranking` that shows just one result per competitor and discipline (the best one) and prints cleanly or exports it as a Word or Excel file, optionally one discipline per page
- **Relay management** — separate page at `/rmgmt` for planning meet days, relays (Durchgänge) and which registered start shoots on which lane of the 25m/50m/100m ranges
- **Meet details and printouts** — separate page at `/meet` for the meet's name, venue, host and dates, which prints or exports as a Word document a start card (one A4 page per starter with their relays and lanes) and a race bib (A4 landscape) per starter, and exports all lane assignments as a CSV file (e.g. for target labels); the ranking can be printed or exported with a cover page and a statistics page (starters and starts per country and discipline)
- **Backup & restore** — separate page at `/backup` to download all data as one zip file and to restore it from one
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

## Frontend

The frontend is plain HTML, vanilla JavaScript, and Tailwind (via CDN) — no build step, no framework. It lives entirely under `static/` and is served as-is by Jetty.

Every page shares one sidebar (`static/js/appNav.js`, styles in `static/style.css`), grouped in the order a competition runs in, with the one-off setup (Management) last. On narrow screens it folds into a menu button:

| Group | Entry | Page |
|---|---|---|
| **Starters** | Competitors | `/#competitors` |
| | Starts | `/#starts` |
| | Teams | `/tmgmt/` |
| | Lane Assignment | `/rmgmt/#assignment` |
| | Starter Overview | `/rmgmt/#overview` |
| **Results** | Enter Results | `/#results` |
| **Rankings** | Ranking | `/ranking/` |
| **Management** | Meet | `/meet/` |
| | Disciplines | `/dmgmt/` |
| | Ranges & Relays | `/rmgmt/#settings` |
| | Meet Days | `/rmgmt/#schedule` |
| | Backup & Restore | `/backup/` |

A new entry is one line in `GROUPS` in `appNav.js`; a page takes part by putting `class="has-sidebar"` on `<body>` and loading `appNav.js`.

- **`/`** (`static/index.html` + `static/js/`) — competitors, starts and result entry, split into modules under `static/js/modules/`
- **`/ranking`** (`static/ranking/`) — the ranking: printable, one result per competitor and discipline, team disciplines with their team ranking (`static/js/teamRanking.js`); its Export button offers Print, Word (.docx) or Excel (.xlsx) and whether to add the cover and statistics pages (`static/ranking/printPages.js`, files built by `static/ranking/rankingExport.js`)
- **`/dmgmt`** (`static/dmgmt/`) — discipline management page
- **`/rmgmt`** (`static/rmgmt/`) — relay management page
- **`/tmgmt`** (`static/tmgmt/`) — team management page
- **`/meet`** (`static/meet/`) — meet details, start cards, race bibs (printed or as Word documents, `static/meet/wordExport.js`) and the lane assignments CSV (`static/meet/laneExport.js`); the meet details and dates for all printouts come from `static/js/meet.js`; Word and Excel files are written in the browser without any library by `static/js/officeFiles.js`, so the export also works offline
- **`/backup`** (`static/backup/`) — backup download and restore

Each page talks to the backend directly via `fetch` calls to the `/api` endpoints described below.

## Data Storage

- **`data.json`** — competitors (with their starts) and results. Created automatically on first run. Contains real personal data, so it is git-ignored — never commit it. Ids that follow from other data are not stored: a start's discipline comes from the key it is filed under, and a result's competitor and discipline from its start.
- **`disciplines.json`** — the MLAIC discipline catalog (event names, categories, levels, default shooting distance). Tracked in the repo, shipped with every release and replaced on every deploy, so the app never writes to it.
- **`competition.json`** — what this competition changes on top of the catalog: the active disciplines, edited fields (e.g. a different shooting distance), disciplines added (ids from 1000 up) or removed on the discipline management page. Only differences are stored, so a new catalog release still comes through. Created on first start (from the old `active_disciplines` in `data.json`, or from `disciplines.previous.json`, the runtime-edited catalog the deploy script saves aside once); git-ignored.
- **`teams.json`** — the teams of the team disciplines (name, member start ids, tie-break value, notes). Created on the first saved team; git-ignored like `data.json`. Members are only referenced by start id.
- **`meet.json`** — the meet's name, venue, host and optional first/last day. Created on the first save on the Meet page; git-ignored like `data.json`.
- **`relays.json`** — everything about relays (meet days, relays, lane assignments, ranges with their lane counts, relay duration and break, locked days), kept separate from `data.json`. Created automatically on first use of the relay management page; git-ignored like `data.json`. Competitors and their starts are not copied into it, only referenced by id.

### Backup and restore

The **Backup & Restore** page (`/backup`) downloads `data.json`, `competition.json`, `teams.json`, `relays.json` and `meet.json` as one zip file (`mlaiccmanager-backup-<date>-<time>.zip`, plus a `backup-info.json` with the time it was taken). The catalog `disciplines.json` is not included, since it ships with every release. The zip contains the competitors' personal data, so keep it safe.

Restoring a backup replaces all five files; a file the backup does not contain is removed, so the app is exactly in the state the backup was taken in. Before that, the current files are saved to `backups/pre-restore-<date>-<time>.zip` next to `data.json`, so a restore can be undone by restoring that file. An upload that is not a zip, has no `data.json` or holds a file that is not a JSON object is rejected and changes nothing. Backup and restore hold every file's lock, so they never see or leave a half-saved state. The page does not refresh other open pages: reload them after a restore.

## Multiple Users

Several people can use the app at the same time:

- All reads and writes of `data.json` are serialized on the server, so simultaneous saves never overwrite each other.
- Competitors and results carry a `version` number. Updates and deletes send back the version the user saw; if someone else saved the record in the meantime, the server answers **409 Conflict** with `{"error": ..., "current": <latest record>}` and the UI loads the latest data so the user can redo the change. Requests without `version` skip this check.
- Entering a second result for the same start also returns 409 with the existing result.
- Updating or deleting a record that no longer exists returns **404**.

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

### Disciplines
- `GET /api/active-disciplines` — get currently active disciplines (stored in `competition.json`)
- `POST /api/active-disciplines` — set active disciplines, flipping each catalog entry's `active` flag (optional `base_ids`: the list the change is based on; 409 if it changed meanwhile)
- `DELETE /api/active-disciplines/{id}` — deactivate a single discipline
- `GET /api/available-disciplines` — get all disciplines (the catalog with this competition's changes applied)
- `PUT /api/available-disciplines/shooting-distances` — map disciplines to the range they fire on, e.g. `{"shooting_distances": {"52": "m25"}}`; only the listed disciplines change, and an empty string clears the mapping so the discipline may be assigned to any range

### Results (`/api/results`)
- `GET /api/results` — list results (optional discipline filter)
- `POST /api/results` — create a result (or update it when `id` is set; send `version`)
- `PUT /api/results/{id}` — update a result (send `version`)
- `DELETE /api/results/{id}?version=N` — delete a result

### Rankings (`/api/ranking`)
- `GET /api/ranking` — list rankings
- `GET /api/ranking/{disciplineId}` — get ranked results for a discipline
- `GET /api/ranking/best` — like `GET /api/ranking`, but with one result per competitor: each competitor's best result
- `GET /api/ranking/best/{disciplineId}` — one discipline's ranking with one result per competitor (team disciplines: the team ranking)

### Scoring and tie-breaks

- An individual result's score is the sum of its shots (`entries`); `value` is stored alongside. The result's `override_value` is a **tie-break value, and the lower value wins** (distance of the furthest shot from the centre); it no longer replaces the score. A result without it loses a tie against one with it.
- The results page records a result as the number of shots per ring (10, 9, … 0 for a miss), which must add up to 10 shots. It is saved as the list of the 10 shots, best ring first, so `entries` keeps its format.
- Individual ranking: best four results, then the number of 10s, 9s, … 7s, then the tie-break.
- One-result ranking (`/api/ranking/best`, `/ranking` page): each competitor's best result only, ranked by its score, then its 10s, 9s, … 1s, then its tie-break (lower wins). Identical results share the rank.

### Teams (`/api/teams`)

Backs the standalone page at `/tmgmt` and stores everything in `teams.json`.

- A team belongs to a team discipline (`level: "team"`) and has up to `team_size` members (3 unless the catalog says otherwise). A member is one registered start in the individual discipline the team discipline is `based_on`, of the same category; original teams take original starts, reproduction teams reproduction starts, open teams any. If `based_on` names no event (e.g. an aggregate), every individual discipline of the category is accepted.
- A competitor is in at most one team per team discipline, and at most once per team. The same start may count for different team disciplines based on the same event (e.g. *Gustav Adolph* and *Halikko*).
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

- A **relay** (Durchgang) is one time slot in which all **ranges** fire at once. A range is a lane block: 25m with 15 lanes, 50m with 12, 100m with 8 (ids `m25`/`m50`/`m100`, lane counts editable in `relays.json`). Ranges are not the MLAIC disciplines — those keep living in `disciplines.json`.
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

When the dates are empty, the printouts use the first and last meet day of the relay management. The start card lists every start of a starter with day, relay, time, range and lane, then the starts that have no lane yet. The starter ID printed on the start card and race bib is the competitor ID. Start cards and race bibs are printed sorted by country, then club, then name (starters without a country or club come last). The search field above each starter list filters it by starter ID, name, club or country (case- and accent-insensitive, every word must match); with "All matching starters" selected, only the matching starters are printed, and a search with a single match selects that starter.

The **Export Start Cards** and **Export Race Bibs** buttons ask for the format: **Print** opens the browser's print dialog (choose "Save as PDF" there for a PDF file), **Word (.docx)** downloads the same pages as a Word document to edit or print. The Ranking page's **Export** button offers Print, Word and Excel: the Word document has the ranking laid out like the printout (with the cover and statistics pages when chosen, and one discipline per page when that option is on); the Excel workbook has one sheet per discipline (team disciplines one row per team with its members) with numbers as numbers, and a Statistics sheet when the statistics are chosen. The last chosen format is remembered.

The **lane assignments CSV** (built in the browser from `/api/rmgmt/overview`) has one row per start, sorted by date, time, range and lane, with the columns Date (`YYYY-MM-DD`), Weekday, Relay, Start time, End time, Range, Lane, Start ID, Starter ID, Name, Club, Country, Discipline, Event, Type, Category, Meet and Venue. It is UTF-8 with a byte order mark and semicolons (or commas) between the fields, so Excel and mail-merge label programs open it directly; starts without a lane can be added at the end with empty relay and lane fields.

### Backup (`/api/backup`)
- `GET /api/backup` — all runtime data as a zip file (`Content-Disposition: attachment`)
- `POST /api/backup/restore` — restore from a backup; the request body is the zip file itself (e.g. `Content-Type: application/zip`). Returns `{"restored_files": [...], "safety_copy": "backups/pre-restore-….zip"}`; `400` with the reason if the file is not a valid backup

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
├── dmgmt/                    # Discipline management page, served at /dmgmt
├── rmgmt/                    # Relay management page, served at /rmgmt
├── meet/                     # Meet details, start cards, race bibs and lane CSV, served at /meet
├── backup/                   # Backup & restore page, served at /backup
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
3. Double-click `start.bat`. The app opens in the browser at http://localhost:5000. Closing the console window stops it. If tablets on the network need access, allow Java in the Windows Firewall prompt.

On every start, `start.bat` checks for a newer build and swaps it in. It only replaces `mlaiccmanager.jar`, `static\` and `disciplines.json`, so your data is never touched. When offline, it simply starts the current version. To freeze the version, for example during a competition, create an empty file named `no-auto-update` in the folder. All data (`data.json`, `competition.json`, `logs\`, …) lives in that folder. To upgrade the bundled Java, download the zip again and copy your data files over.

## License

This project is open source and available under the MIT License.
