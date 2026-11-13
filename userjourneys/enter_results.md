Take a fully configured meet with starts and lanes, no results yet.
a range official works at a result entry station that only reaches port 5001
the official scans the barcode of a target label (EAN-13) or types the start id
enters the shots per ring (10 shots, misses as 0) and the tie-break value, saves, scans the next one
every result is stored with its start, the ranking shows it

Variation 1:
a second result is entered for the same start: 409 with the existing result, nothing is overwritten

Variation 2:
a result was entered wrong: the official looks it up again, corrects it, later deletes it; the ranking follows each time

Variation 3:
the shots on the page do not add up to 10: the page does not save
more than 10 shots, a shot that is not a ring from 0 to 10, or a start id that does not exist: refused by the server, nothing stored

Variation 4:
the station tries any other page or API call on port 5001: 404
