# MLAICC Manager

A Java backend for managing historical firearms shooting competitions (MLAIC events). It handles competitors, starts, results, and rankings for historical rifle and pistol competitions, with support for original, reproduction, and combined categories.

This is a Java/Jetty/Jersey implementation of the same competition-management concept as the [Python/Flask version](https://github.com/sgusenba/windsurf-project).

## Features

- **Competitor management** — add, edit, and delete competitors with full details (name, gender, club, email, phone, address, year of birth)
- **Start management** — register competitors for specific disciplines with unique start IDs
- **Discipline configuration** — pre-configured historical firearms disciplines (rifle and pistol, original/reproduction/combined, individual/team)
- **Results management** — record detailed results with individual scoring entries and override values
- **Rankings** — automatically sorted rankings with tie-breaking support
- **Relay management** — separate page at `/rmgmt` for planning meet days, relays (Durchgänge) and which registered start shoots on which lane of the 25m/50m/100m ranges
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

## Data Storage

- **`data.json`** — all competition-specific data (competitors, starts, results, active disciplines). Created automatically on first run. Contains real personal data, so it is git-ignored — never commit it.
- **`disciplines.json`** — pre-configured MLAIC discipline definitions (event names, categories, levels). Tracked in the repo as shared configuration.
- **`relays.json`** — everything about relays (meet days, relays, lane assignments, ranges with their lane counts, the discipline-to-range mapping, relay duration), kept separate from `data.json`. Created automatically on first use of the relay management page; git-ignored like `data.json`. Competitors and their starts are not copied into it, only referenced by id.

## Multiple Users

Several people can use the app at the same time:

- All reads and writes of `data.json` are serialized on the server, so simultaneous saves never overwrite each other.
- Competitors and results carry a `version` number. Updates and deletes send back the version the user saw; if someone else saved the record in the meantime, the server answers **409 Conflict** with `{"error": ..., "current": <latest record>}` and the UI loads the latest data so the user can redo the change. Requests without `version` skip this check.
- Entering a second result for the same start also returns 409 with the existing result.
- Updating or deleting a record that no longer exists returns **404**.

Browsers do not refresh on their own; other users' changes show up when switching sections or reloading the page.

## API Endpoints

### Competitors (`/api/competitors`)
- `GET /api/competitors` — list all competitors
- `POST /api/competitors` — create a competitor (or update it when `id` is set; send `version`)
- `PUT /api/competitors/{id}` — update a competitor's details (send `version`; starts are not changed here)
- `DELETE /api/competitors/{id}?version=N` — delete a competitor

### Starts (`/api/competitors/{competitorId}/starts`)
- `POST /api/competitors/{competitorId}/starts` — create a start for a competitor. The start ID is `competitorId-disciplineId-startNumber` (e.g. `1-52-1`); starts created before this format keep their old unseparated IDs. Returns `409` if the ID is already used by any start or result.
- `DELETE /api/competitors/{competitorId}/starts/{generatedId}` — delete a start

### Disciplines
- `GET /api/active-disciplines` — get currently active disciplines
- `POST /api/active-disciplines` — set active disciplines (optional `base_ids`: the list the change is based on; 409 if it changed meanwhile)
- `DELETE /api/active-disciplines/{id}` — deactivate a single discipline
- `GET /api/available-disciplines` — get all available disciplines from config
- `GET /api/disciplines` — list disciplines
- `POST /api/disciplines` — create a discipline
- `PUT /api/disciplines/{id}` — update a discipline
- `DELETE /api/disciplines/{id}` — delete a discipline

### Results (`/api/results`)
- `GET /api/results` — list results (optional discipline filter)
- `POST /api/results` — create a result (or update it when `id` is set; send `version`)
- `PUT /api/results/{id}` — update a result (send `version`)
- `DELETE /api/results/{id}?version=N` — delete a result

### Rankings (`/api/ranking`)
- `GET /api/ranking` — list rankings
- `GET /api/ranking/{disciplineId}` — get ranked results for a discipline

### Relay management (`/api/rmgmt`)

Backs the standalone page at `/rmgmt` and stores everything in `relays.json`.

- A **relay** (Durchgang) is one time slot in which all **ranges** fire at once. A range is a lane block: 25m with 15 lanes, 50m with 12, 100m with 8 (ids `m25`/`m50`/`m100`, lane counts editable in `relays.json`). Ranges are not the MLAIC disciplines — those keep living in `disciplines.json`.
- The relay duration is a single meet-wide value, so a day's relay times follow from its start time.
- A lane holds one **registered start** (e.g. `1-52-1`), created as usual in the competition management. `relays.json` only stores the start id; competitor and discipline are resolved from `data.json` on read.
- Each MLAIC discipline can be mapped to the range it fires on. A lane then only offers starts of that range; a discipline left on "any range" is offered everywhere.

Two rules are enforced on the server and also drive the lane dropdowns, so conflicting starts are not offered:

1. A registered start takes at most one lane (it is shot once).
2. A competitor has at most one lane per relay (all ranges fire simultaneously).

- `GET /api/rmgmt` — config, ranges, disciplines with their range, days, relays and assignments in one response
- `PUT /api/rmgmt/config` — set `relay_duration_min` (recalculates every day's start times)
- `PUT /api/rmgmt/discipline-ranges` — replace the discipline-to-range mapping, e.g. `{"discipline_ranges": {"52": "m25"}}` (a discipline left out, or set to `null`, may be assigned to any range)
- `POST /api/rmgmt/days`, `PUT /api/rmgmt/days/{id}`, `DELETE /api/rmgmt/days/{id}` — meet days (deleting a day removes its relays and assignments)
- `POST /api/rmgmt/days/{id}/relays` — append `count` relays to a day (no maximum per day)
- `GET /api/rmgmt/relays/{id}` — relay detail: one lane block per range with its current assignments
- `DELETE /api/rmgmt/relays/{id}` — delete a relay and its assignments; later relays of the day move up
- `GET /api/rmgmt/relays/{id}/available-starts?range_id={id}` — registered starts that may take a lane here
- `POST /api/rmgmt/assignments` — put a start in a lane (`relay_id`, `range_id`, `lane_no`, `start_id`); `409` with the reason on a rule violation, `400` if the start's discipline fires on another range. Send `expected_assignment_id` (the assignment the user saw, `null` for an empty lane) and the save is rejected with `409` if someone else changed that lane meanwhile
- `DELETE /api/rmgmt/assignments/{id}` — clear a lane
- `GET /api/rmgmt/competitors/{id}/schedule` — one competitor's lanes over the whole meet
- `GET /api/rmgmt/overview` — every competitor's starts, where each is scheduled, which still need a lane, plus any rule violation found in the stored data (safety net for a hand-edited `relays.json`, a deleted start, or a discipline remapped to another range afterwards)

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
static/                     # Frontend assets served at /
└── rmgmt/                   # Relay management page, served at /rmgmt
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

## License

This project is open source and available under the MIT License.
