// Lane assignments as a CSV file, one row per start, e.g. to print the
// labels that identify the targets. Built from the relay overview, so every
// lane of the whole meet is in it, in time, range and lane order.

const SEPARATORS = { semicolon: ';', comma: ',' };

const COLUMNS = [
    ['Date', row => row.date],
    ['Weekday', row => row.weekday],
    ['Relay', row => row.relay],
    ['Start time', row => row.startTime],
    ['End time', row => row.endTime],
    ['Range', row => row.range],
    ['Lane', row => row.lane],
    ['Start ID', row => row.startId],
    ['Starter ID', row => row.starterId],
    ['Name', row => row.name],
    ['Club', row => row.club],
    ['Country', row => row.country],
    ['Discipline', row => row.discipline],
    ['Event', row => row.event],
    ['Type', row => row.type],
    ['Category', row => row.category],
    ['Meet', row => row.meet],
    ['Venue', row => row.venue]
];

function addMinutes(time, minutes) {
    if (!time || !Number.isFinite(minutes)) return '';
    const [h, m] = time.split(':').map(Number);
    const total = (h * 60 + m + minutes) % (24 * 60);
    return `${String(Math.floor(total / 60)).padStart(2, '0')}:${String(total % 60).padStart(2, '0')}`;
}

function weekday(isoDate) {
    const [year, month, day] = String(isoDate ?? '').split('-').map(Number);
    return year ? new Date(year, month - 1, day).toLocaleDateString(undefined, { weekday: 'short' }) : '';
}

/** Quoted when it holds the separator, a quote or a line break; quotes are doubled. */
function csvField(value, separator) {
    const text = value === null || value === undefined ? '' : String(value);
    return text.includes(separator) || /["\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

/**
 * The rows of the export: every scheduled start, plus the starts without a
 * lane when includeUnscheduled is set (with empty relay and lane fields).
 */
export function laneRows({ overview, relays, competitors, disciplines, meet, includeUnscheduled }) {
    const competitorById = new Map((competitors || []).map(c => [c.id, c]));
    const disciplineById = new Map((disciplines || []).map(d => [d.id, d]));
    const rangeOrder = new Map((overview.ranges || []).map((range, i) => [range.id, i]));
    const duration = Number(relays?.config?.relay_duration_min);

    const row = (entry, scheduled) => {
        const competitor = competitorById.get(entry.competitor?.id) || entry.competitor || {};
        const discipline = disciplineById.get(entry.discipline_id) || {};
        return {
            scheduled,
            date: scheduled ? entry.date ?? '' : '',
            weekday: scheduled ? weekday(entry.date) : '',
            relay: scheduled ? entry.sequence_no ?? '' : '',
            startTime: scheduled ? entry.start_time ?? '' : '',
            endTime: scheduled ? addMinutes(entry.start_time, duration) : '',
            rangeId: scheduled ? entry.range_id : '',
            range: scheduled ? entry.range_name ?? entry.range_id ?? '' : '',
            lane: scheduled ? entry.lane_no ?? '' : '',
            startId: entry.start_id,
            starterId: competitor.id ?? '',
            name: competitor.name ?? '',
            club: competitor.club ?? '',
            country: competitor.country ?? '',
            discipline: entry.discipline_name ?? '',
            event: discipline.event ?? '',
            type: discipline.type ?? '',
            category: discipline.category ?? '',
            meet: meet?.name ?? '',
            venue: meet?.location ?? ''
        };
    };

    const rows = [];
    for (const competitorRow of overview.rows || []) {
        (competitorRow.scheduled || []).forEach(entry => rows.push(row(entry, true)));
        if (includeUnscheduled) {
            (competitorRow.unscheduled || []).forEach(entry => rows.push(row(entry, false)));
        }
    }

    // Time, then range, then lane; starts without a lane last, by starter
    return rows.sort((a, b) =>
        (b.scheduled - a.scheduled)
        || a.date.localeCompare(b.date)
        || String(a.startTime).localeCompare(String(b.startTime))
        || (rangeOrder.get(a.rangeId) ?? 99) - (rangeOrder.get(b.rangeId) ?? 99)
        || (Number(a.lane) || 0) - (Number(b.lane) || 0)
        || (Number(a.starterId) || 0) - (Number(b.starterId) || 0)
        || String(a.startId).localeCompare(String(b.startId)));
}

/**
 * The CSV text: a header line, then one line per row, CRLF line ends. Starts
 * with a byte order mark so Excel reads umlauts as UTF-8.
 */
export function toCsv(rows, separatorName = 'semicolon') {
    const separator = SEPARATORS[separatorName] || ';';
    const lines = [COLUMNS.map(([header]) => csvField(header, separator)).join(separator)];
    for (const row of rows) {
        lines.push(COLUMNS.map(([, value]) => csvField(value(row), separator)).join(separator));
    }
    return '﻿' + lines.join('\r\n') + '\r\n';
}
