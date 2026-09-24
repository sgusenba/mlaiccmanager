# Test feeder

Loads the results of a real event into a running competition manager through
the REST API, so rankings and team pages can be checked against official results.

Two scripts:

| Script | Does | Needs |
|---|---|---|
| `extract_results.py` | Reads an EDL-ShootingStar results PDF into an event JSON file | `pip install pdfplumber` |
| `feed.py` | Sends an event JSON file to a server at a given IP and port | Python 3 standard library only |

## Usage

```bash
python extract_results.py 26-vl-stm.pdf -o data/vl-stm-2026.json
python feed.py data/vl-stm-2026.json --host 192.168.1.20 --port 5000 --verify
```

`feed.py` options:

- `--host`, `--port`: the server (defaults: `localhost`, `5000`). Also `--scheme` and `--api-path` (default `/api`).
- `--verify`: after loading, compares each discipline's ranking on the server with the ranks in the JSON. Tied rows may appear in any order. Exits with 1 if a ranking differs.
- `--verify-only`: runs only the comparison and changes nothing.
- `--dry-run`: reads from the server but sends no changes (`-v` prints every request).
- `--skip-teams`: loads individual results only.

You can run it more than once. Competitors are matched by name and club, starts by
discipline, results by start and teams by discipline and name. A second run only
updates what changed.

**The feeder adds to the server's data.** Point it at a test instance or an empty
data directory, not at a live competition. It also creates and activates disciplines.

## Event JSON

```jsonc
{
  "event": { "name": "...", "place": "...", "dates": ["2026-06-26"] },
  "disciplines": [   // matched in the catalog by category, level, type, event; created if missing
    { "key": "Kuchenreuter/O", "category": "pistol", "level": "individual",
      "type": "original", "event": "No 6 Kuchenreuter", "shooting_distance": "m25" },
    { "key": "Boutet (Kuchenreuter/O)", "category": "pistol", "level": "team",
      "type": "original", "event": "No 18 Boutet", "based_on": "No 6 Kuchenreuter", "team_size": 3 }
  ],
  "competitors": [ { "bib": 61, "name": "Werner Fasching", "club": "PSV Burgenland", "country": "BGL" } ],
  "results": [       // one per competitor and discipline; "dns": true registers the start only
    { "discipline": "Kuchenreuter/O", "bib": 28, "rank": 5, "total": 91,
      "counts": { "10": 2, "9": 7, "8": 1, "7": 0, "...": 0, "0": 0 },
      "tie_break": 51.0, "marker": null, "dns": false }
  ],
  "teams": [
    { "discipline": "Boutet (Kuchenreuter/O)", "name": "Oberösterreich 1", "rank": 1, "total": 273,
      "members": [ { "bib": 28, "discipline": "Kuchenreuter/O", "total": 91 } ] }
  ]
}
```

The PDF prints only ring counts, not single shots. The feeder therefore sends the
shots as a sorted list (e.g. four 10s and six 9s). That is enough for the total
and the countback. The printed tie-break goes into `override_value` (lower wins).
The competitor's federal state (`BGL`, `OOE`, …) goes into `country`.

## How the PDF maps to the catalog

- `X/O` and `X/R` lists map to the `original` and `reproduction` disciplines. `X(O/R)` lists map to `combined`.
- The catalog has no Meixner or junior lists, so they become custom disciplines (ids from 1000).
- Some team events score combined results (Wogdon, Nagashino, Halikko, Enfield, Lucca and Magenta), and so does Springfield (Meixner). The catalog's team entries for these would only accept original or reproduction starts, so they are created as `combined` or `open` team disciplines.
- The aggregate lists (Remington, Schulhof) and the entry and medal summaries are skipped.

The extractor checks every row: the ring counts must add up to the printed total,
and every team member must match that member's individual result. It exits with 1 on any mismatch.

`data/` is git-ignored because the extracted files contain real competitor names.
