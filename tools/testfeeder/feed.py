#!/usr/bin/env python3
"""
Feeds an event's results (the JSON written by extract_results.py) into a
running competition manager through its REST API (see static/openapi.yaml).

    python feed.py data/vl-stm-2026.json --host 192.168.1.20 --port 5000

Steps, each safe to repeat (a second run updates instead of duplicating):
  1. disciplines  find each by category/level/type/name (name without the
                  MLAIC number and type suffix; short names need not be
                  unique), create the missing ones; an O/R list goes into
                  the event's original and reproduction disciplines, a
                  list with variants (Kuchenreuter R: S and U) into one
                  discipline per variant; a team discipline's results that
                  count (team_of) get the disciplines its members start in.
                  Each list becomes a ranking of the meet (matched by name):
                  an O/R list or a list with variants ranks its disciplines
                  together, which also puts them into the meet's program
  2. competitors  matched by name and club, created if missing
  3. starts       one start per competitor and discipline
  4. results      shots rebuilt from the ring counts, tie-break as override
  5. teams        matched by discipline and name, created or updated
  6. --verify     compare the server's rankings with the ranks in the JSON

Only the Python standard library is needed.
"""
import argparse
import json
import re
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


TYPE_SUFFIX = re.compile(r"[_ ](?:O/R|O|R)$")
NUMBER_PREFIX = re.compile(r"^(?:\d+|XX)_")


def match_event(event):
    """The name without MLAIC number and type suffix: "6_Kuchenreuter_O" and "Kuchenreuter" are the same event."""
    event = TYPE_SUFFIX.sub("", (event or "").strip()).strip()
    return norm(NUMBER_PREFIX.sub("", event))


def discipline_key(d):
    """Category, level, type and event: a catalog discipline has a name, the event JSON an event."""
    return (norm(d.get("category")), norm(d.get("level")), norm(d.get("type")),
            match_event(d.get("name") or d.get("event")))


def shots(counts):
    """Ring counts {"10": 4, "9": 6, ...} -> [10, 10, 10, 10, 9, ...]."""
    return [float(ring) for ring in RINGS for _ in range(counts.get(str(ring), 0))]


class Feeder:
    def __init__(self, api, data):
        self.api = api
        self.data = data
        self.discipline_ids = {}   # JSON key -> discipline id (of an O/R list: the original's)
        self.split = {}            # JSON key of an O/R list -> {"original": id, "reproduction": id}
        self.variants = {}         # JSON key of a list with variants -> {marker: id}
        self.ranking_ids = {}      # JSON key -> id of the meet's ranking of that list
        self.result_types = {(r["discipline"], r["bib"]): r.get("type") for r in data["results"]}
        self.result_markers = {(r["discipline"], r["bib"]): r.get("marker") for r in data["results"]}
        self.competitor_ids = {}   # bib -> competitor id
        self.start_ids = {}        # (JSON discipline key, bib) -> start id
        self.stats = {}

    def count(self, what):
        self.stats[what] = self.stats.get(what, 0) + 1

    # -------------------------------------------------------------- steps

    def disciplines(self):
        print("Disciplines")
        catalog = {}
        for d in self.api.get("/available-disciplines"):
            catalog.setdefault(discipline_key(d), d)   # first in catalog order wins
        # Individual disciplines first: a team discipline's team_of needs their ids
        specs = sorted(self.data["disciplines"], key=lambda s: s.get("level") == "team")
        for spec in specs:
            if self.split_list(spec, catalog) or self.split_variants(spec, catalog):
                continue
            existing = catalog.get(discipline_key(spec))
            team_of = self.team_of(spec) if spec.get("level") == "team" else None
            if existing:
                self.discipline_ids[spec["key"]] = existing["id"]
                self.count("disciplines found")
                if self.api.verbose:
                    print(f"  {spec['key']} -> {existing['id']} '{existing['name']}'")
                # Let the catalog team also count the disciplines the PDF's members start in
                current = existing.get("team_of") or []
                if team_of and not set(team_of) <= set(current):
                    wanted = current + [i for i in team_of if i not in current]
                    self.api.put(f"/available-disciplines/{existing['id']}", {"team_of": wanted})
                    self.count("team compositions extended")
                    print(f"  {existing['name']}: results that count {current} -> {wanted}")
                continue
            body = self.discipline_body(spec, spec["event"])
            if team_of:
                body["team_of"] = team_of
            created = self.api.post("/available-disciplines", body)
            self.discipline_ids[spec["key"]] = created["id"]
            self.count("disciplines created")
            print(f"  created {spec['category']} {spec['level']} {spec['type']} '{spec['event']}' -> id {created['id']}")
        self.rankings()

    @staticmethod
    def discipline_body(spec, name):
        body = {k: v for k, v in spec.items() if k not in ("key", "based_on", "event", "variants") and v is not None}
        body["name"] = name
        if body.get("type") == "combined":
            body["type"] = "open"
        return body

    def rankings(self):
        """One ranking of the meet per JSON list, matched by name: an O/R list or a list with
        variants ranks its disciplines together, every other list its one discipline."""
        existing = {norm(r["name"]): r for r in self.api.get("/rankings")["rankings"]}
        for spec in self.data["disciplines"]:
            key = spec["key"]
            if key in self.split:
                ids = [self.split[key]["original"], self.split[key]["reproduction"]]
            elif key in self.variants:
                ids = list(self.variants[key].values())
            else:
                ids = [self.discipline_ids[key]]
            body = {
                "name": spec["event"],
                "level": "team" if spec.get("level") == "team" else "individual",
                "entries": [{"discipline_ids": [i]} for i in ids],
            }
            current = existing.get(norm(body["name"]))
            if current is None:
                created = self.api.post("/rankings", body)
                self.ranking_ids[key] = created["id"]
                self.count("rankings created")
                print(f"  ranking '{body['name']}' of {ids}")
            elif [e["discipline_ids"] for e in current["entries"]] == [[i] for i in ids]:
                self.ranking_ids[key] = current["id"]
                self.count("rankings found")
            else:
                self.api.put(f"/rankings/{current['id']}", {**body, "version": current.get("version")})
                self.ranking_ids[key] = current["id"]
                self.count("rankings updated")
                print(f"  ranking '{body['name']}' now of {ids}")

    def split_list(self, spec, catalog):
        """An O/R list whose results carry their type goes into the catalog's original and
        reproduction disciplines of the event, which its ranking then ranks together."""
        if spec.get("type") != "combined" or spec.get("level") != "individual":
            return False
        rows = [r for r in self.data["results"] if r["discipline"] == spec["key"]]
        pair = {t: catalog.get(discipline_key({**spec, "type": t})) for t in ("original", "reproduction")}
        if not rows or not all(r.get("type") for r in rows) or not all(pair.values()):
            return False
        self.split[spec["key"]] = {t: d["id"] for t, d in pair.items()}
        self.discipline_ids[spec["key"]] = pair["original"]["id"]
        self.count("O/R lists split")
        if self.api.verbose:
            print(f"  {spec['key']} -> {pair['original']['id']} + {pair['reproduction']['id']}")
        return True

    def split_variants(self, spec, catalog):
        """A list whose rows are marked with a variant goes into one discipline per variant
        (Kuchenreuter R: the catalog's Kuchenreuter_RS and _RU), found in the catalog or created."""
        events = spec.get("variants")
        if not events:
            return False
        rows = [r for r in self.data["results"] if r["discipline"] == spec["key"]]
        unknown = sorted({str(r.get("marker")) for r in rows if r.get("marker") not in events})
        if unknown:
            raise ValueError(f"{spec['key']}: rows marked {unknown}, but only {sorted(events)} have a discipline")
        self.variants[spec["key"]] = {}
        for marker, event in events.items():
            variant = {**spec, "event": event}
            existing = catalog.get(discipline_key(variant))
            if existing:
                discipline_id = existing["id"]
                self.count("disciplines found")
            else:
                discipline_id = self.api.post("/available-disciplines", self.discipline_body(spec, event))["id"]
                self.count("disciplines created")
                print(f"  created {spec['category']} individual {spec['type']} '{event}' -> id {discipline_id}")
            self.variants[spec["key"]][marker] = discipline_id
            if self.api.verbose:
                print(f"  {spec['key']} {marker} -> {discipline_id}")
        return True

    def discipline_of(self, key, bib):
        """The discipline id a competitor's result in a JSON list goes into."""
        if key in self.split:
            return self.split[key][self.result_types[(key, bib)]]
        if key in self.variants:
            return self.variants[key][self.result_markers[(key, bib)]]
        return self.discipline_ids[key]

    def team_of(self, spec):
        """Ids of the individual disciplines the team discipline's members start in."""
        return sorted({self.discipline_of(m["discipline"], m["bib"])
                       for t in self.data["teams"] if t["discipline"] == spec["key"] for m in t["members"]})

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
            discipline_id = self.discipline_of(r["discipline"], bib)
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
                "discipline_id": self.discipline_of(r["discipline"], r["bib"]),
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
        # Each list through its ranking, a list with variants or an O/R list as a whole
        targets = [(key, key, ranking_id) for key, ranking_id in self.ranking_ids.items()]
        for label, key, ranking_id in targets:
            expected_individual = [r for r in self.data["results"] if r["discipline"] == key and not r.get("dns")]
            expected_teams = [t for t in self.data["teams"] if t["discipline"] == key]
            try:
                ranking = self.api.get(f"/rankings/{ranking_id}/result")
            except ApiError as e:
                print(f"  ! {label}: {e}")
                problems += 1
                continue
            rows = [row for row in ranking.get("rankings", []) if row.get("has_result", True)]
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
                print(f"  ✗ {label}: {mismatch}")
            else:
                print(f"  ✓ {label} ({len(got)} ranked)")
        print(f"  {problems} ranking(s) differ from the PDF" if problems else "  all rankings match the PDF")
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
    except (ApiError, ValueError) as e:
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
