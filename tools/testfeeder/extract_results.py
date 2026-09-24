#!/usr/bin/env python3
"""
Extracts the results of an EDL-ShootingStar results PDF (e.g. the
"ÖSTM/ÖM-Vorderlader 2026" results) into the JSON file that feed.py sends to
the competition manager.

    pip install pdfplumber
    python extract_results.py 26-vl-stm.pdf -o data/vl-stm-2026.json

Read: the individual result lists ("Einzelwertung") and the team lists
("Mannschaft"). Skipped: the entries/medal summaries and the aggregate lists
(Remington, Schulhof), which the app has no discipline type for.

Every individual row is checked: the ring counts must add up to the printed
total. Every team member must match the member's individual result.
"""
import argparse
import json
import re
import sys

RINGS = [10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0]
SHOTS = 10  # scoring shots per result

STATES = {
    "BGL": "Burgenland", "KTN": "Kärnten", "NOE": "Niederösterreich",
    "OOE": "Oberösterreich", "SBG": "Salzburg", "STM": "Steiermark",
    "TIR": "Tirol", "VBG": "Vorarlberg", "WIE": "Wien",
}
# Typos in the team lists
TEAM_NAME_FIXES = {"Obderösterreich": "Oberösterreich"}


def ind(category, type_, event, distance):
    return {"category": category, "level": "individual", "type": type_,
            "event": event, "shooting_distance": distance}


def team(category, type_, event, based_on, size=3):
    return {"category": category, "level": "team", "type": type_,
            "event": event, "based_on": based_on, "team_size": size}


# PDF list title -> discipline in the app's catalog (disciplines.json). The
# feeder finds a discipline by category, level, type and event and creates the
# ones the catalog lacks (Meixner, junior lists, teams of combined events).
INDIVIDUAL = {
    "Kuchenreuter/O": ind("pistol", "original", "Kuchenreuter", "m25"),
    "Kuchenreuter/R": ind("pistol", "reproduction", "Kuchenreuter", "m25"),
    "Cominazzo(O/R)": ind("pistol", "combined", "Cominazzo", "m25"),
    "Tanzutsu(O/R)": ind("pistol", "combined", "Tanzutsu", "m25"),
    "Colt/O": ind("pistol", "original", "Colt", "m25"),
    "Mariette/R": ind("pistol", "reproduction", "Mariette", "m25"),
    "Donald Malson/O": ind("pistol", "original", "Donald Malson", "m50"),
    "Donald Malson/R": ind("pistol", "reproduction", "Donald Malson", "m50"),
    "Meixner/RS": ind("pistol", "reproduction", "Meixner RS", "m50"),
    "Meixner/RU": ind("pistol", "reproduction", "Meixner RU", "m50"),
    "Tanegashima(O/R)": ind("rifle", "combined", "Tanegashima", "m50"),
    "Hizadai(O/R)": ind("rifle", "combined", "Hizadai", "m50"),
    "Miquelet(O/R)": ind("rifle", "combined", "Miquelet", "m50"),
    "Vetterli/O": ind("rifle", "original", "Vetterli", "m50"),
    "Vetterli/R": ind("rifle", "reproduction", "Vetterli", "m50"),
    "Lamarmora(O/R)": ind("rifle", "combined", "Lamarmora", "m50"),
    "Pennsylvania(O/R)": ind("rifle", "combined", "Pennsylvania", "m50"),
    "Maximilian(O/R)": ind("rifle", "combined", "Maximilian", "m100"),
    "Minie(O/R)": ind("rifle", "combined", "Minie", "m100"),
    "Whitworth (O/R)": ind("rifle", "combined", "Whitworth", "m100"),
    "Kuchenreuter Junioren": ind("pistol", "combined", "Kuchenreuter Junioren", "m25"),
    "Mariette Junioren": ind("pistol", "combined", "Mariette Junioren", "m25"),
    "Vetterli Junioren": ind("rifle", "combined", "Vetterli Junioren", "m50"),
    "Whitworth Junioren": ind("rifle", "combined", "Whitworth Junioren", "m100"),
}

TEAMS = {
    "Boutet (Kuchenreuter/O)": team("pistol", "original", "Boutet", "Kuchenreuter"),
    "Forsyth (Kuchenreuter/R)": team("pistol", "reproduction", "Forsyth", "Kuchenreuter"),
    "Wogdon (Cominazzo-R/O)": team("pistol", "combined", "Wogdon", "Cominazzo"),
    "Kunitomo (Tanzutsu O/R)": team("pistol", "open", "Kunitomo", "Tanzutsu"),
    "Adams (Colt)": team("pistol", "original", "Adams", "Colt"),
    "Peterlongo (Mariette)": team("pistol", "reproduction", "Peterlongo", "Mariette"),
    "El Alamo (D.Malson O/R)": team("pistol", "open", "El Alamo", "Not specified"),
    "Springfield(MEI-R/O)": team("pistol", "open", "Springfield", "Meixner"),
    "Nagashino (Tanegash. O/R)": team("rifle", "combined", "Nagashino", "Tanegashima"),
    "Halikko (Miqelet O/R)": team("rifle", "combined", "Halikko", "Miquelet"),
    "Pforzheim (Vetterli-R/O)": team("rifle", "open", "Pforzheim", "Vetterli"),
    "Enfield (Lamarmora O/R)": team("rifle", "combined", "Enfield", "Lamarmora"),
    "Kossut (Pennsylvania O/R)": team("rifle", "open", "Kossuth", "Pennsylvania"),
    "Lucca (Maximilian-R/O)": team("rifle", "combined", "Lucca", "Maximilian"),
    "Magenta (Minie-R/O)": team("rifle", "combined", "Magenta", "Minie"),
    "Rigby (Whitworth-R/O)": team("rifle", "open", "Rigby", "Whitworth"),
}

# Discipline code in the team lists -> individual list the member's result is from
MEMBER_CODES = {
    "KUCO": "Kuchenreuter/O", "KUCR": "Kuchenreuter/R", "COM": "Cominazzo(O/R)",
    "TNZ": "Tanzutsu(O/R)", "COL": "Colt/O", "MAR": "Mariette/R",
    "MALO": "Donald Malson/O", "MAL": "Donald Malson/R",
    "MEIRS": "Meixner/RS", "MEIRU": "Meixner/RU", "TAN": "Tanegashima(O/R)",
    "HIZ": "Hizadai(O/R)", "MIQ": "Miquelet(O/R)", "VETO": "Vetterli/O",
    "VETR": "Vetterli/R", "LAM": "Lamarmora(O/R)", "PEN": "Pennsylvania(O/R)",
    "MAX": "Maximilian(O/R)", "MIN": "Minie(O/R)", "WHI": "Whitworth (O/R)",
}

INDIVIDUAL_HEADER = "Rng Zuname Vorname St#"
TEAM_HEADER = "Rng Mannschaft/Schützen"
COUNT = r"(?:\d+|\.)"
STATE = "|".join(STATES)

# [rank] SURNAME Firstname bib [R/O marker] state club ... 11 counts [tie-break] total
IND_ROW = re.compile(
    rf"^(?:(?P<rank>\d+) )?(?P<name>\D+?) (?P<bib>\d+) (?:(?P<marker>[ORSU]) )?"
    rf"(?P<state>{STATE}) (?P<rest>.+)$")
# bib SURNAME Firstname CODE 11 counts total
MEMBER_ROW = re.compile(
    rf"^(?P<bib>\d+) (?P<name>\D+?) (?P<code>[A-Z]{{2,5}}) (?P<counts>(?:{COUNT} ){{11}})(?P<total>\d+)$")


def split_name(text):
    """'LOACKER-SCHÖCH Gert' -> ('LOACKER-SCHÖCH', 'Gert'); the surname is upper case."""
    words = text.split()
    surname = [w for w in words if w == w.upper()]
    first = [w for w in words if w != w.upper()]
    return " ".join(surname), " ".join(first)


def parse_counts(tokens):
    return {str(ring): (0 if t == "." else int(t)) for ring, t in zip(RINGS, tokens)}


def score(counts):
    return sum(int(ring) * n for ring, n in counts.items())


class Extractor:
    def __init__(self):
        self.competitors = {}   # bib -> competitor
        self.results = []
        self.teams = []
        self.errors = []
        self.warnings = []

    def competitor(self, bib, surname, first, state=None, club=None):
        c = self.competitors.setdefault(bib, {"bib": bib, "last_name": surname, "first_name": first,
                                              "state": None, "club": None})
        if first and len(first) > len(c["first_name"] or ""):
            c["first_name"] = first
        if state:
            c["state"] = state
        if club:
            c["club"] = club
        return c

    # ------------------------------------------------------------ sections

    def run(self, pages):
        lines = "\n".join(pages).splitlines()
        i = 0
        while i < len(lines):
            line = lines[i].strip()
            if line.startswith(INDIVIDUAL_HEADER):
                # Title is two lines above the header: "<title>" / "25m Bewerbe"
                title = lines[i - 2].strip()
                i = self.individual(title, lines, i + 1)
            elif line.startswith(TEAM_HEADER):
                title = lines[i - 1].strip()
                i = self.team(title, lines, i + 1)
            else:
                i += 1

    def individual(self, title, lines, i):
        if title not in INDIVIDUAL:
            self.warnings.append(f"skipped individual list '{title}' (no discipline mapping)")
            return i
        rank = None
        last = None
        while i < len(lines):
            line = lines[i].strip()
            if line.startswith("____") or "Nennungen" in line or line.startswith("Created by"):
                break
            # The tie-break is glued to the last count: ". .118,1 74"
            line = re.sub(r"\.(\d+,\d)", r". \1", line)
            m = IND_ROW.match(line)
            if not m:
                if last and re.fullmatch(r"[^\d\s.]+", line):
                    # First name wrapped to the next line
                    last_c = self.competitors[last["bib"]]
                    if line not in last_c["first_name"].split():
                        last_c["first_name"] = (last_c["first_name"] + " " + line).strip()
                else:
                    self.warnings.append(f"{title}: unparsed line '{line}'")
                i += 1
                continue
            bib = int(m["bib"])
            surname, first = split_name(m["name"])
            rest = m["rest"].split()
            result = {"discipline": title, "bib": bib, "marker": m["marker"]}
            if rest[-1] == "DNS":
                club = " ".join(rest[:-1])
                result.update({"dns": True})
            else:
                total = int(rest[-1])
                tie_break = None
                body = rest[:-1]
                if "," in body[-1]:
                    tie_break = float(body[-1].replace(",", "."))
                    body = body[:-1]
                # 11 counts (10 down to misses); a tie-break is printed over the misses
                # column, leaving 10. Clubs may end in a number ("SG Scheibbs 1569"), so
                # take the width whose counts give SHOTS shots adding up to the total.
                counts = club = None
                for width in (11, 10):
                    tokens = body[-width:]
                    if len(body) <= width or not all(re.fullmatch(COUNT, t) for t in tokens):
                        continue
                    candidate = parse_counts(tokens + ["."] * (11 - width))
                    if sum(candidate.values()) == SHOTS and score(candidate) == total:
                        counts, club = candidate, " ".join(body[:-width])
                        break
                if counts is None:
                    self.errors.append(f"{title}: ring counts do not add up to {total} in '{line}'")
                    i += 1
                    continue
                if m["rank"]:
                    rank = int(m["rank"])
                result.update({"rank": rank, "total": total, "counts": counts,
                               "tie_break": tie_break, "dns": False})
            self.competitor(bib, surname, first, m["state"], club)
            self.results.append(result)
            last = result
            i += 1
        return i

    def team(self, title, lines, i):
        if title not in TEAMS:
            self.warnings.append(f"skipped team list '{title}' (no discipline mapping)")
            return i
        teams = []
        while i < len(lines):
            line = lines[i].strip()
            if line.startswith("____") or line.startswith("Created by"):
                break
            m = MEMBER_ROW.match(line)
            if m and teams:
                bib = int(m["bib"])
                surname, first = split_name(m["name"])
                self.competitor(bib, surname, first)
                code = m["code"]
                if code not in MEMBER_CODES:
                    self.errors.append(f"{title}: unknown discipline code {code}")
                teams[-1]["members"].append({
                    "bib": bib, "discipline": MEMBER_CODES.get(code, code),
                    "total": int(m["total"]),
                })
            else:
                # "<rank> <team name> <ring counts, partly glued> <total>"
                words = line.split()
                name = []
                for w in words[1:]:
                    if w[0].isdigit():
                        break
                    name.append(TEAM_NAME_FIXES.get(w, w))
                teams.append({"discipline": title, "rank": int(words[0]), "base_name": " ".join(name),
                              "rest": words[1 + len(name):], "total": int(words[-1]), "members": []})
            i += 1
        # A state with several teams numbers them: "Oberösterreich 1", "Oberösterreich 2"
        for t in teams:
            same = sum(1 for o in teams if o["base_name"] == t["base_name"])
            t["name"] = f"{t['base_name']} {t['rest'][0]}" if same > 1 else t["base_name"]
        for t in teams:
            del t["base_name"], t["rest"]
            member_sum = sum(mb["total"] for mb in t["members"])
            if member_sum != t["total"]:
                self.errors.append(f"{title}: team {t['name']} members add up to {member_sum}, printed {t['total']}")
        self.teams.extend(teams)
        return i

    # ------------------------------------------------------------ checks

    def check_members(self):
        by_start = {(r["discipline"], r["bib"]): r for r in self.results}
        for t in self.teams:
            for mb in t["members"]:
                r = by_start.get((mb["discipline"], mb["bib"]))
                if r is None:
                    self.errors.append(f"{t['discipline']}: #{mb['bib']} of {t['name']} has no result in {mb['discipline']}")
                elif r.get("total") != mb["total"]:
                    self.errors.append(f"{t['discipline']}: #{mb['bib']} total {mb['total']} differs from "
                                       f"{mb['discipline']} result {r.get('total')}")


def build(pdf_path):
    import pdfplumber
    with pdfplumber.open(pdf_path) as pdf:
        pages = [page.extract_text() or "" for page in pdf.pages]

    ex = Extractor()
    ex.run(pages)
    ex.check_members()

    used = []
    for r in ex.results:
        if r["discipline"] not in used:
            used.append(r["discipline"])
    disciplines = [{"key": title, **INDIVIDUAL[title]} for title in used]
    team_titles = []
    for t in ex.teams:
        if t["discipline"] not in team_titles:
            team_titles.append(t["discipline"])
    disciplines += [{"key": title, **TEAMS[title]} for title in team_titles]

    competitors = []
    for bib in sorted(ex.competitors):
        c = ex.competitors[bib]
        name = f"{c['first_name']} {c['last_name'].title()}".strip()
        competitors.append({
            "bib": bib, "name": name, "first_name": c["first_name"], "last_name": c["last_name"],
            "club": c["club"] or "", "country": c["state"] or "",
            "state_name": STATES.get(c["state"], ""),
        })

    data = {
        "event": {
            "name": "ÖSTM/ÖM Vorderlader 2026",
            "place": "Bad Zell (OÖ)",
            "dates": ["2026-06-26", "2026-06-27"],
            "source": pdf_path.replace("\\", "/").split("/")[-1],
        },
        "disciplines": disciplines,
        "competitors": competitors,
        "results": ex.results,
        "teams": ex.teams,
    }
    return data, ex


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("pdf", help="results PDF")
    parser.add_argument("-o", "--output", default="-", help="JSON file to write (default: stdout)")
    args = parser.parse_args()

    data, ex = build(args.pdf)
    for w in ex.warnings:
        print(f"warning: {w}", file=sys.stderr)
    for e in ex.errors:
        print(f"ERROR: {e}", file=sys.stderr)

    text = json.dumps(data, ensure_ascii=False, indent=2)
    if args.output == "-":
        print(text)
    else:
        with open(args.output, "w", encoding="utf-8") as f:
            f.write(text + "\n")
    print(f"{len(data['disciplines'])} disciplines, {len(data['competitors'])} competitors, "
          f"{len(data['results'])} results ({sum(1 for r in data['results'] if r['dns'])} DNS), "
          f"{len(data['teams'])} teams, {len(ex.errors)} errors", file=sys.stderr)
    return 1 if ex.errors else 0


if __name__ == "__main__":
    sys.exit(main())
