#!/usr/bin/env python3
"""
Feeds an event's results (the JSON written by extract_results.py) into a
running competition manager through its REST API (see static/openapi.yaml).

    python feed.py data/vl-stm-2026.json --host 192.168.1.20 --port 5000

Steps, each safe to repeat (a second run updates instead of duplicating):
  1. disciplines  find each by category/level/type/event, create the missing
                  ones and activate all of them
  2. competitors  matched by name and club, created if missing
  3. starts       one start per competitor and discipline
  4. results      shots rebuilt from the ring counts, tie-break as override
  5. teams        matched by discipline and name, created or updated
  6. --verify     compare the server's rankings with the ranks in the JSON

Only the Python standard library is needed.
"""
import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request

RINGS = [10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0]


class ApiError(Exception):
    def __init__(self, method, path, status, body):
        super().__init__(f"{method} {path} -> {status}: {body}")
        self.status = status
        self.body = body


class Api:
    def __init__(self, base_url, dry_run=False, verbose=False):
        self.base_url = base_url.rstrip("/")
        self.dry_run = dry_run
        self.verbose = verbose
        self.fake_id = 0

    def request(self, method, path, body=None, params=None):
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        if self.dry_run and method != "GET":
            self.fake_id -= 1
            if self.verbose:
                print(f"  [dry-run] {method} {path} {json.dumps(body, ensure_ascii=False) if body else ''}")
            return {"id": self.fake_id, "generated_id": f"dry{self.fake_id}", "version": 0}
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("Accept", "application/json")
        if data is not None:
            req.add_header("Content-Type", "application/json; charset=utf-8")
        if self.verbose:
            print(f"  {method} {url}")
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                text = resp.read().decode("utf-8")
                return json.loads(text) if text else None
        except urllib.error.HTTPError as e:
            raise ApiError(method, path, e.code, e.read().decode("utf-8", "replace")) from None

    def get(self, path, **params):
        return self.request("GET", path, params=params or None)

    def post(self, path, body):
        return self.request("POST", path, body)

    def put(self, path, body):
        return self.request("PUT", path, body)


def norm(value):
    return (value or "").strip().casefold()


def discipline_key(d):
    return (norm(d.get("category")), norm(d.get("level")), norm(d.get("type")), norm(d.get("event")))


def shots(counts):
    """Ring counts {"10": 4, "9": 6, ...} -> [10, 10, 10, 10, 9, ...]."""
    return [float(ring) for ring in RINGS for _ in range(counts.get(str(ring), 0))]


class Feeder:
    def __init__(self, api, data):
        self.api = api
        self.data = data
        self.discipline_ids = {}   # JSON key -> discipline id
        self.competitor_ids = {}   # bib -> competitor id
        self.start_ids = {}        # (JSON discipline key, bib) -> start id
        self.stats = {}

    def count(self, what):
        self.stats[what] = self.stats.get(what, 0) + 1

    # -------------------------------------------------------------- steps

    def disciplines(self):
        print("Disciplines")
        catalog = {discipline_key(d): d for d in self.api.get("/available-disciplines")}
        for spec in self.data["disciplines"]:
            existing = catalog.get(discipline_key(spec))
            if existing:
                self.discipline_ids[spec["key"]] = existing["id"]
                self.count("disciplines found")
                continue
            body = {k: v for k, v in spec.items() if k != "key" and v is not None}
            created = self.api.post("/available-disciplines", body)
            self.discipline_ids[spec["key"]] = created["id"]
            self.count("disciplines created")
            print(f"  created {spec['category']} {spec['level']} {spec['type']} '{spec['event']}' -> id {created['id']}")

        active = self.api.get("/active-disciplines")
        wanted = sorted(set(active) | set(self.discipline_ids.values()))
        if set(wanted) != set(active):
            self.api.post("/active-disciplines", {"discipline_ids": wanted, "base_ids": active})
            print(f"  activated {len(set(wanted) - set(active))} disciplines")

    def competitors(self):
        print("Competitors")
        existing = {(norm(c["name"]), norm(c.get("club"))): c for c in self.api.get("/competitors")}
        self.competitor_rows = {}
        for spec in self.data["competitors"]:
            c = existing.get((norm(spec["name"]), norm(spec["club"])))
            if c is None:
                c = self.api.post("/competitors", {
                    "name": spec["name"], "club": spec["club"], "country": spec.get("country", ""),
                    "gender": spec.get("gender", ""), "year_of_birth": spec.get("year_of_birth", ""),
                    "email": "", "phone": "", "address": "",
                })
                c.setdefault("starts", {})
                self.count("competitors created")
            else:
                self.count("competitors found")
            self.competitor_ids[spec["bib"]] = c["id"]
            self.competitor_rows[spec["bib"]] = c

    def starts(self):
        print("Starts")
        for r in self.data["results"]:
            bib = r["bib"]
            discipline_id = self.discipline_ids[r["discipline"]]
            competitor = self.competitor_rows[bib]
            registered = (competitor.get("starts") or {}).get(str(discipline_id)) or []
            if registered:
                start_id = registered[0]["generated_id"]
                self.count("starts found")
            else:
                start = self.api.post(f"/competitors/{competitor['id']}/starts", {"discipline_id": discipline_id})
                start_id = start["generated_id"]
                competitor.setdefault("starts", {})[str(discipline_id)] = [start]
                self.count("starts created")
            self.start_ids[(r["discipline"], bib)] = start_id

    def results(self):
        print("Results")
        existing = {x.get("start_id"): x for x in self.api.get("/results")}
        for r in self.data["results"]:
            if r.get("dns"):
                self.count("results skipped (DNS)")
                continue
            start_id = self.start_ids[(r["discipline"], r["bib"])]
            notes = []
            if r.get("marker"):
                notes.append({"O": "Original", "R": "Replika"}.get(r["marker"], r["marker"]))
            body = {
                "competitor_id": self.competitor_ids[r["bib"]],
                "discipline_id": self.discipline_ids[r["discipline"]],
                "start_id": start_id,
                "value": r["total"],
                "entries": shots(r["counts"]),
                "override_value": r.get("tie_break"),
                "notes": ", ".join(notes),
            }
            current = existing.get(start_id)
            if current is None:
                self.api.post("/results", body)
                self.count("results created")
            elif same_result(current, body):
                self.count("results unchanged")
            else:
                body["id"] = current["id"]
                body["version"] = current.get("version")
                self.api.put(f"/results/{current['id']}", body)
                self.count("results updated")

    def teams(self):
        print("Teams")
        existing = {(t["discipline_id"], norm(t["name"])): t for t in self.api.get("/teams")}
        for t in self.data["teams"]:
            discipline_id = self.discipline_ids[t["discipline"]]
            members = [self.start_ids[(m["discipline"], m["bib"])] for m in t["members"]]
            body = {"name": t["name"], "members": members, "tie_break": None, "notes": ""}
            current = existing.get((discipline_id, norm(t["name"])))
            try:
                if current is None:
                    self.api.post("/teams", {"discipline_id": discipline_id, **body})
                    self.count("teams created")
                elif sorted(m["start_id"] for m in current.get("members", [])) == sorted(members):
                    self.count("teams unchanged")
                else:
                    self.api.put(f"/teams/{current['id']}", {**body, "version": current.get("version")})
                    self.count("teams updated")
            except ApiError as e:
                self.count("teams failed")
                print(f"  ! {t['discipline']} / {t['name']}: {e.status} {e.body}")

    # -------------------------------------------------------------- verify

    def verify(self):
        """Compares the server's rankings with the JSON's ranks (tied rows may come in any order)."""
        print("Verify")
        bib_of = {cid: bib for bib, cid in self.competitor_ids.items()}
        problems = 0
        for key, discipline_id in self.discipline_ids.items():
            expected_individual = [r for r in self.data["results"] if r["discipline"] == key and not r.get("dns")]
            expected_teams = [t for t in self.data["teams"] if t["discipline"] == key]
            try:
                ranking = self.api.get(f"/ranking/{discipline_id}")
            except ApiError as e:
                print(f"  ! {key}: {e}")
                problems += 1
                continue
            rows = ranking.get("rankings", [])
            if ranking.get("kind") == "team":
                got = [norm(row["name"]) for row in rows]
                want = sorted(expected_teams, key=lambda t: t["rank"])
                want_groups = [[norm(t["name"])] for t in want]
            else:
                got = [bib_of.get(row["competitor"]["id"]) for row in rows]
                want = sorted(expected_individual, key=lambda r: r["rank"])
                groups = {}
                for r in want:
                    groups.setdefault(r["rank"], []).append(r["bib"])
                want_groups = list(groups.values())
            mismatch = self.compare(got, want_groups)
            if mismatch:
                problems += 1
                print(f"  ✗ {key}: {mismatch}")
            else:
                print(f"  ✓ {key} ({len(got)} ranked)")
        print(f"  {problems} discipline(s) differ from the PDF" if problems else "  all rankings match the PDF")
        return problems

    @staticmethod
    def compare(got, want_groups):
        pos = 0
        for group in want_groups:
            chunk = got[pos:pos + len(group)]
            if sorted(map(str, chunk)) != sorted(map(str, group)):
                return f"position {pos + 1}: expected {group}, got {chunk}"
            pos += len(group)
        if pos != len(got):
            return f"{len(got) - pos} extra ranked entries: {got[pos:]}"
        return None


def same_result(current, body):
    return (float(current.get("value") or 0) == float(body["value"])
            and [float(x) for x in current.get("entries") or []] == body["entries"]
            and current.get("override_value") == body["override_value"]
            and (current.get("notes") or "") == body["notes"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("json_file", help="event JSON (see extract_results.py)")
    parser.add_argument("--host", default="localhost", help="server IP or host name (default: localhost)")
    parser.add_argument("--port", type=int, default=5000, help="server port (default: 5000)")
    parser.add_argument("--scheme", default="http", choices=["http", "https"])
    parser.add_argument("--api-path", default="/api", help="API base path (default: /api)")
    parser.add_argument("--skip-teams", action="store_true", help="do not create teams")
    parser.add_argument("--verify", action="store_true", help="compare the server's rankings with the JSON afterwards")
    parser.add_argument("--verify-only", action="store_true", help="only look up ids and verify, change nothing")
    parser.add_argument("--dry-run", action="store_true", help="read from the server but send no changes")
    parser.add_argument("-v", "--verbose", action="store_true", help="print every request")
    args = parser.parse_args()

    with open(args.json_file, encoding="utf-8") as f:
        data = json.load(f)

    base_url = f"{args.scheme}://{args.host}:{args.port}{args.api_path}"
    api = Api(base_url, dry_run=args.dry_run or args.verify_only, verbose=args.verbose)
    try:
        build = api.get("/build-info")
    except (urllib.error.URLError, OSError) as e:
        print(f"Cannot reach {base_url}: {e}", file=sys.stderr)
        return 2
    print(f"Server {base_url} (build {build.get('buildTime') if build else '?'})")
    print(f"Event: {data.get('event', {}).get('name', args.json_file)}")

    feeder = Feeder(api, data)
    try:
        feeder.disciplines()
        feeder.competitors()
        feeder.starts()
        if args.verify_only:
            return 1 if feeder.verify() else 0
        feeder.results()
        if not args.skip_teams:
            feeder.teams()
    except ApiError as e:
        print(f"Aborted: {e}", file=sys.stderr)
        return 1

    print("Summary")
    for what, n in feeder.stats.items():
        print(f"  {what}: {n}")
    if args.verify and not args.dry_run:
        return 1 if feeder.verify() else 0
    return 1 if feeder.stats.get("teams failed") else 0


if __name__ == "__main__":
    sys.exit(main())
