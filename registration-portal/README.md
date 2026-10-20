# MLAIC Registration Portal

Online registration for MLAIC meets: clubs log in with an access code and enter their starters, the organiser downloads them as one `mlaic-registrations` file and imports it on the MLAICC Manager's **Import Registrations** page (`/import/`). The portal is the only part on the internet; the Manager has no login and stays offline at the range. The two only exchange files.

The plan, with the decisions taken so far, is in [`../docs/registration-portal-plan.md`](../docs/registration-portal-plan.md).

**Status:** phase 1 (skeleton): server, storage and health check. Log-in, meets, the entry sheet and the export follow in the next phases.

## Build and run

The same stack as the Manager (Java 17 bytecode, Jetty, Jersey, Jackson, JUnit 5), but a Maven project of its own: the Manager's build and release bundle stay untouched.

```bash
cd registration-portal
mvn clean verify                      # build and test
java -jar target/mlaic-portal-1.0.0.jar
```

The portal then listens on `http://127.0.0.1:8080` and keeps its data in `portal-data/` (git-ignored: it holds personal data). `GET /api/health` answers `{"ok":true}` while it runs and can read its data, else 503.

The jar is pure Java, so the one built on x64 runs unchanged on Oracle Cloud's ARM (Ampere) VMs.

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

## Release

`.github/workflows/portal-release.yml` builds the portal on every push to `main` that touches `registration-portal/` and publishes `mlaic-portal-dist.tar.gz` (jar and `static/`) on the release tag `portal-latest`. The Manager's release (`release.yml`, tag `latest`) skips portal-only changes, so they never redeploy the installations at the ranges.
