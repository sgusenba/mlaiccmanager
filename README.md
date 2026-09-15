# MLAICC Manager

A Java backend for managing historical firearms shooting competitions (MLAIC events). It handles competitors, starts, results, and rankings for historical rifle and pistol competitions, with support for original, reproduction, and combined categories.

This is a Java/Jetty/Jersey implementation of the same competition-management concept as the [Python/Flask version](https://github.com/sgusenba/windsurf-project).

## Features

- **Competitor management** — add, edit, and delete competitors with full details (name, gender, club, email, phone, address, year of birth)
- **Start management** — register competitors for specific disciplines with unique start IDs
- **Discipline configuration** — pre-configured historical firearms disciplines (rifle and pistol, original/reproduction/combined, individual/team)
- **Results management** — record detailed results with individual scoring entries and override values
- **Rankings** — automatically sorted rankings with tie-breaking support
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
- `POST /api/competitors/{competitorId}/starts` — create a start for a competitor
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
