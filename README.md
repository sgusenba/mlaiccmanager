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

## API Endpoints

### Competitors (`/api/competitors`)
- `GET /api/competitors` — list all competitors
- `POST /api/competitors` — create a competitor
- `PUT /api/competitors/{id}` — update a competitor
- `DELETE /api/competitors/{id}` — delete a competitor

### Starts (`/api/competitors/{competitorId}/starts`)
- `POST /api/competitors/{competitorId}/starts` — create a start for a competitor
- `DELETE /api/competitors/{competitorId}/starts/{generatedId}` — delete a start

### Disciplines
- `GET /api/active-disciplines` — get currently active disciplines
- `POST /api/active-disciplines` — set active disciplines
- `GET /api/available-disciplines` — get all available disciplines from config
- `GET /api/disciplines` — list disciplines
- `POST /api/disciplines` — create a discipline
- `PUT /api/disciplines/{id}` — update a discipline
- `DELETE /api/disciplines/{id}` — delete a discipline

### Results (`/api/results`)
- `GET /api/results` — list results (optional discipline filter)
- `POST /api/results` — create a result
- `PUT /api/results/{id}` — update a result
- `DELETE /api/results/{id}` — delete a result

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

## License

This project is open source and available under the MIT License.
