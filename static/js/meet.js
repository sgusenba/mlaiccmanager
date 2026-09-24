// The meet's details (name, venue, host, dates) for the printouts: the Meet
// page's start cards and race bibs, and the Ranking page's cover page.

export { escapeHtml } from './teamRanking.js';

async function getJson(path) {
    const response = await fetch(`/api${path}`);
    if (!response.ok) throw new Error(`Loading ${path} failed: HTTP ${response.status}`);
    return response.json();
}

const parseDate = (iso) => {
    const [year, month, day] = String(iso ?? '').split('-').map(Number);
    return year && month && day ? new Date(year, month - 1, day) : null;
};

/** "26–27 June 2026", "26 June 2026" or '' in the browser's language. */
export function formatDateRange(from, to) {
    const start = parseDate(from);
    const end = parseDate(to) || start;
    if (!start) return '';
    const format = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
    return end > start ? format.formatRange(start, end) : format.format(start);
}

/** The first and last meet day of the relay management, or nulls if there are none. */
export function meetDayRange(days) {
    const dates = (days || []).map(day => day.date).filter(Boolean).sort();
    return { from: dates[0] ?? null, to: dates[dates.length - 1] ?? null };
}

/**
 * The stored meet details plus the dates to print: the meet's own dates, or
 * else the first to last meet day.
 */
export async function loadMeet() {
    const [meet, relays] = await Promise.all([
        getJson('/meet'),
        getJson('/rmgmt').catch(() => ({ days: [] }))
    ]);
    const days = meetDayRange(relays.days);
    const dateFrom = meet.date_from || (meet.date_to ? null : days.from);
    const dateTo = meet.date_to || (meet.date_from ? null : days.to);
    return {
        ...meet,
        days,
        dateText: formatDateRange(dateFrom || dateTo, dateTo)
    };
}
